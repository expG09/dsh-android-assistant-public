/**
 * phone-bridge — 把手机端通道接进 DSH 会话的宿主插件（M7）。
 *
 * 作用：手机 App → channel-server（WebSocket 8788）→ 本插件（本机 HTTP 回环）→ 目标会话。
 * 收到 { text, sessionId? } 后，把这段文本当成一条**用户输入**投进目标会话（agent.followup），
 * 等这一轮结束，只把回复的**正文文本**回给 channel-server，由它转 TTS 回手机。
 *
 * 安全边界（硬性）：
 *   - 只监听回环地址（127.0.0.1 / ::1），配置里写非回环地址直接拒绝启动。
 *   - 必须配置共享密钥；没配密钥就不启动监听（fail closed），带错密钥一律 401。
 *   - 只接受「一段用户文本」，不接受 shell、文件名、路径、命令；请求体上限 8 KiB。
 *   - 日志只写方法/状态/字节数/耗时/会话 id，永不写消息正文与密钥。
 *
 * 依赖的宿主内部 API（已按本机 0.2.2-rc.3 的源码核对，见 docs/report/M7.md）：
 *   - ctx.agents            @deepseek-ai/dsh-agent       AgentRegistry：get(id) / list()
 *   - agent.followup(msg)   @deepseek-ai/dsh-agent       投递一条用户消息并唤醒驱动
 *   - agent/meta 事件       agent/inbox/claimed { agent, message, turn }
 *   - session/event 事件    event.type === 'assistant/message' / 'turn/end'
 */

import { createServer, get as httpGet } from 'node:http';
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { chmodSync, existsSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createSecretProvider, maskText, readDotEnvValue, writeDotEnvValue } from './lib/env.mjs';
import { ChannelServerManager } from './lib/process.mjs';
import { createPanel } from './lib/panel.mjs';
import { createSessionBinding, resolveSettingsPath } from './lib/settings.mjs';

/** 本插件依赖的宿主服务；没有 agents 的 profile 里保持不激活，而不是抛错。 */
export const inject = ['agents'];

/** 用户文本上限（字符）。超过直接 400，不进会话。 */
const MAX_TEXT_CHARS = 4000;
/** 请求体上限（字节）。 */
const MAX_BODY_BYTES = 8 * 1024;
/** 一次提问等回复的默认上限。 */
const DEFAULT_REQUEST_TIMEOUT_MS = 180 * 1000;
/** 本插件目录：用 import.meta.url 算，装成目录链接时也指向真实检出目录。 */
const PLUGIN_DIR = dirname(fileURLToPath(import.meta.url));
/** 设置面板接口挂在宿主 webServer 上的前缀（client.js 里写死同一个值）。 */
const PANEL_API_PREFIX = '/api/phone-bridge';

/**
 * 允许的监听地址：只有回环。写成别的（0.0.0.0、局域网地址）一律拒绝启动，
 * 这条是硬要求，不交给用户配错。
 */
function assertLoopbackHost(host) {
  const value = String(host || '').trim();
  if (value === 'localhost' || value === '::1' || /^127(?:\.\d{1,3}){3}$/.test(value)) return value;
  throw new Error(
    `phone-bridge: host ${JSON.stringify(value)} 不是回环地址；本插件只允许监听 127.0.0.1 / ::1`,
  );
}

/**
 * 解析共享密钥。优先级：config.token → 环境变量 → tokenFile → 托管目录的 channel-server/.env。
 * 只记录「来源」，绝不记录值、长度、前后缀。
 */
function resolveToken(config, channelEnvPath) {
  const direct = String(config.token || '').trim();
  if (direct) return { token: direct, source: 'config' };

  const envName = String(config.tokenEnv || 'PHONE_BRIDGE_TOKEN');
  const fromEnv = String(process.env[envName] || '').trim();
  if (fromEnv) return { token: fromEnv, source: 'env' };

  const file = String(config.tokenFile || '').trim();
  if (file) {
    const fromFile = readDotEnvValue(file, envName);
    if (fromFile) return { token: fromFile, source: 'tokenFile' };
  }

  if (channelEnvPath) {
    const fromChannel = readDotEnvValue(channelEnvPath, envName);
    if (fromChannel) return { token: fromChannel, source: 'channel-server/.env' };
  }
  return { token: '', source: 'none' };
}

