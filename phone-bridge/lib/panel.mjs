/**
 * lib/panel.mjs —— 设置页「语音通道」的宿主接口（挂在 DSH webServer 的 `/api/phone-bridge`）。
 *
 * 面板本身只是浏览器里的一段 React（client.js），它不能直接碰文件、也不能直接杀进程；
 * 所有动作都从这里走：
 *
 *   GET  /api/phone-bridge/status        通道状态 + 配对状态 + 语音配置 + 日志尾部
 *   POST /api/phone-bridge/channel/start 启动托管进程
 *   POST /api/phone-bridge/channel/stop  停止托管进程
 *   POST /api/phone-bridge/pair          代理 channel-server 的 POST /admin/pair
 *   GET  /api/phone-bridge/devices       代理 channel-server 的 GET /admin/devices
 *   POST /api/phone-bridge/voice         保存语音提供商 / DEEPSEEK_API_KEY
 *
 * 安全（规格 C 段，硬性）：
 *   - 所有路由先过「本机同源」：Host 必须是回环；带了 Origin/Referer 就必须与 Host 同源；
 *     能拿到 DSH 的信任栅栏（connection.requestRejection）时先过它；
 *   - 访问 channel-server 的 /admin/* 只走回环 127.0.0.1，带共享密钥（PHONE_BRIDGE_TOKEN）；
 *   - API key 出站一律掩码；本模块不返回、不记录 key 明文；
 *   - 只接受固定的动作与固定路径，不接受任何由请求体拼接的命令/参数/路径。
 *
 * 这里不 import 任何 `@deepseek-ai/*`，只用 node 内置模块。
 */

import { request as httpRequest } from 'node:http';
import { networkInterfaces } from 'node:os';
import {
  maskSecret,
  readDotEnvValue,
  validateApiKey,
  writeDotEnvValue,
  VOICE_PROVIDERS,
  VOICE_PROVIDER_ENV,
} from './env.mjs';

export const PANEL_API_PREFIX = '/api/phone-bridge';

const MAX_BODY_BYTES = 16 * 1024;
const ADMIN_TIMEOUT_MS = 2500;
const HEALTH_TIMEOUT_MS = 900;
const MAX_ADDRESSES = 3;

/** Tailscale 的地址是 100.64.0.0/10；网卡名也一起认（TAP 名字可能不标准）。 */
function isTailscaleAddress(name, address) {
  const iface = String(name || '').toLowerCase();
  if (iface.includes('tailscale') || iface.includes('tailscale0')) return true;
  const m = String(address).match(/^100\.(\d+)\./);
  if (!m) return false;
  const second = Number(m[1]);
  return second >= 64 && second <= 127;
}

/**
 * 列手机能填的「电脑端地址」：非回环 IPv4，Tailscale 的 100.x 排最前，最多 3 个。
 * 一个非回环地址都没有时退回 127.0.0.1，并标 `loopback:true` / note「仅本机」。
 * 返回值只含 IP、端口、网卡名，**不含任何密钥**。
 *
 * @param {number} port channel-server 端口
 * @param {object} [interfaces] 测试可注入（os.networkInterfaces() 的形状）
 */
export function enumerateAddresses(port = 8788, interfaces = null) {
  const portNumber = Number.isInteger(Number(port)) && Number(port) > 0 ? Number(port) : 8788;
  const table = interfaces && typeof interfaces === 'object' ? interfaces : networkInterfaces() || {};
  const seen = new Set();
  const found = [];
  for (const [name, list] of Object.entries(table)) {
    for (const info of list || []) {
      if (!info || info.internal === true) continue;
      const family = info.family;
      if (family !== 'IPv4' && family !== 4) continue;
      const address = String(info.address || '').trim();
      if (!address || address.startsWith('127.') || address === '0.0.0.0') continue;
      if (seen.has(address)) continue;
      seen.add(address);
      const tailscale = isTailscaleAddress(name, address);
      found.push({
        url: `http://${address}:${portNumber}`,
        host: address,
        port: portNumber,
        iface: String(name || ''),
        tailscale,
        loopback: false,
        note: tailscale ? 'Tailscale' : String(name || ''),
      });
    }
  }
  found.sort((a, b) => Number(b.tailscale) - Number(a.tailscale));
  if (found.length === 0) {
    return [
      {
        url: `http://127.0.0.1:${portNumber}`,
        host: '127.0.0.1',
        port: portNumber,
        iface: 'lo',
        tailscale: false,
        loopback: true,
        note: '仅本机（手机连不上：没找到非回环 IPv4，检查 Tailscale / 网卡）',
      },
    ];
  }
  return found.slice(0, MAX_ADDRESSES);
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  try {
    res.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(body),
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
    });
    res.end(body);
  } catch {
    /* 客户端已经断开：没有可写的响应，忽略 */
  }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('body too large'), { code: 'TOO_LARGE' }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/** 本机同源：Host 是回环，且浏览器带来的 Origin/Referer（如果有）与 Host 同源（端口也要比）。 */
