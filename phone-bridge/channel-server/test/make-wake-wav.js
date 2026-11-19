'use strict';

/** 一次性脚本：生成唤醒词「砚砚」的 WAV，用来在电脑音箱上放，验证「喊唤醒词 → 立刻停播」。 */
const fs = require('fs');
const path = require('path');

function loadKey() {
  const fromEnv = (process.env.DEEPSEEK_API_KEY || '').trim();
  if (fromEnv) return fromEnv;
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
  return null;
}

(async () => {
  const key = loadKey();
  if (!key) {
    console.log('NO_KEY');
    return;
  }
  const text = process.argv[2] || '砚砚';
  const r = await fetch('https://api.deepseek.com/v1/tools/tts', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: text, voice_id: 'stella', format: 'wav' }),
  });
  if (!r.ok) {
    console.log('http ' + r.status);
    return;
  }
  const buf = Buffer.from(await r.arrayBuffer());
  const out = path.join(__dirname, '..', 'tmp', 'wake_yanyan.wav');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, buf);
  console.log('wrote ' + out + ' bytes=' + buf.length);
})();
