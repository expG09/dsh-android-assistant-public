'use strict';

/**
 * 通道服务（M2 配对/WebSocket + M4 音频上传与 DeepSeek ASR + M5 意图解析与命令下发
 *          + M6a 语音回复 TTS）
 *
 * 职责：
 *   1) 启动时打印 6 位配对码（5 分钟有效、用一次即失效）；
 *   2) HTTP POST /pair {code, deviceId} 校验后下发 deviceToken（服务端只存 SHA-256 哈希）；
 *   3) WebSocket 首帧 hello {deviceId, token, proto}（proto 1 兼容 / proto 2 支持音频），回 ready；
 *   4) text 帧交给 onUserText(text, reply, sendCommand)：M5 起先解析意图，闲聊回一句人话，
 *      要办事就下发 command 帧（白名单 8 种），收到 commandResult 后再回一句结果；
 *   5) audioStart / audioChunk / audioEnd 收齐音频 → 调 DeepSeek ASR → asrResult / asrError；
 *   6) 30 秒心跳，90 秒没有任何帧就判死；
 *   7) M6a：回复文本确定后按句切分 → DeepSeek TTS → speakStart/speakChunk/speakDone 下发；
 *      手机端 speakConfig 告知开关与音色、speakStop 打断在途合成、speakRequest 试听音色。
 *   8) M7：纯闲聊先转发给本机 DSH 宿主插件（phone-bridge，回环 HTTP），让手机上提问的是
 *      「同一个会话」；插件不可用就退回本地模型（上面第 4 条的旧行为），日志注明走了哪条。
 *
 * 日志约束：不写 token 原文、不写消息正文、不写识别文本、不写回复正文、不写音频、不写任何密钥内容。
 * 命令的 kind/id/ok/detail 可以写；命令参数（号码、地址、标题这类用户内容）只进
 * data/events.jsonl 的「事件记录」，不进 console 日志。
 * 语音回复只写元数据：id / kind / 音色 / 句数 / 字数 / 字节 / 分块 / 耗时。
 * 密钥只从环境变量 DEEPSEEK_API_KEY 或 channel-server/.env 读（环境变量优先），
 * 启动日志只写「来源」，不写值、不写前后缀。
 */

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { WebSocketServer, OPEN } = require('ws');
const intentParser = require('./intent');
const tts = require('./tts');
// M7：把闲聊转发给本机 DSH 宿主插件（phone-bridge），插件不可用时退回下面的本地模型
const bridge = require('./bridge');
// M7 真机联调修复：bridge 的超时/失败也要走本进程统一的日志（带时间戳），
// 否则「等会话超时」这件事故障时在日志里看不见。log() 是函数声明，已提升。
bridge.setLogger(log);

// ---------------------------------------------------------------- 配置

// 命令行参数优先于环境变量：--port=8788 --host=0.0.0.0 --dev --echo=stream
const ARGV = process.argv.slice(2);
function argValue(name) {
  const hit = ARGV.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (!hit) return undefined;
  const eq = hit.indexOf('=');
  return eq >= 0 ? hit.slice(eq + 1) : true;
}

const PORT = Number(argValue('port') || process.env.CHANNEL_PORT || 8788);
const HOST = String(argValue('host') || process.env.CHANNEL_HOST || '0.0.0.0');
/** M2 时代只认 1；M4 起认 1（只有文本）和 2（文本 + 音频）。回 ready 时原样回客户端版本。 */
const PROTO_MIN = 1;
const PROTO_MAX = 2;
const PAIR_TTL_MS = Number(process.env.CHANNEL_PAIR_TTL_MS || 5 * 60 * 1000);
const HEARTBEAT_MS = Number(process.env.CHANNEL_HEARTBEAT_MS || 30 * 1000);
const DEAD_MS = Number(process.env.CHANNEL_DEAD_MS || 90 * 1000);
const HELLO_TIMEOUT_MS = Number(process.env.CHANNEL_HELLO_TIMEOUT_MS || 10 * 1000);
// text 处理方式：m5 = 意图解析 + 命令下发（默认）；text/stream = M2 时代的原样回显（联调用）
const RAW_ECHO = argValue('echo') || process.env.CHANNEL_ECHO_MODE;
const ECHO_MODE =
  RAW_ECHO === 'stream' ? 'stream' : RAW_ECHO === 'text' || RAW_ECHO === true ? 'text' : 'off';
const DEV_MODE = argValue('dev') === true || process.env.CHANNEL_DEV === '1';
const DATA_DIR = process.env.CHANNEL_DATA_DIR || path.join(__dirname, 'data');
const DEVICES_FILE = path.join(DATA_DIR, 'devices.json');

// ------------------------------------------------ M5：意图 / 命令配置

/** 混合意图里「先回答、后执行」的间隔：给手机把回答渲染出来、让人读到（默认 900 ms）。 */
const COMMAND_AFTER_REPLY_MS = Number(process.env.CHANNEL_COMMAND_DELAY_MS || 900);
/** 等手机回执的超时：超时就回一句「手机没回执行结果」，不静默。 */
const COMMAND_TIMEOUT_MS = Number(process.env.CHANNEL_COMMAND_TIMEOUT_MS || 20 * 1000);
/** 「确认打开」的有效期：银行/支付类先问一句，60 秒内说「确认」才执行。 */
const CONFIRM_TTL_MS = Number(process.env.CHANNEL_CONFIRM_TTL_MS || 60 * 1000);
/** 命令事件记录（本地 JSONL，供后续回忆）：只进这个文件，不进 console 日志。 */
const EVENTS_FILE = process.env.CHANNEL_EVENTS_FILE || path.join(DATA_DIR, 'events.jsonl');

// ------------------------------------------------ M4：音频 / ASR 配置

/** DeepSeek ASR：multipart file 字段；8 MiB / 120 秒上限；语种自动识别。 */
const ASR_ENDPOINT = process.env.CHANNEL_ASR_ENDPOINT || 'https://api.deepseek.com/v1/tools/asr';
/** 单次调用超时（文档要求 30 秒） */
const ASR_TIMEOUT_MS = Number(process.env.CHANNEL_ASR_TIMEOUT_MS || 30 * 1000);
/** 音频硬上限：超过就本地拒绝，绝不上传 */
const AUDIO_MAX_BYTES = Number(process.env.CHANNEL_AUDIO_MAX_BYTES || 8 * 1024 * 1024);
const AUDIO_MAX_DURATION_MS = Number(process.env.CHANNEL_AUDIO_MAX_DURATION_MS || 120 * 1000);
/** 同一连接同时只允许 1 段音频在传/在识别（手机串行上传，这里只是兜底） */
const MAX_ASR_INFLIGHT = 2;
/** 音频 id 只允许这些字符（要拼进临时文件名，必须防目录穿越） */
const AUDIO_ID_RE = /^[A-Za-z0-9._-]{1,64}$/;
/** 临时文件目录：识别完就删；不随仓库提交 */
const TMP_DIR = process.env.CHANNEL_TMP_DIR || path.join(__dirname, 'tmp');
/** 启动时清理超过这个年龄的残留临时音频（异常退出留下的） */
const TMP_STALE_MS = 60 * 60 * 1000;

// M5 起 command / commandResult 已实现；还没实现的是 file / approval
const RESERVED = new Set(['file', 'approval']);
const AUDIO_TYPES = new Set(['audioStart', 'audioChunk', 'audioEnd']);
// M6a：手机端只发这三个 speak 帧；speakStart/speakChunk/speakDone 只由电脑端发
const SPEAK_CLIENT_TYPES = new Set(['speakConfig', 'speakStop', 'speakRequest']);

// ---------------------------------------------------------------- 日志

function ts() {
  return new Date().toISOString().replace('T', ' ').replace('Z', '');
}
function log(msg) {
  console.log(ts() + ' ' + msg);
}

// ---------------------------------------------------------------- 密钥

/**
 * 读 DeepSeek API Key：环境变量优先，channel-server/.env 兜底。
 * 任何日志都只允许出现「来源」（env / .env / none），禁止出现值、前后缀、长度。
 */
function loadApiKey() {
  const fromEnv = (process.env.DEEPSEEK_API_KEY || '').trim();
  if (fromEnv) return { key: fromEnv, source: 'env' };

  const envPath = path.join(__dirname, '.env');
  try {
    const raw = fs.readFileSync(envPath, 'utf8');
    for (const line of raw.split(/\r?\n/)) {
      const m = line.match(/^\s*(?:export\s+)?DEEPSEEK_API_KEY\s*=\s*(.*)$/);
      if (!m) continue;
      let value = m[1].trim();
      if (
        (value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))
      ) {
        value = value.slice(1, -1);
      }
      if (value) return { key: value, source: '.env' };
    }
    return { key: null, source: 'none' };
  } catch (e) {
    return { key: null, source: 'none' };
  }
}

const API_KEY = loadApiKey();