/**
 * 拿到桥端共享密钥；**没有就生成一个**（第一次运行的人不用手填）。
 *
 * - 已有值（config.token / 环境变量 / tokenFile / channel-server/.env）一律不覆盖；
 * - 生成后写进 channel-server/.env（文件不存在会创建，尽量 chmod 600）；
 * - 写不进去也不崩：退回内存 token，并在日志 + 面板状态里说明（`warning`）；
 * - `DEEPSEEK_API_KEY` 缺失不算错误（只影响语音合成/识别），这里不碰它。
 */
export function ensureBridgeToken(config, envPath, log) {
  const resolved = resolveToken(config, envPath);
  if (resolved.token) {
    return { ...resolved, generated: false, persisted: true, warning: '' };
  }

  const token = randomBytes(24).toString('hex');
  if (!envPath) {
    const warning = '没有 channel-server 目录，生成的 token 只能存在内存里（重启会变）';
    log.error(`phone-bridge: ${warning}`);
    return { token, source: 'generated-memory', generated: true, persisted: false, warning };
  }
  try {
    writeDotEnvValue(envPath, 'PHONE_BRIDGE_TOKEN', token);
    try {
      chmodSync(envPath, 0o600);
    } catch {
      // Windows / 非 POSIX 上 chmod 未必有意义，失败不影响使用
    }
    log.info(
      `phone-bridge: 没有找到 PHONE_BRIDGE_TOKEN，已生成新 token 并写入 ${envPath}（已有值不会被覆盖）`,
    );
    return { token, source: 'generated-file', generated: true, persisted: true, warning: '' };
  } catch (error) {
    const warning = `生成 token 后写入 ${envPath} 失败，改用内存 token：${String((error && error.message) || error)}`;
    log.error(`phone-bridge: ${warning}`);
    return { token, source: 'generated-memory', generated: true, persisted: false, warning };
  }
}

/** 常数时间比较两个字符串（先比长度，避免 timingSafeEqual 抛错）。 */
function safeEqual(a, b) {
  const ba = Buffer.from(String(a), 'utf8');
  const bb = Buffer.from(String(b), 'utf8');
  if (ba.length !== bb.length) return false;
  return timingSafeEqual(ba, bb);
}

/**
 * 算出 channel-server 的目录（`.env` 的 token / DEEPSEEK_API_KEY 也从同一个目录读）。
 *
 * 优先级：
 *   1. config.channelServerDir 非空且是**绝对路径** → 用它；
 *      是相对路径 → 记为 warning、忽略，继续走 2（状态里会带原因）；
 *      绝对路径但目录里没有 server.js → 直接 script-missing，不再回落（配置优先，错了就说清）；
 *   2. `<插件目录>\channel-server`（**自包含安装**用这个：channel-server 就在插件包里）；
 *   3. `<插件目录>\..\channel-server`（开发时仓库布局，保留兼容）；
 *   4. `tokenFile` 所在目录（兜底，保留）；
 *   5. 都不行 → script-missing，`message` 里把试过的路径逐个列出来。
 *
 * 返回 { dir, script, envPath, source, scriptMissing, message, tried, warning }。
 * 面板改不了这个路径 —— 面板只能「启动 / 停止」这个入口。
 */
export function resolveChannelServerDir(config, pluginDir = PLUGIN_DIR) {
  const tried = [];
  let warning = '';
  const configured = String((config && config.channelServerDir) || '').trim();

  if (configured) {
    if (!isAbsolute(configured)) {
      warning = `channelServerDir=${configured} 不是绝对路径，已忽略；改用插件自带的 channel-server`;
    } else {
      const dir = resolve(configured);
      tried.push(dir);
      const script = join(dir, 'server.js');
      if (!existsSync(script)) {
        return {
          dir,
          script,
          envPath: join(dir, '.env'),
          source: 'config',
          scriptMissing: true,
          message: `配置的 channelServerDir=${configured} 里没有 server.js（找的是 ${script}）`,
          tried,
          warning: '',
        };
      }
      return {
        dir,
        script,
        envPath: join(dir, '.env'),
        source: 'config',
        scriptMissing: false,
        message: '',
        tried,
        warning: '',
      };
    }
  }

  const candidates = [
    { dir: join(pluginDir, 'channel-server'), source: 'plugin-embedded' },
    { dir: resolve(pluginDir, '..', 'channel-server'), source: 'plugin-relative' },
  ];
  const tokenFile = String((config && config.tokenFile) || '').trim();
  if (tokenFile) candidates.push({ dir: dirname(resolve(tokenFile)), source: 'token-file' });

  for (const candidate of candidates) {
    tried.push(candidate.dir);
    const script = join(candidate.dir, 'server.js');
    if (existsSync(script)) {
      return {
        ...candidate,
        script,
        envPath: join(candidate.dir, '.env'),
        scriptMissing: false,
        message: '',
        tried,
        warning,
      };
    }
  }

  const first = candidates[0];
  return {
    ...first,
    script: join(first.dir, 'server.js'),
    envPath: join(first.dir, '.env'),
    scriptMissing: true,
    message: `channel-server 入口不存在；试过：${tried.join('；')}（每个目录下都要有 server.js）`,
    tried,
    warning,
  };
}

