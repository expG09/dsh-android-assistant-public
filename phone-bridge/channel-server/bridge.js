'use strict';

/**
 * phone-bridge 客户端（M7）：channel-server 侧把一条用户文本转发给本机 DSH 宿主插件。
 *
 * 契约（与 phone-bridge 插件一致）：
 *   POST http://127.0.0.1:8795/m7/ask
 *   header: x-phone-bridge-key: <共享密钥>
 *   body:   { "text": "用户原话", "sessionId": "可选，钉死某个会话" }
 *   200:    { ok: true, reply: "回复正文", sessionId, reason, interrupted }
 *   401/404/409/504: 插件不可用或这次没答上来 → 调用方退回本地模型
 *
 * 设计约束：
 *   - 只连回环地址；URL 里出现非回环 host 直接当配置错误关掉，不外发。
 *   - 密钥只从环境变量或 channel-server/.env 读，日志只写「来源」，不写值、不写长度。
 *   - 永不抛异常：任何失败都折成 { ok:false, error }，让调用方安静地退回旧路径。
 *   - 日志不写正文（用户原话与模型回复都不写），只写耗时、字数、错误码。
 *
 * 超时（M7 真机联调修复）：
 *   - 旧实现只调 `req.setTimeout()`，那是 socket **空闲**超时，不是「这次提问的总时限」；
 *     对端把连接攥住不写一个字节时，拿不到可靠的截止时间，请求就永远挂着。
 *   - 现在再加一个**总时限计时器**：到点无论 socket 在干嘛，都 `req.destroy()` 真正 abort，
 *     打一行日志，然后 resolve `{ ok:false, error:'timeout' }`，让调用方退回本地模型。
 *   - 两层都有，先到先算；`settled` 保证只 resolve 一次。
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

/** 等会话回答的上限；超时就算不可用，退回本地模型。 */
const DEFAULT_TIMEOUT_MS = 180 * 1000;

/**
 * 版本标记：只出现在启动日志里，用来一眼分辨「8788 上的进程加载的是不是这份 bridge.js」。
 * 旧进程没有这一项，新进程会打 `会话桥（phone-bridge）：已启用 版本=…`。
 * 改动 behavior 时递增，例：m7-fix-1 → m7-fix-2。
 */
const BRIDGE_VERSION = 'm7-fix-2';

/** 日志出口：由 server.js 注入统一的 log()；没注入就用 console。 */
let logger = null;

/** 注入日志函数（server.js 的 log 已经带时间戳）。 */
function setLogger(fn) {
  logger = typeof fn === 'function' ? fn : null;
}

/** 只写元数据的一行日志；日志本身失败绝不影响主流程。 */
function note(message) {
  try {
    if (logger) logger(message);
    else console.log(message);
  } catch (e) {
    /* ignore */
  }
}

/** 从 .env 里读一个键（与 server.js 的宽松解析一致，只取值不记值）。 */
function readDotEnvValue(file, key) {
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (e) {
    return '';
  }
  const re = new RegExp(`^\\s*(?:export\\s+)?${key}\\s*=\\s*(.*)$`);
  for (const line of raw.split(/\r?\n/)) {
    const m = line.match(re);
    if (!m) continue;
    let value = m[1].trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (value) return value;
  }
  return '';
}

/** 只允许回环目标：插件本来就只听 127.0.0.1，配成别的地址说明是配错了。 */
function isLoopbackHost(host) {
  return host === 'localhost' || host === '::1' || host === '[::1]' || /^127(?:\.\d{1,3}){3}$/.test(host);
}

/** 解析一份 bridge 配置；返回 { enabled, reason, url, token, tokenSource, timeoutMs }。 */
function loadConfig() {
  const env = process.env;
  const envName = (env.PHONE_BRIDGE_TOKEN_ENV || 'PHONE_BRIDGE_TOKEN').trim();

  // 显式关闭：PHONE_BRIDGE_ENABLED=0
  const enabledFlag = String(env.PHONE_BRIDGE_ENABLED || '').trim().toLowerCase();
  if (enabledFlag === '0' || enabledFlag === 'false' || enabledFlag === 'no') {
    return { enabled: false, reason: 'disabled-by-env' };
  }

  const urlText =
    env.PHONE_BRIDGE_URL ||
    `http://127.0.0.1:${env.PHONE_BRIDGE_PORT || 8795}${env.PHONE_BRIDGE_PATH || '/m7/ask'}`;

  let parsed;
  try {
    parsed = new URL(urlText);
  } catch (e) {
    return { enabled: false, reason: 'bad-url' };
  }
  if (parsed.protocol !== 'http:') return { enabled: false, reason: 'non-http-url' };
  if (!isLoopbackHost(parsed.hostname)) return { enabled: false, reason: 'non-loopback-url' };

  // 密钥：环境变量优先，channel-server/.env 兜底
  let token = String(env[envName] || '').trim();
  let tokenSource = token ? 'env' : 'none';
  if (!token) {
    const fromFile = readDotEnvValue(path.join(__dirname, '.env'), envName);
    if (fromFile) {
      token = fromFile;
      tokenSource = '.env';
    }
  }
  if (!token) return { enabled: false, reason: 'no-token' };

  const timeoutMs = Number(env.PHONE_BRIDGE_TIMEOUT_MS || DEFAULT_TIMEOUT_MS);
  return {
    enabled: true,
    reason: 'ok',
    url: urlText,
    token,
    tokenSource,
    timeoutMs: Number.isFinite(timeoutMs) && timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS,
  };
}