/**
 * 读管理端点共享密钥 PHONE_BRIDGE_TOKEN：环境变量优先，channel-server/.env 兜底。
 * 与 bridge.js 读的是同一个键（插件那边也用它），日志只写「来源」，不写值、不写长度。
 */
function loadBridgeToken() {
  const fromEnv = String(process.env.PHONE_BRIDGE_TOKEN || '').trim();
  if (fromEnv) return { token: fromEnv, source: 'env' };

  const envPath = path.join(__dirname, '.env');
  try {
    const raw = fs.readFileSync(envPath, 'utf8');
    for (const line of raw.split(/\r?\n/)) {
      const m = line.match(/^\s*(?:export\s+)?PHONE_BRIDGE_TOKEN\s*=\s*(.*)$/);
      if (!m) continue;
      let value = m[1].trim();
      if (
        (value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))
      ) {
        value = value.slice(1, -1);
      }
      if (value) return { token: value, source: '.env' };
    }
    return { token: null, source: 'none' };
  } catch (e) {
    return { token: null, source: 'none' };
  }
}

/** 管理端点鉴权用的共享密钥；没配就置 null（管理端点一律 401，fail closed）。 */
const ADMIN_TOKEN = loadBridgeToken();

// ---------------------------------------------------------------- 配对状态

/** deviceId -> { tokenHash, pairedAt }，只存 SHA-256 哈希，不存 token 原文 */
const devices = new Map();

/** 当前配对码：{ code, expiresAt, failedAttempts }，同一时刻只有一个 */
let pairCode = null;

function loadDevices() {
  try {
    const raw = fs.readFileSync(DEVICES_FILE, 'utf8');
    const obj = JSON.parse(raw);
    for (const [deviceId, rec] of Object.entries(obj)) {
      if (rec && typeof rec.tokenHash === 'string') devices.set(deviceId, rec);
    }
    log(`已加载 ${devices.size} 台配对设备（仅哈希）`);
  } catch (e) {
    if (e.code !== 'ENOENT') log(`加载设备文件失败：${e.code || e.message}（按空处理）`);
  }
}

function saveDevices() {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const obj = {};
    for (const [deviceId, rec] of devices) obj[deviceId] = rec;
    fs.writeFileSync(DEVICES_FILE, JSON.stringify(obj, null, 2) + '\n', { mode: 0o600 });
  } catch (e) {
    log(`保存设备文件失败：${e.code || e.message}`);
  }
}

function sha256(s) {
  return crypto.createHash('sha256').update(s, 'utf8').digest('hex');
}

function timingSafeEqualHex(a, b) {
  const ba = Buffer.from(String(a), 'utf8');
  const bb = Buffer.from(String(b), 'utf8');
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

function newPairCode() {
  pairCode = {
    code: String(crypto.randomInt(0, 1000000)).padStart(6, '0'),
    expiresAt: Date.now() + PAIR_TTL_MS,
    failedAttempts: 0,
  };
  return pairCode;
}

function printPairCode(suffix) {
  const pc = pairCode;
  log(`配对码: ${pc.code}（${Math.round(PAIR_TTL_MS / 60000)} 分钟内有效，用一次即失效）${suffix || ''}`);
}

/** 校验并消费配对码。错误码不区分「码错」和「过期」，统一 pair_failed。 */
function consumePairCode(code) {
  if (!pairCode) return { ok: false, reason: 'no_code' };
  if (Date.now() > pairCode.expiresAt) {
    pairCode = null;
    log('配对码已过期，已作废');
    return { ok: false, reason: 'expired' };
  }
  if (typeof code !== 'string' || code.trim() !== pairCode.code) {
    pairCode.failedAttempts += 1;
    if (pairCode.failedAttempts >= 20) {
      pairCode = null;
      log('配对码连续输错 20 次，已作废（需重启服务重新生成）');
    }
    return { ok: false, reason: 'mismatch' };
  }
  pairCode = null; // 用一次就失效
  return { ok: true };
}

// ---------------------------------------------------------------- HTTP

function readJsonBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error('body_too_large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      try {
        const text = Buffer.concat(chunks).toString('utf8');
        resolve(text ? JSON.parse(text) : {});
      } catch (e) {
        reject(new Error('bad_json'));
      }
    });
    req.on('error', reject);
  });
}

function json(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

async function handlePair(req, res) {
  let body;
  try {
    body = await readJsonBody(req, 4096);
  } catch (e) {
    json(res, 400, { ok: false, error: 'bad_request' });
    return;
  }
  const deviceId = typeof body.deviceId === 'string' ? body.deviceId.trim() : '';
  const code = body.code;
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(deviceId)) {
    log('配对失败：deviceId 非法');
    json(res, 400, { ok: false, error: 'bad_request' });
    return;
  }
  const verdict = consumePairCode(code);
  if (!verdict.ok) {
    log(`配对失败 deviceId=${deviceId} reason=${verdict.reason}`);
    json(res, 401, { ok: false, error: 'pair_failed' });
    return;
  }
  const token = crypto.randomBytes(32).toString('base64url');
  devices.set(deviceId, { tokenHash: sha256(token), pairedAt: Date.now() });
  saveDevices();
  log(`配对成功 deviceId=${deviceId}（已存 token 哈希，共 ${devices.size} 台设备）`);
  json(res, 200, { ok: true, deviceToken: token, proto: PROTO_MAX });
}

function handleHealthz(res) {
  json(res, 200, {
    ok: true,
    proto: PROTO_MAX,
    protoSupported: [PROTO_MIN, PROTO_MAX],
    pairedDevices: devices.size,
    onlineDevice: activeConn ? activeConn.deviceId : null,
    echoMode: ECHO_MODE,
    // 只暴露「配没配」，不暴露来源以外的任何信息（更不暴露值）
    asrConfigured: Boolean(API_KEY.key),
    asrKeySource: API_KEY.source,
    // M5：意图解析状态（模型兜底只用来「解析/回答」，命令白名单是硬编码的 8 种）
    intentMode: ECHO_MODE === 'off' ? 'm5' : 'echo',
    intentModel: intentParser.modelEnabled() && Boolean(API_KEY.key) ? 'on' : 'off',
    commandKinds: intentParser.KINDS,
    defaultCity: intentParser.DEFAULT_CITY,
    // M6a：语音回复（TTS）。enabled 是「手机端这一次连上时说的开关状态」，voice 是它选的音色
    ttsConfigured: Boolean(API_KEY.key),
    ttsVoices: tts.VOICES,
    speakEnabled: activeConn ? activeConn.speakEnabled : false,
    speakVoice: activeConn ? activeConn.speakVoice : tts.DEFAULT_VOICE,
  });
}

/** 开发调试用：给当前在线连接注入一个帧（默认关闭，CHANNEL_DEV=1 才可用）。 */
async function handleDevInject(req, res) {
  let body;
  try {
    body = await readJsonBody(req, 4096);
  } catch (e) {
    json(res, 400, { ok: false, error: 'bad_request' });
    return;
  }
  if (!activeConn || activeConn.ws.readyState !== OPEN) {
    json(res, 409, { ok: false, error: 'no_online_device' });
    return;
  }
  const type = typeof body.type === 'string' ? body.type : '';
  if (!type) {
    json(res, 400, { ok: false, error: 'bad_request' });
    return;
  }
  const frame = Object.assign({ type }, body.payload && typeof body.payload === 'object' ? body.payload : {});
  sendFrame(activeConn, frame);
  log(`[dev] 已向 ${activeConn.deviceId} 注入帧 type=${type}`);
  json(res, 200, { ok: true });
}

/**
 * 开发调试用：把一段文本当成「手机发来的 text」喂进主流程（默认关闭，CHANNEL_DEV=1 才可用）。
 *
 * 用途：联调意图解析 → command 下发 → commandResult → 回话这条链路时，不用真的对着手机喊；
 * 走的完全是手机发 text 帧的同一条路径（onUserText），不是测试旁路。
 * 产品代码里没有任何测试开关——这个接口只存在于电脑端服务，且需要显式 --dev。
 */
async function handleDevText(req, res) {
  let body;
  try {
    body = await readJsonBody(req, 4096);
  } catch (e) {
    json(res, 400, { ok: false, error: 'bad_request' });
    return;
  }
  if (!activeConn || activeConn.ws.readyState !== OPEN) {
    json(res, 409, { ok: false, error: 'no_online_device' });
    return;
  }
  const text = typeof body.text === 'string' ? body.text : '';
  if (!text) {
    json(res, 400, { ok: false, error: 'bad_request' });
    return;
  }
  const id = `dev${Date.now()}`;
  log(`[dev] 模拟手机发 text（${Buffer.byteLength(text, 'utf8')} 字节）id=${id}`);
  Promise.resolve()
    .then(() => onUserText(text, makeReply(activeConn, id), activeConn, id, 'dev'))
    .catch((e) => log(`[dev] text 处理异常：${e && (e.code || e.message)}`));
  json(res, 200, { ok: true, id: id });
}

