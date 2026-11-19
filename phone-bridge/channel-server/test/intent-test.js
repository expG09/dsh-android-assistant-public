'use strict';

/**
 * 意图解析规则自测（不联网、不调模型）：node test/intent-test.js
 *
 * 只覆盖「规则层 + 切分 + 校验」，用来防止改规则时把已有句子弄坏。
 * 期望值写成人能看懂的断言，失败会打印实际解析结果。
 */

process.env.CHANNEL_MODEL = 'off';
process.env.CHANNEL_DEFAULT_CITY = '南京';

const intent = require('../intent');

let pass = 0;
let fail = 0;
const failures = [];

function fmt(v) {
  return JSON.stringify(v, null, 0);
}

async function expect(text, want, now) {
  const saved = Date.now;
  if (now) Date.now = () => now;
  let got;
  try {
    got = await intent.parseIntent(text, { apiKey: null });
  } finally {
    Date.now = saved;
  }
  let ok = true;
  if (want.type && got.type !== want.type) ok = false;
  if (want.kind && got.kind !== want.kind) ok = false;
  if (want.number && (!got.args || got.args.number !== want.number)) ok = false;
  if (want.destination && (!got.args || got.args.destination !== want.destination)) ok = false;
  if (want.appName && (!got.args || got.args.appName !== want.appName)) ok = false;
  if (want.query && (!got.args || got.args.query !== want.query)) ok = false;
  if (want.url && (!got.args || got.args.url !== want.url)) ok = false;
  if (want.title && (!got.args || got.args.title !== want.title)) ok = false;
  if (want.hour !== undefined && (!got.args || got.args.hour !== want.hour)) ok = false;
  if (want.minute !== undefined && (!got.args || got.args.minute !== want.minute)) ok = false;
  if (want.chat !== undefined) {
    const chat = got.chat || '';
    if (want.chat === null ? Boolean(chat) : chat.indexOf(want.chat) < 0) ok = false;
  }
  if (want.startAt) {
    if (!got.args || got.args.start !== want.startAt.getTime()) ok = false;
  }
  if (want.askHas && !String(got.ask || '').includes(want.askHas)) ok = false;

  if (ok) {
    pass += 1;
    console.log(`  ok   ${text}  →  ${fmt(got)}`);
  } else {
    fail += 1;
    failures.push({ text, want, got });
    console.log(`  FAIL ${text}\n       想要 ${fmt(want)}\n       实际 ${fmt(got)}`);
  }
}

(async () => {
  // 固定"现在"：2026-11-16 10:30（北京时间由本机时区决定，只用来算相对日期）
  const NOW = new Date(2026, 10, 16, 10, 30, 0).getTime(); // 2026-11-16 10:30
  const tomorrow10 = new Date(2026, 10, 17, 10, 0, 0);

  console.log('\n== 打电话 ==');
  await expect('打电话给 13812345678', { type: 'command', kind: 'call', number: '13812345678' });
  await expect('帮我打电话给13812345678', { type: 'command', kind: 'call', number: '13812345678' });
  await expect('给13812345678打电话', { type: 'command', kind: 'call', number: '13812345678' });
  await expect('拨号 13812345678', { type: 'command', kind: 'call', number: '13812345678' });
  await expect('打电话给老王', { type: 'ask', askHas: '号码' });
  await expect('打电话', { type: 'ask', askHas: '谁' });

  // 注意：数字匹配是「不猜测」的，电话里出现不合法号码时要问而不是乱发
  await expect('打电话给 123', { type: 'ask' });

  console.log('\n== 导航 ==');
  await expect('导航去南京南站', { type: 'command', kind: 'navigate', destination: '南京南站' });
  await expect('导航去建邺区', { type: 'command', kind: 'navigate', destination: '南京 建邺区' });
  await expect('导航去夫子庙', { type: 'command', kind: 'navigate', destination: '南京 夫子庙' });
  await expect('带我去机场', { type: 'command', kind: 'navigate', destination: '南京 机场' });
  await expect('南京南站怎么走', { type: 'command', kind: 'navigate', destination: '南京南站' });
  await expect('导航去', { type: 'ask', askHas: '目的地' });
  await expect('带我去', { type: 'ask' });

  console.log('\n== 打开应用 ==');
  await expect('打开微信', { type: 'command', kind: 'open_app', appName: '微信' });
  await expect('启动一下支付宝', { type: 'command', kind: 'open_app', appName: '支付宝' });
  // 结尾的「应用 / app / 软件 / 程序」当后缀去掉；查不到由手机端回 app_not_found
  await expect('打开不存在的AppXYZ', { type: 'command', kind: 'open_app', appName: '不存在的AppXYZ' });
  await expect('打开一个不存在的应用', { type: 'command', kind: 'open_app', appName: '一个不存在的' });

  console.log('\n== 日程 / 闹钟 ==');
  await expect(
    '帮我加个日程，明天上午十点开会',
    { type: 'command', kind: 'calendar', title: '开会', startAt: tomorrow10 },
    NOW
  );
  await expect('明天上午十点开会', { type: 'command', kind: 'calendar', title: '开会' }, NOW);
  await expect('加个日程', { type: 'ask', askHas: '什么时候' }, NOW);
  await expect('设个明天早上七点的闹钟', { type: 'command', kind: 'alarm', hour: 7, minute: 0 }, NOW);
  await expect('设个闹钟', { type: 'ask', askHas: '几点' }, NOW);

  console.log('\n== 网页 / 搜索 ==');
  await expect('打开 https://example.com', { type: 'command', kind: 'open_url', url: 'https://example.com' });
  await expect('用浏览器搜一下南京天气', { type: 'command', kind: 'search_web', query: '南京天气' });
  await expect('搜一下南京天气', { type: 'none' }); // 没明确说用浏览器 → 不当命令

  console.log('\n== 闲聊（无意图，不执行） ==');
  await expect('今天天气真好', { type: 'none' });
  await expect('明天要下雨', { type: 'none' });
  await expect('去了就回来', { type: 'none' });
  await expect('你好', { type: 'none' });

  console.log('\n== 混合意图 ==');
  await expect(
    '今天天气怎么样，帮我加个日程明天上午十点开会',
    { type: 'command', kind: 'calendar', title: '开会', chat: '今天天气怎么样' },
    NOW
  );
  await expect('打开微信 顺便看看天气', {
    type: 'command',
    kind: 'open_app',
    appName: '微信',
    chat: '看看天气',
  });
  await expect('明天天气怎么样 顺便导航去南京南站', {
    type: 'command',
    kind: 'navigate',
    destination: '南京南站',
    chat: '明天天气怎么样',
  });
  // 一句话里两个命令 → 回问，一个都不执行
  await expect('帮我打开微信，顺便打开支付宝', { type: 'ask', askHas: '哪个' });

  console.log(`\n结果：${pass} 通过，${fail} 失败`);
  if (fail > 0) {
    console.log('\n失败明细：');
    for (const f of failures) console.log(` - ${f.text}`);
    process.exit(1);
  }
})().catch((e) => {
  console.error('自测异常：', e);
  process.exit(1);
});
