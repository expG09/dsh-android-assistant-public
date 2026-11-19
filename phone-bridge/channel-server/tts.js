'use strict';

/**
 * M6a：DeepSeek TTS 合成 + 按句切分 + speak 帧会话。
 *
 * 约束（见 docs/dev/M6a 模块开发文档.md）：
 *   - 只在「回复文本已经确定」后合成，按句切（。！？\n），一句一合成、边合成边发，不等整段；
 *   - 第一句尽量短（首音越快越好）；
 *   - TTS 失败不影响文字：已经发过 speakStart 就补一条 speakStop 收尾，只记日志、不弹错；
 *   - 密钥由调用方传进来（环境变量 / .env），本文件不读也不写任何密钥；
 *   - 日志只写元数据（id / kind / 音色 / 句数 / 字数 / 字节 / 分块 / 耗时），**不写正文**。
 *
 * 音频格式（2026-11-16 实测 `POST /v1/tools/tts` format=pcm）：
 *   audio/pcm; rate=24000; encoding=signed-integer; bits=16; channels=1
 *   即 24 kHz / 16-bit / 单声道 小端 PCM，48 KB/秒。手机端按这个参数起 AudioTrack。
 *   speakStart 里带上 mime/sampleRate/channels，手机端优先用帧里的值，缺省才用小表。
 */

const TTS_ENDPOINT = process.env.CHANNEL_TTS_ENDPOINT || 'https://api.deepseek.com/v1/tools/tts';
const TTS_TIMEOUT_MS = Number(process.env.CHANNEL_TTS_TIMEOUT_MS || 20 * 1000);

/** 接口支持的音色：stella（女声，默认）/ tide（男声）。 */
const VOICES = ['stella', 'tide'];
const DEFAULT_VOICE = 'stella';

/** 实测的 PCM 参数（接口 content-type 会回这几个值；这里作缺省）。 */
const PCM = { mime: 'audio/pcm', sampleRate: 24000, channels: 1, bits: 16 };

/** 每个 speakChunk 的原始字节数：24 KiB = 0.5 秒 @24k/16bit，base64 后 32 KiB。 */
const CHUNK_PCM_BYTES = 24 * 1024;

/** 接口单次上限 10,000 计费字符；一整个回复可能更长，按句切分后逐句送。 */
const MAX_SEGMENT_CHARS = 60;
/** 首句切得更短：首音快一点。 */
const FIRST_SEGMENT_CHARS = 18;

/** 试听用的固定短句（手机端设置页「试听」按钮触发，服务端固定文案，不接受客户端正文）。 */
const AUDITION_TEXT = '我是砚砚。现在用的是这个音色，听得清吗？';