// ---------------------------------------------------------------- WebSocket

/**
 * 开发调试用：把一段文本直接走「语音回复」的合成链路（默认关闭，CHANNEL_DEV=1 才可用）。
 *
 * 用途：验收「长回复按句边合成边播」时不用等模型恰好答出 200 字，也不想为了测试去打开手机端开关。
 * 走的是与真实回复完全相同的 [startSpeak]（按句切分 → 逐句 TTS → speak 三帧），
 * 只跳过「文本从哪来」这一步。产品代码里没有任何测试开关。
 */
async function handleDevSpeak(req, res) {
  let body;
  try {
    body = await readJsonBody(req, 8192);
  } catch (e) {
    json(res, 400, { ok: false, error: 'bad_request' });
    return;
  }
  if (!activeConn || activeConn.ws.readyState !== OPEN) {
    json(res, 409, { ok: false, error: 'no_online_device' });
    return;
  }
  const text = typeof body.text === 'string' ? body.text : '';
  if (!text.trim()) {
    json(res, 400, { ok: false, error: 'bad_request' });
    return;
  }
  if (!API_KEY.key) {
    json(res, 200, { ok: false, error: 'no_key' });
    return;
  }
  const voice = tts.normalizeVoice(body.voice || activeConn.speakVoice);
  const session = startSpeak(activeConn, null, text, voice, 'dev');
  log(`[dev] 语音回复注入：id=${session.id} chars=${text.length} voice=${voice}`);
  json(res, 200, { ok: true, id: session.id });
}

// ---------------------------------------------------------------- 管理端点（面板用）

/**
 * 面板调用的三个受控端点：GET /admin/status、POST /admin/pair、GET /admin/devices。
 *
 * 安全边界（规格 C 段）：
 *   - 只接受来自回环地址的连接（服务本身仍按原来的 HOST 监听，手机那条 WS 不受影响）；
 *   - 必须带共享密钥 PHONE_BRIDGE_TOKEN（header `x-phone-bridge-key`，也接受 `Authorization: Bearer`）；
 *   - 缺密钥 / 错密钥 / 服务端没配密钥，一律 401，不回任何细节；
 *   - 设备列表只回 deviceId / pairedAt，**绝不把 devices.json 里的 tokenHash 发出去**。
 *   - 不依赖 /dev/*（那些要 --dev），面板不需要用户开调试模式。
 */

/** 进程启动时间（/admin/status 的 uptime 基准）。 */
const STARTED_AT = Date.now();

/** 来源是不是回环（::ffff:127.0.0.1 是 Windows 上 IPv4 映射成 IPv6 的写法）。 */
function isLoopbackRequest(req) {
  const addr = (req.socket && req.socket.remoteAddress) || '';
  return addr === '::1' || addr === '127.0.0.1' || addr === '::ffff:127.0.0.1';
}

/** 从请求头取共享密钥；两种写法都认，取不到返回空串。 */
function requestSharedKey(req) {
  const headers = req.headers || {};
  const direct = headers['x-phone-bridge-key'];
  if (typeof direct === 'string' && direct.trim()) return direct.trim();
  const auth = headers.authorization;
  if (typeof auth === 'string') {
    const m = auth.match(/^Bearer\s+(.+)$/i);
    if (m && m[1].trim()) return m[1].trim();
  }
  return '';
}

/** 定长比较（两边都先 SHA-256），不泄露密钥长度；缺密钥 / 错密钥一律 false。 */
function sharedKeyMatches(given) {
  if (!ADMIN_TOKEN.token || !given) return false;
  const a = crypto.createHash('sha256').update(given, 'utf8').digest();
  const b = crypto.createHash('sha256').update(ADMIN_TOKEN.token, 'utf8').digest();
  return crypto.timingSafeEqual(a, b);
}

/** 读文件尾部若干字节（events.jsonl 会一直变长，只读尾巴就够找最后一条）。 */
function readTail(filePath, maxBytes) {
  let fd = null;
  try {
    fd = fs.openSync(filePath, 'r');
    const size = fs.fstatSync(fd).size;
    const start = Math.max(0, size - maxBytes);
    const len = size - start;
    const buf = Buffer.alloc(len);
    if (len > 0) fs.readSync(fd, buf, 0, len, start);
    return buf.toString('utf8');
  } catch (e) {
    return null; // 文件不存在 = 还没发生过事件
  } finally {
    if (fd !== null) {
      try {
        fs.closeSync(fd);
      } catch (e) {
        /* ignore */
      }
    }
  }
}

/** 最近一条事件的时间（ISO 字符串）；文件缺失、坏行、空行都跳过，返回 null 而不是抛错。 */
function lastEventAt() {
  const tail = readTail(EVENTS_FILE, 64 * 1024);
  if (!tail) return null;
  const lines = tail.split(/\r?\n/);
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].trim();
    if (!line) continue;
    try {
      const rec = JSON.parse(line);
      if (rec && typeof rec.at === 'string') return rec.at;
    } catch (e) {
      /* 尾部第一行可能被截断，跳过 */
    }
  }
  return null;
}

/**
 * 设备列表：以 data/devices.json 为准（文件缺失 / 坏 JSON → 空列表，不抛错）。
 * 只回 deviceId 与 pairedAt；tokenHash 不外发。
 * events.jsonl 里没有 deviceId，没法按设备归因，所以每台设备的 lastActiveAt 只对
 * 当前在线的那台取内存里的最后收帧时间，其余为 null；最近事件时间放在响应的顶层。
 */
function listDevices() {
  let obj = null;
  try {
    obj = JSON.parse(fs.readFileSync(DEVICES_FILE, 'utf8'));
  } catch (e) {
    obj = null;
  }
  const out = [];
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    const onlineId = activeConn ? activeConn.deviceId : null;
    for (const [deviceId, rec] of Object.entries(obj)) {
      if (!rec || typeof rec !== 'object') continue;
      const online = deviceId === onlineId;
      out.push({
        deviceId: deviceId,
        pairedAt: Number.isFinite(rec.pairedAt) ? rec.pairedAt : null,
        online: online,
        lastActiveAt: online && activeConn ? activeConn.lastFrameAt : null,
      });
    }
  }
  out.sort((a, b) => (b.pairedAt || 0) - (a.pairedAt || 0));
  return out;
}

/** GET /admin/status：运行状态、端口、设备数、最近一条事件时间。 */
function handleAdminStatus(req, res) {
  const active = pairCode && Date.now() <= pairCode.expiresAt ? pairCode : null;
  json(res, 200, {
    ok: true,
    running: server.listening === true,
    pid: process.pid,
    startedAt: STARTED_AT,
    uptimeSec: Math.round((Date.now() - STARTED_AT) / 1000),
    host: HOST,
    port: PORT,
    deviceCount: devices.size,
    onlineDeviceId: activeConn ? activeConn.deviceId : null,
    onlineDeviceProto: activeConn ? activeConn.proto : null,
    pairCodeActive: Boolean(active),
    pairCodeExpiresAt: active ? active.expiresAt : null,
    lastEventAt: lastEventAt(),
    bridgeEnabled: Boolean(bridge.enabled),
  });
}

/**
 * POST /admin/pair：生成新配对码。
 * 直接复用 newPairCode() / printPairCode()：与启动时打印、/dev/pair-code 用的是
 * **同一个全局 pairCode**，不另写一套生成逻辑；生成新码的同时旧码作废（同一时刻只有一个码）。
 */
function handleAdminPair(req, res) {
  const pc = newPairCode();
  printPairCode('（面板生成）');
  log('管理端点：已生成新配对码（回给面板，同时打印到 stdout）');
  json(res, 200, { ok: true, code: pc.code, expiresAt: pc.expiresAt, ttlMs: PAIR_TTL_MS });
}

/** GET /admin/devices：设备列表 + 最近一条事件时间；文件缺失返回空列表。 */
function handleAdminDevices(req, res) {
  const list = listDevices();
  json(res, 200, {
    ok: true,
    count: list.length,
    devices: list,
    lastEventAt: lastEventAt(),
  });
}

/** 管理端点统一入口：先判回环与密钥，再按路径分发。 */
function handleAdmin(req, res) {
  const url = req.url || '';
  // 非回环来源：连「有管理端点」这件事都不暴露
  if (!isLoopbackRequest(req)) {
    json(res, 404, { ok: false, error: 'not_found' });
    return;
  }
  if (!sharedKeyMatches(requestSharedKey(req))) {
    log(`管理端点拒绝：${req.method} ${url}（密钥缺失或不匹配）`);
    json(res, 401, { ok: false, error: 'unauthorized' });
    return;
  }
  if (url === '/admin/status' && req.method === 'GET') return void handleAdminStatus(req, res);
  if (url === '/admin/pair' && req.method === 'POST') return void handleAdminPair(req, res);
  if (url === '/admin/devices' && req.method === 'GET') return void handleAdminDevices(req, res);
  json(res, 405, { ok: false, error: 'method_not_allowed' });
}