export function sameOriginOk(req) {
  try {
    const host = String(req.headers.host || '');
    if (!host) return true; // 没有 Host 头 = 不是浏览器/不是外来的 HTTP 请求
    if (!/^(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/i.test(host)) return false;
    const pick = String(req.headers.origin || req.headers.referer || '');
    if (!pick) return true; // 本机脚本/宿主内部
    const u = new URL(pick);
    const originAuthority = String(u.host || '').toLowerCase();
    const hostAuthority = host.toLowerCase();
    if (originAuthority && originAuthority === hostAuthority) return true;
    const defaultPort = u.protocol === 'https:' ? '443' : '80';
    const withPort = `${u.hostname}:${u.port || defaultPort}`.toLowerCase();
    return withPort === hostAuthority;
  } catch {
    return false;
  }
}

/** 回环 HTTP 调用（admin 接口与 healthz 共用）。 */
function loopbackRequest({ port, path, method = 'GET', headers = {}, body = undefined, timeoutMs = ADMIN_TIMEOUT_MS }) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };
    let req;
    try {
      req = httpRequest(
        { host: '127.0.0.1', port, path, method, headers, timeout: timeoutMs },
        (res) => {
          let text = '';
          res.setEncoding('utf8');
          res.on('data', (chunk) => {
            text += chunk;
            if (text.length > 256 * 1024) {
              text = text.slice(0, 256 * 1024);
              res.destroy();
            }
          });
          res.on('end', () => {
            let json = null;
            try {
              json = text ? JSON.parse(text) : null;
            } catch {
              json = null;
            }
            finish({ ok: true, status: res.statusCode || 0, json, text });
          });
        },
      );
    } catch (error) {
      finish({ ok: false, code: 'request-failed', message: String((error && error.message) || error) });
      return;
    }
    req.on('timeout', () => {
      try {
        req.destroy(new Error('timeout'));
      } catch {
        /* 忽略 */
      }
    });
    req.on('error', (error) => {
      finish({ ok: false, code: (error && error.code) || 'request-failed', message: String((error && error.message) || error) });
    });
    if (body !== undefined) {
      try {
        req.write(body);
      } catch {
        /* 下面的 end 会报错 */
      }
    }
    try {
      req.end();
    } catch (error) {
      finish({ ok: false, code: 'request-failed', message: String((error && error.message) || error) });
    }
  });
}

/**
 * 创建面板接口。
 *
 * @param {object} options
 * @param {object} options.manager   ChannelServerManager（只用 status/start/stop/appendNote）
 * @param {number} options.channelPort  channel-server 端口（默认 8788，回环访问用）
 * @param {string} options.envPath   channel-server/.env 的绝对路径
 * @param {string} [options.token]   配置里直接给的共享密钥（优先）
 * @param {string} [options.tokenEnv] 共享密钥的环境变量名（默认 PHONE_BRIDGE_TOKEN）
 * @param {function} [options.getConnection] 取 DSH 信任栅栏（拿不到返回 null）
 * @param {object} [options.secrets]  密钥掩码提供者（createSecretProvider 的返回值）
 * @param {function} [options.log]
 */
