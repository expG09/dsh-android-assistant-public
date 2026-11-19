/**
 * lib/settings.mjs —— 投递会话的绑定文件（跟着 DSH 走，不跟着插件包走）
 *
 * 默认落点（`resolveSettingsPath`）：
 *
 *     <DSH_HOME>\phone-bridge\settings.json
 *     DSH_HOME = process.env.DSH_HOME || os.homedir() + '.dsh'
 *     Windows 默认 C:\Users\<user>\.dsh\phone-bridge\settings.json
 *
 * 目录不存在就 mkdir -p 建出来；**建不出来不让插件加载失败**：降级到
 * `<插件目录>\data\settings.json`，并在面板状态里带 `settingsFallback: true` + 原因。
 * `config.settingsFile` 非空时优先于上面整套解析（selfcheck 靠它指临时目录）。
 *
 * 只存一件事：手机轮次要投进哪个会话（`sessionId`）。取值顺序：
 *
 *     设置文件里的 sessionId  >  cordis.patch.yml 的 config.sessionId  >  空（未绑定）
 *
 * 语义（**没有"跟随最近活跃"这回事**——宁可拒绝，不要猜）：
 *   - sessionId 有值 ⇒ 只投那一个会话；
 *   - 两边都没有值 ⇒ 未绑定 ⇒ `/m7/ask` 明确 409，不把消息丢给"最近活跃的那个"；
 *   - 设置文件不存在 / 坏 JSON / 没有 sessionId 字段 ⇒ 这一层视为「没配过」，
 *     回落到 config.sessionId；两层都空才是未绑定。坏文件不会让插件加载失败；
 *   - 只接受两种形状：`session-<uuid>` 或裸 `<uuid>`；其它形状在写入时 400。
 *
 * 只用 node 内置模块，不 import 任何 `@deepseek-ai/*`。
 */

import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { homedir as osHomedir } from 'node:os';

export const BAD_SESSION_ID_MESSAGE = '格式不对，应该是 session-xxx 或 uuid';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * 算出「会话绑定设置文件」的绝对路径，并尽量把默认目录建出来。
 *
 * @param {object} options
 * @param {string} [options.configFile] config.settingsFile（非空优先）
 * @param {object} [options.env]        环境变量（测试可注入）
 * @param {function} [options.homedir]  os.homedir（测试可注入）
 * @param {string} [options.pluginDir]  插件目录（降级落点 <pluginDir>\data\settings.json）
 * @returns {{ file:string, fallback:boolean, reason:string, source:string, defaultFile:string }}
 *   永远不抛：默认目录建不出来就返回 fallback（file 指向插件 data 目录）。
 */
export function resolveSettingsPath({
  configFile = '',
  env = process.env,
  homedir = osHomedir,
  pluginDir = process.cwd(),
} = {}) {
  const configured = String(configFile || '').trim();
  if (configured) {
    return { file: resolve(configured), fallback: false, reason: '', source: 'config', defaultFile: '' };
  }

  const envHome = String((env && env.DSH_HOME) || '').trim();
  const base = envHome || join(homedir(), '.dsh');
  const defaultDir = join(base, 'phone-bridge');
  const defaultFile = join(defaultDir, 'settings.json');
  const fallbackFile = join(pluginDir, 'data', 'settings.json');

  try {
    mkdirSync(defaultDir, { recursive: true });
    return {
      file: defaultFile,
      fallback: false,
      reason: '',
      source: envHome ? 'DSH_HOME' : 'homedir',
      defaultFile,
    };
  } catch (error) {
    return {
      file: fallbackFile,
      fallback: true,
      reason: `建不出 ${defaultDir}：${String((error && error.message) || error)}`,
      source: 'plugin-data-fallback',
      defaultFile,
    };
  }
}

/**
 * 校验/归一化会话 id。
 * 空串、null、undefined 都表示「未绑定 / 清除绑定」，返回 { ok:true, value:'' }。
 * 字符串会去掉首尾空白后按两种形状校验；**原样保留**用户写的那一种
 * （DSH 里顶层会话叫 `session-<uuid>`、子代理会话是裸 uuid，两种都要能填）。
 */
export function normalizeSessionId(input) {
  if (input === null || input === undefined) return { ok: true, value: '' };
  const text = String(input).trim();
  if (!text) return { ok: true, value: '' };
  const bare = text.startsWith('session-') ? text.slice('session-'.length) : text;
  if (!UUID_RE.test(bare)) return { ok: false, error: 'bad-session-id', message: BAD_SESSION_ID_MESSAGE };
  return { ok: true, value: text };
}

/**
 * 读设置文件。
 * 返回 { present, sessionId, warning }：
 *   present=true 表示文件里**明确有**合格的 sessionId 字段（字符串），这一层才算「配过」；
 *   其它情况 present=false，调用方回落到 patch。
 * 任何读盘/解析错误都吞掉，绝不抛。
 */