const server = http.createServer((req, res) => {
  const url = req.url || '/';
  if (req.method === 'POST' && url === '/pair') return void handlePair(req, res);
  if (req.method === 'GET' && (url === '/healthz' || url === '/')) return void handleHealthz(res);
  // 面板用的三个管理端点（回环 + 共享密钥，见 handleAdmin）；与 /dev/* 无关
  if (url === '/admin/status' || url === '/admin/pair' || url === '/admin/devices') {
    return void handleAdmin(req, res);
  }
  if (DEV_MODE && req.method === 'POST' && url === '/dev/inject') return void handleDevInject(req, res);
  if (DEV_MODE && req.method === 'POST' && url === '/dev/text') return void handleDevText(req, res);
  if (DEV_MODE && req.method === 'POST' && url === '/dev/speak') return void handleDevSpeak(req, res);
  // 开发调试用：优雅退出（沙箱里杀进程受限时，用它换掉一个在跑的实例）
  if (DEV_MODE && req.method === 'POST' && url === '/dev/quit') {
    json(res, 200, { ok: true });
    log('[dev] 收到 /dev/quit，进程退出');
    setTimeout(() => process.exit(0), 50).unref();
    return;
  }
  if (DEV_MODE && req.method === 'POST' && url === '/dev/pair-code') {
    const pc = newPairCode();
    printPairCode('（开发模式重新生成）');
    return void json(res, 200, { ok: true, code: pc.code, expiresAt: pc.expiresAt });
  }
  json(res, 404, { ok: false, error: 'not_found' });
});

const wss = new WebSocketServer({ server });

/** ws -> conn，避免把自定义字段塞进 ws 对象 */
const conns = new WeakMap();
/** 当前在线的连接（同一时刻只允许一台设备） */
let activeConn = null;

function sendFrame(conn, frame) {
  if (!conn || !conn.ws || conn.ws.readyState !== OPEN) return false;
  try {
    conn.ws.send(JSON.stringify(frame));
    return true;
  } catch (e) {
    log(`发送失败 type=${frame && frame.type}：${e.code || e.message}`);
    return false;
  }
}

function sendError(conn, code, message) {
  sendFrame(conn, { type: 'error', code: code, message: message });
}

function sendAsrError(conn, id, code, message) {
  sendFrame(conn, { type: 'asrError', id: id, code: code, message: message });
}

function closeConn(conn, code, reason) {
  try {
    conn.ws.close(code, reason);
  } catch (e) {
    try {
      conn.ws.terminate();
    } catch (e2) {
      /* ignore */
    }
  }
}

wss.on('connection', (ws) => {
  const conn = {
    ws: ws,
    deviceId: null,
    authed: false,
    proto: PROTO_MIN,
    lastFrameAt: Date.now(),
    lastPingAt: Date.now(),
    helloTimer: null,
    /** M4：id -> 正在收的音频会话 */
    audio: new Map(),
    /** 正在跑 ASR 的数量（同一连接串行上传，这里只是兜底） */
    asrInflight: 0,
    /** M6a：语音回复开关 / 音色（由手机端 speakConfig 帧告知；连上前默认不发音频） */
    speakEnabled: false,
    speakVoice: tts.DEFAULT_VOICE,
    /** 当前在途的 speak 会话（同一连接同一时刻只有一段语音） */
    speak: null,
  };
  conns.set(ws, conn);
  log('ws 新连接（尚未认证）');

  conn.helloTimer = setTimeout(() => {
    if (!conn.authed) {
      log('ws 首帧 hello 超时，断开');
      sendError(conn, 'hello_timeout', 'hello must be the first frame');
      closeConn(conn, 4001, 'hello timeout');
    }
  }, HELLO_TIMEOUT_MS);

  ws.on('message', (data, isBinary) => {
    conn.lastFrameAt = Date.now();
    if (isBinary) {
      // 音频走 JSON + base64 的 audioChunk，proto 1/2 都不接二进制帧
      log('收到二进制帧，回 unsupported');
      sendError(conn, 'unsupported', 'binary frames are not supported (audio uses audioChunk)');
      return;
    }
    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch (e) {
      log(`收到非法 JSON（${data.length} 字节），回 bad_frame`);
      sendError(conn, 'bad_frame', 'frame must be a JSON object');
      return;
    }
    if (!msg || typeof msg !== 'object' || typeof msg.type !== 'string') {
      sendError(conn, 'bad_frame', 'frame must have a string type');
      return;
    }
    const type = msg.type;
    if (!conn.authed) {
      if (type === 'hello') {
        handleHello(conn, msg);
      } else {
        log(`认证前收到非法帧 type=${type}，断开`);
        sendError(conn, 'auth_required', 'send hello first');
        closeConn(conn, 4003, 'auth required');
      }
      return;
    }
    handleAuthedFrame(conn, type, msg);
  });

  ws.on('close', (code) => {
    if (conn.helloTimer) clearTimeout(conn.helloTimer);
    if (activeConn === conn) activeConn = null;
    abortAudioSessions(conn, 'connection_closed');
    // M6a：把在途的语音合成一起停掉（不留一个还在往死连接写音频的定时任务）
    abortSpeak(conn, 'connection_closed');
    // M5：这条连接上还没回执的命令作废（别 20 秒后再往死连接发话）
    for (const [id, rec] of pendingCommands) {
      if (rec.conn !== conn) continue;
      clearTimeout(rec.timer);
      pendingCommands.delete(id);
      log(`command 作废（连接断开）id=${id} kind=${rec.kind}`);
    }
    if (pendingConfirm && pendingConfirm.conn === conn) pendingConfirm = null;
    log(`ws 断开 deviceId=${conn.deviceId || '-'} code=${code}`);
  });

  ws.on('error', (err) => {
    log(`ws 错误 deviceId=${conn.deviceId || '-'}：${err.code || err.message}`);
  });
});

function handleHello(conn, msg) {
  const clientProto = Number(msg.proto);
  if (!Number.isInteger(clientProto) || clientProto < PROTO_MIN || clientProto > PROTO_MAX) {
    log(`hello proto 不支持：${String(msg.proto)}（支持 ${PROTO_MIN}..${PROTO_MAX}）`);
    sendError(conn, 'bad_proto', 'unsupported proto version');
    closeConn(conn, 4004, 'bad proto');
    return;
  }
  const deviceId = typeof msg.deviceId === 'string' ? msg.deviceId.trim() : '';
  const token = typeof msg.token === 'string' ? msg.token : '';
  if (!deviceId || !token) {
    sendError(conn, 'auth_failed', 'deviceId and token are required');
    closeConn(conn, 4003, 'auth failed');
    return;
  }
  const rec = devices.get(deviceId);
  if (!rec || !timingSafeEqualHex(sha256(token), rec.tokenHash)) {
    log(`hello 认证失败 deviceId=${deviceId}`);
    sendError(conn, 'auth_failed', 'unknown device or bad token');
    closeConn(conn, 4003, 'auth failed');
    return;
  }
  // 同一时刻只允许一台设备在线；同一台设备重连就顶掉旧连接（否则它永远回不来）
  if (activeConn && activeConn !== conn) {
    if (activeConn.deviceId !== deviceId) {
      log(`设备 ${deviceId} 被拒绝：${activeConn.deviceId} 已在线，回 busy 并断开`);
      conn.deviceId = deviceId;
      sendError(conn, 'busy', 'another device is online');
      closeConn(conn, 4009, 'busy');
      return;
    }
    log(`设备 ${deviceId} 重新连接，顶掉旧连接`);
    closeConn(activeConn, 4000, 'replaced by new connection');
  }
  conn.authed = true;
  conn.deviceId = deviceId;
  conn.proto = clientProto;
  if (conn.helloTimer) {
    clearTimeout(conn.helloTimer);
    conn.helloTimer = null;
  }
  activeConn = conn;
  sendFrame(conn, { type: 'ready', proto: clientProto, serverTime: Date.now() });
  log(`设备已接入 deviceId=${deviceId} proto=${clientProto}`);
}