/** 句末切分符：。！？!?…\n（英文的 . 也当句末，但避免把小数点/网址切坏，只在后面跟空白或结尾时算）。 */
const SENTENCE_MARK = /[。！？!?…\n]/;
const SOFT_MARK = /[，,、；;：:]/;
/** 句末标点后面紧跟的收尾符号：应并入前一句，而不是被切成下一段的开头（或被当作无字段丢掉）。 */
const CLOSING_MARK = /[”’」』）】"')\]]/;

function normalizeVoice(v) {
  const s = String(v == null ? '' : v).trim().toLowerCase();
  return VOICES.includes(s) ? s : DEFAULT_VOICE;
}

/** 去掉行首的列表符号 / 标题符号（念出来会把「-」「#」也念一遍）。 */
function stripLineMarkers(line) {
  return line.replace(/^[\s>*#\-–—•·]+/, '');
}

function speakable(seg) {
  // 只有标点/空白/符号的段落不合成（没有可念的字）
  return /[\p{L}\p{N}]/u.test(seg);
}

/**
 * 把回复正文切成「一句一段」。
 *
 * 规则：
 *   - 先按句末符号（。！？!?…换行）切；
 *   - 单段超过 MAX_SEGMENT_CHARS 的，再按逗号/顿号/分号/冒号切（找不到就硬切）；
 *   - 第一段超过 FIRST_SEGMENT_CHARS 的再切一刀，让首音更快；
 *   - 保留原有标点（念出来的语气更自然），只去首尾空白；纯符号段丢弃。
 */
function splitSentences(text) {
  const raw = String(text == null ? '' : text).replace(/\r\n?/g, '\n');
  if (!raw.trim()) return [];

  // 1) 按句末符号切开，标点留在前一段末尾
  const coarse = [];
  let buf = '';
  for (let i = 0; i < raw.length; i++) {
    const ch = raw[i];
    buf += ch;
    if (SENTENCE_MARK.test(ch)) {
      // 英文句点：后面紧跟数字/字母时不算句末（3.5 / example.com）
      if (ch === '.' && /[0-9A-Za-z]/.test(raw[i + 1] || '')) continue;
      // 句末标点后面的收尾引号/括号并进本段：
      // 否则 `。”` 会被切成两段，`”` 单独成段后因「没有可念的字」被丢掉；
      // 或下一段以 `”` 开头（TTS 合成多一个孤零零的符号）。
      let j = i + 1;
      while (j < raw.length && CLOSING_MARK.test(raw[j])) {
        buf += raw[j];
        j += 1;
      }
      i = j - 1;
      coarse.push(buf);
      buf = '';
    }
  }
  if (buf) coarse.push(buf);

  // 2) 去列表符号、合并空白、超长段再切
  const out = [];
  for (const piece of coarse) {
    const cleaned = stripLineMarkers(piece.replace(/\s+/g, ' ')).trim();
    if (!cleaned) continue;
    if (cleaned.length <= MAX_SEGMENT_CHARS) {
      out.push(cleaned);
      continue;
    }
    // 先按软标点切
    let rest = cleaned;
    while (rest.length > MAX_SEGMENT_CHARS) {
      const window = rest.slice(0, MAX_SEGMENT_CHARS);
      let cut = -1;
      for (let i = window.length - 1; i > 0; i--) {
        if (SOFT_MARK.test(window[i])) {
          cut = i + 1;
          break;
        }
      }
      if (cut <= 0) cut = MAX_SEGMENT_CHARS;
      out.push(rest.slice(0, cut).trim());
      rest = rest.slice(cut).trim();
    }
    if (rest) out.push(rest);
  }

  // 3) 第一段太长再切一刀（首句尽量短）
  if (out.length > 0 && out[0].length > FIRST_SEGMENT_CHARS) {
    const first = out[0];
    let cut = -1;
    for (let i = Math.min(FIRST_SEGMENT_CHARS, first.length - 1); i > 0; i--) {
      if (SOFT_MARK.test(first[i])) {
        cut = i + 1;
        break;
      }
    }
    if (cut <= 0) cut = Math.min(FIRST_SEGMENT_CHARS, first.length);
    const rest = first.slice(cut).trim();
    out[0] = first.slice(0, cut).trim();
    if (rest) out.splice(1, 0, rest);
  }

  return out.filter((s) => s && speakable(s));
}

/** 单次 HTTP 调用（format=pcm）；网络错误标记 kind=network。 */
async function ttsCallOnce(text, voice, apiKey) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TTS_TIMEOUT_MS);
  let res;
  try {
    res = await fetch(TTS_ENDPOINT, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ text: text, voice_id: voice, format: 'pcm' }),
      signal: controller.signal,
    });
  } catch (e) {
    const error = new Error('deepseek tts network error');
    error.kind = e.name === 'AbortError' ? 'timeout' : 'network';
    throw error;
  } finally {
    clearTimeout(timer);
  }
  if (!res.ok) {
    // 不把响应体写进日志/异常（上游描述没有价值，也不该留在日志里）
    const error = new Error('deepseek tts http ' + res.status);
    error.kind = 'http';
    error.status = res.status;
    throw error;
  }
  let buf;
  try {
    buf = Buffer.from(await res.arrayBuffer());
  } catch (e) {
    const error = new Error('deepseek tts read body failed');
    error.kind = 'network';
    throw error;
  }
  if (!buf || buf.length === 0) {
    const error = new Error('deepseek tts returned empty audio');
    error.kind = 'bad_response';
    throw error;
  }
  return { buf: buf, contentType: res.headers.get('content-type') || '' };
}

/**
 * 合成一段话。网络错误重试一次（与 ASR 的规则一致），其余错误直接抛。
 * @returns {Promise<Buffer>} PCM 原始字节
 */
async function synthesize(text, voice, apiKey) {
  let lastError = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const r = await ttsCallOnce(text, voice, apiKey);
      return r;
    } catch (e) {
      lastError = e;
      if ((e.kind === 'network' || e.kind === 'timeout') && attempt === 0) continue;
      throw e;
    }
  }
  throw lastError || new Error('deepseek tts failed');
}

/**
 * 一段话的 speak 会话：按句合成 → 逐块下发 speakChunk → speakDone。
 *
 * - send(frame)：由 server.js 注入（带连接状态判断）；
 * - abort(reason)：手机 speakStop / 新回复顶掉 / 连接断开 → 立刻停止在途合成；
 * - 日志只写元数据，不写正文。
 */