/**
 * 探一下 channel-server 的 `/healthz`（不需要密钥）：
 * 用来判断「端口上是不是已经有一个通道在跑」——包括不是本插件拉起的。
 */
function probeChannel(port, timeoutMs = 800) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (value) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };
    let req;
    try {
      req = httpGet({ host: '127.0.0.1', port, path: '/healthz', timeout: timeoutMs }, (res) => {
        res.resume();
        done(res.statusCode === 200);
      });
    } catch {
      done(false);
      return;
    }
    req.on('timeout', () => {
      try {
        req.destroy();
      } catch {
        /* 忽略 */
      }
      done(false);
    });
    req.on('error', () => done(false));
  });
}

/**
 * 挂设置面板接口（宿主有 webServer 才挂；没有就只做通道托管，面板降级）。
 * 注册走 ctx.effect：插件卸载时路由一起撤掉。
 * webServer 可能比本插件晚就绪（别的插件都踩过）：先试一次，拿不到就每 500ms 再试，
 * 最多 20 次；仍拿不到就降级，不影响通道托管。
 */
function setupPanel(ctx, cfg, paths, manager, secrets, sessionBinding, tokenInfo, log) {
  const getWebServer = () => {
    let webServer = null;
    try {
      webServer = typeof ctx.get === 'function' ? ctx.get('webServer') : undefined;
    } catch {
      webServer = null;
    }
    if (!webServer) {
      try {
        if (ctx.webServer && typeof ctx.webServer.register === 'function') webServer = ctx.webServer;
      } catch {
        webServer = null;
      }
    }
    return webServer && typeof webServer.register === 'function' ? webServer : null;
  };

  /** 取 DSH 的信任栅栏（三种写法都试，拿不到就退到本机同源这一道）。 */
  const getConnection = () => {
    const tries = [
      () => (typeof ctx.get === 'function' ? ctx.get('connection') : null),
      () => Reflect.get(ctx, 'connection'),
      () => (ctx.root && typeof ctx.root === 'object' ? ctx.root.connection : null),
    ];
    for (const fn of tries) {
      try {
        const connection = fn();
        if (connection && typeof connection.requestRejection === 'function') return connection;
      } catch {
        /* 换下一个写法 */
      }
    }
    return null;
  };

  const panel = createPanel({
    manager,
    channelPort: manager.port,
    envPath: paths.envPath,
    // 用解析/生成后的 token（可能刚写进 .env，也可能只在内存里）；面板调 /admin 与桥端永远一致
    token: tokenInfo.token,
    tokenEnv: cfg.tokenEnv,
    getConnection,
    secrets,
    sessionBinding,
    channelServerInfo: paths,
    tokenInfo,
    log: (message) => log.info(message),
  });

  let mounted = false;
  const mount = () => {
    if (mounted) return true;
    const webServer = getWebServer();
    if (!webServer) return false;
    try {
      ctx.effect(
        () => webServer.register({ kind: 'prefix', path: PANEL_API_PREFIX, handler: panel.handler }),
        'phone-bridge: 设置面板接口',
      );
      mounted = true;
      log.info(`phone-bridge: 设置面板接口已挂 ${PANEL_API_PREFIX}`);
      return true;
    } catch (error) {
      log.error(`phone-bridge: 设置面板接口没挂上 ${String((error && error.message) || error)}`);
      return false;
    }
  };

  if (mount()) return true;
  log.info('phone-bridge: 宿主还没有 webServer 服务 ⇒ 面板接口先不挂（通道托管照常，稍后重试）');
  let tries = 0;
  ctx.effect(() => {
    const timer = setInterval(() => {
      tries += 1;
      if (mount() || tries >= 20) {
        clearInterval(timer);
        if (!mounted) log.info('phone-bridge: 重试 20 次仍没有 webServer ⇒ 面板降级（通道托管照常）');
      }
    }, 500);
    if (timer.unref) timer.unref();
    return () => clearInterval(timer);
  }, 'phone-bridge: 设置面板接口重试');
  return false;
}