function handleAuthedFrame(conn, type, msg) {
  switch (type) {
    case 'ping':
      sendFrame(conn, { type: 'pong' });
      return;
    case 'pong':
      return;
    case 'hello':
      log(`重复 hello（已认证，忽略）deviceId=${conn.deviceId}`);
      sendError(conn, 'already_ready', 'hello already accepted');
      return;
    case 'text': {
      const id = typeof msg.id === 'string' || typeof msg.id === 'number' ? msg.id : null;
      const text = typeof msg.text === 'string' ? msg.text : null;
      if (id === null || text === null) {
        log(`text 帧缺字段 deviceId=${conn.deviceId}`);
        sendError(conn, 'bad_frame', 'text frame needs id and text');
        return;
      }
      log(`收到 text deviceId=${conn.deviceId} id=${id} bytes=${Buffer.byteLength(text, 'utf8')}`);
      // M5：先解析意图，再按结果回话 / 下发命令；异步做，不阻塞 WebSocket 收帧
      Promise.resolve()
        .then(() => onUserText(text, makeReply(conn, id), conn, id, 'text'))
        .catch((e) => {
          log(`text 处理异常：${e && (e.code || e.message)}`);
          sendFrame(conn, { type: 'text', id: id, text: '这句我处理时出错了，再说一次？' });
        });
      return;
    }
    // M5：手机回执命令执行结果 → 电脑把结果说成一句人话
    case 'commandResult':
      onCommandResult(conn, msg);
      return;
    // M6a：手机端上报语音回复开关与音色（每次连上 ready 后都会发一次）
    case 'speakConfig':
      onSpeakConfig(conn, msg);
      return;
    // M6a：用户打断（按录音 / 唤醒命中 / 点别的语音）→ 立刻停掉在途合成
    case 'speakStop':
      onSpeakStop(conn, msg);
      return;
    // M6a：试听（设置页点「试听」）→ 用固定短句合成当前音色
    case 'speakRequest':
      onSpeakRequest(conn, msg);
      return;
    case 'audioStart':
    case 'audioChunk':
    case 'audioEnd':
      handleAudioFrame(conn, type, msg);
      return;
    case 'ready':
    case 'chunk':
    case 'done':
    case 'asrResult':
    case 'asrError':
    case 'command':
    case 'speakStart':
    case 'speakChunk':
    case 'speakDone':
      // 这几帧只该由电脑端发；手机端发来就是协议用错
      log(`收到不该由手机端发送的帧 type=${type} deviceId=${conn.deviceId}`);
      sendError(conn, 'unexpected_frame', `client must not send ${type}`);
      return;
    case 'error':
      log(`手机端回报 error code=${String(msg.code)} deviceId=${conn.deviceId}`);
      return;
    default:
      if (RESERVED.has(type)) {
        log(`收到预留帧 type=${type}，回 unsupported`);
        sendError(conn, 'unsupported', `frame type ${type} is reserved for a later milestone`);
        return;
      }
      log(`收到未知帧 type=${String(type)}，回 unsupported`);
      sendError(conn, 'unsupported', `unknown frame type ${String(type)}`);
  }
}

// ================================================================ M4：音频上传

/**
 * 收齐一段音频的会话状态。
 * 分块可能乱序（WebSocket/TCP 内一般不会，但协议没保证），按 seq 拼接；
 * 缺块或总字节数对不上 → asrError{code:"incomplete"}。
 */
function handleAudioFrame(conn, type, msg) {
  if (conn.proto < 2) {
    log(`proto ${conn.proto} 收到音频帧 type=${type}，回 unsupported`);
    sendError(conn, 'unsupported', 'audio frames require proto 2');
    return;
  }
  if (type === 'audioStart') return onAudioStart(conn, msg);
  if (type === 'audioChunk') return onAudioChunk(conn, msg);
  return onAudioEnd(conn, msg);
}

function onAudioStart(conn, msg) {
  const id = typeof msg.id === 'string' ? msg.id : '';
  if (!AUDIO_ID_RE.test(id)) {
    sendAsrError(conn, id || '-', 'bad_frame', 'audio id must match [A-Za-z0-9._-]{1,64}');
    return;
  }
  if (conn.audio.has(id)) {
    sendAsrError(conn, id, 'duplicate_id', 'audio id already in use');
    return;
  }
  if (conn.audio.size >= 1 || conn.asrInflight >= MAX_ASR_INFLIGHT) {
    sendAsrError(conn, id, 'busy', 'another audio is uploading or being recognized');
    return;
  }
  const size = Number(msg.size);
  const durationMs = Number(msg.durationMs);
  if (!Number.isFinite(size) || size <= 0) {
    sendAsrError(conn, id, 'bad_frame', 'audioStart needs a positive size');
    return;
  }
  if (!Number.isFinite(durationMs) || durationMs < 0) {
    sendAsrError(conn, id, 'bad_frame', 'audioStart needs durationMs');
    return;
  }
  if (size > AUDIO_MAX_BYTES) {
    log(`audioStart 拒绝 id=${id} size=${size}：超过 ${AUDIO_MAX_BYTES} 字节上限`);
    sendAsrError(conn, id, 'too_large', `audio exceeds ${AUDIO_MAX_BYTES} bytes`);
    return;
  }
  if (durationMs > AUDIO_MAX_DURATION_MS) {
    log(`audioStart 拒绝 id=${id} dur=${durationMs}：超过 ${AUDIO_MAX_DURATION_MS} 毫秒上限`);
    sendAsrError(conn, id, 'too_long', `audio exceeds ${AUDIO_MAX_DURATION_MS} ms`);
    return;
  }
  try {
    fs.mkdirSync(TMP_DIR, { recursive: true });
    const filePath = path.join(
      TMP_DIR,
      `asr_${Date.now()}_${crypto.randomBytes(4).toString('hex')}.wav`
    );
    const fd = fs.openSync(filePath, 'w');
    conn.audio.set(id, {
      id: id,
      size: size,
      declaredDurationMs: durationMs,
      mime: typeof msg.mime === 'string' ? msg.mime : 'audio/wav',
      fd: fd,
      filePath: filePath,
      received: 0,
      expectedSeq: 0,
      pending: new Map(),
      chunks: 0,
      startedAt: Date.now(),
    });
  } catch (e) {
    log(`创建临时音频文件失败：${e.code || e.message}`);
    sendAsrError(conn, id, 'server_error', 'cannot create temp audio file');
  }
}

function onAudioChunk(conn, msg) {
  const id = typeof msg.id === 'string' ? msg.id : '';
  const session = conn.audio.get(id);
  if (!session) {
    sendAsrError(conn, id, 'unknown_id', 'audioStart must come first');
    return;
  }
  const seq = Number(msg.seq);
  if (!Number.isInteger(seq) || seq < 0) {
    sendAsrError(conn, id, 'bad_frame', 'audioChunk needs a non-negative seq');
    abortAudioSessions(conn, 'bad_frame', id);
    return;
  }
  if (typeof msg.data !== 'string' || !msg.data) {
    sendAsrError(conn, id, 'bad_frame', 'audioChunk needs base64 data');
    abortAudioSessions(conn, 'bad_frame', id);
    return;
  }
  let buf;
  try {
    buf = Buffer.from(msg.data, 'base64');
  } catch (e) {
    sendAsrError(conn, id, 'bad_frame', 'audioChunk data is not base64');
    abortAudioSessions(conn, 'bad_frame', id);
    return;
  }
  if (session.received + buf.length > session.size) {
    log(`音频超过声明大小 id=${id} size=${session.size}`);
    sendAsrError(conn, id, 'too_large', 'audio exceeds declared size');
    abortAudioSessions(conn, 'too_large', id);
    return;
  }
  if (seq < session.expectedSeq) {
    // 重传的重复块：协议上不会发生，忽略即可（不重复写）
    return;
  }
  if (seq === session.expectedSeq) {
    writeChunk(session, buf);
    session.expectedSeq += 1;
    drainPending(session);
  } else {
    // 前面缺块：先存着，等缺的补上来再一起写（audioEnd 时还没补齐就报 incomplete）
    if (!session.pending.has(seq) && session.pending.size < 4096) session.pending.set(seq, buf);
  }
}

function writeChunk(session, buf) {
  fs.writeSync(session.fd, buf);
  session.received += buf.length;
  session.chunks += 1;
}

function drainPending(session) {
  while (session.pending.has(session.expectedSeq)) {
    const buf = session.pending.get(session.expectedSeq);
    session.pending.delete(session.expectedSeq);
    writeChunk(session, buf);
    session.expectedSeq += 1;
  }
}

function onAudioEnd(conn, msg) {
  const id = typeof msg.id === 'string' ? msg.id : '';
  const session = conn.audio.get(id);
  if (!session) {
    sendAsrError(conn, id, 'unknown_id', 'audioStart must come first');
    return;
  }
  conn.audio.delete(id);
  try {
    fs.closeSync(session.fd);
  } catch (e) {
    /* ignore */
  }
  if (session.pending.size > 0 || session.received !== session.size) {
    log(`音频不完整 id=${id} 声明=${session.size} 实收=${session.received} 缺块=${session.pending.size}`);
    sendAsrError(
      conn,
      id,
      'incomplete',
      `expected ${session.size} bytes, got ${session.received}, missing ${session.pending.size} chunk(s)`
    );
    unlinkQuietly(session.filePath);
    return;
  }
  if (session.received === 0) {
    sendAsrError(conn, id, 'empty', 'audio file is empty');
    unlinkQuietly(session.filePath);
    return;
  }
  runAsr(conn, session);
}

