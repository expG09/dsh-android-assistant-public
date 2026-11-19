/**
 * lib/process.mjs —— channel-server 的进程托管（spawn / 状态 / 有限次退避拉起 / 日志尾部）。
 *
 * 照 `dsh-bili-scroll/lib/mcp.mjs` 的 spawn 写法（同机已验证可用），但这里管的是
 * **本机固定路径的 node 脚本**，不是可配置命令：
 *   - 入口路径 = 插件目录上一级的 `channel-server\server.js`（index.js 算好传进来）；
 *   - 参数只有 `--port=<配置里的整数端口>`，**面板不能拼参数**；
 *   - stdout/stderr 进环形缓冲（最后 200 行），写进缓冲前一律掩码；
 *   - 非主动停止时按 1s/2s/5s/10s/20s 退避拉起，最多 5 次；稳定跑满 60 秒后次数清零；
 *   - 退出码 / 退出时间 / 最近错误都记下来，面板只读 `status()`。
 *
 * 这里不 import 任何 `@deepseek-ai/*`，只用 node 内置模块。
 */

import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';

const BACKOFF_MS = [1000, 2000, 5000, 10000, 20000];
const MAX_LOG_LINES = 200;
const MAX_LOG_LINE_CHARS = 1000;
const STABLE_RESET_MS = 60_000;
const STOP_WAIT_MS = 2500;
const KILL_WAIT_MS = 800;

