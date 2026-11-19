/**
 * lib/env.mjs —— channel-server 的 `.env` 读写、密钥掩码、密钥来源探测。
 *
 * 为什么单独一个模块：
 *   - 插件面板要能**写** DEEPSEEK_API_KEY 进 `channel-server\.env`，而原来的读法只读不写；
 *   - 日志/接口里**任何密钥只许出掩码**，掩码规则只能有一份（写在这里，别散在四处）；
 *   - 这里不 import 任何 `@deepseek-ai/*`，只用 node 内置模块。
 *
 * 安全约束（规格 C 段）：
 *   - `maskSecret` 只回「前 4 + … + 后 4」，短值一律 `****`；
 *   - `maskText` 把已知密钥原文整段替换成 `***`，另外兜底掩掉 `sk-…` 形状的串；
 *   - 本模块不打印、不日志、不回传任何明文。
 */

import { readFileSync, writeFileSync, renameSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

/** 宽松解析 .env 的一行：`KEY=value`（允许 `export ` 前缀与成对引号）。 */
function parseLineValue(line, key) {
  const re = new RegExp(`^\\s*(?:export\\s+)?${key}\\s*=\\s*(.*)$`);
  const m = line.match(re);
  if (!m) return null;
  let value = m[1].trim();
  if (
    (value.startsWith('"') && value.endsWith('"')) ||
    (value.startsWith("'") && value.endsWith("'"))
  ) {
    value = value.slice(1, -1);
  }
  return value;
}

/** 从 .env 里读一个键；文件不存在、行不存在、值为空都返回 ''（绝不抛错）。 */
export function readDotEnvValue(file, key) {
  let raw;
  try {
    raw = readFileSync(file, 'utf8');
  } catch {
    return '';
  }
  for (const line of raw.split(/\r?\n/)) {
    const value = parseLineValue(line, key);
    if (value) return value;
  }
  return '';
}

/** 一次读多个键：返回 { KEY: value }，只含非空的。 */
export function readDotEnvKeys(file, keys) {
  const out = {};
  for (const key of keys || []) {
    const value = readDotEnvValue(file, key);
    if (value) out[key] = value;
  }
  return out;
}

/** 值里出现空白/引号/井号时要加引号，否则原样写（含 `=` 的值也照原样写，解析器只按第一个 `=` 之前的键名匹配）。 */
function encodeDotEnvValue(value) {
  const text = String(value);
  if (!/[\s#"']/.test(text)) return text;
  return '"' + text.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

/**
 * 写（或替换）`.env` 里的一个键，保留其它行与注释，末尾补一个换行。
 * 先写临时文件再 rename：写一半崩掉也不会留下半个密钥文件。
 * 返回 { ok, file } —— **不回值、不回长度**。
 */
export function writeDotEnvValue(file, key, value) {
  let raw = '';
  try {
    raw = readFileSync(file, 'utf8');
  } catch (error) {
    if (!error || error.code !== 'ENOENT') throw error;
  }
  const re = new RegExp(`^\\s*(?:export\\s+)?${key}\\s*=`);
  const lines = raw ? raw.split(/\r?\n/) : [];
  const encoded = encodeDotEnvValue(value);
  let replaced = false;
  const next = lines.map((line) => {
    if (!replaced && re.test(line)) {
      replaced = true;
      return `${key}=${encoded}`;
    }
    return line;
  });
  if (!replaced) {
    while (next.length && next[next.length - 1] === '') next.pop();
    next.push(`${key}=${encoded}`);
  }
  const text = next.join('\n') + '\n';
  mkdirSync(dirname(file), { recursive: true });
  const tmp = `${file}.tmp-${process.pid}-${Date.now()}`;
  writeFileSync(tmp, text, { encoding: 'utf8' });
  renameSync(tmp, file);
  return { ok: true, file };
}

/**
 * 密钥的展示形态：前 4 + `…` + 后 4；短于 9 位的一律 `****`。
 * 空值返回空串（面板据此显示「未设置」）。
 */
export function maskSecret(value) {
  const text = String(value ?? '').trim();
  if (!text) return '';
  if (text.length <= 8) return '****';
  return `${text.slice(0, 4)}…${text.slice(-4)}`;
}

/** 把文本里出现的已知密钥原文替换掉，再兜底掩掉像密钥的串（`sk-…`）。 */
export function maskText(text, secrets) {
  let out = String(text ?? '');
  for (const secret of secrets || []) {
    const value = String(secret || '');
    if (value.length < 4) continue;
    out = out.split(value).join('***');
  }
  // 兜底：防的是「密钥刚换、还没进已知列表」时漏出去
  out = out.replace(/\bsk-[A-Za-z0-9_-]{8,}\b/g, (hit) => `${hit.slice(0, 3)}***`);
  return out;
}

/**
 * 当前进程知道的密钥集合（用于掩码）：`.env` 里的 + 环境变量里的。
 *
 * 每 2 秒最多读一次文件（日志按行追加时会被反复调用，别每行都读盘）；
 * 面板写完 .env 后调 `invalidate()` 立刻失效。
 */
export function createSecretProvider({ envPath, names, extra = [] } = {}) {
  const keys = [...new Set([...(names || []), 'PHONE_BRIDGE_TOKEN', 'DEEPSEEK_API_KEY'])];
  const ttlMs = 2000;
  let cache = { at: 0, values: [] };
  const invalidate = () => {
    cache = { at: 0, values: [] };
  };
  const values = () => {
    if (Date.now() - cache.at < ttlMs) return cache.values;
    const found = new Set();
    if (envPath) {
      for (const value of Object.values(readDotEnvKeys(envPath, keys))) found.add(value);
    }
    for (const key of keys) {
      const value = String(process.env[key] || '').trim();
      if (value) found.add(value);
    }
    for (const value of extra || []) {
      const text = String(value || '').trim();
      if (text) found.add(text);
    }
    cache = { at: Date.now(), values: [...found].filter((v) => v.length >= 4) };
    return cache.values;
  };
  return { values, invalidate };
}

/**
 * 校验面板交来的 API key：只收可打印 ASCII、8–512 位、不含空白/换行。
 * 返回 { ok, value } 或 { ok:false, error }（错误里不含输入值）。
 */
export function validateApiKey(input) {
  const value = String(input ?? '').trim();
  if (!value) return { ok: false, error: 'empty' };
  if (value.length < 8) return { ok: false, error: 'too-short' };
  if (value.length > 512) return { ok: false, error: 'too-long' };
  if (!/^[\x21-\x7E]+$/.test(value)) return { ok: false, error: 'bad-chars' };
  return { ok: true, value };
}

/** 语音提供商：面板只认这三个值。 */
export const VOICE_PROVIDERS = Object.freeze(['deepseek', 'edge', 'local']);

/** `.env` 里存提供商用的键（channel-server 现在不读它，是面板的配置入口；见 README）。 */
export const VOICE_PROVIDER_ENV = 'CHANNEL_TTS_PROVIDER';