/** 连接断开 / 出错：把没收完的音频会话清掉，临时文件删除。 */
function abortAudioSessions(conn, reason, onlyId) {
  const ids = onlyId ? [onlyId] : Array.from(conn.audio.keys());
  for (const id of ids) {
    const session = conn.audio.get(id);
    if (!session) continue;
    conn.audio.delete(id);
    try {
      fs.closeSync(session.fd);
    } catch (e) {
      /* ignore */
    }
    unlinkQuietly(session.filePath);
    log(`音频会话中止 id=${id} reason=${reason}`);
  }
}

function unlinkQuietly(filePath) {
  try {
    fs.unlinkSync(filePath);
  } catch (e) {
    if (e.code !== 'ENOENT') log(`删除临时音频失败：${e.code || e.message}`);
  }
}

// ---------------------------------------------------------------- ASR 调用

function asrErrorCode(status) {
  if (status === 401 || status === 403) return 'auth_failed';
  if (status === 413) return 'too_large';
  if (status === 429) return 'rate_limited';
  return 'upstream_error';
}

/** 单次 HTTP 调用；网络错误标记 kind=network（唯一允许重试一次的错误）。 */
async function asrCallOnce(buf) {
  const form = new FormData();
  form.append('file', new Blob([buf], { type: 'audio/wav' }), 'audio.wav');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ASR_TIMEOUT_MS);
  let res;
  try {
    res = await fetch(ASR_ENDPOINT, {
      method: 'POST',
      headers: { Authorization: `Bearer ${API_KEY.key}` },
      body: form,
      signal: controller.signal,
    });
  } catch (e) {
    const error = new Error('deepseek asr network error');
    error.kind = e.name === 'AbortError' ? 'timeout' : 'network';
    throw error;
  } finally {
    clearTimeout(timer);
  }
  if (!res.ok) {
    // 不把响应体写进日志（里面可能有上游描述；也没有识别文本）
    const error = new Error(`deepseek asr http ${res.status}`);
    error.kind = 'http';
    error.status = res.status;
    throw error;
  }
  let payload;
  try {
    payload = await res.json();
  } catch (e) {
    const error = new Error('deepseek asr bad json');
    error.kind = 'bad_response';
    throw error;
  }
  return payload;
}

/**
 * 调 ASR：超时 30 秒；仅网络错误重试一次；成功返回 {text, durationMs}。
 * 不打印任何响应内容。
 */
async function requestAsr(buf, id) {
  let lastError = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const payload = await asrCallOnce(buf);
      if (!payload || typeof payload.text !== 'string') {
        const error = new Error('deepseek asr response has no text');
        error.kind = 'bad_response';
        throw error;
      }
      return {
        text: payload.text,
        durationMs: Number.isFinite(payload.duration_ms) ? payload.duration_ms : null,
      };
    } catch (e) {
      lastError = e;
      if (e.kind === 'network' && attempt === 0) {
        log(`asr 网络错误，重试一次（id=${id}）`);
        continue;
      }
      throw e;
    }
  }
  throw lastError;
}

async function runAsr(conn, session) {
  const id = session.id;
  const startedAt = Date.now();
  conn.asrInflight += 1;
  let durMs = session.declaredDurationMs;
  try {
    if (!API_KEY.key) {
      const error = new Error('server has no DeepSeek API key');
      error.kind = 'no_key';
      throw error;
    }
    const buf = fs.readFileSync(session.filePath);
    const result = await requestAsr(buf, id);
    durMs = result.durationMs === null ? session.declaredDurationMs : result.durationMs;
    const costMs = Date.now() - startedAt;
    sendFrame(conn, {
      type: 'asrResult',
      id: id,
      text: result.text,
      durationMs: durMs,
      costMs: costMs,
    });
    log(`asr id=${id} size=${session.size} dur=${durMs} cost=${costMs} ok`);
    // M7 修复：识别结果不能只回给手机，还要进「文本处理」这一条路。
    // 旧实现只发 asrResult 就结束，手机端把它显示成「我的语音」后不会自动发 text 帧，
    // 于是语音永远进不了 intentParser / answerChat（真机现象：asr ok 之后一行都没有）。
    // 手机端仍会收到 asrResult（聊天记录照常显示），这里只是把同一条输入再投一次。
    const heard = typeof result.text === 'string' ? result.text.trim() : '';
    if (!heard) {
      log(`asr id=${id} 识别结果为空，跳过文本处理`);
      return;
    }
    log(`asr id=${id} → 文本处理 source=audio len=${heard.length}`);
    Promise.resolve()
      .then(() => onUserText(heard, makeReply(conn, id), conn, id, 'audio'))
      .catch((e) => {
        log(`语音文本处理异常：${e && (e.code || e.message)}`);
        sendFrame(conn, { type: 'text', id: id, text: '这句我处理时出错了，再说一次？' });
      });
  } catch (e) {
    const costMs = Date.now() - startedAt;
    const code =
      e.kind === 'http' ? asrErrorCode(e.status) : e.kind === 'no_key' ? 'no_key' : e.kind || 'asr_failed';
    // 只回短原因，不回上游正文（既没正文也没音频）
    const message = e.kind === 'no_key' ? 'server has no DeepSeek API key' : e.message;
    sendAsrError(conn, id, code, message.slice(0, 200));
    log(`asr id=${id} size=${session.size} dur=${durMs} cost=${costMs} fail code=${code}`);
  } finally {
    conn.asrInflight = Math.max(0, conn.asrInflight - 1);
    unlinkQuietly(session.filePath);
  }
}

// ================================================================ M6a：语音回复（TTS）

/** speak 会话 id 自增序号 */
let speakSeq = 0;

/**
 * 「回复文本已经确定」之后才调这里：按句切分 → 逐句 TTS → speakStart/Chunk/Done。
 *
 * - 手机端没开语音回复（speakConfig 里 enabled=false）或还没告知过 → **一次 TTS 请求都不发**；
 * - 同一连接同一时刻只有一段语音：新回复顶掉旧的（被打断的那段已在手机端自行停播）；
 * - 文字已经发了，所以这里任何失败都只记日志 + 补 speakStop，不弹错、不再回文字。
 */
function maybeSpeak(conn, replyId, text) {
  if (!conn || !conn.authed) return;
  if (!conn.speakEnabled) return; // 开关关着：连请求都不发（验收第 2 条）
  if (!API_KEY.key) {
    log('tts skipped：电脑端没有 DeepSeek API Key，本次只回文字');
    return;
  }
  const body = String(text == null ? '' : text);
  if (!body.trim()) return;
  startSpeak(conn, replyId, body, conn.speakVoice, 'reply');
}

/** 起一段合成（reply / audition / dev 三条入口共用）。 */
function startSpeak(conn, replyId, text, voice, kind) {
  abortSpeak(conn, 'superseded');
  const id = `sp${Date.now()}-${++speakSeq}`;
  const session = new tts.SpeakSession({
    id: id,
    replyId: replyId,
    text: text,
    voice: voice,
    kind: kind,
    apiKey: API_KEY.key,
    send: (frame) => sendFrame(conn, frame),
    log: log,
  });
  conn.speak = session;
  session.start();
  return session;
}

/** 停掉在途合成（手机 speakStop / 新回复 / 断连）。重复调用是安全的。 */
function abortSpeak(conn, reason) {
  const session = conn && conn.speak;
  if (!session) return false;
  conn.speak = null;
  session.abort(reason);
  return true;
}

/** 手机端上报开关 / 音色。关掉时立刻把在途的那段停掉。 */
function onSpeakConfig(conn, msg) {
  const enabled = msg.enabled === true;
  const voice = tts.normalizeVoice(msg.voice);
  conn.speakEnabled = enabled;
  conn.speakVoice = voice;
  if (!enabled) abortSpeak(conn, 'client_disabled');
  log(`speakConfig deviceId=${conn.deviceId} enabled=${enabled} voice=${voice}`);
}

/** 手机端打断：停止在途合成（音频已经不放了，剩下的句子也不用再合成）。 */
function onSpeakStop(conn, msg) {
  const id = typeof msg.id === 'string' ? msg.id : '';
  const session = conn.speak;
  if (!session || (id && session.id !== id)) {
    log(`speakStop id=${id || '-'}：没有在途合成，忽略`);
    return;
  }
  const where = `已发 ${session.sentSegments}/${session.segments.length} 句`;
  abortSpeak(conn, 'client_speak_stop');
  log(`speakStop id=${session.id}：已停止在途合成（${where}，本次不再请求 TTS）`);
}

/**
 * 试听（设置页「试听当前音色」）：用服务端固定短句合成，不接受客户端正文。
 * 这是用户显式点的操作，**不受语音回复总开关约束**（关着也能先听一下音色）。
 */
function onSpeakRequest(conn, msg) {
  if (!API_KEY.key) {
    sendFrame(conn, { type: 'speakStop', id: String(msg.id || 'aud'), reason: 'no_key' });
    log('tts audition：电脑端没有 DeepSeek API Key，回 speakStop{no_key}');
    return;
  }
  const voice = tts.normalizeVoice(msg.voice || conn.speakVoice);
  startSpeak(conn, null, tts.AUDITION_TEXT, voice, 'audition');
}