/** 从一个 assistant/message 的内容块里只取正文文本，丢掉工具调用、思考过程、附件。 */
function textOfBlocks(content) {
  if (!Array.isArray(content)) return '';
  const parts = [];
  for (const block of content) {
    if (block && block.type === 'text' && typeof block.text === 'string' && block.text) {
      parts.push(block.text);
    }
  }
  return parts.join('');
}

/** 从会话事件里判断是不是非顶层子代理会话（子代理不该被手机当成「我」）。 */
function isSubagentSession(session) {
  const header = session && session.header;
  if (!header) return false;
  if (header.origin === 'subagent') return true;
  return typeof header.delegationDepth === 'number' && header.delegationDepth > 0;
}

export function apply(ctx, config) {
  config = config || {};
  const cfg = {
    host: assertLoopbackHost(config.host ?? '127.0.0.1'),
    port: Number(config.port ?? 8795),
    path: String(config.path || '/m7/ask'),
    token: config.token,
    tokenEnv: config.tokenEnv,
    tokenFile: config.tokenFile,
    sessionId: String(config.sessionId || '').trim(),
    requestTimeoutMs: Number(config.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS),
    // ---- 托管 channel-server / 设置面板（规格 B 段） ----
    channelPort: Number(config.channelPort ?? 8788),
    autoStart: config.autoStart !== false,
    nodePath: String(config.nodePath || ''),
    maxRestarts: Number(config.maxRestarts ?? 5),
    // 投递会话的设置文件；留空 = 插件目录下的 data\settings.json（测试/便携时才会指别处）
    settingsFile: String(config.settingsFile || '').trim(),
    // channel-server 目录：留空 = 插件自带的 channel-server（自包含）；开发时可填兄弟目录的绝对路径
    channelServerDir: String(config.channelServerDir || '').trim(),
  };

  const log = makeLogger(ctx);

  // ---- 设置面板 + channel-server 进程托管（与 8795 的桥端各自独立） ----
  // 先挂面板与托管：就算桥端因为没密钥而不监听，面板仍然能显示「为什么没起来」。
  const paths = resolveChannelServerDir(cfg);
  const secrets = createSecretProvider({
    envPath: paths.envPath,
    names: [String(cfg.tokenEnv || 'PHONE_BRIDGE_TOKEN'), 'DEEPSEEK_API_KEY'],
  });
  // 首次运行自动生成共享密钥（已有值不覆盖）；写失败降级成内存 token，不崩。
  // 找不到 channel-server 目录时不要顺着 envPath 去造一个目录，直接用内存 token 并说明。
  const tokenInfo = ensureBridgeToken(cfg, paths.scriptMissing ? '' : paths.envPath, log);
  try {
    secrets.invalidate();
  } catch {
    /* 忽略 */
  }
  const manager = new ChannelServerManager({
    scriptPath: paths.script,
    cwd: paths.dir,
    port: cfg.channelPort,
    nodePath: cfg.nodePath,
    autoRestart: true,
    maxRestarts: cfg.maxRestarts,
    // 入口定位失败时（尤其是复制安装路径不对）把「试过哪些路径 / 配置错在哪」原样交给面板
    missingReason: paths.message,
    // 子进程也拿同一个 token：即使 .env 写不进去（内存 token）两边也一致
    extraEnv: { PHONE_BRIDGE_TOKEN: tokenInfo.token },
    // 子进程输出进缓冲区之前先掩码：日志里永远不出现 token / API key 明文
    mask: (text) => maskText(text, secrets.values()),
    log: (message) => log.info(message),
  });
  if (paths.warning) log.error(`phone-bridge: ${paths.warning}`);
  if (!paths.scriptMissing) {
    log.info(`phone-bridge: channel-server 目录=${paths.dir}（来源=${paths.source}）`);
  }
  // 投递会话：设置文件（DSH_HOME\phone-bridge\settings.json，跟着 DSH 走）优先于 patch 的 sessionId。
  // 两层都空 = 未绑定：手机消息直接 409，不猜「最近活跃」。默认目录建不出来时降级到插件 data\。
  const settingsLocation = resolveSettingsPath({
    configFile: cfg.settingsFile,
    env: process.env,
    homedir,
    pluginDir: PLUGIN_DIR,
  });
  const sessionBinding = createSessionBinding({
    file: settingsLocation.file,
    location: settingsLocation,
    fallback: () => String(cfg.sessionId || '').trim(),
    log: (message) => log.info(message),
  });
  if (settingsLocation.fallback) {
    log.error(
      `phone-bridge: 会话绑定设置文件降级到 ${settingsLocation.file}（${settingsLocation.reason}）`,
    );
  } else {
    log.info(`phone-bridge: 会话绑定设置文件 = ${settingsLocation.file}`);
  }
  setupPanel(ctx, cfg, paths, manager, secrets, sessionBinding, tokenInfo, log);
  ctx.effect(() => {
    let disposed = false;
    if (paths.scriptMissing) log.error(`phone-bridge: ${paths.message}`);
    const autoStart = async () => {
      if (!cfg.autoStart || paths.scriptMissing) return;
      // 端口上已经有一个通道（可能是用户手动启动的）：别去抢，重复拉起只会 EADDRINUSE
      const occupied = await probeChannel(manager.port);
      if (disposed) return;
      if (manager.running) return; // 探测期间用户已经在面板里点过「启动」
      if (occupied) {
        log.info(
          `phone-bridge: 127.0.0.1:${manager.port} 上已有通道在跑（不是本插件拉起的），跳过自动拉起`,
        );
        return;
      }
      const started = manager.start('插件加载');
      if (!started.ok) {
        log.error(`phone-bridge: 托管通道没起来（${started.error}）：${started.message || ''}`);
      }
    };
    autoStart().catch((error) => {
      log.error(`phone-bridge: 自动拉起通道出错 ${String((error && error.message) || error)}`);
    });
    return () => {
      // 插件卸载（桌面端退出）时把托管的通道一起收掉，别留孤儿进程占着 8788
      disposed = true;
      manager.dispose();
    };
  }, 'phone-bridge: channel-server 托管');

  const token = tokenInfo.token;
  const tokenSource = tokenInfo.source;
  if (!token) {
    // fail closed：没有密钥就不开端口，也不会出现「无密钥也能问」的窗口。
    // （正常路径上 ensureBridgeToken 一定会给出一个 token；这里是最后的保险）
    log.error('phone-bridge: 没有可用的共享密钥（生成也失败了），未启动监听');
    return;
  }
  if (!Number.isInteger(cfg.port) || cfg.port <= 0 || cfg.port > 65535) {
    log.error(`phone-bridge: port ${String(config.port)} 不是合法端口，未启动监听`);
    return;
  }

  /** 待回应的提问：messageId → waiter。 */
  const waiters = new Map();
  /** 已绑定会话的待回应提问：sessionId → Set<waiter>。 */
  const bySession = new Map();
  /** 每个会话串行处理，避免两个提问在同一轮里互相插队。 */
  const queues = new Map();

  /** 宿主把我们在 inbox 里排的那条消息认领进某一轮时，记下轮次。 */
  ctx.on('agent/inbox/claimed', (payload) => {
    if (!payload || !payload.message) return;
    const waiter = waiters.get(String(payload.message.id));
    if (!waiter) return;
    waiter.turn = payload.turn;
    bindSession(waiter, payload.agent && payload.agent.session);
  });

  /** 会话日志增长：user/message 兜底绑定，assistant/message 收正文，turn/end 收尾。 */
  ctx.on('session/event', (session, event) => {
    if (!session || !event) return;
    const sessionId = String(session.id);
    const set = bySession.get(sessionId);

    if (event.type === 'user/message') {
      const data = event.data || {};
      const waiter = waiters.get(String(data.id || ''));
      if (waiter) bindSession(waiter, session);
      return;
    }
    if (!set || set.size === 0) return;

    if (event.type === 'assistant/message') {
      const data = event.data || {};
      for (const waiter of set) {
        if (waiter.turn !== undefined && waiter.turn !== data.turn) continue;
        const text = textOfBlocks(data.message && data.message.content);
        if (text) waiter.parts.push(text);
        if (data.interrupted === true) waiter.interrupted = true;
      }
      return;
    }

    if (event.type === 'turn/end') {
      const data = event.data || {};
      for (const waiter of [...set]) {
        if (waiter.turn !== undefined && waiter.turn !== data.turn) continue;
        finish(waiter, data.reason);
      }
    }
  });

  // ---------------------------------------------------------------- 投递与等待

  function bindSession(waiter, session) {
    if (!session || waiter.sessionId) return;
    // 自动挑会话时不用子代理；但调用方**显式点名**的会话照办（它自己知道在问谁）。
    if (!waiter.allowSubagent && isSubagentSession(session)) return;
    const sessionId = String(session.id);
    waiter.sessionId = sessionId;
    let set = bySession.get(sessionId);
    if (!set) bySession.set(sessionId, (set = new Set()));
    set.add(waiter);
  }

  function detach(waiter) {
    waiters.delete(waiter.messageId);
    const set = bySession.get(waiter.sessionId);
    if (!set) return;
    set.delete(waiter);
    if (set.size === 0) bySession.delete(waiter.sessionId);
  }

  function finish(waiter, reason) {
    if (waiter.done) return;
    waiter.done = true;
    clearTimeout(waiter.timer);
    detach(waiter);
    const reply = waiter.parts.join('\n\n').trim();
    const kind = reason && reason.kind ? reason.kind : 'completed';
    waiter.resolve({
      reply,
      reason: kind,
      interrupted: waiter.interrupted === true || kind === 'aborted',
    });
  }

  /**
   * 选目标会话：请求里显式点名的 → 插件设置文件绑定的 → patch 里钉住的。
   * **没有绑定就明确拒绝，不再去猜「最近活跃的那个」**（宁可拒绝，不要投错会话）。
   */
  function resolveAgent(explicitSessionId) {
    const direct = String(explicitSessionId || '').trim() || sessionBinding.effective();
    if (!direct) return { error: 'no-session-bound' };
    const agent = ctx.agents.get(direct);
    if (!agent) return { error: 'session-not-found', sessionId: direct, allowSubagent: true };
    return { agent, allowSubagent: true };
  }

  /** 串行队列：同一个会话一次只跑一个提问。 */
  function enqueue(sessionId, job) {
    const prev = queues.get(sessionId) || Promise.resolve();
    const next = prev.then(job, job);
    const settled = next.then(
      () => {},
      () => {},
    );
    queues.set(sessionId, settled);
    settled.then(() => {
      if (queues.get(sessionId) === settled) queues.delete(sessionId);
    });
    return next;
  }

  /**
   * 给一个 promise 加硬上限：到点就 resolve 兜底值。
   * ask() 内部已经有 requestTimeoutMs 的计时器，这一层是最后的保险：
   * 任何内部异常（宿主事件没来、队列卡住……）都不允许把 8795 上的 HTTP 响应无限挂住。
   */
  function withDeadline(promise, ms, fallback) {
    return new Promise((resolve) => {
      let done = false;
      const timer = setTimeout(() => {
        if (done) return;
        done = true;
        resolve(fallback());
      }, ms);
      if (timer.unref) timer.unref();
      promise.then(
        (value) => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          resolve(value);
        },
        (error) => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          resolve({ error: 'internal', message: String((error && error.message) || error) });
        },
      );
    });
  }

  async function ask(text, explicitSessionId) {
    const target = resolveAgent(explicitSessionId);
    if (target.error) return { error: target.error, sessionId: target.sessionId };
    const agent = target.agent;
    const sessionId = String(agent.session.id);

    return enqueue(sessionId, async () => {
      const messageId = randomUUID();
      const message = {
        id: messageId,
        role: 'user',
        content: [{ type: 'text', text: `[phone] ${text}` }],
        // source.via 是给其它通道（如 dsh-qqbot）看的**路由标记**：
        // 手机发起的这一轮只回手机，不往 QQ/群里发（B4：手机端是通道不是场合）。
        // DSH 的 createUserMessage 只做 structuredClone，未知字段会原样保留进 session 事件。
        source: { kind: 'user', via: 'phone-bridge' },
      };

      const result = new Promise((resolve) => {
        const waiter = {
          messageId,
          sessionId: undefined,
          allowSubagent: target.allowSubagent === true,
          turn: undefined,
          parts: [],
          interrupted: false,
          done: false,
          resolve,
          timer: undefined,
        };
        waiter.timer = setTimeout(() => {
          if (waiter.done) return;
          waiter.done = true;
          detach(waiter);
          // 只写元数据：这条请求等了多久、回到 504。不写正文。
          log.info(
            `phone-bridge: 等会话回复超时 ${cfg.requestTimeoutMs}ms（messageId=${messageId}），回 504`,
          );
          resolve({ reply: waiter.parts.join('\n\n').trim(), reason: 'timeout', interrupted: true });
        }, cfg.requestTimeoutMs);
        if (waiter.timer.unref) waiter.timer.unref();
        waiters.set(messageId, waiter);
      });

      try {
        agent.followup(message);
      } catch (error) {
        const waiter = waiters.get(messageId);
        if (waiter) {
          clearTimeout(waiter.timer);
          waiter.done = true;
          detach(waiter);
        }
        return { error: 'followup-failed', message: String((error && error.message) || error), sessionId };
      }

      const out = await result;
      return { ...out, sessionId };
    });
  }

  // ---------------------------------------------------------------- HTTP

  function sendJson(res, status, payload) {
    const body = JSON.stringify(payload);
    try {
      res.writeHead(status, {
        'content-type': 'application/json; charset=utf-8',
        'content-length': Buffer.byteLength(body),
        'cache-control': 'no-store',
      });
      res.end(body);
    } catch {
      // 客户端已经断开（例如请求体超限时被 destroy）：没有可写的响应了，忽略。
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

  async function handle(req, res) {
    const startedAt = Date.now();
    const url = String(req.url || '/');
    const pathname = url.split('?')[0];
    let status = 500;

    try {
      // 鉴权先做，且对任何路径都做：不允许存在「免密钥就能摸」的端点。
      const presented = String(req.headers['x-phone-bridge-key'] || '');
      if (!presented || !safeEqual(presented, token)) {
        status = 401;
        sendJson(res, status, { ok: false, error: 'unauthorized' });
        return;
      }

      if (pathname === `${cfg.path}/health`) {
        if (req.method !== 'GET' && req.method !== 'HEAD') {
          status = 405;
          sendJson(res, status, { ok: false, error: 'method-not-allowed' });
          return;
        }
        status = 200;
        const live = ctx.agents.list().filter((a) => a && a.session && !isSubagentSession(a.session));
        sendJson(res, status, {
          ok: true,
          sessions: live.map((a) => String(a.session.id)),
          pinned: sessionBinding.effective() || null,
        });
        return;
      }

      if (pathname !== cfg.path) {
        status = 404;
        sendJson(res, status, { ok: false, error: 'not-found' });
        return;
      }
      if (req.method !== 'POST') {
        status = 405;
        sendJson(res, status, { ok: false, error: 'method-not-allowed' });
        return;
      }

      let parsed;
      try {
        const raw = await readBody(req);
        parsed = JSON.parse(raw || '{}');
      } catch (error) {
        status = error && error.code === 'TOO_LARGE' ? 413 : 400;
        sendJson(res, status, { ok: false, error: 'bad-request' });
        return;
      }

      const text = typeof parsed.text === 'string' ? parsed.text.trim() : '';
      if (!text) {
        status = 400;
        sendJson(res, status, { ok: false, error: 'empty-text' });
        return;
      }
      if (text.length > MAX_TEXT_CHARS) {
        status = 400;
        sendJson(res, status, { ok: false, error: 'text-too-long', maxChars: MAX_TEXT_CHARS });
        return;
      }
      const explicitSessionId = typeof parsed.sessionId === 'string' ? parsed.sessionId : '';

      // 等会话回复要有硬上限：ask() 内已有 cfg.requestTimeoutMs 的计时器，
      // 这里再兜一层（+2 秒），保证任何内部异常都不会把这个 HTTP 响应无限挂住。
      const hardDeadlineMs = cfg.requestTimeoutMs + 2000;
      const out = await withDeadline(ask(text, explicitSessionId), hardDeadlineMs, () => {
        log.error(
          `phone-bridge: ${cfg.path} 等会话回复超过兜底上限 ${hardDeadlineMs}ms，直接回 504`,
        );
        return {
          reason: 'timeout',
          reply: '',
          interrupted: true,
          sessionId: explicitSessionId || sessionBinding.effective() || '',
        };
      });
      if (out.error) {
        const map = {
          'session-not-found': 404,
          'no-session-bound': 409,
          'followup-failed': 502,
        };
        status = map[out.error] || 500;
        sendJson(res, status, {
          ok: false,
          error: out.error,
          // 「未绑定」要把原因写清楚：手机消息不会投递，管理员得先去面板绑一个会话
          message: out.error === 'no-session-bound' ? '未绑定投递会话' : out.message,
          sessionId: out.sessionId,
        });
        return;
      }

      status = out.reason === 'timeout' ? 504 : 200;
      sendJson(res, status, {
        ok: out.reason !== 'timeout',
        reply: out.reply,
        sessionId: out.sessionId,
        reason: out.reason,
        interrupted: out.interrupted === true,
      });
    } catch (error) {
      status = 500;
      if (!res.headersSent) sendJson(res, status, { ok: false, error: 'internal' });
      log.error(`phone-bridge: 处理请求失败 ${String((error && error.message) || error)}`);
    } finally {
      // 只记元数据：方法、状态、字节数、耗时、会话。永不记正文与密钥。
      log.info(
        `phone-bridge: ${req.method} ${pathname} -> ${status} ${Date.now() - startedAt}ms`,
      );
    }
  }

  const server = createServer((req, res) => {
    handle(req, res).catch(() => {
      if (!res.headersSent) {
        try {
          res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
        } catch {
          /* 响应已不可写，忽略 */
        }
      }
      try {
        res.end();
      } catch {
        /* 同上 */
      }
    });
  });

  server.on('error', (error) => {
    log.error(`phone-bridge: HTTP 服务出错 ${String((error && error.message) || error)}`);
    // 监听失败（端口被占等）时把没跑起来的 server 关掉，别留一个悬着的句柄
    if (!server.listening) {
      try {
        server.close();
      } catch {
        /* 本来就没在跑，忽略 */
      }
    }
  });

  ctx.effect(() => {
    server.listen(cfg.port, cfg.host, () => {
      log.info(
        `phone-bridge: 监听 http://${cfg.host}:${cfg.port}${cfg.path}（密钥来源=${tokenSource}，投递会话=${sessionBinding.effective() || '未绑定（手机消息不投递）'}）`,
      );
    });
    return () => {
      for (const waiter of [...waiters.values()]) {
        clearTimeout(waiter.timer);
        waiter.done = true;
        detach(waiter);
        waiter.resolve({ reply: '', reason: 'disposed', interrupted: true });
      }
      queues.clear();
      return new Promise((resolve) => {
        // 注意：listen 是异步的 —— 插件可能在「还没 listening」时就被卸载。
        // 这时不能直接 resolve 走人（那样 listen 完成后会留下一个孤儿监听），
        // 要等它真的起来再 close；监听失败（error）或超时也算收摊。
        let settled = false;
        const finish = () => {
          if (settled) return;
          settled = true;
          resolve();
        };
        const closeNow = () => {
          try {
            server.close(() => finish());
          } catch {
            finish();
          }
          if (typeof server.closeAllConnections === 'function') server.closeAllConnections();
        };
        if (server.listening) {
          closeNow();
          return;
        }
        server.once('listening', () => closeNow());
        server.once('error', () => finish());
        const timer = setTimeout(() => {
          try {
            server.close();
          } catch {
            /* 忽略 */
          }
          finish();
        }, 2000);
        if (timer.unref) timer.unref();
      });
    };
  });
}

/** 优先用宿主 logger，拿不到就退回 console（不写正文，只写元数据）。 */
function makeLogger(ctx) {
  const logger = ctx && ctx.logger;
  if (logger && typeof logger.info === 'function') {
    return {
      info: (message) => logger.info(message),
      error: (message) => logger.error(message),
    };
  }
  return {
    info: (message) => console.log(message),
    error: (message) => console.error(message),
  };
}