class SpeakSession {
  constructor(opts) {
    this.id = opts.id;
    this.replyId = opts.replyId || null;
    this.text = opts.text || '';
    this.voice = normalizeVoice(opts.voice);
    this.kind = opts.kind || 'reply';
    this.apiKey = opts.apiKey;
    this.send = opts.send;
    this.log = opts.log || (() => {});
    this.aborted = false;
    this.abortReason = null;
    this.startedAt = Date.now();
    this.sentBytes = 0;
    this.chunks = 0;
    this.sentSegments = 0;
    this.segments = [];
  }

  start() {
    this.segments = splitSentences(this.text);
    this.send({
      type: 'speakStart',
      id: this.id,
      replyId: this.replyId,
      text: this.text,
      voice: this.voice,
      // 手机端按这几个参数起 AudioTrack；缺省值两端一致（24k / 16-bit / mono）
      mime: PCM.mime,
      sampleRate: PCM.sampleRate,
      channels: PCM.channels,
      segments: this.segments.length,
      kind: this.kind,
    });
    this.log(
      `tts start id=${this.id} kind=${this.kind} voice=${this.voice} chars=${this.text.length} seg=${this.segments.length}`
    );
    if (this.segments.length === 0) {
      // 没有可念的字（比如只有符号/表情）：直接收尾，别留一个永远不结束的播放器
      this.send({ type: 'speakDone', id: this.id });
      this.log(`tts id=${this.id} 无朗读内容，直接 speakDone`);
      return;
    }
    this._run().catch(() => {});
  }

  abort(reason) {
    if (this.aborted) return;
    this.aborted = true;
    this.abortReason = reason || 'aborted';
  }

  async _run() {
    const id = this.id;
    let seq = 0;
    let lastError = null;
    try {
      for (let i = 0; i < this.segments.length; i++) {
        if (this.aborted) break;
        const seg = this.segments[i];
        const segStarted = Date.now();
        let audio;
        try {
          audio = await synthesize(seg, this.voice, this.apiKey);
        } catch (e) {
          lastError = e;
          break;
        }
        if (this.aborted) break;
        const buf = audio.buf;
        // 实测参数与缺省不一致时只记一条（音色/格式变了要能发现，但不影响下发）
        if (i === 0 && audio.contentType && audio.contentType.indexOf('rate=24000') < 0) {
          this.log(`tts 注意：content-type=${audio.contentType}（与缺省的 24k/16bit/mono 不同）`);
        }
        for (let off = 0; off < buf.length; off += CHUNK_PCM_BYTES) {
          if (this.aborted) break;
          const part = buf.subarray(off, Math.min(off + CHUNK_PCM_BYTES, buf.length));
          this.send({
            type: 'speakChunk',
            id: id,
            seq: seq,
            data: part.toString('base64'),
            mime: PCM.mime,
          });
          seq += 1;
          this.sentBytes += part.length;
          // 让出一次事件循环：这样手机端的 speakStop 能在半句里就被看到，
          // 而不是等这一整句的几十块都灌进 socket 之后才生效。
          await new Promise((resolve) => setImmediate(resolve));
        }
        this.chunks = seq;
        this.sentSegments = i + 1;
        this.log(
          `tts seg id=${id} i=${i + 1}/${this.segments.length} chars=${seg.length} bytes=${buf.length} cost=${Date.now() - segStarted}`
        );
      }

      if (this.aborted) {
        this.log(
          `tts id=${id} aborted reason=${this.abortReason || '-'} 已发=${this.sentSegments}/${this.segments.length} 句 bytes=${this.sentBytes}`
        );
        return;
      }

      if (lastError) throw lastError;

      this.send({ type: 'speakDone', id: id });
      this.log(
        `tts id=${id} kind=${this.kind} voice=${this.voice} seg=${this.sentSegments}/${this.segments.length} ` +
          `bytes=${this.sentBytes} chunks=${this.chunks} cost=${Date.now() - this.startedAt} ok`
      );
    } catch (e) {
      const code = e.kind === 'http' ? `http_${e.status}` : e.kind || 'tts_failed';
      // 已经开播的段落照常放完不了：补一条 speakStop 让手机收尾（不弹错，只记日志）
      this.send({ type: 'speakStop', id: id, reason: 'tts_failed' });
      this.log(
        `tts id=${id} kind=${this.kind} fail code=${code} 已发=${this.sentSegments}/${this.segments.length} 句 cost=${Date.now() - this.startedAt}`
      );
    }
  }
}

module.exports = {
  VOICES: VOICES,
  DEFAULT_VOICE: DEFAULT_VOICE,
  PCM: PCM,
  AUDITION_TEXT: AUDITION_TEXT,
  normalizeVoice: normalizeVoice,
  splitSentences: splitSentences,
  synthesize: synthesize,
  SpeakSession: SpeakSession,
};