// ---------------------------------------------------------------- 启动时清理残留

function cleanupStaleTmp() {
  try {
    if (!fs.existsSync(TMP_DIR)) return;
    const now = Date.now();
    let removed = 0;
    for (const name of fs.readdirSync(TMP_DIR)) {
      if (!/^asr_.*\.wav$/.test(name)) continue;
      const p = path.join(TMP_DIR, name);
      try {
        if (now - fs.statSync(p).mtimeMs > TMP_STALE_MS) {
          fs.unlinkSync(p);
          removed += 1;
        }
      } catch (e) {
        /* ignore */
      }
    }
    if (removed > 0) log(`清理了 ${removed} 个上次残留的临时音频`);
  } catch (e) {
    log(`清理临时目录失败：${e.code || e.message}`);
  }
}

// ---------------------------------------------------------------- 文本回显（M2），M5 换实现

// ================================================================ M5：意图解析 + 命令下发

/** 电脑 → 手机的在飞命令：id -> {kind, args, replyId, conn, at, timer} */
const pendingCommands = new Map();
let commandSeq = 0;

/** 「确认打开」的待确认状态（银行/支付类先问一句）：{conn, appName, at} */
let pendingConfirm = null;

/** 用户回「确认 / 好 / 打开吧」这类词：用来确认上一条问过的事。 */
const CONFIRM_RE = /^(?:确认|确定|好|好的|好呀|可以|行|嗯|对|是的|打开|打开吧|开吧|确认打开|那就打开|开)\S{0,10}$/;

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
}

/** 命令事件记录（本地 JSONL）：给「后续回忆」用；console 日志里不写参数正文。 */
function appendEvent(rec) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.appendFileSync(EVENTS_FILE, JSON.stringify(rec) + '\n', { mode: 0o600 });
  } catch (e) {
    log(`事件记录写入失败：${e.code || e.message}`);
  }
}

/**
 * 闲聊回答：**优先问本机 DSH 会话**（M7 phone-bridge），不可用就退回原来的本地模型。
 *
 * 只有「纯闲聊（意图解析结果 none）」才走会话。带命令/待确认的话不进会话，
 * 因为这个会话手里有工具，把「打开微信」这类话喂进去等于开了一条新的执行通道——
 * 那不在 M7 范围内，也不安全。
 *
 * 日志只写「走了哪条 + 耗时 + 字数/错误码」，不写提问与回复正文。
 *
 * @param {string} rawText 用户原话（原样交给会话，不做改写）
 * @param {string} localPrompt 退回本地模型时用的提示词（意图解析给出的闲聊部分）
 * @returns {Promise<string>} 一句人话
 */
async function answerChat(rawText, localPrompt) {
  if (bridge.enabled) {
    const r = await bridge.ask(rawText);
    if (r.ok && r.reply) {
      log(`答复来源=会话 耗时=${r.ms}ms 字数=${r.reply.length}`);
      return r.reply;
    }
    log(
      `答复来源=本地模型 原因=${r.ok ? 'empty-reply' : r.error}` +
        `${r.timedOut ? '（超时）' : ''}` +
        `${r.status ? ' status=' + r.status : ''} 耗时=${r.ms}ms`
    );
  } else {
    log(`答复来源=本地模型 原因=bridge-${bridge.reason}`);
  }
  return intentParser.replyChat(localPrompt, API_KEY.key);
}

/** 明确不走会话的分支（带命令/待确认）：仍走本地模型，但把原因写进日志。 */
async function answerLocalChat(prompt, why) {
  log(`答复来源=本地模型 原因=${why}`);
  return intentParser.replyChat(prompt, API_KEY.key);
}

/**
 * 收到一条用户文本后怎么回（M2 契约里唯一的入口）。
 *
 * M2：onUserText(text, reply) 原样回显。
 * M5：onUserText(text, reply, conn, replyId) —— 多带连接与请求 id，用来下发 command 帧。
 * M7：纯闲聊改为优先转发给本机 DSH 会话（同一段上下文），插件不可用才退回本地模型。
 *   - 闲聊 / 无意图：回一句人话（模型能答就答，不能答就说「这句我没听出要干什么」），**不下发命令**；
 *   - 缺参数：回一句要问的话，**不下发命令**；
 *   - 有命令：混合意图先回闲聊回答、隔 [COMMAND_AFTER_REPLY_MS] 再下发 command（执行永远在回答之后），
 *     纯命令直接下发；执行结果由手机回 commandResult，再由 onCommandResult 说一句人话。
 */
async function onUserText(text, reply, conn, replyId, source = 'text') {
  // M2 回显模式（--echo=text|stream）：保留给联调，不解析意图
  if (ECHO_MODE !== 'off') {
    if (ECHO_MODE === 'stream') {
      reply({ delta: text });
      reply({ done: true });
    } else {
      reply({ text: text });
    }
    return;
  }

  // 上一条是「银行/支付类先问一句」的待确认状态
  const confirm = consumeConfirm(conn, text);
  if (confirm) {
    log(`确认打开应用 kind=open_app（上一轮问过）`);
    dispatchCommand(conn, replyId, {
      kind: 'open_app',
      args: { appName: confirm.appName, confirmed: true },
      say: `好，打开「${confirm.appName}」`,
      source: 'confirm',
    });
    return;
  }

  const parsed = await intentParser.parseIntent(text, { apiKey: API_KEY.key });
  // M7 修复：语音（audio）与打字（text）现在共用这一个入口。
  // 这一行在「分到哪条分支」之后打，日志里能直接看出语音是不是进来了、被分到哪。
  log(`文本处理 source=${source} len=${text.length} branch=${parsed.type || '-'}`);

  // 1) 没有可执行意图：只回一句话，绝不瞎发命令（M7：优先问本机会话）
  if (parsed.type === 'none') {
    const answer = await answerChat(text, parsed.chat || text);
    log('intent none（无命令）：只回文字，不下发 command');
    reply({
      text:
        answer ||
        '这句我没听出要干什么。要办事的话说具体点，比如「导航去南京南站」「打电话给 13800138000」。',
    });
    return;
  }

  // 2) 需要问用户（缺参数 / 两个命令 / 有冲突）：只回文字，不执行
  if (parsed.type === 'ask') {
    const head = parsed.chat ? await answerLocalChat(parsed.chat, 'intent-ask') : '';
    log(`intent ask（不执行）conflict=${Boolean(parsed.conflict)}`);
    reply({ text: head ? `${head}${parsed.ask}` : parsed.ask });
    return;
  }

  // 3) 一条命令
  const chatAnswer = parsed.chat ? await answerLocalChat(parsed.chat, 'intent-command') : '';
  if (chatAnswer) {
    // 混合意图：先把闲聊答完，再执行（执行永远在回答之后）
    reply({ text: chatAnswer });
    log(`intent command+chat kind=${parsed.kind} source=${parsed.source}：先回答，${COMMAND_AFTER_REPLY_MS}ms 后下发`);
    await delay(COMMAND_AFTER_REPLY_MS);
  }
  dispatchCommand(conn, replyId, parsed);
}

/** 用户回了「确认」就返回上一轮待确认的应用；否则清掉待确认状态。 */
function consumeConfirm(conn, text) {
  const pending = pendingConfirm;
  pendingConfirm = null;
  if (!pending) return null;
  if (pending.conn !== conn) return null;
  if (Date.now() - pending.at > CONFIRM_TTL_MS) return null;
  const t = intentParser.norm(text);
  if (!CONFIRM_RE.test(t)) return null;
  return pending;
}

/** 把一条命令下发给手机，并挂 20 秒超时兜底。 */
function dispatchCommand(conn, replyId, cmd) {
  const id = `c${Date.now()}-${++commandSeq}`;
  const frame = {
    type: 'command',
    id: id,
    kind: cmd.kind,
    args: cmd.args,
    say: cmd.say,
  };
  if (!sendFrame(conn, frame)) {
    log(`command 下发失败 kind=${cmd.kind}（连接不在）`);
    sendFrame(conn, { type: 'text', id: replyId, text: '命令没发出去，通道好像断了。' });
    return false;
  }
  const timer = setTimeout(() => {
    const rec = pendingCommands.get(id);
    if (!rec) return;
    pendingCommands.delete(id);
    log(`command 超时无回执 id=${id} kind=${rec.kind}`);
    sendFrame(conn, {
      type: 'text',
      id: rec.replyId,
      text: '手机那边没回执行结果，可能刚断线了。',
    });
  }, COMMAND_TIMEOUT_MS);
  if (timer.unref) timer.unref();
  pendingCommands.set(id, {
    kind: cmd.kind,
    args: cmd.args,
    replyId: replyId,
    conn: conn,
    at: Date.now(),
    timer: timer,
  });
  // 只记 kind 与参数名，不记参数值（号码、地址、标题这些是用户内容）
  log(
    `command 下发 id=${id} kind=${cmd.kind} args=[${Object.keys(cmd.args || {}).join(',')}] source=${
      cmd.source || 'rule'
    }`
  );
  return true;
}

