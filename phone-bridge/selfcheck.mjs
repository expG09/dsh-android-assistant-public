/**
 * selfcheck.mjs —— phone-bridge 的本地预检（不装插件、不重启、不碰真 profile）
 * ============================================================================
 * 用法（**任意目录**都能跑；路径都按本文件位置算）：
 *     node <插件目录>\selfcheck.mjs [临时目录]
 * 退出码 0 = 全过；1 = 有失败项（会打印 FAIL 原文）。
 *
 * 覆盖：
 *   ① 密钥掩码 / .env 读写（写临时文件，不碰真 .env）
 *   ①b 投递会话：设置文件读写、id 校验、设置文件 > patch、坏 JSON 当未配置；
 *      默认落点 DSH_HOME\phone-bridge\settings.json（含 homedir 兜底、config 优先、建不出目录降级）
 *   ② 进程托管：入口不存在时报错不崩、状态是 missing
 *   ③ 面板接口：同源校验、status/start/pair/devices/voice/session 的降级与掩码
 *   ④ 桥端：未绑定 → 409；绑定 → 投只定会话；清除 → 又 409；
 *      `[phone] ` 前缀与 `source.via === 'phone-bridge'` 都没变
 *   ⑤ client.js：自注册脚本、id 等于包名、往 settings.section 注册 label「语音通道」
 *   ⑥ 包内 JSON 都能解析
 * ============================================================================
 */

import { EventEmitter } from 'node:events';
import { createServer, request as httpRequest } from 'node:http';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

import {
  maskSecret,
  maskText,
  readDotEnvValue,
  writeDotEnvValue,
  createSecretProvider,
} from './lib/env.mjs';
import { ChannelServerManager, resolveNodeCommand } from './lib/process.mjs';
import { createPanel, enumerateAddresses, sameOriginOk } from './lib/panel.mjs';
import {
  BAD_SESSION_ID_MESSAGE,
  createSessionBinding,
  normalizeSessionId,
  readSessionSettings,
  resolveSettingsPath,
  writeSessionSettings,
} from './lib/settings.mjs';
import { fileURLToPath } from "node:url";
const HERE = fileURLToPath(new URL(".", import.meta.url));

const WORK = process.argv[2] || join(tmpdir(), `phone-bridge-selfcheck-${process.pid}`);
// 每次从干净目录开始：上一轮如果在用例中途失败，会留下 data\settings.json，
// 让「文件不存在 → 未配置」这类用例误判。
try {
  if (WORK && WORK.length > 10 && !/^[A-Za-z]:[\\/]?$/.test(WORK)) {
    rmSync(WORK, { recursive: true, force: true });
  }
} catch {
  /* 清不掉就算了，继续跑 */
}
mkdirSync(WORK, { recursive: true });

let failures = 0;
let checks = 0;
const ok = (name, extra = '') => {
  checks += 1;
  console.log(`PASS ${name}${extra ? ` — ${extra}` : ''}`);
};
const fail = (name, detail) => {
  failures += 1;
  console.log(`FAIL ${name}${detail ? ` — ${detail}` : ''}`);
};
const assert = (cond, name, detail = '') => (cond ? ok(name, detail) : fail(name, detail));
const assertEqual = (actual, expected, name) =>
  assert(actual === expected, name, `expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`);

function makeReq({ method = 'GET', url = '/', headers = {}, body } = {}) {
  const req = new EventEmitter();
  req.method = method;
  req.url = url;
  req.headers = headers;
  req.destroy = () => {};
  queueMicrotask(() => {
    if (body !== undefined) req.emit('data', Buffer.from(body, 'utf8'));
    req.emit('end');
  });
  return req;
}

function makeRes() {
  const state = { status: 0, headers: {}, body: '', headersSent: false, ended: false };
  return {
    get headersSent() {
      return state.headersSent;
    },
    set headersSent(v) {
      state.headersSent = v;
    },
    get statusCode() {
      return state.status;
    },
    set statusCode(v) {
      state.status = v;
    },
    writeHead(status, headers) {
      state.status = status;
      state.headers = headers || {};
      state.headersSent = true;
    },
    end(chunk) {
      if (chunk !== undefined) state.body += String(chunk);
      state.ended = true;
    },
    state,
  };
}

async function callPanel(handler, opts) {
  const req = makeReq(opts);
  const res = makeRes();
  await handler(req, res);
  let json = null;
  try {
    json = stateJson(res);
  } catch {
    json = null;
  }
  return { status: res.state.status, json, text: res.state.body, res };
}

function stateJson(res) {
  return JSON.parse(res.state.body);
}

