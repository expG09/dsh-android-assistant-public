'use strict';

/**
 * M6a 电脑端自测（不经过 WebSocket）：
 *   1) 按句切分的规则（长短句、无标点、纯符号、markdown 列表）；
 *   2) 真调一次 TTS：首句耗时 / 逐句耗时 / 每句 PCM 字节（用于证明「一句一合成、边合成边发」可行）。
 * 用法：node test/tts-test.js [--no-net]
 * 不打印密钥，不打印正文（只打印字数与切分后的段数与首段字数）。
 */

const path = require('path');
const tts = require('../tts');

let key = null;
function loadApiKey() {
  const fromEnv = (process.env.DEEPSEEK_API_KEY || '').trim();
  if (fromEnv) return fromEnv;
  const fs = require('fs');
  try {
    const raw = fs.readFileSync(path.join(__dirname, '..', '.env'), 'utf8');
    for (const line of raw.split(/\r?\n/)) {
      const m = line.match(/^\s*(?:export\s+)?DEEPSEEK_API_KEY\s*=\s*(.*)$/);
      if (!m) continue;
      let v = m[1].trim();
      if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
        v = v.slice(1, -1);
      }
      if (v) return v;
    }
  } catch (e) {
    /* ignore */
  }
  return null;
}

function sampleSplit() {
  const cases = [
    '明天多云，二十度。带把伞吧。',
    '好的。',
    '导航去南京南站，顺便看看路上堵不堵。',
    '- 第一件事：打开微信\n- 第二件事：给妈妈发消息\n',
    '这句话没有任何句末标点但是很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长',
    '……',
    '3.5 公里的路，大概走 40 分钟。',
  ];
  let bad = 0;
  for (const text of cases) {
    const segs = tts.splitSentences(text);
    console.log(
      'case chars=' + text.length + ' segs=' + segs.length +
        ' lens=[' + segs.map((s) => s.length).join(',') + ']'
    );
    for (const s of segs) {
      if (s.length > tts.splitSentences('').length + 200) bad += 1;
    }
  }
  if (bad) throw new Error('切分异常：存在超长段');
}

async function realSynthesis() {
  if (!loadApiKey()) {
    console.log('NO_KEY：跳过真调 TTS');
    return;
  }
  if (!key) key = loadApiKey();
  const text =
    '我先说第一句短的。后面这句会长一点，用来观察第二句合成要多久，以及音频块是不是按顺序到的。' +
    '第三句再长一点：这句话里加了很多细节，比如明天二十度、后天十九度、风不大、空气还行，出门记得带伞，' +
    '晚上早点回，别熬夜。第四句收尾。';
  const segs = tts.splitSentences(text);
  console.log('长回复 chars=' + text.length + ' segs=' + segs.length + ' 首段字数=' + segs[0].length);
  let total = 0;
  const t0 = Date.now();
  for (let i = 0; i < segs.length; i++) {
    const t = Date.now();
    const r = await tts.synthesize(segs[i], 'stella', key);
    total += r.buf.length;
    console.log(
      'seg ' + (i + 1) + '/' + segs.length + ' chars=' + segs[i].length +
        ' bytes=' + r.buf.length + ' cost=' + (Date.now() - t) +
        ' ct=' + r.contentType
    );
  }
  console.log(
    'total bytes=' + total + ' 累计耗时=' + (Date.now() - t0) +
      'ms 折合音频秒数=' + (total / 48000).toFixed(2)
  );
}

(async () => {
  sampleSplit();
  if (process.argv.indexOf('--no-net') < 0) {
    await realSynthesis();
  }
})().catch((e) => {
  console.error('FAIL ' + (e && e.message));
  process.exit(1);
});