/** 手机回执：把结果说成一句人话，并另存一条事件记录（不进对话正文）。 */
function onCommandResult(conn, msg) {
  const id = typeof msg.id === 'string' ? msg.id : '';
  const rec = pendingCommands.get(id);
  if (rec) {
    clearTimeout(rec.timer);
    pendingCommands.delete(id);
  }
  const ok = msg.ok === true;
  const launched = msg.launched === true;
  const detail = String(msg.detail == null ? '' : msg.detail).slice(0, 120);
  const kind = rec ? rec.kind : 'unknown';
  const args = rec ? rec.args : {};
  log(`commandResult id=${id} kind=${kind} ok=${ok} launched=${launched} detail=${detail || '-'}`);

  appendEvent({
    at: new Date().toISOString(),
    id: id,
    kind: kind,
    args: args,
    ok: ok,
    launched: launched,
    detail: detail,
  });

  // 银行/支付类被挡回来：记下待确认状态，等他点头
  if (!ok && detail === 'confirm_required' && kind === 'open_app' && args && args.appName) {
    pendingConfirm = { conn: conn, appName: String(args.appName), at: Date.now() };
  }

  const sentence = resultSentence(kind, args, { ok: ok, launched: launched, detail: detail });
  const replyId = rec ? rec.replyId : `r${Date.now()}`;
  sendFrame(conn, {
    type: 'text',
    id: replyId,
    text: sentence,
  });
  // M6a：命令结果这一句也念出来（它同样是一句「回复」）
  maybeSpeak(conn, replyId, sentence);
}

/** (kind, 结果) → 一句人话。失败也在这里说清楚，不另起一段长解释。 */
function resultSentence(kind, args, res) {
  const a = args || {};
  const name = a.appName || a.package || '';
  const dest = a.destination || '';
  const query = a.query || '';
  const title = a.title || '';

  if (!res.ok) {
    switch (res.detail) {
      case 'unsupported':
        return '这个命令手机端还不认识，这次没执行。';
      case 'app_not_found':
        return name ? `没找到这个应用「${name}」，是不是名字记错了？` : '没找到这个应用。';
      case 'app_ambiguous':
        return name ? `有不止一个叫「${name}」的应用，你说是哪个？` : '有不止一个同名应用，你说是哪个？';
      case 'confirm_required':
        return name
          ? `「${name}」是支付/银行类，我先不动（怕误触）；要开就说一句「确认打开${name}」。`
          : '这类应用我先不动；要开就说一句「确认打开」。';
      case 'invalid_args':
        return '参数不对，这次没执行。';
      case 'no_maps_app':
        return '手机上没找到地图应用，没打开导航。';
      case 'no_dialer':
        return '手机上没找到拨号应用。';
      case 'no_sms_app':
        return '手机上没找到短信应用。';
      case 'no_clock_app':
        return '手机上没找到闹钟应用。';
      case 'no_calendar_app':
        return '手机上没找到日历应用。';
      case 'no_browser':
        return '手机上没找到浏览器。';
      case 'launch_failed':
        return '试了一下没拉起来，可能被系统拦了，再点一次试试。';
      case 'exception':
        return '执行时出错了，没拉起来。';
      default:
        return `没执行成功${res.detail ? `（${res.detail}）` : ''}。`;
    }
  }

  switch (kind) {
    case 'call':
      return '拨号盘已打开，号码填好了（按拨出才会打出去）。';
    case 'sms':
      return '短信界面已打开，正文填好了（按发送才会发出去）。';
    case 'navigate':
      return dest ? `地图开了，目的地「${dest}」填好了（点开始导航）。` : '地图开了，目的地填好了。';
    case 'alarm':
      return '闹钟页面开了，时间填好了（确认保存才会生效）。';
    case 'open_app':
      return name ? `「${name}」已经打开了。` : '应用已经打开了。';
    case 'calendar':
      return title
        ? `日程界面给你打开了，「${title}」和时间都填好了（按保存才会存进去）。`
        : '日程界面给你打开了（按保存才会存进去）。';
    case 'open_url':
      return '网页已经用浏览器打开了。';
    case 'search_web':
      return query ? `浏览器里已经搜了「${query}」。` : '浏览器已经打开了。';
    default:
      return '已经执行了。';
  }
}

/** reply 的三种形状：{text} 回 text；{delta} 回 chunk；{done:true} 回 done。 */
function makeReply(conn, id) {
  return function reply(payload) {
    if (!payload || typeof payload !== 'object') return;
    if (payload.done === true) {
      sendFrame(conn, { type: 'done', id: id });
      return;
    }
    if (typeof payload.delta === 'string') {
      sendFrame(conn, { type: 'chunk', id: id, delta: payload.delta });
      return;
    }
    if (typeof payload.text === 'string') {
      sendFrame(conn, { type: 'text', id: id, text: payload.text });
      // M6a：文字先发（同时看到字），紧接着按句合成念出来
      maybeSpeak(conn, id, payload.text);
      return;
    }
    log('reply 参数形状不认识，已忽略');
  };
}

// ---------------------------------------------------------------- 心跳 / 判死

setInterval(() => {
  const now = Date.now();
  for (const ws of wss.clients) {
    const conn = conns.get(ws);
    if (!conn || ws.readyState !== OPEN) continue;
    if (conn.authed && now - conn.lastFrameAt > DEAD_MS) {
      log(`判死：deviceId=${conn.deviceId} 已 ${Math.round((now - conn.lastFrameAt) / 1000)} 秒没有任何帧`);
      try {
        ws.terminate();
      } catch (e) {
        /* ignore */
      }
      continue;
    }
    if (conn.authed && now - conn.lastPingAt >= HEARTBEAT_MS) {
      conn.lastPingAt = now;
      sendFrame(conn, { type: 'ping' });
    }
  }
  if (pairCode && now > pairCode.expiresAt) {
    pairCode = null;
    log('配对码已过期，已作废');
  }
}, 5000);

// ---------------------------------------------------------------- 启动

loadDevices();
cleanupStaleTmp();
server.listen(PORT, HOST, () => {
  log(
    `channel-server 启动，监听 ${HOST}:${PORT}，proto=${PROTO_MIN}..${PROTO_MAX}，` +
      `文本处理=${ECHO_MODE === 'off' ? 'M5 意图解析 + 命令下发' : `回显（${ECHO_MODE}）`}`
  );
  // 只写来源，不写值/长度/前后缀
  if (API_KEY.key) {
    log(`ASR：已加载 DeepSeek API Key（来源=${API_KEY.source === 'env' ? '环境变量' : '.env'}）`);
  } else {
    log('ASR：未配置 DeepSeek API Key（环境变量 DEEPSEEK_API_KEY 与 .env 都没有），收到音频将回 asrError{code:"no_key"}');
  }
  if (ECHO_MODE === 'off') {
    // M7：闲聊是否转发给本机 DSH 会话。只写开关/地址/密钥来源，不写密钥。
    log(`会话桥（phone-bridge）：${bridge.describe()}`);
    const model = intentParser.modelEnabled() && Boolean(API_KEY.key);
    log(
      `意图解析：规则优先 → ${model ? `模型兜底（${intentParser.MODEL_NAME}）` : '未配置模型（规则不中时回「没听出」）'}；` +
        `命令白名单 ${intentParser.KINDS.length} 种：${intentParser.KINDS.join('/')}；` +
        `默认城市=${intentParser.DEFAULT_CITY}；事件记录=${path.relative(__dirname, EVENTS_FILE)}`
    );
  }
  newPairCode();
  printPairCode();
  // M6a：语音回复。不打印密钥来源以外的任何信息；开关由手机端 speakConfig 决定
  if (API_KEY.key) {
    log(
      `语音回复（TTS）：已配置，音色 ${tts.VOICES.join('/')}（默认 ${tts.DEFAULT_VOICE}），` +
        `PCM ${tts.PCM.sampleRate}Hz/${tts.PCM.bits}bit/${tts.PCM.channels}ch，按句合成边合成边发`
    );
  } else {
    log('语音回复（TTS）：未配置 DeepSeek API Key，只回文字（不会发任何 TTS 请求）');
  }
  // 面板用的管理端点：只写来源，不写密钥；没配就明确说会一律 401
  log(
    `管理端点：GET /admin/status、POST /admin/pair、GET /admin/devices（仅回环，密钥来源=${
      ADMIN_TOKEN.source === 'env' ? '环境变量' : ADMIN_TOKEN.source === '.env' ? '.env' : '未配置 → 一律 401'
    }）`
  );
  if (DEV_MODE) log('开发模式已开启：POST /dev/inject、POST /dev/text、POST /dev/speak、POST /dev/pair-code 可用');
});

process.on('SIGINT', () => {
  log('收到 SIGINT，退出');
  process.exit(0);
});
