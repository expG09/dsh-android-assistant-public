'use strict';

/**
 * 通道联调客户端（仅用于本机验收，不是交付功能的一部分）
 *
 * 用法：
 *   node test/client.js --pair-url http://127.0.0.1:8788 --code 123456 --device-id dev-a
 *   node test/client.js --pair-url http://127.0.0.1:8788 --code 123456 --device-id dev-b --expect-busy
 *   node test/client.js --pair-url http://127.0.0.1:8788 --code 123456 --device-id dev-a --no-respond --hold 130000
 *
 * 参数：
 *   --pair-url       配对用 HTTP 地址（默认 http://127.0.0.1:8788）
 *   --ws             WebSocket 地址（默认由 pair-url 推导）
 *   --code           6 位配对码；不传则必须传 --token
 *   --device-id      设备 id（默认 dev-<随机>）
 *   --token          直接用已有 token 连（验证「服务端重启后设备仍可重连」）
 *   --text           连上后发一条 text，可重复
 *   --hold           连上后再保持多少毫秒（默认 2000）
 *   --no-respond     收到 ping 不回 pong（用于测服务端 90 秒判死）
 *   --no-ready-wait  不等 ready 就发 text（默认等 ready）
 *   --proto          hello 里的协议版本（默认 1；测音频要 2）
 *   --audio          WAV 文件路径：ready 后按 audioStart/audioChunk/audioEnd 上传并打印 asrResult
 *   --chunk-bytes    每个 audioChunk 的原始字节数（默认 49152 = 48 KiB，base64 后恰好 64 KiB）
 *   --speak-config   'on' / 'off'：ready 后发 speakConfig{enabled, voice}（M6a）
 *   --voice          音色（stella / tide，默认 stella）
 *   --speak-request  试听：ready 后发一条 speakRequest
 *   --stop-after     收到 N 块 speakChunk 后发 speakStop（测打断）
 *   --dev-speak      文本：ready 后 POST /dev/speak 让服务端走真实合成链路（配合 --dev 启动的服务端）
 */

const http = require('http');
const fs = require('fs');
const WebSocket = require('ws');

const ARGV = process.argv.slice(2);
function arg(name, dflt) {
  const hit = ARGV.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (!hit) return dflt;
  const eq = hit.indexOf('=');
  if (eq >= 0) return hit.slice(eq + 1);
  const idx = ARGV.indexOf(hit);
  const next = ARGV[idx + 1];
  if (next && !next.startsWith('--')) return next;
  return true;
}
function has(name) {
  return ARGV.includes(`--${name}`);
}

const PAIR_URL = String(arg('pair-url', 'http://127.0.0.1:8788'));
const WS_URL = String(arg('ws', PAIR_URL.replace(/^http/, 'ws')));
const DEVICE_ID = String(arg('device-id', 'dev-' + Math.random().toString(36).slice(2, 8)));
const HOLD_MS = Number(arg('hold', 2000));
const NO_RESPOND = has('no-respond');
const PROTO = Number(arg('proto', 1));
const AUDIO = arg('audio', null);
const CHUNK_BYTES = Number(arg('chunk-bytes', 49152));
// M6a
const SPEAK_CONFIG = arg('speak-config', null);
const VOICE = String(arg('voice', 'stella'));
const SPEAK_REQUEST = has('speak-request');
const STOP_AFTER = Number(arg('stop-after', 0));
const DEV_SPEAK = arg('dev-speak', null);
let speakChunks = 0;
let stopped = false;

/** speakChunk 里的 base64 太吵：日志里只留长度，不做全文打印。 */
function compactFrame(text) {
  if (text.length <= 300) return text;
  try {
    const obj = JSON.parse(text);
    if (obj && obj.type === 'speakChunk' && typeof obj.data === 'string') {
      obj.data = `<base64 ${obj.data.length} chars>`;
      return JSON.stringify(obj);
    }
  } catch (e) {
    /* ignore */
  }
  return text.slice(0, 200) + `…（共 ${text.length} 字节）`;
}

const t0 = Date.now();
function log(msg) {
  console.log(`[+${String(Date.now() - t0).padStart(5)}ms] ${msg}`);
}

function postJson(url, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const data = Buffer.from(JSON.stringify(body));
    const req = http.request(
      {
        hostname: u.hostname,
        port: u.port || 80,
        path: u.pathname,
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Content-Length': data.length },
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try {
            json = JSON.parse(text);
          } catch (e) {
            /* ignore */
          }
          resolve({ status: res.statusCode, json, text });
        });
      }
    );
    req.on('error', reject);
    req.end(data);
  });
}

function texts() {
  const out = [];
  ARGV.forEach((a, i) => {
    if (a === '--text') out.push(ARGV[i + 1]);
    else if (a.startsWith('--text=')) out.push(a.slice('--text='.length));
  });
  return out;
}

/** --send-json '{"type":"audio"}'：连上后原样发这些帧，用于验证预留帧回 unsupported */
function rawFrames() {
  const out = [];
  ARGV.forEach((a, i) => {
    if (a === '--send-json') out.push(ARGV[i + 1]);
    else if (a.startsWith('--send-json=')) out.push(a.slice('--send-json='.length));
  });
  return out.filter(Boolean);
}