export function createPanel({
  manager,
  channelPort = 8788,
  envPath = '',
  token = '',
  tokenEnv = 'PHONE_BRIDGE_TOKEN',
  getConnection = () => null,
  sessionBinding = null,
  channelServerInfo = null,
  tokenInfo = null,
  secrets = null,
  log = () => {},
} = {}) {
  const port = Number.isInteger(Number(channelPort)) && Number(channelPort) > 0 ? Number(channelPort) : 8788;
  const envKey = String(tokenEnv || 'PHONE_BRIDGE_TOKEN');
  /** 最近一次由面板生成的配对码（内存里留 5 分钟，页面刷新后还能看到倒计时）。 */
  let lastPair = null;

  const note = (text) => {
    try {
      manager.appendNote?.(text);
    } catch {
      /* 忽略 */
    }
  };

  /** 共享密钥：config.token → 环境变量 → channel-server/.env。只在内存里用，绝不回传。 */
  const resolveSharedToken = () => {
    const direct = String(token || '').trim();
    if (direct) return direct;
    const fromEnv = String(process.env[envKey] || '').trim();
    if (fromEnv) return fromEnv;
    return envPath ? readDotEnvValue(envPath, envKey) : '';
  };

  /** 调 channel-server 的 /admin/*；失败返回 {ok:false, error}，不抛错。 */
  const adminCall = async (method, path, payload) => {
    const shared = resolveSharedToken();
    if (!shared) {
      return {
        ok: false,
        error: 'no-token',
        message: `没有找到共享密钥（${envKey}）：channel-server/.env 与环境变量都没有`,
        hint: '先在 channel-server/.env 里配置 PHONE_BRIDGE_TOKEN，再重启通道。',
      };
    }
    const body = payload === undefined ? undefined : JSON.stringify(payload);
    const headers = {
      accept: 'application/json',
      'x-phone-bridge-key': shared,
      authorization: `Bearer ${shared}`,
    };
    if (body !== undefined) {
      headers['content-type'] = 'application/json';
      headers['content-length'] = Buffer.byteLength(body);
    }
    const r = await loopbackRequest({ port, path, method, headers, body, timeoutMs: ADMIN_TIMEOUT_MS });
    if (!r.ok) {
      return {
        ok: false,
        error: 'channel-unreachable',
        message: r.code === 'ECONNREFUSED' ? `127.0.0.1:${port} 没有在听` : r.message,
        hint: '通道没在跑：先点「启动」，或确认 channel-server 已启动。',
      };
    }
    if (r.status === 401) {
      return {
        ok: false,
        error: 'admin-unauthorized',
        message: 'channel-server 拒绝了共享密钥（401）',
        hint: '确认 channel-server/.env 的 PHONE_BRIDGE_TOKEN 与插件读到的是同一个值，然后重启通道。',
      };
    }
    if (r.status === 404) {
      return {
        ok: false,
        error: 'admin-missing',
        message: 'channel-server 没有 /admin 接口（404）',
        hint: '这个 channel-server 还是旧版：先更新到带 /admin/pair、/admin/devices 的版本。',
      };
    }
    if (!r.json || typeof r.json !== 'object') {
      return { ok: false, error: 'admin-bad-response', message: `channel-server 回了 HTTP ${r.status}（不是 JSON）` };
    }
    if (r.json.ok === false) {
      return { ok: false, error: 'admin-error', message: String(r.json.error || `HTTP ${r.status}`) };
    }
    return { ok: true, status: r.status, data: r.json };
  };

  /** GET /healthz（不需要密钥）：用来判断「有没有一个通道在跑」，包括不是面板拉起的。 */
  const healthProbe = async () => {
    const r = await loopbackRequest({ port, path: '/healthz', timeoutMs: HEALTH_TIMEOUT_MS });
    if (!r.ok || r.status !== 200) return { reachable: false };
    return { reachable: true, health: r.json && typeof r.json === 'object' ? r.json : null };
  };

  const channelSnapshot = async () => {
    const base = manager.status(15);
    const [probe, admin] = await Promise.all([healthProbe(), adminCall('GET', '/admin/status')]);
    const adminData = admin.ok ? admin.data : null;
    return {
      ...base,
      // 端口用配置值（也是托管进程实际用的端口）
      port,
      // channel-server 目录是怎么定位的（复制安装填错 channelServerDir 时能一眼看出来）
      serverDir: channelServerInfo && channelServerInfo.dir ? channelServerInfo.dir : '',
      serverSource: channelServerInfo && channelServerInfo.source ? channelServerInfo.source : '',
      resolutionWarning: channelServerInfo && channelServerInfo.warning ? channelServerInfo.warning : '',
      triedDirs: channelServerInfo && Array.isArray(channelServerInfo.tried) ? channelServerInfo.tried : [],
      reachable: probe.reachable === true,
      health: probe.health || null,
      external: probe.reachable === true && base.managed !== true,
      admin: admin.ok
        ? {
            ok: true,
            deviceCount: Number(adminData.deviceCount) || 0,
            onlineDeviceId: adminData.onlineDeviceId || null,
            pairCodeActive: adminData.pairCodeActive === true,
            pairCodeExpiresAt: Number(adminData.pairCodeExpiresAt) || null,
            lastEventAt: adminData.lastEventAt || null,
            bridgeEnabled: adminData.bridgeEnabled === true,
            uptimeSec: Number(adminData.uptimeSec) || 0,
            startedAt: Number(adminData.startedAt) || null,
          }
        : { ok: false, error: admin.error, message: admin.message, hint: admin.hint },
    };
  };

  /** 语音配置（只读掩码）：.env 里的提供商与 key；环境变量里的 key 优先级更高。 */
  const voiceSnapshot = () => {
    const providerRaw = envPath ? readDotEnvValue(envPath, VOICE_PROVIDER_ENV) : '';
    const provider = VOICE_PROVIDERS.includes(providerRaw) ? providerRaw : 'deepseek';
    const fileKey = envPath ? readDotEnvValue(envPath, 'DEEPSEEK_API_KEY') : '';
    const envKeyValue = String(process.env.DEEPSEEK_API_KEY || '').trim();
    let keySource = 'none';
    let keyMask = '';
    if (envKeyValue) {
      keySource = 'process-env';
      keyMask = maskSecret(envKeyValue);
    } else if (fileKey) {
      keySource = 'env-file';
      keyMask = maskSecret(fileKey);
    }
    return {
      provider,
      keyMask,
      keySource,
      envPrecedence: Boolean(envKeyValue),
      running: manager.running === true,
    };
  };

  const sessionSnapshot = () =>
    sessionBinding && typeof sessionBinding.snapshot === 'function' ? sessionBinding.snapshot() : null;

  const statusPayload = async () => {
    const channel = await channelSnapshot();
    const voice = voiceSnapshot();
    const admin = channel.admin;
    let pair;
    if (lastPair && lastPair.expiresAt > Date.now() && channel.reachable === true) {
      pair = { active: true, code: lastPair.code, expiresAt: lastPair.expiresAt, ttlMs: lastPair.ttlMs };
    } else if (admin.ok && admin.pairCodeActive && channel.reachable === true) {
      // 码只存在进程内存里：通道没在听时显示「有效」只会误导
      pair = { active: true, code: null, expiresAt: admin.pairCodeExpiresAt, ttlMs: null };
    } else {
      pair = { active: false, code: null, expiresAt: null, ttlMs: null };
    }
    return {
      ok: true,
      api: PANEL_API_PREFIX,
      channel,
      pair,
      voice,
      session: sessionSnapshot(),
      token: tokenInfo
        ? {
            source: String(tokenInfo.source || ''),
            generated: tokenInfo.generated === true,
            persisted: tokenInfo.persisted !== false,
            warning: String(tokenInfo.warning || ''),
          }
        : null,
      addresses: enumerateAddresses(port),
      log: channel.log,
      managerLog: channel.log,
      at: Date.now(),
    };
  };

  /** 信任栅栏：能拿到 connection 服务就先过它（隧道把 Host 改写成回环时也能挡住）。 */
  const fenceReject = (req, res) => {
    let connection = null;
    try {
      connection = getConnection();
    } catch {
      connection = null;
    }
    if (!connection || typeof connection.requestRejection !== 'function') return false;
    try {
      const rejection = connection.requestRejection({ headers: req.headers });
      if (rejection === undefined) return false;
      res.statusCode = typeof rejection === 'number' ? rejection : 403;
      try {
        res.end();
      } catch {
        /* 忽略 */
      }
      return true;
    } catch {
      res.statusCode = 403;
      try {
        res.end();
      } catch {
        /* 忽略 */
      }
      return true;
    }
  };

  const handle = async (req, res) => {
    try {
      const url = new URL(String(req.url || '/'), 'http://127.0.0.1');
      const path = url.pathname;
      if (!path.startsWith(PANEL_API_PREFIX)) {
        sendJson(res, 404, { ok: false, error: 'not-found' });
        return;
      }
      const sub = path.slice(PANEL_API_PREFIX.length) || '/';
      const method = String(req.method || 'GET').toUpperCase();

      // ① 信任栅栏（宿主有就过）② 本机同源（兜底）
      if (fenceReject(req, res)) return;
      if (!sameOriginOk(req)) {
        sendJson(res, 403, { ok: false, error: 'forbidden', message: '只接受本机同源的请求' });
        return;
      }

      if (method === 'GET' && (sub === '/status' || sub === '/' || sub === '')) {
        sendJson(res, 200, await statusPayload());
        return;
      }

      // 手机该填的电脑端地址（非回环优先、Tailscale 100.x 最前；不含任何密钥）
      if (method === 'GET' && sub === '/address') {
        sendJson(res, 200, { ok: true, port, addresses: enumerateAddresses(port) });
        return;
      }

      // ---- 投递会话绑定：写插件自己的 data\settings.json，优先于 patch ----
      if (method === 'GET' && sub === '/session') {
        if (!sessionBinding) {
          sendJson(res, 503, { ok: false, error: 'no-session-store', message: '插件没有会话绑定存储' });
          return;
        }
        sendJson(res, 200, { ok: true, ...sessionSnapshot() });
        return;
      }

      if (method === 'POST' && sub === '/session') {
        if (!sessionBinding) {
          sendJson(res, 503, { ok: false, error: 'no-session-store', message: '插件没有会话绑定存储' });
          return;
        }
        let parsed;
        try {
          const raw = await readBody(req);
          parsed = JSON.parse(raw || '{}');
        } catch (error) {
          sendJson(res, error && error.code === 'TOO_LARGE' ? 413 : 400, { ok: false, error: 'bad-request' });
          return;
        }
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
          sendJson(res, 400, { ok: false, error: 'bad-request' });
          return;
        }
        // 字段缺失 / null / 空串都表示「清除绑定」；有值则必须是 session-<uuid> 或裸 uuid
        const input = Object.prototype.hasOwnProperty.call(parsed, 'sessionId') ? parsed.sessionId : null;
        let result;
        try {
          result = sessionBinding.set(input);
        } catch (error) {
          log(`phone-bridge: 写会话设置失败 ${String((error && error.message) || error)}`);
          sendJson(res, 500, {
            ok: false,
            error: 'settings-write-failed',
            message: `写设置文件失败：${String((error && error.message) || error)}`,
          });
          return;
        }
        if (!result.ok) {
          sendJson(res, 400, { ok: false, error: result.error, message: result.message });
          return;
        }
        note(result.snapshot.bound ? '投递会话已绑定' : '投递会话绑定已清除（未绑定）');
        sendJson(res, 200, { ok: true, ...result.snapshot });
        return;
      }

      if (method === 'POST' && sub === '/channel/start') {
        if (manager.running !== true) {
          // 端口上已经有通道（外部启动）：再 spawn 只会 EADDRINUSE，直接说清楚
          const occupied = await healthProbe();
          if (occupied.reachable) {
            sendJson(res, 200, {
              ok: false,
              error: 'already-running-external',
              message: `127.0.0.1:${port} 上已经有一个通道在跑（不是面板拉起的）`,
              hint: '不需要再启动；要让面板托管，先把那个外部实例停掉。',
              channel: await channelSnapshot(),
            });
            return;
          }
        }
        const started = manager.start('面板');
        note(started.ok ? '面板请求启动通道' : `面板启动通道失败：${started.message || started.error}`);
        if (started.ok) lastPair = null; // 新进程会重新生成配对码，旧码作废
        sendJson(res, 200, {
          ok: started.ok === true,
          error: started.error,
          message: started.message,
          channel: await channelSnapshot(),
        });
        return;
      }

      if (method === 'POST' && sub === '/channel/stop') {
        const stopped = await manager.stop('面板');
        note(stopped.ok ? '面板请求停止通道' : `面板停止通道失败：${stopped.error}`);
        if (stopped.ok) lastPair = null;
        sendJson(res, 200, {
          ok: stopped.ok === true,
          error: stopped.error,
          channel: await channelSnapshot(),
        });
        return;
      }

      if (method === 'POST' && sub === '/pair') {
        const r = await adminCall('POST', '/admin/pair', {});
        if (!r.ok) {
          sendJson(res, 200, { ok: false, error: r.error, message: r.message, hint: r.hint, pair: { active: false } });
          return;
        }
        const code = String(r.data.code || '').trim();
        const expiresAt = Number(r.data.expiresAt) || Date.now() + 5 * 60 * 1000;
        const ttlMs = Number(r.data.ttlMs) || Math.max(0, expiresAt - Date.now());
        lastPair = { code, expiresAt, ttlMs, at: Date.now() };
        note(`已生成新配对码（${Math.round(ttlMs / 60000)} 分钟有效）`);
        sendJson(res, 200, { ok: true, code, expiresAt, ttlMs, pair: { active: true, code, expiresAt, ttlMs } });
        return;
      }

      if (method === 'GET' && sub === '/devices') {
        const r = await adminCall('GET', '/admin/devices');
        if (!r.ok) {
          sendJson(res, 200, { ok: false, error: r.error, message: r.message, hint: r.hint, devices: [], count: 0 });
          return;
        }
        const list = Array.isArray(r.data.devices) ? r.data.devices : [];
        sendJson(res, 200, {
          ok: true,
          count: list.length,
          lastEventAt: r.data.lastEventAt || null,
          devices: list.map((item) => ({
            deviceId: String(item.deviceId || ''),
            pairedAt: Number(item.pairedAt) || null,
            online: item.online === true,
            lastActiveAt: Number(item.lastActiveAt) || null,
          })),
        });
        return;
      }

      if (method === 'POST' && sub === '/voice') {
        let parsed;
        try {
          const raw = await readBody(req);
          parsed = JSON.parse(raw || '{}');
        } catch (error) {
          sendJson(res, error && error.code === 'TOO_LARGE' ? 413 : 400, { ok: false, error: 'bad-request' });
          return;
        }
        if (!parsed || typeof parsed !== 'object') {
          sendJson(res, 400, { ok: false, error: 'bad-request' });
          return;
        }
        const providerRaw = parsed.provider === undefined ? '' : String(parsed.provider).trim();
        if (providerRaw && !VOICE_PROVIDERS.includes(providerRaw)) {
          sendJson(res, 400, { ok: false, error: 'bad-provider', providers: VOICE_PROVIDERS });
          return;
        }
        if (!envPath) {
          sendJson(res, 200, { ok: false, error: 'no-env-path', message: '找不到 channel-server/.env 的路径（入口目录不存在）' });
          return;
        }

        const writes = [];
        try {
          if (providerRaw) {
            writeDotEnvValue(envPath, VOICE_PROVIDER_ENV, providerRaw);
            writes.push('provider');
          }
          const keyInput = parsed.apiKey === undefined ? '' : String(parsed.apiKey);
          let wroteKey = false;
          if (keyInput.trim()) {
            const checked = validateApiKey(keyInput);
            if (!checked.ok) {
              sendJson(res, 400, { ok: false, error: `bad-key:${checked.error}` });
              return;
            }
            writeDotEnvValue(envPath, 'DEEPSEEK_API_KEY', checked.value);
            wroteKey = true;
            writes.push('apiKey');
          }
          try {
            secrets?.invalidate?.();
          } catch {
            /* 忽略 */
          }
          if (writes.length === 0) {
            sendJson(res, 200, { ok: true, changed: false, voice: voiceSnapshot() });
            return;
          }
          // 只把掩码写进日志，永不写明文
          note(`语音配置已保存（${writes.join(' + ')}${wroteKey ? '，key 掩码 ' + maskSecret(readDotEnvValue(envPath, 'DEEPSEEK_API_KEY')) : ''}）`);
          const voice = voiceSnapshot();
          sendJson(res, 200, {
            ok: true,
            changed: true,
            wroteKey,
            restartNeeded: wroteKey && manager.running === true,
            voice,
            note: wroteKey && manager.running === true ? '通道正在跑：新 key 要重启通道才生效（停止 → 启动）。' : '',
          });
          return;
        } catch (error) {
          log(`phone-bridge: 写 .env 失败 ${String((error && error.message) || error)}`);
          sendJson(res, 500, { ok: false, error: 'env-write-failed', message: String((error && error.message) || error) });
          return;
        }
      }

      sendJson(res, 404, { ok: false, error: 'not-found', path: sub });
    } catch (error) {
      log(`phone-bridge: 面板接口处理失败 ${String((error && error.message) || error)}`);
      if (!res.headersSent) sendJson(res, 500, { ok: false, error: 'internal' });
    }
  };

  return { handler: handle, api: PANEL_API_PREFIX };
}