const config = loadConfig();

/** 给启动日志用的一句话；不出现密钥、不出现正文。 */
function describe() {
  if (!config.enabled) return `未启用（原因=${config.reason}）`;
  return `已启用 版本=${BRIDGE_VERSION} url=${config.url} 密钥来源=${config.tokenSource} 超时=${config.timeoutMs}ms`;
}

/**
 * 问一次本机会话。
 * @param {string} text 用户原话
 * @param {object} [opts] { sessionId }
 * @returns {Promise<{ok:boolean, reply?:string, sessionId?:string, reason?:string, error?:string, status?:number, ms:number}>}
 */
function ask(text, opts = {}) {
  const startedAt = Date.now();
  return new Promise((resolve) => {
    if (!config.enabled) {
      resolve({ ok: false, error: 'bridge-disabled', ms: 0 });
      return;
    }
    const body = Buffer.from(JSON.stringify({ text, sessionId: opts.sessionId }), 'utf8');
    let url;
    try {
      url = new URL(config.url);
    } catch (e) {
      resolve({ ok: false, error: 'bad-url', ms: Date.now() - startedAt });
      return;
    }

    /** 只 resolve 一次；cancel 总时限计时器。 */
    let settled = false;
    /** 已经因为超时 abort 了：之后的 error 不再覆盖 timeout 结果。 */
    let timedOut = false;
    /** @type {import('http').ClientRequest | null} */
    let req = null;
    /** @type {NodeJS.Timeout | null} */
    let totalTimer = null;

    const done = (result) => {
      if (settled) return;
      settled = true;
      if (totalTimer) {
        clearTimeout(totalTimer);
        totalTimer = null;
      }
      resolve(result);
    };

    /** 真正 abort：毁掉这条请求，打一行日志，然后按超时返回。 */
    const abort = (why) => {
      if (settled || timedOut) return;
      timedOut = true;
      note(
        `会话桥：等会话回复超时 ${config.timeoutMs}ms（${why}），已 abort 请求，退回本地模型`,
      );
      try {
        if (req) req.destroy(new Error('bridge-timeout'));
      } catch (e) {
        /* ignore */
      }
      done({ ok: false, error: 'timeout', status: 0, ms: Date.now() - startedAt, timedOut: true });
    };

    req = http.request(
      {
        host: url.hostname,
        port: url.port || 80,
        path: url.pathname,
        method: 'POST',
        headers: {
          'content-type': 'application/json; charset=utf-8',
          'content-length': body.length,
          'x-phone-bridge-key': config.token,
        },
      },
      (res) => {
        const chunks = [];
        let size = 0;
        res.on('data', (c) => {
          size += c.length;
          if (size <= 256 * 1024) chunks.push(c);
        });
        res.on('error', (e) => {
          if (timedOut) return;
          done({ ok: false, error: e.code || e.message || 'response-error', ms: Date.now() - startedAt });
        });
        res.on('end', () => {
          if (timedOut) return;
          const ms = Date.now() - startedAt;
          const status = res.statusCode || 0;
          let json = null;
          try {
            json = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          } catch (e) {
            json = null;
          }
          if (status === 200 && json && json.ok === true && typeof json.reply === 'string') {
            done({ ok: true, reply: json.reply, sessionId: json.sessionId, reason: json.reason, ms });
            return;
          }
          // 504 = 插件在但这一轮超时；401 = 密钥不对；其余当作不可用
          done({
            ok: false,
            error: (json && json.error) || `http-${status}`,
            status,
            reason: json && json.reason,
            ms,
          });
        });
      }
    );

    // 第一层：socket 空闲超时（既有的那层；对端一个字节都不回时也能断）。
    req.setTimeout(config.timeoutMs, () => abort('socket-idle'));
    // 第二层：这次提问的总时限。到点不管 socket 在等什么，都真正 abort。
    totalTimer = setTimeout(() => abort('total-timeout'), config.timeoutMs);
    if (totalTimer.unref) totalTimer.unref();

    req.on('error', (e) => {
      if (timedOut) return;
      done({ ok: false, error: e.code || e.message || 'request-failed', ms: Date.now() - startedAt });
    });
    req.end(body);
  });
}

module.exports = { enabled: config.enabled, reason: config.reason, describe, ask, setLogger };