(async () => {
  let token = arg('token', null);
  const code = arg('code', null);
  if (!token) {
    if (!code) {
      console.error('需要 --code 或 --token');
      process.exit(2);
    }
    const r = await postJson(`${PAIR_URL}/pair`, { code, deviceId: DEVICE_ID });
    log(`POST /pair -> ${r.status} ${r.text.replace(/"deviceToken":"[^"]+"/, '"deviceToken":"<omitted>"')}`);
    if (r.status !== 200) process.exit(3);
    token = r.json.deviceToken;
  } else {
    token = String(token);
  }

  const ws = new WebSocket(WS_URL);
  let readySeen = false;
  let pending = rawFrames()
    .map((json) => ({ raw: json }))
    .concat(texts().map((text) => ({ text: text })));
  let holdTimer = null;

  function send(obj) {
    log(`>> ${JSON.stringify(obj).replace(String(token), '<token>')}`);
    ws.send(JSON.stringify(obj));
  }

  function sendNext() {
    if (!pending.length) return;
    const item = pending.shift();
    if (item.raw !== undefined) {
      log(`>> ${item.raw}`);
      ws.send(item.raw);
    } else {
      send({ type: 'text', id: 'c' + Date.now(), text: item.text });
    }
  }

  /** M4：把 --audio 指的文件按 audioStart/audioChunk/audioEnd 上传（分块 48 KiB → base64 64 KiB）。 */
  function sendAudio() {
    const buf = fs.readFileSync(String(AUDIO));
    const id = 'test-' + Date.now();
    const durationMs = Math.max(0, Math.round(((buf.length - 44) / 32000) * 1000));
    log(`>> audioStart id=${id} size=${buf.length} dur=${durationMs}`);
    ws.send(
      JSON.stringify({ type: 'audioStart', id, mime: 'audio/wav', size: buf.length, durationMs })
    );
    let seq = 0;
    for (let off = 0; off < buf.length; off += CHUNK_BYTES) {
      const part = buf.subarray(off, Math.min(off + CHUNK_BYTES, buf.length));
      ws.send(JSON.stringify({ type: 'audioChunk', id, seq, data: part.toString('base64') }));
      seq += 1;
    }
    log(`>> audioEnd id=${id} chunks=${seq}`);
    ws.send(JSON.stringify({ type: 'audioEnd', id }));
  }

  ws.on('open', () => {
    log(`open ${WS_URL}，发送 hello（token 已省略）`);
    send({ type: 'hello', deviceId: DEVICE_ID, token, proto: PROTO });
    holdTimer = setTimeout(() => {
      log('保持时间到，主动退出');
      try {
        ws.close(1000, 'done');
      } catch (e) {
        /* ignore */
      }
    }, HOLD_MS);
  });

  ws.on('message', (data) => {
    const text = data.toString('utf8');
    log(`<< ${compactFrame(text)}`);
    let msg = null;
    try {
      msg = JSON.parse(text);
    } catch (e) {
      return;
    }
    if (msg.type === 'ready') {
      readySeen = true;
      log(`ready（serverTime=${msg.serverTime} proto=${msg.proto}）`);
      if (SPEAK_CONFIG) {
        const enabled = String(SPEAK_CONFIG) !== 'off';
        send({ type: 'speakConfig', enabled: enabled, voice: VOICE });
      }
      if (SPEAK_REQUEST) {
        send({ type: 'speakRequest', id: 'aud-' + Date.now(), voice: VOICE });
      }
      if (DEV_SPEAK) {
        postJson(`${PAIR_URL}/dev/speak`, { text: String(DEV_SPEAK) }).then((r) => {
          log(`POST /dev/speak -> ${r.status} ${r.text}`);
        });
      }
      if (AUDIO) {
        sendAudio();
        return;
      }
      if (has('send-reserved')) {
        ['audio', 'file', 'command', 'commandResult', 'approval', 'weirdUnknown'].forEach((t) => {
          const frame = { type: t, id: 'r-' + t };
          log(`>> ${JSON.stringify(frame)}`);
          ws.send(JSON.stringify(frame));
        });
      }
      sendNext();
    } else if (msg.type === 'speakStart') {
      log(`speakStart id=${msg.id} replyId=${msg.replyId} voice=${msg.voice} 句数=${msg.segments} 字数=${String(msg.text || '').length} 参数=${msg.sampleRate}Hz/${msg.channels}ch`);
    } else if (msg.type === 'speakChunk') {
      speakChunks += 1;
      if (STOP_AFTER > 0 && !stopped && speakChunks >= STOP_AFTER) {
        stopped = true;
        log(`收到 ${speakChunks} 块 speakChunk，发 speakStop（测打断）`);
        send({ type: 'speakStop', id: msg.id });
      }
    } else if (msg.type === 'speakDone') {
      log(`speakDone id=${msg.id}（共 ${speakChunks} 块 speakChunk）`);
    } else if (msg.type === 'speakStop') {
      log(`speakStop id=${msg.id} reason=${msg.reason || '-'}（服务端要求收尾）`);
    } else if (msg.type === 'text' || msg.type === 'done') {
      sendNext();
    } else if (msg.type === 'ping') {
      if (!NO_RESPOND) send({ type: 'pong' });
    } else if (msg.type === 'error' && msg.code === 'busy') {
      log('收到 busy（这是验收 5 期望的结果）');
    }
  });

  ws.on('close', (code, reason) => {
    log(`closed code=${code} reason=${reason.toString() || '-'}`);
    if (holdTimer) clearTimeout(holdTimer);
    process.exit(readySeen ? 0 : 4);
  });

  ws.on('error', (err) => {
    log(`ws error: ${err.code || err.message}`);
  });
})().catch((e) => {
  console.error('客户端异常：', e.message);
  process.exit(1);
});