function delay(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

/**
 * 找执行 node 的那个可执行文件：
 *   1) 配置里明确给了 nodePath 就用它；
 *   2) 宿主进程本身就是 node（`process.execPath` 叫 node/node.exe）就用它；
 *   3) 宿主是 Electron 时用同一个可执行文件 + `ELECTRON_RUN_AS_NODE=1`；
 *   4) 兜底交给 PATH 里的 `node`。
 */
export function resolveNodeCommand(nodePath) {
  const configured = String(nodePath || '').trim();
  if (configured) return { command: configured, env: {} };
  const execPath = String(process.execPath || '');
  const base = execPath.split(/[\\/]/).pop().toLowerCase();
  if (base === 'node' || base === 'node.exe') return { command: execPath, env: {} };
  if (process.versions && process.versions.electron) {
    return { command: execPath, env: { ELECTRON_RUN_AS_NODE: '1' } };
  }
  return { command: 'node', env: {} };
}

export class ChannelServerManager {
  constructor({
    scriptPath,
    cwd,
    port = 8788,
    nodePath = '',
    autoRestart = true,
    maxRestarts = 5,
    mask = (text) => text,
    missingReason = '',
    extraEnv = {},
    log = () => {},
  } = {}) {
    this.scriptPath = String(scriptPath || '');
    this.cwd = String(cwd || '');
    this.port = Number.isInteger(Number(port)) && Number(port) > 0 ? Number(port) : 8788;
    this.node = resolveNodeCommand(nodePath);
    this.autoRestart = autoRestart !== false;
    this.maxRestarts = Number.isInteger(Number(maxRestarts)) && Number(maxRestarts) >= 0 ? Number(maxRestarts) : 5;
    this.mask = typeof mask === 'function' ? mask : (text) => text;
    this.missingReason = String(missingReason || '');
    this.extraEnv = extraEnv && typeof extraEnv === 'object' ? extraEnv : {};
    this.log = typeof log === 'function' ? log : () => {};

    this.child = null;
    this.pid = null;
    this.state = 'stopped';
    this.restarts = 0;
    this.startedAt = null;
    this.lastExitCode = null;
    this.lastExitAt = null;
    this.nextRestartAt = null;
    this.lastError = '';
    this.stopping = false;
    this.disposed = false;

    this.lines = [];
    this.retryTimer = null;
    this.stableTimer = null;
    this.exitWaiters = [];
  }

  /** 环形日志：掩码后入缓冲，超长截断。 */
  #pushLine(text) {
    let line = String(text ?? '').replace(/\r$/, '');
    if (!line) return;
    if (line.length > MAX_LOG_LINE_CHARS) line = `${line.slice(0, MAX_LOG_LINE_CHARS)}…`;
    line = this.mask(line);
    this.lines.push(line);
    if (this.lines.length > MAX_LOG_LINES) this.lines.splice(0, this.lines.length - MAX_LOG_LINES);
  }

  /** 面板操作也写进同一份日志（例如「已生成配对码」）—— 内容由调用方保证不含明文。 */
  appendNote(text) {
    const note = String(text ?? '').trim();
    if (!note) return;
    this.#pushLine(`[panel] ${note}`);
    try {
      this.log(`phone-bridge: [panel] ${note}`);
    } catch {
      /* 日志服务出错不影响业务 */
    }
  }

  get running() {
    return Boolean(this.child);
  }

  /** 状态快照（面板只读这个）。log 是最后 15 行（已掩码）。 */
  status(logLines = 15) {
    const lines = Number.isInteger(Number(logLines)) ? Math.max(0, Math.min(Number(logLines), MAX_LOG_LINES)) : 15;
    return {
      managed: Boolean(this.child),
      running: Boolean(this.child),
      state: this.state,
      pid: this.pid,
      port: this.port,
      scriptPath: this.scriptPath,
      restarts: this.restarts,
      maxRestarts: this.maxRestarts,
      autoRestart: this.autoRestart,
      lastExitCode: this.lastExitCode,
      lastExitAt: this.lastExitAt,
      startedAt: this.startedAt,
      nextRestartAt: this.nextRestartAt,
      lastError: this.lastError,
      log: this.lines.slice(-lines),
    };
  }

  /**
   * 启动（用户点「启动」或插件加载时自动调用）。同步返回结果，不抛错：
   * 入口不存在就是 `{ ok:false, error:'script-missing' }`，面板照常显示。
   */
  start(reason = '手动') {
    if (this.disposed) return { ok: false, error: 'disposed', status: this.status() };
    if (this.child) return { ok: true, already: true, status: this.status() };

    this.stopping = false;
    this.restarts = 0;
    this.nextRestartAt = null;
    this.#clearRetry();

    if (!this.scriptPath || !existsSync(this.scriptPath)) {
      this.state = 'missing';
      this.lastError =
        this.missingReason || `找不到 channel-server 的入口：${this.scriptPath || '(空路径)'}`;
      this.#pushLine(`[manager] ${this.lastError}`);
      this.log(`phone-bridge: ${this.lastError}`);
      return { ok: false, error: 'script-missing', message: this.lastError, status: this.status() };
    }

    this.#pushLine(`[manager] 启动 channel-server（${reason}），入口=${this.scriptPath}`);
    const result = this.#spawn(reason);
    return { ...result, status: this.status() };
  }

  #spawn(reason) {
    const args = [this.scriptPath];
    // 参数只来自配置：端口不是默认 8788 时才显式传，面板无法拼别的参数
    if (this.port !== 8788) args.push(`--port=${this.port}`);

    let child;
    try {
      child = spawn(this.node.command, args, {
        cwd: this.cwd || undefined,
        env: { ...process.env, ...this.node.env, ...this.extraEnv },
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true,
      });
    } catch (error) {
      this.state = 'crashed';
      this.lastError = String((error && error.message) || error);
      this.#pushLine(`[manager] 启动失败：${this.lastError}`);
      return { ok: false, error: 'spawn-failed', message: this.lastError };
    }

    this.child = child;
    this.pid = child.pid || null;
    this.state = 'running';
    this.startedAt = Date.now();
    this.lastError = '';

    child.stdout?.setEncoding('utf8');
    child.stderr?.setEncoding('utf8');

    let outBuf = '';
    let errBuf = '';
    const onData = (chunk, isErr) => {
      let buf = `${isErr ? errBuf : outBuf}${chunk}`;
      let idx;
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx);
        buf = buf.slice(idx + 1);
        if (line.trim()) this.#pushLine(line);
      }
      if (buf.length > 4000) buf = buf.slice(-4000);
      if (isErr) errBuf = buf;
      else outBuf = buf;
    };
    child.stdout?.on('data', (chunk) => onData(chunk, false));
    child.stderr?.on('data', (chunk) => onData(chunk, true));

    let settled = false;
    const settle = (code, signal) => {
      if (settled) return;
      settled = true;
      if (this.child !== child) return;
      this.child = null;
      this.pid = null;
      this.lastExitCode = typeof code === 'number' ? code : null;
      this.lastExitAt = Date.now();
      const ranMs = this.startedAt ? Math.max(0, this.lastExitAt - this.startedAt) : 0;
      if (this.stableTimer) {
        clearTimeout(this.stableTimer);
        this.stableTimer = null;
      }
      const how = signal ? `signal=${signal}` : `code=${code}`;
      this.#pushLine(`[manager] channel-server 退出（${how}，运行 ${Math.round(ranMs / 1000)}s）`);
      for (const resolve of this.exitWaiters.splice(0)) resolve();

      if (this.stopping || this.disposed) {
        this.state = 'stopped';
        return;
      }
      this.state = 'crashed';
      if (this.autoRestart && this.restarts < this.maxRestarts) {
        const wait = BACKOFF_MS[Math.min(this.restarts, BACKOFF_MS.length - 1)];
        this.restarts += 1;
        this.#clearRetry();
        this.nextRestartAt = Date.now() + wait;
        this.state = 'restarting';
        this.#pushLine(`[manager] ${wait}ms 后自动拉起（第 ${this.restarts}/${this.maxRestarts} 次）`);
        this.retryTimer = setTimeout(() => {
          this.retryTimer = null;
          this.nextRestartAt = null;
          if (this.disposed || this.stopping || this.child) return;
          this.#pushLine('[manager] 自动拉起');
          this.#spawn('自动拉起');
        }, wait);
        if (this.retryTimer.unref) this.retryTimer.unref();
      } else if (this.autoRestart) {
        this.#pushLine('[manager] 自动拉起次数用尽，停止重试（点「启动」可重置次数）');
      }
    };

    child.on('exit', (code, signal) => settle(code, signal));
    child.on('close', (code, signal) => settle(code, signal));
    child.on('error', (error) => {
      this.lastError = String((error && error.message) || error);
      this.#pushLine(`[manager] 子进程错误：${this.lastError}`);
      this.log(`phone-bridge: channel-server 子进程错误：${this.lastError}`);
    });

    // 稳定跑满 60 秒 ⇒ 退避次数清零（下一次再挂还是从 1s 开始）
    if (this.stableTimer) clearTimeout(this.stableTimer);
    this.stableTimer = setTimeout(() => {
      this.stableTimer = null;
      if (this.child) this.restarts = 0;
    }, STABLE_RESET_MS);
    if (this.stableTimer.unref) this.stableTimer.unref();

    this.log(`phone-bridge: 已拉起 channel-server（pid=${this.pid}，${reason}）`);
    return { ok: true, pid: this.pid };
  }

  #clearRetry() {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.nextRestartAt = null;
  }

  /** 停止托管进程（面板点「停止」/ 插件卸载）。外部启动的通道不归这里管。 */
  async stop(reason = '面板') {
    this.stopping = true;
    this.#clearRetry();
    if (this.stableTimer) {
      clearTimeout(this.stableTimer);
      this.stableTimer = null;
    }
    const child = this.child;
    if (!child) {
      this.state = 'stopped';
      return { ok: true, already: true, status: this.status() };
    }
    this.#pushLine(`[manager] 停止 channel-server（${reason}，pid=${child.pid}）`);
    const exited = new Promise((resolve) => {
      this.exitWaiters.push(resolve);
    });
    try {
      child.kill();
    } catch (error) {
      this.lastError = String((error && error.message) || error);
      this.#pushLine(`[manager] 发送停止信号失败：${this.lastError}`);
    }
    await Promise.race([exited, delay(STOP_WAIT_MS)]);
    if (this.child === child) {
      this.#pushLine('[manager] 常规停止未生效，改用强制结束');
      try {
        child.kill('SIGKILL');
      } catch {
        /* 已经没了 */
      }
      await Promise.race([exited, delay(KILL_WAIT_MS)]);
    }
    if (this.child === child) {
      this.state = 'crashed';
      this.lastError = '停止失败：子进程仍在运行';
      this.#pushLine(`[manager] ${this.lastError}`);
      return { ok: false, error: 'kill-failed', status: this.status() };
    }
    this.state = 'stopped';
    return { ok: true, status: this.status() };
  }

  /** 插件卸载：不再自动拉起，并把托管进程收掉。 */
  async dispose() {
    this.disposed = true;
    this.autoRestart = false;
    this.#clearRetry();
    if (this.stableTimer) {
      clearTimeout(this.stableTimer);
      this.stableTimer = null;
    }
    const child = this.child;
    if (!child) {
      this.state = 'stopped';
      return;
    }
    try {
      child.kill();
    } catch {
      /* 忽略 */
    }
  }
}
