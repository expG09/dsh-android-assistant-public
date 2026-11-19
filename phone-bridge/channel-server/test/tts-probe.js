'use strict';

/**
 * TTS 接口探针（一次性，用来确认 wav / pcm 的实际格式与耗时）。
 * 不打印密钥、不打印正文以外的内容（正文是测试用固定短句）。
 * 用法：node test/tts-probe.js
 */

const fs = require('fs');
const path = require('path');

function loadApiKey() {
  const fromEnv = (process.env.DEEPSEEK_API_KEY || '').trim();
  if (fromEnv) return { key: fromEnv, source: 'env' };
  const envPath = path.join(__dirname, '..', '.env');
  const raw = fs.readFileSync(envPath, 'utf8');
  for (const line of raw.split(/\r?\n/)) {
    const m = line.match(/^\s*(?:export\s+)?DEEPSEEK_API_KEY\s*=\s*(.*)$/);
    if (!m) continue;
    let v = m[1].trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      v = v.slice(1, -1);
    }
    if (v) return { key: v, source: '.env' };
  }
  return { key: null, source: 'none' };
}

const KEY = loadApiKey();
if (!KEY.key) {
  console.log('NO_KEY');
  process.exit(2);
}
console.log('key source=' + KEY.source + ' (not printed)');

const TEXT = '明天多云，二十度。带把伞吧。';

async function probe(format) {
  const started = Date.now();
  const res = await fetch('https://api.deepseek.com/v1/tools/tts', {
    method: 'POST',
    headers: {
      Authorization: 'Bearer ' + KEY.key,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ text: TEXT, voice_id: 'stella', format: format }),
  });
  if (!res.ok) {
    console.log(format + ' http=' + res.status + ' body=' + (await res.text()).slice(0, 200));
    return null;
  }
  const ct = res.headers.get('content-type');
  const buf = Buffer.from(await res.arrayBuffer());
  const firstByteMs = Date.now() - started;
  const out = path.join(__dirname, 'tts-probe-' + format + '.bin');
  fs.writeFileSync(out, buf);
  console.log(
    format + ' status=' + res.status + ' ct=' + ct + ' bytes=' + buf.length + ' costMs=' + firstByteMs
  );
  if (format === 'wav') {
    const riff = buf.toString('ascii', 0, 4);
    const wave = buf.toString('ascii', 8, 12);
    const audioFormat = buf.readUInt16LE(20);
    const channels = buf.readUInt16LE(22);
    const sampleRate = buf.readUInt32LE(24);
    const bits = buf.readUInt16LE(34);
    // 找 data 块
    let off = 12;
    let dataSize = 0;
    while (off + 8 <= buf.length) {
      const id = buf.toString('ascii', off, off + 4);
      const size = buf.readUInt32LE(off + 4);
      if (id === 'data') {
        dataSize = Math.min(size, buf.length - off - 8);
        break;
      }
      off += 8 + size + (size & 1);
    }
    const bytesPerSec = sampleRate * channels * (bits / 8);
    console.log(
      'wav header riff=' + riff + ' wave=' + wave + ' audioFormat=' + audioFormat +
        ' ch=' + channels + ' rate=' + sampleRate + ' bits=' + bits +
        ' dataSize=' + dataSize + ' bytesPerSec=' + bytesPerSec +
        ' durationSec=' + (dataSize / bytesPerSec).toFixed(2)
    );
  }
  return buf.length;
}

(async () => {
  try {
    const wav = await probe('wav');
    const pcm = await probe('pcm');
    if (wav && pcm) {
      console.log('pcm/wav ratio=' + (pcm / wav).toFixed(3) + '（wav 含 44 字节头左右）');
    }
  } catch (e) {
    console.log('ERROR ' + (e.code || e.message));
    process.exit(1);
  }
})();