export function readSessionSettings(file) {
  if (!file) return { present: false, sessionId: '', warning: 'no-file-path' };
  let raw;
  try {
    raw = readFileSync(file, 'utf8');
  } catch (error) {
    if (error && error.code === 'ENOENT') return { present: false, sessionId: '', warning: 'missing' };
    return { present: false, sessionId: '', warning: String((error && error.message) || error) };
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { present: false, sessionId: '', warning: 'bad-json' };
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { present: false, sessionId: '', warning: 'not-an-object' };
  }
  if (!Object.prototype.hasOwnProperty.call(parsed, 'sessionId')) {
    return { present: false, sessionId: '', warning: 'no-session-id-field' };
  }
  if (typeof parsed.sessionId !== 'string') {
    return { present: false, sessionId: '', warning: 'session-id-not-a-string' };
  }
  const normalized = normalizeSessionId(parsed.sessionId);
  if (!normalized.ok) {
    return { present: false, sessionId: '', warning: 'bad-session-id-shape' };
  }
  return { present: true, sessionId: normalized.value, warning: '' };
}

/**
 * 写设置文件（保留文件里其它键，原子替换：临时文件 + rename）。
 * 只写 sessionId；空串就是清除绑定。返回 { ok:true, file, value }。
 */
export function writeSessionSettings(file, sessionId) {
  const normalized = normalizeSessionId(sessionId);
  if (!normalized.ok) return { ok: false, error: normalized.error, message: normalized.message };
  let base = {};
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8'));
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) base = parsed;
  } catch {
    // 文件不存在 / 坏 JSON：从空对象开始，坏文件被这次写入覆盖掉（这是用户点「绑定」的意图）
  }
  const next = { ...base, sessionId: normalized.value };
  const text = `${JSON.stringify(next, null, 2)}\n`;
  mkdirSync(dirname(file), { recursive: true });
  const tmp = `${file}.tmp-${process.pid}-${Date.now()}`;
  writeFileSync(tmp, text, { encoding: 'utf8' });
  renameSync(tmp, file);
  return { ok: true, file, value: normalized.value };
}

/**
 * 会话绑定：把设置文件与 patch 的 config.sessionId 合成「当前生效值」。
 *
 * @param {object} options
 * @param {string} options.file       设置文件绝对路径
 * @param {object} [options.location] resolveSettingsPath() 的结果（带 fallback/reason/defaultFile）
 * @param {function} [options.fallback] 返回 patch 里的 sessionId（默认空）
 * @param {function} [options.log]
 */
export function createSessionBinding({ file, location = null, fallback = () => '', log = () => {} } = {}) {
  const fallbackValue = () => {
    const value = String(fallback() || '').trim();
    if (!value) return '';
    const normalized = normalizeSessionId(value);
    return normalized.ok ? normalized.value : '';
  };

  const read = () => readSessionSettings(file);

  /** 当前生效的会话 id（'' = 未绑定，不投递）。 */
  const effective = () => {
    const settings = read();
    if (settings.present) return settings.sessionId;
    return fallbackValue();
  };

  /** 给面板/健康检查看的快照。 */
  const snapshot = () => {
    const settings = read();
    let sessionId = '';
    let source = 'default';
    if (settings.present) {
      sessionId = settings.sessionId;
      source = 'settings';
    } else if (fallbackValue()) {
      sessionId = fallbackValue();
      source = 'patch';
    }
    const bound = Boolean(sessionId);
    let hint;
    if (bound) {
      hint =
        source === 'settings'
          ? '已绑定：手机消息只投这个会话（设置文件优先于 cordis.patch.yml）'
          : '已绑定：手机消息只投这个会话（来自 cordis.patch.yml，面板绑定/清除会覆盖它）';
    } else {
      hint = '未绑定：手机消息不会投递 —— 先在上面填一个会话 id 再点「绑定」';
    }
    const loc = location && typeof location === 'object' ? location : {};
    return {
      sessionId,
      bound,
      mode: bound ? 'pinned' : 'unbound',
      hint,
      source,
      // 面板需要能看到「当前用的是哪个文件」；不带任何密钥内容
      file: loc.file || file,
      fallback: loc.fallback === true,
      settingsFallback: loc.fallback === true,
      fallbackReason: loc.reason || '',
      defaultFile: loc.defaultFile || '',
    };
  };

  /** 绑定 / 清除。非法形状回 { ok:false, error, message }，不写文件。 */
  const set = (input) => {
    const written = writeSessionSettings(file, input);
    if (!written.ok) return written;
    if (written.value) log(`phone-bridge: 投递会话已绑定（文件=${file}）`);
    else log('phone-bridge: 投递会话绑定已清除（未绑定 ⇒ 手机消息不投递）');
    return { ok: true, snapshot: snapshot() };
  };

  return { file, effective, snapshot, set, read };
}