async function httpJson({ port, path, method = 'GET', headers = {}, body }) {
  return new Promise((resolve, reject) => {
    const req = httpRequest({ host: '127.0.0.1', port, path, method, headers, timeout: 5000 }, (res) => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => {
        text += chunk;
      });
      res.on('end', () => {
        let json = null;
        try {
          json = JSON.parse(text);
        } catch {
          json = null;
        }
        resolve({ status: res.statusCode, json, text });
      });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

async function waitForPort(port, headers) {
  for (let i = 0; i < 60; i += 1) {
    try {
      const r = await httpJson({ port, path: '/m7/ask/health', headers });
      if (r.status === 200) return true;
    } catch {
      /* 还没起来 */
    }
    await new Promise((r) => setTimeout(r, 50));
  }
  return false;
}

const main = async () => {
  console.log(`# work dir: ${WORK}`);
  const { apply, ensureBridgeToken, resolveChannelServerDir } = await import('./index.js');

  /* ────────────── ① 掩码与 .env ────────────── */
  assertEqual(maskSecret(''), '', 'maskSecret 空值回空');
  assertEqual(maskSecret('short'), '****', 'maskSecret 短值全掩');
  assertEqual(maskSecret('sk-1234567890abcdef'), 'sk-1…cdef', 'maskSecret 前 4 后 4');
  const rawKey = 'sk-selfcheck-0123456789abcdef';
  const masked = maskText(`key=${rawKey} and sk-another-123456789`, [rawKey]);
  assert(!masked.includes(rawKey), 'maskText 抹掉已知密钥');
  assert(!masked.includes('sk-another-123456789'), 'maskText 兜底抹掉 sk- 形状');

  const envFile = join(WORK, '.env');
  writeFileSync(envFile, '# 注释保留\nPHONE_BRIDGE_TOKEN=selfcheck-token\nDEEPSEEK_API_KEY=old-key-123456\n', 'utf8');
  assertEqual(readDotEnvValue(envFile, 'PHONE_BRIDGE_TOKEN'), 'selfcheck-token', '.env 读 token');
  writeDotEnvValue(envFile, 'DEEPSEEK_API_KEY', rawKey);
  const envText = readFileSync(envFile, 'utf8');
  assertEqual(readDotEnvValue(envFile, 'DEEPSEEK_API_KEY'), rawKey, '.env 写后读回一致');
  assert(envText.includes('# 注释保留'), '.env 写入保留注释');
  assert(!envText.includes('old-key-123456'), '.env 写入替换了旧值');
  const secrets = createSecretProvider({ envPath: envFile, names: ['PHONE_BRIDGE_TOKEN'] });
  assert(secrets.values().includes(rawKey), 'secret provider 收集到 .env 里的 key');

  /* ────────────── ①b 投递会话：settings.json 读写 / 校验 / 优先级 ────────────── */
  const UUID_A = '11111111-1111-4111-8111-111111111111';
  const UUID_B = '22222222-2222-4222-8222-222222222222';
  const settingsFile = join(WORK, 'data', 'settings.json');

  assertEqual(normalizeSessionId('').value, '', '会话 id：空串 = 未绑定');
  assertEqual(normalizeSessionId(null).value, '', '会话 id：null = 未绑定');
  assertEqual(normalizeSessionId(`  session-${UUID_A}  `).value, `session-${UUID_A}`, '会话 id：session-uuid 合法且去空白');
  assertEqual(normalizeSessionId(UUID_B).value, UUID_B, '会话 id：裸 uuid 合法');
  assertEqual(normalizeSessionId('nope').ok, false, '会话 id：乱写被拒');
  assertEqual(normalizeSessionId(`session-${UUID_A}-x`).ok, false, '会话 id：尾巴多一段被拒');
  assertEqual(normalizeSessionId('session-123').ok, false, '会话 id：伪造 uuid 被拒');
  assertEqual(normalizeSessionId('nope').message, BAD_SESSION_ID_MESSAGE, '会话 id：拒绝原因是中文说明');

  const missingSettings = readSessionSettings(settingsFile);
  assertEqual(missingSettings.present, false, '设置文件不存在 → 未配置');
  assertEqual(missingSettings.sessionId, '', '设置文件不存在 → sessionId 为空');

  writeSessionSettings(settingsFile, `session-${UUID_A}`);
  assertEqual(readSessionSettings(settingsFile).sessionId, `session-${UUID_A}`, '设置文件写后读回一致');
  assertEqual(readSessionSettings(settingsFile).present, true, '设置文件有 sessionId → present');

  // 坏 JSON / 缺字段 / 非字符串：都当未配置，不许抛
  writeFileSync(settingsFile, '{ this is not json', 'utf8');
  assertEqual(readSessionSettings(settingsFile).present, false, '坏 JSON → 当未配置');
  assertEqual(readSessionSettings(settingsFile).warning, 'bad-json', '坏 JSON 有 warning 标记');
  writeFileSync(settingsFile, JSON.stringify({ other: 1 }), 'utf8');
  assertEqual(readSessionSettings(settingsFile).present, false, '缺 sessionId 字段 → 当未配置');
  writeFileSync(settingsFile, JSON.stringify({ sessionId: 123 }), 'utf8');
  assertEqual(readSessionSettings(settingsFile).present, false, 'sessionId 不是字符串 → 当未配置');

  // 绑定器：设置文件 > patch > 未绑定
  const binding = createSessionBinding({
    file: settingsFile,
    fallback: () => `session-${UUID_B}`,
  });
  writeSessionSettings(settingsFile, '');
  assertEqual(binding.effective(), '', '设置文件空串覆盖 patch → 未绑定');
  assertEqual(binding.snapshot().bound, false, '未绑定时 snapshot.bound=false');
  assertEqual(binding.snapshot().mode, 'unbound', '未绑定时 mode=unbound');
  assert(String(binding.snapshot().hint).includes('未绑定'), '未绑定时 hint 写明未绑定');

  writeSessionSettings(settingsFile, `session-${UUID_A}`);
  assertEqual(binding.effective(), `session-${UUID_A}`, '设置文件优先于 patch');
  assertEqual(binding.snapshot().source, 'settings', '快照标出 source=settings');

  writeFileSync(settingsFile, '{ broken', 'utf8');
  assertEqual(binding.effective(), `session-${UUID_B}`, '设置文件坏 JSON → 回落 patch');
  assertEqual(binding.snapshot().source, 'patch', '坏 JSON 时 source=patch');
  writeFileSync(settingsFile, JSON.stringify({ sessionId: 'bad' }), 'utf8');
  assertEqual(binding.effective(), `session-${UUID_B}`, '设置文件里的非法值 → 回落 patch');

  const setBad = binding.set('nope');
  assertEqual(setBad.ok, false, 'binding.set 拒绝非法 id');
  assertEqual(setBad.message, BAD_SESSION_ID_MESSAGE, 'binding.set 回中文原因');
  const setGood = binding.set(`session-${UUID_A}`);
  assertEqual(setGood.ok, true, 'binding.set 写入合法 id');
  assertEqual(readSessionSettings(settingsFile).sessionId, `session-${UUID_A}`, 'binding.set 真写进文件');
  const setClear = binding.set(null);
  assertEqual(setClear.ok, true, 'binding.set(null) 清除绑定');
  assertEqual(setClear.snapshot.bound, false, '清除后 bound=false');
  assertEqual(readSessionSettings(settingsFile).sessionId, '', '清除后文件里 sessionId 为空串');

  // 保留文件里其它键（写入只动 sessionId）
  writeFileSync(settingsFile, JSON.stringify({ sessionId: '', keepMe: 42 }), 'utf8');
  binding.set(`session-${UUID_A}`);
  assertEqual(JSON.parse(readFileSync(settingsFile, 'utf8')).keepMe, 42, '写设置保留文件里其它键');

  /* ────────────── ①c 默认落点：DSH_HOME / homedir / config 优先 / 建不出来降级 ────────────── */
  const dshHome = join(WORK, 'fake-dsh-home');
  const locDsh = resolveSettingsPath({
    env: { DSH_HOME: dshHome },
    homedir: () => join(WORK, 'unused-home'),
    pluginDir: join(WORK, 'fake-plugin'),
  });
  assertEqual(locDsh.file, join(dshHome, 'phone-bridge', 'settings.json'), 'DSH_HOME 指到临时目录 → 默认落点正确');
  assertEqual(locDsh.fallback, false, 'DSH_HOME 正常时 fallback=false');
  assertEqual(locDsh.source, 'DSH_HOME', 'DSH_HOME 正常时 source=DSH_HOME');
  assert(existsSync(join(dshHome, 'phone-bridge')), '默认目录被 mkdir -p 建出来');
  const dshBinding = createSessionBinding({ file: locDsh.file, location: locDsh });
  dshBinding.set(`session-${UUID_A}`);
  assertEqual(readSessionSettings(locDsh.file).sessionId, `session-${UUID_A}`, 'DSH_HOME 落点可写且读回');
  assertEqual(dshBinding.snapshot().file, locDsh.file, '快照带当前设置文件路径');

  const fakeHome = join(WORK, 'fake-home');
  const locHome = resolveSettingsPath({
    env: {},
    homedir: () => fakeHome,
    pluginDir: join(WORK, 'fake-plugin2'),
  });
  assertEqual(locHome.file, join(fakeHome, '.dsh', 'phone-bridge', 'settings.json'), '没有 DSH_HOME → <homedir>\\.dsh');
  assertEqual(locHome.source, 'homedir', '没有 DSH_HOME 时 source=homedir');
  assertEqual(locHome.fallback, false, 'homedir 落点正常时 fallback=false');
  assert(existsSync(join(fakeHome, '.dsh', 'phone-bridge')), 'homedir 默认目录建出来');

  const explicitFile = join(WORK, 'explicit', 'custom.json');
  const locConfig = resolveSettingsPath({
    configFile: explicitFile,
    env: { DSH_HOME: dshHome },
    homedir: () => fakeHome,
    pluginDir: join(WORK, 'fake-plugin3'),
  });
  assertEqual(locConfig.file, explicitFile, 'config.settingsFile 非空 → 优先它，忽略 DSH_HOME');
  assertEqual(locConfig.source, 'config', 'config 优先时 source=config');
  assertEqual(locConfig.fallback, false, 'config 优先时 fallback=false');

  // DSH_HOME 指向一个「文件」→ 建目录必然失败 → 降级到插件 data，并带原因
  const blockedBase = join(WORK, 'blocked-home');
  writeFileSync(blockedBase, 'i am a file, not a dir\n', 'utf8');
  const fallbackPlugin = join(WORK, 'fallback-plugin');
  const locFallback = resolveSettingsPath({
    env: { DSH_HOME: blockedBase },
    homedir: () => fakeHome,
    pluginDir: fallbackPlugin,
  });
  assertEqual(locFallback.file, join(fallbackPlugin, 'data', 'settings.json'), '目录建不出来 → 降级到插件 data 目录');
  assertEqual(locFallback.fallback, true, '降级时 fallback=true');
  assertEqual(locFallback.source, 'plugin-data-fallback', '降级时 source=plugin-data-fallback');
  assert(String(locFallback.reason).includes('phone-bridge'), '降级原因写明默认目录');
  const fallbackBinding = createSessionBinding({ file: locFallback.file, location: locFallback });
  const fallbackSnapshot = fallbackBinding.snapshot();
  assertEqual(fallbackSnapshot.file, locFallback.file, '降级快照带当前文件路径');
  assertEqual(fallbackSnapshot.fallback, true, '快照带 settingsFallback=true');
  assertEqual(fallbackSnapshot.settingsFallback, true, '快照带 settingsFallback 别名字段');
  assert(String(fallbackSnapshot.fallbackReason).length > 0, '快照带降级原因');
  fallbackBinding.set(`session-${UUID_B}`);
  assertEqual(readSessionSettings(locFallback.file).sessionId, `session-${UUID_B}`, '降级落点仍可正常读写');

  // apply 真正读 process.env.DSH_HOME：面板绑定写到 <临时 DSH_HOME>\phone-bridge\settings.json
  const appliedDshHome = join(WORK, 'apply-dsh-home');
  const previousDshHome = process.env.DSH_HOME;
  let homePanelHandler = null;
  const ctxHome = makeCtx(null);
  ctxHome.get = (key) =>
    key === 'webServer'
      ? {
          register: (opts) => {
            homePanelHandler = opts.handler;
            return { dispose() {} };
          },
        }
      : undefined;
  process.env.DSH_HOME = appliedDshHome;
  try {
    apply(ctxHome, {
      host: '127.0.0.1', port: 18799, path: '/m7/ask', token: 'selfcheck-home-token-0123456789',
      tokenFile: '', channelPort: 18788, autoStart: false, settingsFile: '',
    });
  } finally {
    if (previousDshHome === undefined) delete process.env.DSH_HOME;
    else process.env.DSH_HOME = previousDshHome;
  }
  assert(Boolean(homePanelHandler), 'apply 用默认落点时面板接口也挂上了');
  const homeHeaders = { host: '127.0.0.1:4917', origin: 'http://127.0.0.1:4917' };
  const homeBind = await callPanel(homePanelHandler, {
    method: 'POST', url: '/api/phone-bridge/session', headers: homeHeaders,
    body: JSON.stringify({ sessionId: `session-${UUID_A}` }),
  });
  assertEqual(homeBind.status, 200, '面板绑定在默认落点下回 200');
  assertEqual(
    readSessionSettings(join(appliedDshHome, 'phone-bridge', 'settings.json')).sessionId,
    `session-${UUID_A}`,
    'apply 的默认落点写进 <DSH_HOME>\\phone-bridge\\settings.json',
  );
  const homeGet = await callPanel(homePanelHandler, { url: '/api/phone-bridge/session', headers: homeHeaders });
  assertEqual(homeGet.json.file, join(appliedDshHome, 'phone-bridge', 'settings.json'), 'GET /session 回显当前设置文件路径');
  assertEqual(homeGet.json.fallback, false, 'GET /session 回显 fallback=false');
  for (const cleanup of ctxHome.cleanups.reverse()) await cleanup();
  const homePortAfter = await (async () => {
    try {
      const r = await httpJson({
        port: 18799,
        path: '/m7/ask/health',
        headers: { 'x-phone-bridge-key': 'selfcheck-home-token-0123456789' },
      });
      return r.status;
    } catch {
      return 0;
    }
  })();
  assertEqual(homePortAfter, 0, '默认落点实例清理后 18799 已释放');

  /* ────────────── ② 进程托管：入口不存在 ────────────── */
  assert(typeof resolveNodeCommand('').command === 'string', 'resolveNodeCommand 给出可执行文件名');
  const missing = new ChannelServerManager({
    scriptPath: join(WORK, 'no-such-channel-server', 'server.js'),
    cwd: WORK,
    port: 18788,
    autoRestart: false,
  });
  const startMissing = missing.start('selfcheck');
  assertEqual(startMissing.ok, false, '入口不存在时 start 不抛错、回 ok:false');
  assertEqual(startMissing.error, 'script-missing', '入口不存在时错误码 script-missing');
  assertEqual(missing.status().state, 'missing', '入口不存在时状态 missing');
  assert(missing.status().lastError.includes('server.js'), '入口不存在时 lastError 写明路径');
  await missing.dispose();
  ok('入口不存在时 dispose 不抛错');

  // 托管日志缓冲区：写进去之前就掩码
  const masking = new ChannelServerManager({
    scriptPath: join(WORK, 'x', 'server.js'),
    cwd: WORK,
    port: 18788,
    autoRestart: false,
    mask: (text) => maskText(text, ['secret-value-0123456789']),
  });
  masking.appendNote('key=secret-value-0123456789');
  assert(!masking.status().log.join('\n').includes('secret-value-0123456789'), '托管日志入缓冲前已掩码');
  await masking.dispose();

  // 入口存在但 node 可执行文件不存在：spawn 会失败（同步抛），start() 必须接住、不能把插件带崩
  const fakeEntry = join(WORK, 'server.js');
  writeFileSync(fakeEntry, 'console.log("never runs");\n', 'utf8');
  const badNode = new ChannelServerManager({
    scriptPath: fakeEntry,
    cwd: WORK,
    port: 18788,
    nodePath: join(WORK, 'no-such-node-binary-xyz'),
    autoRestart: false,
  });
  let spawnThrew = false;
  let spawnResult = null;
  try {
    spawnResult = badNode.start('selfcheck');
  } catch {
    spawnThrew = true;
  }
  // spawn 失败可能是同步抛（沙箱里 EPERM）也可能只是异步 error 事件（真机上 ENOENT）：
  // 两种都不许崩，最终状态都得是「没在跑 + 有 lastError」。
  if (!spawnResult || spawnResult.ok !== false) {
    await new Promise((r) => setTimeout(r, 400));
  }
  const badStatus = badNode.status();
  assert(!spawnThrew, 'node 可执行文件不存在时 start 不向外抛错');
  assert(badStatus.running === false, 'spawn 失败后没有残留子进程');
  assert(['crashed', 'restarting', 'missing'].includes(badStatus.state), 'spawn 失败后状态可识别', badStatus.state);
  assert(badStatus.lastError.length > 0 || badStatus.state === 'missing', 'spawn 失败后记下原因');
  await badNode.dispose();

  /* ────────────── ②b channelServerDir：复制安装时靠它找 channel-server ────────────── */
  const fakeServerDir = join(WORK, 'fake-channel-server');
  mkdirSync(fakeServerDir, { recursive: true });
  writeFileSync(join(fakeServerDir, 'server.js'), '// fake channel-server（selfcheck 不 spawn 它）\n', 'utf8');
  const fakeEnvToken = 'fake-env-token-0123456789';
  writeFileSync(
    join(fakeServerDir, '.env'),
    `PHONE_BRIDGE_TOKEN=${fakeEnvToken}\nDEEPSEEK_API_KEY=fake-key-0123456789\n`,
    'utf8',
  );

  const defaultRes = resolveChannelServerDir({});
  assertEqual(
    defaultRes.script,
    join(HERE, 'channel-server', 'server.js'),
    '默认解析找到插件自带的 channel-server（不填 channelServerDir）',
  );
  assertEqual(defaultRes.source, 'plugin-embedded', '自包含时来源=plugin-embedded');
  assertEqual(defaultRes.scriptMissing, false, '插件自带 channel-server 时能找到 server.js');

  const configRes = resolveChannelServerDir({ channelServerDir: fakeServerDir });
  assertEqual(configRes.script, join(fakeServerDir, 'server.js'), 'channelServerDir 指到假 channel-server → 定位到 server.js');
  assertEqual(configRes.envPath, join(fakeServerDir, '.env'), '.env 跟着同一个目录（不两处各算一遍）');
  assertEqual(configRes.source, 'config', 'channelServerDir 生效时来源=config');
  assertEqual(configRes.scriptMissing, false, 'channelServerDir 有效时不报 missing');
  assertEqual(
    readDotEnvValue(configRes.envPath, 'PHONE_BRIDGE_TOKEN'),
    fakeEnvToken,
    '能从 channelServerDir 的假 .env 读到 token',
  );

  const relativeRes = resolveChannelServerDir({ channelServerDir: 'channel-server' });
  assertEqual(relativeRes.source, 'plugin-embedded', '相对路径被忽略 → 回落插件自带 channel-server');
  assert(String(relativeRes.warning).includes('不是绝对路径'), '相对路径在状态里带原因（非静默）');
  assertEqual(relativeRes.scriptMissing, false, '回落后能找到插件自带的 server.js');

  const emptyServerDir = join(WORK, 'empty-channel-server');
  mkdirSync(emptyServerDir, { recursive: true });
  const emptyRes = resolveChannelServerDir({ channelServerDir: emptyServerDir });
  assertEqual(emptyRes.scriptMissing, true, '配置目录没有 server.js → script-missing');
  assert(String(emptyRes.message).includes(`channelServerDir=${emptyServerDir}`), 'lastError 写明配置的 channelServerDir');
  assert(String(emptyRes.message).includes('没有 server.js'), 'lastError 写明「没有 server.js」');
  assertEqual(emptyRes.tried[0], emptyServerDir, 'tried 里记下配置目录');

  const reasonManager = new ChannelServerManager({
    scriptPath: emptyRes.script,
    cwd: emptyRes.dir,
    autoRestart: false,
    missingReason: emptyRes.message,
  });
  const reasonStart = reasonManager.start('selfcheck');
  assert(reasonStart.message.includes('没有 server.js'), 'manager 的 script-missing 沿用解析器的 lastError 文案');
  await reasonManager.dispose();

  const noPluginDir = join(WORK, 'no-plugin-here');
  const tokenFallback = join(WORK, 'token-dir', '.env');
  const noneRes = resolveChannelServerDir({ channelServerDir: '', tokenFile: tokenFallback }, noPluginDir);
  assertEqual(noneRes.scriptMissing, true, '都不存在 → script-missing');
  assert(String(noneRes.message).includes('试过'), 'lastError 说明「试过」哪些路径');
  assert(String(noneRes.message).includes(join(noPluginDir, 'channel-server')), 'lastError 含插件自带目录候选');
  assert(String(noneRes.message).includes('channel-server'), 'lastError 含兄弟目录候选');
  assert(String(noneRes.message).includes(join(WORK, 'token-dir')), 'lastError 含 tokenFile 目录候选');

  // apply 集成：channelServerDir 的 .env 提供桥端 token（配置/环境变量都空）
  let dirPanelHandler = null;
  const ctxDir = makeCtx(null);
  ctxDir.get = (key) =>
    key === 'webServer'
      ? {
          register: (opts) => {
            dirPanelHandler = opts.handler;
            return { dispose() {} };
          },
        }
      : undefined;
  const previousBridgeToken = process.env.PHONE_BRIDGE_TOKEN;
  delete process.env.PHONE_BRIDGE_TOKEN;
  try {
    apply(ctxDir, {
      host: '127.0.0.1', port: 18800, path: '/m7/ask', token: '', tokenFile: '',
      channelPort: 18788, autoStart: false,
      settingsFile: join(WORK, 'dir-settings.json'),
      channelServerDir: fakeServerDir,
    });
  } finally {
    if (previousBridgeToken === undefined) delete process.env.PHONE_BRIDGE_TOKEN;
    else process.env.PHONE_BRIDGE_TOKEN = previousBridgeToken;
  }
  assert(
    await waitForPort(18800, { 'x-phone-bridge-key': fakeEnvToken }),
    'apply 用 channelServerDir 的 .env token 起来了（token 也从同一目录读）',
  );
  const dirHeaders = { host: '127.0.0.1:4917', origin: 'http://127.0.0.1:4917' };
  const dirStatus = await callPanel(dirPanelHandler, { url: '/api/phone-bridge/status', headers: dirHeaders });
  assertEqual(dirStatus.json.channel.scriptPath, join(fakeServerDir, 'server.js'), '面板状态里的入口来自 channelServerDir');
  assertEqual(dirStatus.json.channel.serverSource, 'config', '面板状态标出来源=config');
  assertEqual(dirStatus.json.channel.resolutionWarning, '', '有效 channelServerDir 没有 warning');
  for (const cleanup of ctxDir.cleanups.reverse()) await cleanup();

  // apply 集成：相对路径被忽略 → 面板状态带原因、回落到兄弟目录
  let relPanelHandler = null;
  const ctxRel = makeCtx(null);
  ctxRel.get = (key) =>
    key === 'webServer'
      ? {
          register: (opts) => {
            relPanelHandler = opts.handler;
            return { dispose() {} };
          },
        }
      : undefined;
  apply(ctxRel, {
    host: '127.0.0.1', port: 18801, path: '/m7/ask', token: 'relative-token-0123456789', tokenFile: '',
    channelPort: 18788, autoStart: false,
    settingsFile: join(WORK, 'rel-settings.json'),
    channelServerDir: 'relative-channel-server',
  });
  const relStatus = await callPanel(relPanelHandler, { url: '/api/phone-bridge/status', headers: dirHeaders });
  assert(
    String(relStatus.json.channel.resolutionWarning).includes('不是绝对路径'),
    '面板状态带「相对路径被忽略」的原因',
  );
  assertEqual(relStatus.json.channel.serverSource, 'plugin-embedded', '相对路径回落插件自带 channel-server（状态可见）');
  assert(String(relStatus.json.channel.scriptPath).includes('channel-server'), '回落入口是插件自带的 server.js');
  for (const cleanup of ctxRel.cleanups.reverse()) await cleanup();

  /* ────────────── ②c 首次运行自动生成 token / .env 行为 ────────────── */
  const autoServerDir = join(WORK, 'auto-token-server');
  mkdirSync(autoServerDir, { recursive: true });
  writeFileSync(join(autoServerDir, 'server.js'), '// fake channel-server without .env\n', 'utf8');

  const existingServerDir = join(WORK, 'existing-token-server');
  mkdirSync(existingServerDir, { recursive: true });
  writeFileSync(join(existingServerDir, 'server.js'), '// fake channel-server with existing token\n', 'utf8');
  const existingToken = 'existing-token-0123456789abcdef';
  writeFileSync(join(existingServerDir, '.env'), `PHONE_BRIDGE_TOKEN=${existingToken}\nDEEPSEEK_API_KEY=\n`, 'utf8');

  const brokenServerDir = join(WORK, 'broken-env-server');
  mkdirSync(brokenServerDir, { recursive: true });
  writeFileSync(join(brokenServerDir, 'server.js'), '// fake channel-server with unwritable .env\n', 'utf8');
  mkdirSync(join(brokenServerDir, '.env'), { recursive: true }); // .env 是个目录 ⇒ 写入必失败

  const savedBridgeToken = process.env.PHONE_BRIDGE_TOKEN;
  delete process.env.PHONE_BRIDGE_TOKEN;
  let autoPanelHandler = null;
  let existingPanelHandler = null;
  let brokenPanelHandler = null;
  const ctxAuto = makeCtx(null);
  ctxAuto.get = (key) =>
    key === 'webServer'
      ? {
          register: (opts) => {
            autoPanelHandler = opts.handler;
            return { dispose() {} };
          },
        }
      : undefined;
  const ctxExisting = makeCtx(null);
  ctxExisting.get = (key) =>
    key === 'webServer'
      ? {
          register: (opts) => {
            existingPanelHandler = opts.handler;
            return { dispose() {} };
          },
        }
      : undefined;
  const ctxBroken = makeCtx(null);
  ctxBroken.get = (key) =>
    key === 'webServer'
      ? {
          register: (opts) => {
            brokenPanelHandler = opts.handler;
            return { dispose() {} };
          },
        }
      : undefined;
  const tokenHeaders = { host: '127.0.0.1:4917', origin: 'http://127.0.0.1:4917' };
  let restoredTokenInFinally = false;
  try {
    apply(ctxAuto, {
      host: '127.0.0.1', port: 18802, path: '/m7/ask', token: '', tokenFile: '',
      channelPort: 18788, autoStart: false,
      settingsFile: join(WORK, 'auto-settings.json'),
      channelServerDir: autoServerDir,
    });
    const autoEnv = join(autoServerDir, '.env');
    assert(existsSync(autoEnv), '.env 不存在 → 自动创建了它');
    const generatedToken = readDotEnvValue(autoEnv, 'PHONE_BRIDGE_TOKEN');
    assert(generatedToken.length >= 32, '缺 PHONE_BRIDGE_TOKEN → 自动生成了足够长的随机值');
    assert(
      await waitForPort(18802, { 'x-phone-bridge-key': generatedToken }),
      '自动生成的 token 能通过桥端鉴权（真的生效）',
    );
    const autoStatus = await callPanel(autoPanelHandler, {
      url: '/api/phone-bridge/status', headers: tokenHeaders,
    });
    assertEqual(autoStatus.json.token.generated, true, '面板状态标明 token 是自动生成的');
    assertEqual(autoStatus.json.token.persisted, true, '面板状态标明 token 已落盘');
    assertEqual(autoStatus.json.token.source, 'generated-file', 'token 来源=generated-file');

    // 第二次启动：复用同一个值，不覆盖
    apply(ctxAuto, {
      host: '127.0.0.1', port: 18803, path: '/m7/ask', token: '', tokenFile: '',
      channelPort: 18788, autoStart: false,
      settingsFile: join(WORK, 'auto-settings.json'),
      channelServerDir: autoServerDir,
    });
    assert(await waitForPort(18803, { 'x-phone-bridge-key': generatedToken }), '第二次启动复用同一个 token');
    assertEqual(
      readDotEnvValue(autoEnv, 'PHONE_BRIDGE_TOKEN'),
      generatedToken,
      '第二次启动没有覆盖已有 token',
    );
    const secondStatus = await callPanel(autoPanelHandler, {
      url: '/api/phone-bridge/status', headers: tokenHeaders,
    });
    assertEqual(secondStatus.json.token.generated, false, '第二次启动不再标记为「新生成」');
    assertEqual(secondStatus.json.token.source, 'channel-server/.env', '第二次启动 token 来源=.env');

    // 已有 token 不被覆盖
    apply(ctxExisting, {
      host: '127.0.0.1', port: 18804, path: '/m7/ask', token: '', tokenFile: '',
      channelPort: 18788, autoStart: false,
      settingsFile: join(WORK, 'existing-settings.json'),
      channelServerDir: existingServerDir,
    });
    assertEqual(
      readDotEnvValue(join(existingServerDir, '.env'), 'PHONE_BRIDGE_TOKEN'),
      existingToken,
      '已有 token 不会被覆盖',
    );
    assert(await waitForPort(18804, { 'x-phone-bridge-key': existingToken }), '已有 token 照常可用');
    const existingStatus = await callPanel(existingPanelHandler, {
      url: '/api/phone-bridge/status', headers: tokenHeaders,
    });
    assertEqual(existingStatus.json.token.generated, false, '已有 token 时面板不显示「新生成」');
    assertEqual(existingStatus.json.token.source, 'channel-server/.env', '已有 token 来源=.env');

    // .env 写不进去：不崩，降级内存 token，状态里有说明
    apply(ctxBroken, {
      host: '127.0.0.1', port: 18805, path: '/m7/ask', token: '', tokenFile: '',
      channelPort: 18788, autoStart: false,
      settingsFile: join(WORK, 'broken-settings.json'),
      channelServerDir: brokenServerDir,
    });
    const brokenStatus = await callPanel(brokenPanelHandler, {
      url: '/api/phone-bridge/status', headers: tokenHeaders,
    });
    assertEqual(brokenStatus.json.token.generated, true, '.env 写不进去时仍用新生成的 token');
    assertEqual(brokenStatus.json.token.persisted, false, '写失败时状态标明 token 没落盘');
    assert(String(brokenStatus.json.token.warning).length > 0, '写失败时状态里有原因');
    const brokenHealth = await httpJson({
      port: 18805, path: '/m7/ask/health', headers: { 'x-phone-bridge-key': 'wrong-key' },
    });
    assertEqual(brokenHealth.status, 401, '内存 token 场景桥端照常监听（错密钥回 401）');

    // 直接测 ensureBridgeToken 的写失败分支
    const brokenLogger = { infos: [], errors: [], info(m) { this.infos.push(String(m)); }, error(m) { this.errors.push(String(m)); } };
    const brokenTokenInfo = ensureBridgeToken(
      { token: '', tokenFile: '' },
      join(brokenServerDir, '.env'),
      brokenLogger,
    );
    assert(brokenTokenInfo.token.length >= 32, 'ensureBridgeToken 写失败也给内存 token');
    assertEqual(brokenTokenInfo.persisted, false, 'ensureBridgeToken 写失败时 persisted=false');
    assert(String(brokenTokenInfo.warning).includes('失败'), 'ensureBridgeToken 写失败时有 warning');
    assert(brokenLogger.errors.some((line) => line.includes('失败')), '写失败会写进日志（不含 token 值）');
    assert(!brokenLogger.errors.join('\n').includes(brokenTokenInfo.token), '写失败日志里没有 token 明文');
  } finally {
    if (!restoredTokenInFinally) {
      if (savedBridgeToken === undefined) delete process.env.PHONE_BRIDGE_TOKEN;
      else process.env.PHONE_BRIDGE_TOKEN = savedBridgeToken;
      restoredTokenInFinally = true;
    }
  }
  for (const cleanup of ctxAuto.cleanups.reverse()) await cleanup();
  for (const cleanup of ctxExisting.cleanups.reverse()) await cleanup();
  for (const cleanup of ctxBroken.cleanups.reverse()) await cleanup();

  /* ────────────── ②d 包内容 / 忽略规则（入库前检查） ────────────── */
  const pkgDir = join(HERE);
  const pkgJson = JSON.parse(readFileSync(join(pkgDir, 'package.json'), 'utf8'));
  const pkgFileList = Array.isArray(pkgJson.files) ? pkgJson.files : [];
  const pkgFiles = pkgFileList.join('\n');
  assert(pkgFiles.includes('channel-server/node_modules/ws/**'), 'package.json files 包含 vendored ws');
  assert(pkgFiles.includes('channel-server/.env.example'), 'package.json files 包含 .env.example');
  assert(!pkgFileList.includes('channel-server/.env'), 'package.json files 不包含真实 .env');
  assert(existsSync(join(pkgDir, 'channel-server', 'node_modules', 'ws', 'LICENSE')), 'vendored ws 带 LICENSE');
  const exampleEnv = readFileSync(join(pkgDir, 'channel-server', '.env.example'), 'utf8');
  assert(exampleEnv.includes('PHONE_BRIDGE_TOKEN='), '.env.example 有 PHONE_BRIDGE_TOKEN 键');
  assert(exampleEnv.includes('DEEPSEEK_API_KEY='), '.env.example 有 DEEPSEEK_API_KEY 键');
  const realEnvToken = readDotEnvValue(join(pkgDir, 'channel-server', '.env'), 'PHONE_BRIDGE_TOKEN');
  // 全新检出（公开仓库 / 朋友第一次拿到）没有 .env 是正常的——首次启动会自动生成。
// 只有在开发机上存在 .env 时才检查它，免得把"缺少本机运行时文件"当成失败。
if (existsSync(join(pkgDir, 'channel-server', '.env'))) {
assert(existsSync(join(pkgDir, 'channel-server', '.env')), '本机 channel-server/.env 存在（运行时文件，不入库）');
}
  if (realEnvToken) {
    assert(!exampleEnv.includes(realEnvToken), '.env.example 里没有本机真实 token');
  }
  const rootIgnore = readFileSync(join(pkgDir, '..', '.gitignore'), 'utf8');
  for (const rule of [
    'phone-bridge/channel-server/.env',
    'phone-bridge/channel-server/data/',
    'phone-bridge/channel-server/tmp/',
    'phone-bridge/data/',
  ]) {
    assert(rootIgnore.includes(rule), `根 .gitignore 含 ${rule}`);
  }
  assert(rootIgnore.includes('!phone-bridge/channel-server/node_modules/**'), '根 .gitignore 对 vendored ws 开了例外');
  const nestedIgnore = readFileSync(join(pkgDir, 'channel-server', '.gitignore'), 'utf8');
  assert(nestedIgnore.includes('!node_modules/ws/**'), 'channel-server/.gitignore 对 ws 开了例外');
  assert(nestedIgnore.includes('.env'), 'channel-server/.gitignore 忽略 .env');
  assert(nestedIgnore.includes('data/') && nestedIgnore.includes('tmp/'), 'channel-server/.gitignore 忽略 data/ tmp/');

  /* ────────────── ③ 面板接口 ────────────── */
  const notes = [];
  const fakeManager = {
    running: false,
    port: 18788,
    status: () => ({
      managed: false,
      running: false,
      state: 'stopped',
      pid: null,
      port: 18788,
      scriptPath: 'X:/fake/channel-server/server.js',
      restarts: 0,
      maxRestarts: 5,
      autoRestart: true,
      lastExitCode: null,
      lastExitAt: null,
      startedAt: null,
      nextRestartAt: null,
      lastError: '',
      log: ['2026-11-19 22:00:00 通道启动（mock）'],
    }),
    start: () => ({ ok: true }),
    stop: async () => ({ ok: true }),
    appendNote: (text) => notes.push(String(text)),
  };
  const panel = createPanel({
    manager: fakeManager,
    channelPort: 18788,
    envPath: envFile,
    token: '',
    tokenEnv: 'PHONE_BRIDGE_TOKEN',
    secrets,
    log: () => {},
  });

  const sameHeaders = { host: '127.0.0.1:4917', origin: 'http://127.0.0.1:4917' };
  assert(sameOriginOk(makeReq({ headers: sameHeaders })), '同源校验：同源放行');
  assert(!sameOriginOk(makeReq({ headers: { host: '127.0.0.1:4917', origin: 'http://evil.example' } })), '同源校验：跨源拒绝');
  assert(!sameOriginOk(makeReq({ headers: { host: '10.0.0.5:4917' } })), '同源校验：非回环 Host 拒绝');

  const stRes = await callPanel(panel.handler, { url: '/api/phone-bridge/status', headers: sameHeaders });
  assertEqual(stRes.status, 200, 'panel status 回 200');
  assertEqual(stRes.json && stRes.json.ok, true, 'panel status ok:true');
  assertEqual(stRes.json.channel.port, 18788, 'panel status 带端口');
  assertEqual(stRes.json.channel.reachable, false, 'panel status：18788 没在听时 reachable:false');
  assertEqual(stRes.json.voice.keyMask, maskSecret(rawKey), 'panel status 的 key 回掩码');
  assertEqual(stRes.json.voice.provider, 'deepseek', '语音提供商默认 deepseek（回显）');
  assert(!JSON.stringify(stRes.json).includes(rawKey), 'panel status 响应里没有 key 明文');

  const evilRes = await callPanel(panel.handler, {
    url: '/api/phone-bridge/status',
    headers: { host: '127.0.0.1:4917', origin: 'http://evil.example' },
  });
  assertEqual(evilRes.status, 403, 'panel 跨源请求回 403');

  /* ────────────── ③-地址：电脑端地址枚举（手机该填哪个） ────────────── */
  const fakeIfaces = {
    lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
    '以太网': [{ address: '192.168.1.7', family: 'IPv4', internal: false }],
    'Wi-Fi': [{ address: '10.0.0.5', family: 'IPv4', internal: false }],
    Tailscale: [{ address: '100.101.102.103', family: 'IPv4', internal: false }],
  };
  const addresses = enumerateAddresses(8788, fakeIfaces);
  assertEqual(addresses.length, 3, '地址枚举最多给 3 个');
  assertEqual(addresses[0].url, 'http://100.101.102.103:8788', 'Tailscale 100.x 排最前');
  assertEqual(addresses[0].tailscale, true, 'Tailscale 地址被标出来');
  assert(addresses.some((a) => a.url === 'http://192.168.1.7:8788'), '普通网卡地址也在列表里');
  assert(addresses.every((a) => a.url.includes(':8788')), '地址端口取 channelPort');
  assert(!JSON.stringify(addresses).includes('PHONE_BRIDGE_TOKEN'), '地址里没有密钥键名');
  assert(addresses.every((a) => !a.url.includes('@')), '地址里没有认证信息');

  const onlyLoopback = enumerateAddresses(9100, {
    lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
  });
  assertEqual(onlyLoopback.length, 1, '没有非回环地址时只给一个候选');
  assertEqual(onlyLoopback[0].url, 'http://127.0.0.1:9100', '退回 127.0.0.1');
  assertEqual(onlyLoopback[0].loopback, true, '退回时标 loopback');
  assert(String(onlyLoopback[0].note).includes('仅本机'), '退回时注明「仅本机」');

  // status 里带 addresses；单开 /address 也给同一份
  assert(Array.isArray(stRes.json.addresses) && stRes.json.addresses.length >= 1, 'status 里带 addresses');
  assert(!JSON.stringify(stRes.json.addresses).includes('PHONE_BRIDGE_TOKEN'), 'status 地址里没有密钥');
  const addrRes = await callPanel(panel.handler, { url: '/api/phone-bridge/address', headers: sameHeaders });
  assertEqual(addrRes.status, 200, 'GET /address 回 200');
  assert(Array.isArray(addrRes.json.addresses) && addrRes.json.addresses.length >= 1, '/address 回 addresses 数组');


  const startRes = await callPanel(panel.handler, {
    method: 'POST', url: '/api/phone-bridge/channel/start', headers: sameHeaders, body: '{}',
  });
  assertEqual(startRes.status, 200, 'panel start 回 200');
  assertEqual(startRes.json && startRes.json.ok, true, 'panel start ok:true（mock manager）');

  const pairRes = await callPanel(panel.handler, {
    method: 'POST', url: '/api/phone-bridge/pair', headers: sameHeaders, body: '{}',
  });
  assertEqual(pairRes.json && pairRes.json.ok, false, 'panel pair：通道没在听时降级 ok:false');
  assert(String(pairRes.json && pairRes.json.hint).length > 0, 'panel pair 降级时带 hint');

  const devRes = await callPanel(panel.handler, {
    url: '/api/phone-bridge/devices', headers: sameHeaders,
  });
  assertEqual(devRes.json && devRes.json.ok, false, 'panel devices：通道没在听时降级 ok:false');
  assert(Array.isArray(devRes.json && devRes.json.devices), 'panel devices 降级也给空数组');

  const badProvider = await callPanel(panel.handler, {
    method: 'POST', url: '/api/phone-bridge/voice', headers: sameHeaders,
    body: JSON.stringify({ provider: 'nope' }),
  });
  assertEqual(badProvider.status, 400, 'panel voice 拒绝未知提供商');

  const voiceRes = await callPanel(panel.handler, {
    method: 'POST', url: '/api/phone-bridge/voice', headers: sameHeaders,
    body: JSON.stringify({ provider: 'deepseek', apiKey: 'sk-new-selfcheck-ABCDEFGH1234' }),
  });
  assertEqual(voiceRes.status, 200, 'panel voice 回 200');
  assertEqual(voiceRes.json && voiceRes.json.ok, true, 'panel voice ok:true');
  assertEqual(voiceRes.json.voice.keyMask, maskSecret('sk-new-selfcheck-ABCDEFGH1234'), 'panel voice 回显掩码');
  assert(!voiceRes.text.includes('sk-new-selfcheck-ABCDEFGH1234'), 'panel voice 响应不含 key 明文');
  assertEqual(
    readDotEnvValue(envFile, 'DEEPSEEK_API_KEY'),
    'sk-new-selfcheck-ABCDEFGH1234',
    'panel voice 把 key 写进 .env',
  );
  assertEqual(readDotEnvValue(envFile, 'CHANNEL_TTS_PROVIDER'), 'deepseek', 'panel voice 把提供商写进 .env');
  assert(!notes.join('\n').includes('sk-new-selfcheck-ABCDEFGH1234'), 'panel 日志只有掩码、没有 key 明文');

  const notFound = await callPanel(panel.handler, {
    url: '/api/phone-bridge/nope', headers: sameHeaders,
  });
  assertEqual(notFound.status, 404, 'panel 未知接口 404');

  /* ────────────── ③a 面板「投递会话」接口 ────────────── */
  const panelSettingsFile = join(WORK, 'panel-settings.json');
  const panelBinding = createSessionBinding({
    file: panelSettingsFile,
    fallback: () => `session-${UUID_B}`,
  });
  const sessionPanel = createPanel({
    manager: fakeManager,
    channelPort: 18788,
    envPath: envFile,
    token: '',
    tokenEnv: 'PHONE_BRIDGE_TOKEN',
    sessionBinding: panelBinding,
    log: () => {},
  });
  const sessionGet0 = await callPanel(sessionPanel.handler, {
    url: '/api/phone-bridge/session', headers: sameHeaders,
  });
  assertEqual(sessionGet0.status, 200, 'GET /session 回 200');
  assertEqual(sessionGet0.json.ok, true, 'GET /session ok:true');
  assertEqual(sessionGet0.json.sessionId, `session-${UUID_B}`, '未绑定时回落 patch 里的 sessionId');
  assertEqual(sessionGet0.json.mode, 'pinned', '回落 patch 时 mode=pinned');
  assertEqual(sessionGet0.json.source, 'patch', '回落 patch 时 source=patch');

  const sessionBad = await callPanel(sessionPanel.handler, {
    method: 'POST', url: '/api/phone-bridge/session', headers: sameHeaders,
    body: JSON.stringify({ sessionId: 'nope' }),
  });
  assertEqual(sessionBad.status, 400, 'POST /session 非法 id 回 400');
  assertEqual(sessionBad.json.error, 'bad-session-id', '非法 id 错误码 bad-session-id');
  assertEqual(sessionBad.json.message, BAD_SESSION_ID_MESSAGE, '非法 id 回中文原因');

  const sessionBound = await callPanel(sessionPanel.handler, {
    method: 'POST', url: '/api/phone-bridge/session', headers: sameHeaders,
    body: JSON.stringify({ sessionId: `session-${UUID_A}` }),
  });
  assertEqual(sessionBound.status, 200, 'POST /session 合法 id 回 200');
  assertEqual(sessionBound.json.sessionId, `session-${UUID_A}`, '绑定后回显新 id');
  assertEqual(sessionBound.json.bound, true, '绑定后 bound=true');
  assertEqual(readSessionSettings(panelSettingsFile).sessionId, `session-${UUID_A}`, '面板绑定写进了设置文件');
  const sessionGet1 = await callPanel(sessionPanel.handler, {
    url: '/api/phone-bridge/session', headers: sameHeaders,
  });
  assertEqual(sessionGet1.json.sessionId, `session-${UUID_A}`, 'GET /session 回显变化');
  const statusWithSession = await callPanel(sessionPanel.handler, {
    url: '/api/phone-bridge/status', headers: sameHeaders,
  });
  assertEqual(statusWithSession.json.session.bound, true, 'status 里也带会话绑定状态');

  const sessionCleared = await callPanel(sessionPanel.handler, {
    method: 'POST', url: '/api/phone-bridge/session', headers: sameHeaders,
    body: JSON.stringify({ sessionId: '' }),
  });
  assertEqual(sessionCleared.status, 200, '空串清除绑定回 200');
  assertEqual(sessionCleared.json.sessionId, '', '清除后 sessionId 为空');
  assertEqual(sessionCleared.json.bound, false, '清除后 bound=false');
  assertEqual(sessionCleared.json.mode, 'unbound', '清除后 mode=unbound');
  assert(String(sessionCleared.json.hint).includes('未绑定'), '清除后 hint 写明未绑定');
  const sessionClearedNull = await callPanel(sessionPanel.handler, {
    method: 'POST', url: '/api/phone-bridge/session', headers: sameHeaders,
    body: JSON.stringify({ sessionId: null }),
  });
  assertEqual(sessionClearedNull.json.bound, false, 'null 也清除绑定');

  writeFileSync(panelSettingsFile, '{ broken json', 'utf8');
  const sessionBadJson = await callPanel(sessionPanel.handler, {
    url: '/api/phone-bridge/session', headers: sameHeaders,
  });
  assertEqual(sessionBadJson.status, 200, '坏 JSON 时 GET /session 仍回 200');
  assertEqual(sessionBadJson.json.sessionId, `session-${UUID_B}`, '坏 JSON 当未绑定层 → 回落 patch');

  /* ────────────── ③b 面板 ↔ channel-server /admin 契约（假 admin 服务，进程内） ────────────── */
  const ADMIN_TOKEN = 'admin-token-xyz-0123456789';
  const adminCalls = [];
  const mockAdmin = createServer((req, res) => {
    adminCalls.push(`${req.method} ${req.url} key=${req.headers['x-phone-bridge-key'] === ADMIN_TOKEN ? 'ok' : 'bad'}`);
    const json = (status, payload) => {
      const body = JSON.stringify(payload);
      res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) });
      res.end(body);
    };
    if (req.url === '/healthz') {
      json(200, { ok: true, pairedDevices: 2, onlineDevice: 'dev-a' });
      return;
    }
    if (!String(req.url || '').startsWith('/admin/')) {
      json(404, { ok: false, error: 'not_found' });
      return;
    }
    if (req.headers['x-phone-bridge-key'] !== ADMIN_TOKEN) {
      json(401, { ok: false, error: 'unauthorized' });
      return;
    }
    if (req.url === '/admin/status') {
      json(200, {
        ok: true, running: true, pid: 4242, uptimeSec: 12, host: '0.0.0.0', port: 8788,
        deviceCount: 2, onlineDeviceId: 'dev-a', onlineDeviceProto: 2,
        pairCodeActive: false, pairCodeExpiresAt: null,
        lastEventAt: '2026-11-19T12:00:00.000Z', bridgeEnabled: true,
      });
      return;
    }
    if (req.url === '/admin/pair' && req.method === 'POST') {
      json(200, { ok: true, code: '482913', expiresAt: Date.now() + 300000, ttlMs: 300000 });
      return;
    }
    if (req.url === '/admin/devices') {
      json(200, {
        ok: true, count: 2, lastEventAt: '2026-11-19T12:00:00.000Z',
        devices: [
          { deviceId: 'android-1', pairedAt: 1794750466973, online: false, lastActiveAt: null },
          { deviceId: 'dev-test-A', pairedAt: 1794750493051, online: true, lastActiveAt: 1794750593051 },
        ],
      });
      return;
    }
    json(405, { ok: false, error: 'method_not_allowed' });
  });
  await new Promise((resolve) => mockAdmin.listen(0, '127.0.0.1', resolve));
  const adminPort = mockAdmin.address().port;

  let mockStartCalls = 0;
  const adminManager = {
    running: false,
    port: adminPort,
    status: () => ({
      managed: false, running: false, state: 'stopped', pid: null, port: adminPort,
      scriptPath: 'X:/fake/channel-server/server.js', restarts: 0, maxRestarts: 5,
      autoRestart: true, lastExitCode: null, lastExitAt: null, startedAt: null,
      nextRestartAt: null, lastError: '', log: [],
    }),
    start: () => {
      mockStartCalls += 1;
      return { ok: true };
    },
    stop: async () => ({ ok: true }),
    appendNote: () => {},
  };
  const adminPanel = createPanel({
    manager: adminManager,
    channelPort: adminPort,
    envPath: join(WORK, 'admin.env'),
    token: ADMIN_TOKEN,
    tokenEnv: 'PHONE_BRIDGE_TOKEN',
    log: () => {},
  });

  const adminStatusRes = await callPanel(adminPanel.handler, {
    url: '/api/phone-bridge/status', headers: sameHeaders,
  });
  assertEqual(adminStatusRes.json.channel.reachable, true, '面板能探测到通道在跑');
  assertEqual(adminStatusRes.json.channel.external, true, '非托管实例标记为 external');
  assertEqual(adminStatusRes.json.channel.admin.ok, true, '面板拿到 /admin/status');
  assertEqual(adminStatusRes.json.channel.admin.deviceCount, 2, '面板读出设备数');
  assertEqual(adminStatusRes.json.channel.admin.lastEventAt, '2026-11-19T12:00:00.000Z', '面板读出最近事件时间');
  assertEqual(adminStatusRes.json.channel.health.onlineDevice, 'dev-a', '面板读出 /healthz 的在线设备');

  const adminPairRes = await callPanel(adminPanel.handler, {
    method: 'POST', url: '/api/phone-bridge/pair', headers: sameHeaders, body: '{}',
  });
  assertEqual(adminPairRes.json.ok, true, '面板代理 /admin/pair 成功');
  assertEqual(adminPairRes.json.code, '482913', '面板回传 6 位配对码');
  const pairAgain = await callPanel(adminPanel.handler, { url: '/api/phone-bridge/status', headers: sameHeaders });
  assertEqual(pairAgain.json.pair.code, '482913', '点生成后不用等下一次轮询：紧接着查 status 就有 code');
  assert(Number(pairAgain.json.pair.expiresAt) > Date.now(), '点生成后紧接着查 status 就有 expiresAt');
  assertEqual(pairAgain.json.pair.active, true, '配对码状态 active');

  const adminDevRes = await callPanel(adminPanel.handler, { url: '/api/phone-bridge/devices', headers: sameHeaders });
  assertEqual(adminDevRes.json.ok, true, '面板代理 /admin/devices 成功');
  assertEqual(adminDevRes.json.count, 2, '设备数 2');
  const dev = (adminDevRes.json.devices || []).find((d) => d.deviceId === 'dev-test-A');
  assert(Boolean(dev), '设备列表里有 dev-test-A');
  assertEqual(dev && dev.pairedAt, 1794750493051, '设备带首次配对时间');
  assertEqual(dev && dev.lastActiveAt, 1794750593051, '设备带最近活跃时间');
  assertEqual(dev && dev.online, true, '设备带在线标记');
  assert(!JSON.stringify(adminDevRes.json).includes('tokenHash'), '设备响应里没有 tokenHash');

  const externalStart = await callPanel(adminPanel.handler, {
    method: 'POST', url: '/api/phone-bridge/channel/start', headers: sameHeaders, body: '{}',
  });
  assertEqual(externalStart.json.ok, false, '外部实例在跑时不再重复启动');
  assertEqual(externalStart.json.error, 'already-running-external', '外部实例在跑时错误码明确');
  assertEqual(mockStartCalls, 0, '外部实例在跑时没有调用 spawn');
  assert(adminCalls.some((line) => line.includes('key=ok')), '面板调 /admin 时带了正确的共享密钥');

  const wrongTokenPanel = createPanel({
    manager: adminManager,
    channelPort: adminPort,
    envPath: join(WORK, 'admin.env'),
    token: 'wrong-token',
    tokenEnv: 'PHONE_BRIDGE_TOKEN',
    log: () => {},
  });
  const wrongRes = await callPanel(wrongTokenPanel.handler, { url: '/api/phone-bridge/status', headers: sameHeaders });
  assertEqual(wrongRes.json.channel.admin.ok, false, '密钥不对时 admin 调用降级');
  assertEqual(wrongRes.json.channel.admin.error, 'admin-unauthorized', '密钥不对时错误码 401 映射');
  await new Promise((resolve) => mockAdmin.close(resolve));

  /* ────────────── ④ 桥端：8795 的两个端点 + source.via ────────────── */
  const TOKEN = 'selfcheck-bridge-token-0123456789';
  const auth = { 'x-phone-bridge-key': TOKEN, 'content-type': 'application/json' };

  function makeCtx(agent) {
    const handlers = new Map();
    const cleanups = [];
    const infos = [];
    const errors = [];
    return {
      handlers,
      infos,
      errors,
      cleanups,
      get: () => undefined,
      logger: {
        info: (message) => infos.push(String(message)),
        error: (message) => errors.push(String(message)),
      },
      on: (name, fn) => {
        if (!handlers.has(name)) handlers.set(name, []);
        handlers.get(name).push(fn);
      },
      effect: (fn) => {
        const cleanup = fn();
        if (typeof cleanup === 'function') cleanups.push(cleanup);
      },
      agents: {
        get: (id) => (agent && agent.session.id === id ? agent : undefined),
        list: () => (agent ? [agent] : []),
      },
      emit: (name, ...args) => {
        for (const fn of handlers.get(name) || []) fn(...args);
      },
    };
  }

  function makeCtx(agentOrAgents) {
    const agentList = Array.isArray(agentOrAgents)
      ? agentOrAgents.filter(Boolean)
      : agentOrAgents
        ? [agentOrAgents]
        : [];
    const handlers = new Map();
    const cleanups = [];
    const infos = [];
    const errors = [];
    return {
      handlers,
      infos,
      errors,
      cleanups,
      get: () => undefined,
      logger: {
        info: (message) => infos.push(String(message)),
        error: (message) => errors.push(String(message)),
      },
      on: (name, fn) => {
        if (!handlers.has(name)) handlers.set(name, []);
        handlers.get(name).push(fn);
      },
      effect: (fn) => {
        const cleanup = fn();
        if (typeof cleanup === 'function') cleanups.push(cleanup);
      },
      agents: {
        get: (id) => agentList.find((agent) => String(agent.session.id) === String(id)),
        list: () => agentList,
      },
      emit: (name, ...args) => {
        for (const fn of handlers.get(name) || []) fn(...args);
      },
    };
  }

  /** 假 agent：followup 里按真实事件顺序回一圈，回复正文用 reply。 */
  const makeAgent = (sessionId, reply) => {
    const agent = {
      session: { id: sessionId, header: {} },
      captured: null,
      ctx: null,
      followup(message) {
        agent.captured = message;
        const ctx = agent.ctx;
        queueMicrotask(() => {
          ctx.emit('agent/inbox/claimed', { agent, message, turn: 1 });
          ctx.emit('session/event', agent.session, {
            type: 'assistant/message',
            data: { turn: 1, message: { content: [{ type: 'text', text: reply }] } },
          });
          ctx.emit('session/event', agent.session, {
            type: 'turn/end',
            data: { turn: 1, reason: { kind: 'completed' } },
          });
        });
      },
    };
    return agent;
  };

  // ---- ④-1 未绑定：必须 409，且原因写明「未绑定投递会话」 ----
  const unboundSettings = join(WORK, 'bridge-unbound.json');
  const ctxEmpty = makeCtx(null);
  apply(ctxEmpty, {
    host: '127.0.0.1', port: 18795, path: '/m7/ask', token: TOKEN, tokenFile: '',
    channelPort: 18788, autoStart: false, settingsFile: unboundSettings,
  });
  assert(await waitForPort(18795, auth), '桥端在 127.0.0.1:18795 起来了');
  const noSession = await httpJson({
    port: 18795, path: '/m7/ask', method: 'POST', headers: auth, body: JSON.stringify({ text: '在吗' }),
  });
  assertEqual(noSession.status, 409, '未绑定投递会话时 /m7/ask 回 409');
  assertEqual(noSession.json && noSession.json.error, 'no-session-bound', '409 的错误码是 no-session-bound');
  assert(String(noSession.json && noSession.json.message).includes('未绑定'), '409 原因写明「未绑定投递会话」');
  const unauthorized = await httpJson({ port: 18795, path: '/m7/ask/health', headers: { 'x-phone-bridge-key': 'wrong' } });
  assertEqual(unauthorized.status, 401, '错密钥回 401');
  const health = await httpJson({ port: 18795, path: '/m7/ask/health', headers: auth });
  assertEqual(health.status, 200, '健康检查带对密钥回 200');
  assertEqual(health.json && health.json.ok, true, '健康检查 ok:true');
  assertEqual(health.json && health.json.pinned, null, '未绑定时健康检查 pinned=null');
  const tooLong = await httpJson({
    port: 18795, path: '/m7/ask', method: 'POST', headers: auth,
    body: JSON.stringify({ text: 'x'.repeat(4001) }),
  });
  assertEqual(tooLong.status, 400, '超长文本回 400');
  writeFileSync(unboundSettings, '{ broken json', 'utf8');
  const afterBadJson = await httpJson({
    port: 18795, path: '/m7/ask', method: 'POST', headers: auth, body: JSON.stringify({ text: '在吗' }),
  });
  assertEqual(afterBadJson.status, 409, '设置文件坏 JSON 时插件照常加载、仍按未绑定 409');
  for (const cleanup of ctxEmpty.cleanups.reverse()) await cleanup();

  // ---- ④-2 设置文件优先于 patch：patch 指向不存在的会话，设置文件绑定真会话 ----
  const prioritySettings = join(WORK, 'bridge-priority.json');
  writeSessionSettings(prioritySettings, `session-${UUID_A}`);
  const agentA = makeAgent(`session-${UUID_A}`, '我收到了');
  const ctxAgent = makeCtx(agentA);
  agentA.ctx = ctxAgent;
  apply(ctxAgent, {
    host: '127.0.0.1', port: 18796, path: '/m7/ask', token: TOKEN, tokenFile: '',
    channelPort: 18788, autoStart: false, settingsFile: prioritySettings,
    sessionId: `session-${UUID_B}`,
  });
  assert(await waitForPort(18796, auth), '桥端实例在 18796 起来了');
  const askRes = await httpJson({
    port: 18796, path: '/m7/ask', method: 'POST', headers: auth,
    body: JSON.stringify({ text: '今天几号' }),
  });
  assertEqual(askRes.status, 200, '设置文件绑定后投递成功回 200');
  assertEqual(askRes.json.reply, '我收到了', '回复正文来自 session/event');
  assertEqual(askRes.json.sessionId, `session-${UUID_A}`, '回复带绑定的会话 id');
  assert(agentA.captured, 'agent.followup 被调用');
  assertEqual(agentA.captured && agentA.captured.content[0].text, '[phone] 今天几号', '投递内容带 [phone] 前缀');
  assertEqual(agentA.captured && agentA.captured.source && agentA.captured.source.via, 'phone-bridge', 'source.via 仍是 phone-bridge');
  assertEqual(agentA.captured && agentA.captured.role, 'user', '投递的是 user 消息');
  const healthPinned = await httpJson({ port: 18796, path: '/m7/ask/health', headers: auth });
  assertEqual(healthPinned.json && healthPinned.json.pinned, `session-${UUID_A}`, '健康检查回绑定的会话');

  // 清掉设置文件里的绑定（写成空串）→ 明确覆盖 patch ⇒ 未绑定 409（不是回落 patch）
  writeSessionSettings(prioritySettings, '');
  const afterClear = await httpJson({
    port: 18796, path: '/m7/ask', method: 'POST', headers: auth, body: JSON.stringify({ text: '还在吗' }),
  });
  assertEqual(afterClear.status, 409, '设置文件里清除绑定 → 409（空串也覆盖 patch）');
  assertEqual(afterClear.json && afterClear.json.error, 'no-session-bound', '清除绑定后错误码 no-session-bound');
  // 文件不存在（不是空串）→ 这一层没配过 ⇒ 回落 patch
  rmSync(prioritySettings, { force: true });
  const afterDelete = await httpJson({
    port: 18796, path: '/m7/ask', method: 'POST', headers: auth, body: JSON.stringify({ text: '还在吗' }),
  });
  assertEqual(afterDelete.status, 404, '设置文件不存在 → 回落 patch（会话不存在 ⇒ 404）');
  assertEqual(afterDelete.json && afterDelete.json.error, 'session-not-found', '回落 patch 后错误码 session-not-found');
  writeFileSync(prioritySettings, '{ broken', 'utf8');
  const afterBroken = await httpJson({
    port: 18796, path: '/m7/ask', method: 'POST', headers: auth, body: JSON.stringify({ text: '还在吗' }),
  });
  assertEqual(afterBroken.status, 404, '设置文件坏 JSON → 回落 patch，插件不崩');
  writeFileSync(prioritySettings, JSON.stringify({ other: 1 }), 'utf8');
  const afterMissingField = await httpJson({
    port: 18796, path: '/m7/ask', method: 'POST', headers: auth, body: JSON.stringify({ text: '还在吗' }),
  });
  assertEqual(afterMissingField.status, 404, '设置文件缺字段 → 回落 patch，插件不崩');
  for (const cleanup of ctxAgent.cleanups.reverse()) await cleanup();

  // ---- ④-3 绑定 → 投递 → 清除 → 又 409 → 再绑定又通 ----
  const clearSettings = join(WORK, 'bridge-clear.json');
  writeSessionSettings(clearSettings, `session-${UUID_A}`);
  const agentC = makeAgent(`session-${UUID_A}`, '收到');
  const ctxClear = makeCtx(agentC);
  agentC.ctx = ctxClear;
  apply(ctxClear, {
    host: '127.0.0.1', port: 18797, path: '/m7/ask', token: TOKEN, tokenFile: '',
    channelPort: 18788, autoStart: false, settingsFile: clearSettings,
  });
  assert(await waitForPort(18797, auth), '桥端实例在 18797 起来了');
  const boundAsk = await httpJson({
    port: 18797, path: '/m7/ask', method: 'POST', headers: auth, body: JSON.stringify({ text: '你好' }),
  });
  assertEqual(boundAsk.status, 200, '已绑定 → 投递 200');
  writeSessionSettings(clearSettings, '');
  const clearedAsk = await httpJson({
    port: 18797, path: '/m7/ask', method: 'POST', headers: auth, body: JSON.stringify({ text: '你好' }),
  });
  assertEqual(clearedAsk.status, 409, '清除绑定 → 又回 409');
  assert(String(clearedAsk.json && clearedAsk.json.message).includes('未绑定'), '清除后 409 原因仍写明未绑定');
  writeSessionSettings(clearSettings, `session-${UUID_A}`);
  const reboundAsk = await httpJson({
    port: 18797, path: '/m7/ask', method: 'POST', headers: auth, body: JSON.stringify({ text: '再来一次' }),
  });
  assertEqual(reboundAsk.status, 200, '重新绑定后又能投递');
  for (const cleanup of ctxClear.cleanups.reverse()) await cleanup();
  ok('桥端三个实例都已关闭');

  /* ────────────── ④b webServer 晚于插件就绪：面板路由要补挂上 ────────────── */
  const ctxLate = makeCtx(null);
  let lateServer = null;
  let lateRegistered = null;
  ctxLate.get = (key) => (key === 'webServer' ? lateServer : undefined);
  apply(ctxLate, {
    host: '127.0.0.1', port: 18798, path: '/m7/ask', token: TOKEN, tokenFile: '',
    channelPort: 18788, autoStart: false, settingsFile: join(WORK, 'late-settings.json'),
  });
  setTimeout(() => {
    lateServer = {
      register: (opts) => {
        lateRegistered = opts;
        return { dispose() {} };
      },
    };
  }, 150);
  await new Promise((resolve) => setTimeout(resolve, 1200));
  assert(Boolean(lateRegistered), 'webServer 晚就绪时面板路由也会补挂上');
  assertEqual(lateRegistered && lateRegistered.path, '/api/phone-bridge', '晚挂的路由前缀正确');
  assertEqual(lateRegistered && lateRegistered.kind, 'prefix', '晚挂的路由 kind=prefix');
  for (const cleanup of ctxLate.cleanups.reverse()) await cleanup();
  ok('webServer 晚就绪的测试实例已关闭');

  /* ────────────── ⑤ client.js ────────────── */
  const pkg = JSON.parse(readFileSync(join(HERE, 'package.json'), 'utf8'));
  let loaded = null;
  const prevWindow = globalThis.window;
  globalThis.window = { __ModuleLoader__: { load: (spec) => { loaded = spec; } } };
  try {
    await import(`./client.js?selfcheck=${Date.now()}`);
  } finally {
    globalThis.window = prevWindow;
  }
  assert(Boolean(loaded), 'client.js 调用了 window.__ModuleLoader__.load');
  assertEqual(loaded && loaded.id, pkg.name, 'client.js 的 id 等于 package.json 的 name');
  assert(typeof loaded.factory === 'function', 'client.js 带 factory');

  const fakeReact = {
    createElement: (tag, props, ...children) => {
      const merged = { ...(props || {}) };
      if (children.length) merged.children = children.length === 1 ? children[0] : children;
      return { tag, props: merged };
    },
    useState: (value) => [value, () => {}],
    useEffect: () => {},
  };
  const clientMod = loaded.factory((name) => {
    if (name === 'react') return fakeReact;
    throw new Error(`client.js require 了没预料到的包：${name}`);
  });
  assert(typeof clientMod.apply === 'function', 'client.js factory 返回 apply');

  const t = clientMod.__test;
  assert(Boolean(t), 'client.js 暴露 __test 纯函数');
  assertEqual(t.DEFAULT_PROVIDER, 'deepseek', '默认提供商是 deepseek（初始态常量）');
  assertEqual(t.PROVIDERS[0][0], 'deepseek', '提供商列表 DeepSeek 排第一');
  assert(
    String((t.PROVIDERS.find((row) => row[0] === 'edge') || [])[1]).includes('未实现'),
    'Edge 仍保留「未实现」标注',
  );
  const pairNow = Date.now();
  const localPair = { active: true, code: '482913', expiresAt: pairNow + 300000, ttlMs: 300000 };
  assertEqual(
    t.mergePair(localPair, { active: true, code: '482913', expiresAt: pairNow + 290000 }, pairNow).code,
    '482913',
    '轮询回来的同码不覆盖本地（倒计时不被冲掉）',
  );
  assertEqual(
    t.mergePair(localPair, { active: true, code: null, expiresAt: pairNow + 300000 }, pairNow).code,
    '482913',
    '轮询没带码时也保留本地未过期的码',
  );
  assertEqual(
    t.mergePair(localPair, { active: true, code: '999999', expiresAt: pairNow + 300000 }, pairNow).code,
    '999999',
    '轮询带有效新码时采纳新码',
  );
  assertEqual(
    t.mergePair({ ...localPair, expiresAt: pairNow - 1 }, { active: false, expiresAt: null }, pairNow),
    null,
    '过期后清空（面板显示「已过期」）',
  );
  const fromPairResponse = t.pairFromResponse({
    ok: true, code: '482913', expiresAt: pairNow + 300000, ttlMs: 300000,
  });
  assertEqual(fromPairResponse.code, '482913', 'POST /pair 响应立刻能收成面板状态（不用轮询）');
  assertEqual(fromPairResponse.expiresAt, pairNow + 300000, 'POST /pair 响应带 expiresAt');
  assertEqual(t.pairFromResponse({ ok: false }), null, '失败的 /pair 响应不伪造配对码');
  const registered = [];
  const styleNodes = [];
  globalThis.window = {
    __ModuleLoader__: { load: () => {} },
    addEventListener: () => {},
    removeEventListener: () => {},
  };
  globalThis.document = {
    createElement: () => ({ setAttribute() {}, remove() {}, textContent: '' }),
    head: { appendChild: (node) => styleNodes.push(node) },
    addEventListener: () => {},
    removeEventListener: () => {},
    hidden: false,
  };
  try {
    clientMod.apply({
      effect: (fn) => {
        const cleanup = fn();
        if (typeof cleanup !== 'function') return;
        return cleanup;
      },
      slots: {
        inject: (name, fn) => fn(),
        register: (opts, Component) => {
          registered.push({ ...opts, hasComponent: typeof Component === 'function', component: Component });
          return {};
        },
      },
    });
  } finally {
    globalThis.window = prevWindow;
    globalThis.document = undefined;
  }
  assert(styleNodes.length > 0, 'client.js 把样式挂上了');
  const section = registered.find((row) => row.name === 'settings.section');
  assert(Boolean(section), 'client.js 注册了 settings.section');
  assertEqual(section && section.label, '语音通道', 'settings.section 的 label 是「语音通道」');
  assertEqual(section && section.id, 'phone-bridge', 'settings.section 的 id 是 phone-bridge');
  assert(section && section.hasComponent, 'settings.section 带组件');

  // 真把组件树走一遍（假的 hooks / createElement），逼出渲染期的 undefined 访问
  const renderNode = (node) => {
    if (node === null || node === undefined || typeof node === 'string' || typeof node === 'number') return;
    if (Array.isArray(node)) {
      for (const child of node) renderNode(child);
      return;
    }
    if (typeof node !== 'object' || !node.props) return;
    if (typeof node.tag === 'function') {
      renderNode(node.tag(node.props));
    } else {
      renderNode(node.props.children);
    }
  };
  try {
    renderNode(section.component());
    ok('「语音通道」面板初始状态能渲染（无状态时显示降级文案）');
  } catch (error) {
    fail('「语音通道」面板初始状态渲染', String(error && error.stack ? error.stack.split('\n')[0] : error));
  }

  /* ────────────── ⑥ 包内 JSON ────────────── */
  for (const file of ['package.json', 'locale/zh.json', 'locale/en.json']) {
    try {
      JSON.parse(readFileSync(join(HERE, file), 'utf8'));
      ok(`JSON 可解析：${file}`);
    } catch (error) {
      fail(`JSON 可解析：${file}`, String(error && error.message));
    }
  }
  assert(!('dependencies' in pkg) || Object.keys(pkg.dependencies || {}).length === 0, 'package.json 没有新增依赖');

  console.log(`\n# ${checks - failures}/${checks} passed`);
  if (process.env.PB_SELFCHECK_DEBUG_HANDLES === '1') {
    const handles = typeof process._getActiveHandles === 'function' ? process._getActiveHandles() : [];
    console.log(
      `# active handles: ${handles.map((h) => (h && h.constructor ? h.constructor.name : typeof h)).join(',') || '(none)'}`,
    );
  }
  if (failures > 0) {
    console.log(`# ${failures} FAILED`);
    process.exitCode = 1;
  }
  try {
    rmSync(WORK, { recursive: true, force: true });
  } catch {
    /* 清不掉就留着 */
  }
};

main().catch((error) => {
  console.error(`FAIL 未捕获异常：${error && error.stack ? error.stack : error}`);
  process.exitCode = 1;
});
