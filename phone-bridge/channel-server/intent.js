'use strict';

/**
 * M5 意图解析：规则优先（正则/关键词） → 规则不中再走模型（小请求，严格 JSON）。
 *
 * 铁律（见 docs/dev/M5 模块开发文档.md）：
 * - 只认白名单 8 种 kind，别的一律不执行；
 * - 参数缺失必须问，不猜；
 * - 中文地名做归一化（补城市前缀）；
 * - 混合意图：闲聊 + 指令在同一句里时，先回答、后执行；一次只执行一个；拿不准就只聊天并问一句。
 *
 * 日志约束：本文件不打印任何用户文本，只返回结果。
 */

/** 白名单（顺序即规则优先级）。 */
const KINDS = Object.freeze([
  'calendar',
  'alarm',
  'call',
  'sms',
  'navigate',
  'open_url',
  'search_web',
  'open_app',
]);
const KIND_SET = new Set(KINDS);

// ---------------------------------------------------------------- 小工具

/** 统一空白、去首尾标点噪声（保留原始中文）。 */
function norm(text) {
  return String(text == null ? '' : text)
    .replace(/\r?\n/g, ' ')
    .replace(/[ \t\u3000]+/g, ' ')
    .trim();
}

/** 去掉句首的客套/主语修饰，循环到稳定。「帮我打开微信」→「打开微信」。 */
function stripLead(text) {
  const LEADS = [
    '帮我',
    '给我',
    '麻烦你',
    '麻烦',
    '请你',
    '请',
    '能不能',
    '可不可以',
    '可以帮我',
    '能帮我',
    '我要',
    '我想',
    '你现在',
    '现在',
    '然后',
    '另外',
    '还有',
    '对了',
    '那',
    '就',
    '快',
    '你',
    '能',
    '可以',
    '我',
    '顺便',
  ];
  let s = text;
  let changed = true;
  while (changed) {
    changed = false;
    for (const lead of LEADS) {
      if (s.startsWith(lead) && s.length > lead.length) {
        s = s.slice(lead.length).trim();
        changed = true;
      }
    }
  }
  return s;
}

/** 去掉句尾语气词与标点。 */
function stripTail(text) {
  return String(text == null ? '' : text)
    .replace(/[，,。.、；;：:！!？?~～\s]+$/g, '')
    .replace(/(?:吗|吧|呢|啊|呀|哦|嘛|了|呗)+$/g, '')
    .trim();
}

const CN_DIGITS = { 零: 0, 〇: 0, 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9 };

/** 中文/阿拉伯数字 → number（0–99 够用：时、分、日）。 */
function toNumber(raw) {
  const s = String(raw == null ? '' : raw).trim();
  if (!s) return NaN;
  if (/^\d+$/.test(s)) return Number(s);
  let ten = 0;
  let num = 0;
  for (const ch of s) {
    if (ch === '十') {
      ten += (num || 1) * 10;
      num = 0;
    } else if (CN_DIGITS[ch] !== undefined) {
      num = CN_DIGITS[ch];
    } else {
      return NaN;
    }
  }
  return ten + num;
}

function pad2(n) {
  return String(n).padStart(2, '0');
}

// ---------------------------------------------------------------- 地名归一化

const DEFAULT_CITY = String(process.env.CHANNEL_DEFAULT_CITY || '南京');

/** 城市/省份词：出现任意一个就认为地名已经带了地域前缀，不再补。 */
const REGION_WORDS = [
  '北京',
  '上海',
  '天津',
  '重庆',
  '南京',
  '杭州',
  '苏州',
  '无锡',
  '常州',
  '扬州',
  '徐州',
  '南通',
  '广州',
  '深圳',
  '珠海',
  '东莞',
  '佛山',
  '成都',
  '武汉',
  '西安',
  '长沙',
  '郑州',
  '济南',
  '青岛',
  '烟台',
  '合肥',
  '福州',
  '厦门',
  '泉州',
  '宁波',
  '温州',
  '沈阳',
  '大连',
  '哈尔滨',
  '长春',
  '石家庄',
  '太原',
  '南昌',
  '贵阳',
  '昆明',
  '南宁',
  '海口',
  '三亚',
  '兰州',
  '西宁',
  '银川',
  '乌鲁木齐',
  '呼和浩特',
  '拉萨',
  '香港',
  '澳门',
  '台北',
  '省',
  '自治区',
  '特别行政区',
];

/** 常见「区/县」 → 所属城市（提升地图命中率；不认识的不猜）。 */
const DISTRICT_CITY = {
  建邺区: '南京',
  鼓楼区: '南京',
  玄武区: '南京',
  秦淮区: '南京',
  雨花台区: '南京',
  栖霞区: '南京',
  浦口区: '南京',
  六合区: '南京',
  溧水区: '南京',
  高淳区: '南京',
  江宁区: '南京',
  浦东新区: '上海',
  徐汇区: '上海',
  静安区: '上海',
  黄浦区: '上海',
  长宁区: '上海',
  普陀区: '上海',
  虹口区: '上海',
  闵行区: '上海',
  朝阳区: '北京',
  海淀区: '北京',
  东城区: '北京',
  西城区: '北京',
  丰台区: '北京',
  天河区: '广州',
  越秀区: '广州',
  海珠区: '广州',
  番禺区: '广州',
  南山区: '深圳',
  福田区: '深圳',
  罗湖区: '深圳',
  宝安区: '深圳',
  武侯区: '成都',
  锦江区: '成都',
  成华区: '成都',
  西湖区: '杭州',
  滨江区: '杭州',
  拱墅区: '杭州',
  余杭区: '杭州',
  江岸区: '武汉',
  洪山区: '武汉',
  武昌区: '武汉',
  雁塔区: '西安',
  碑林区: '西安',
  蜀山区: '合肥',
  岳麓区: '长沙',
  金水区: '郑州',
};

/** 地点后缀词：命中说明「这是一个地名」，才敢补城市前缀。 */
const PLACE_SUFFIXES = [
  '站',
  '机场',
  '医院',
  '大学',
  '学院',
  '公园',
  '广场',
  '中心',
  '大厦',
  '大楼',
  '路口',
  '街',
  '路',
  '巷',
  '门',
  '桥',
  '寺',
  '山',
  '湖',
  '园',
  '口',
  '区',
  '县',
  '镇',
  '村',
  '港',
  '塔',
  '宫',
  '馆',
  '庙',
  '祠',
  '陵',
  '街',
];

/**
 * 目的地归一化：去口头语 + 必要时补城市前缀。
 * - 「建邺区」 → 「南京 建邺区」（区县查表）
 * - 「南京南站」 → 原样（已经带了城市）
 * - 「夫子庙」 → 「南京 夫子庙」（短地名 + 地点后缀，补默认城市）
 * - 「公司」 → 原样（不是地名，不硬补）
 */
function normalizeDestination(raw) {
  let d = String(raw == null ? '' : raw).trim();
  d = stripTail(d);
  d = d.replace(/^(?:到|去|往|从)/, '').trim();
  d = d.replace(/^(?:一下|一个)/, '').trim();
  d = d.replace(/[的地得]*(?:怎么走|怎么去|路线|导航)$/, '').trim();
  if (!d) return '';
  // 以语气/助词开头说明这多半不是地名（「去了就回来」），不猜
  if (/^(?:了|就|吧|吗|呢|啊|再|又|还|也|都|是|有)/.test(d)) return '';

  // 已经带地域词：不补
  if (REGION_WORDS.some((w) => d.includes(w))) return d;

  // 区县查表：宁可查表，不认识的不猜
  for (const [district, city] of Object.entries(DISTRICT_CITY)) {
    if (d.includes(district)) {
      return d.startsWith(district) ? `${city} ${d}` : d;
    }
  }

  // 短地名 + 地点后缀 → 补默认城市
  const looksLikePlace = PLACE_SUFFIXES.some((suf) => d.endsWith(suf));
  if (looksLikePlace && d.length >= 2 && d.length <= 12) {
    return `${DEFAULT_CITY} ${d}`;
  }
  return d;
}

// ---------------------------------------------------------------- 时间解析

const AMPM_AFTERNOON = ['下午', '傍晚', '晚上', '夜里', '夜间'];
const AMPM_NOON = ['中午'];
const WEEKDAY = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 日: 0, 天: 0 };

/**
 * 从一段话里抽「日期 + 时刻」。
 * 返回 { start, end, matched }（毫秒时间戳 / 命中的原文）或 null。
 * 支持：今天/明天/后天、周X/星期X/礼拜X、X月X日、2026-11-18、上午/下午/晚上/中午 + X点/X:XX/X点半。
 */
function parseWhen(text, now) {
  const src = String(text || '');
  const day = new Date(now == null ? Date.now() : now);
  day.setSeconds(0, 0);

  // --- 时刻
  let hour = NaN;
  let minute = 0;
  let timeText = '';
  const numTime = src.match(/(\d{1,2})\s*[:：点时]\s*(\d{1,2})?\s*分?/);
  const cnTime = src.match(/([零〇一二两三四五六七八九十]{1,3})\s*点\s*(半|[零〇一二两三四五六七八九十]{1,3}\s*分?)?/);
  if (numTime) {
    hour = Number(numTime[1]);
    minute = numTime[2] === undefined ? 0 : Number(numTime[2]);
    timeText = numTime[0];
  } else if (cnTime) {
    hour = toNumber(cnTime[1]);
    if (cnTime[2] === '半') minute = 30;
    else if (cnTime[2]) minute = toNumber(cnTime[2].replace(/分/g, '')) || 0;
    timeText = cnTime[0];
  }

  if (Number.isNaN(hour) || hour > 24 || Number.isNaN(minute) || minute > 59) {
    hour = NaN;
  }

  // --- 日期
  let dayText = '';
  let base = new Date(day.getTime());
  const rel = src.match(/今天|今日|明天|明日|后天|大后天/);
  const weekday = src.match(/(?:下)?(?:周|星期|礼拜)([一二三四五六日天])/);
  const abs = src.match(/(\d{4})\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})\s*[日号]?/);
  const monthDay = src.match(/(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]/);

  if (abs) {
    base = new Date(Number(abs[1]), Number(abs[2]) - 1, Number(abs[3]));
    dayText = abs[0];
  } else if (monthDay) {
    const m = Number(monthDay[1]);
    const d = Number(monthDay[2]);
    base = new Date(day.getFullYear(), m - 1, d);
    if (base.getTime() < day.getTime() - 24 * 3600 * 1000) {
      base.setFullYear(base.getFullYear() + 1); // 说“1月1日”而今年已过 → 明年
    }
    dayText = monthDay[0];
  } else if (rel) {
    dayText = rel[0];
    const delta = dayText === '今天' || dayText === '今日' ? 0 : dayText === '明天' || dayText === '明日' ? 1 : dayText === '后天' ? 2 : 3;
    base.setDate(base.getDate() + delta);
  } else if (weekday) {
    dayText = weekday[0];
    const target = WEEKDAY[weekday[1]];
    const isNextWeek = weekday[0].startsWith('下');
    let delta = (target - base.getDay() + 7) % 7;
    if (delta === 0) delta = 7;
    if (isNextWeek) delta += 7;
    base.setDate(base.getDate() + delta);
  }

  if (Number.isNaN(hour)) {
    // 只说了“明天”没说几点：日期算出来也没用（日历要具体时刻）
    return null;
  }

  // --- 12 小时制换算
  const before = src.slice(0, Math.max(0, src.indexOf(timeText)));
  let ampmText = '';
  for (const w of AMPM_AFTERNOON.concat(AMPM_NOON, ['凌晨', '早上', '早晨'])) {
    if (before.includes(w)) {
      ampmText = w;
      break;
    }
  }
  if (AMPM_AFTERNOON.includes(ampmText) && hour < 12) hour += 12;
  else if (AMPM_NOON.includes(ampmText) && hour < 11) hour += 12;
  if (hour >= 24) hour = 0;

  const start = new Date(
    base.getFullYear(),
    base.getMonth(),
    base.getDate(),
    hour,
    minute,
    0,
    0
  );
  return {
    start: start.getTime(),
    end: start.getTime() + 60 * 60 * 1000,
    matched: `${dayText}${ampmText}${timeText}`.trim(),
    timeText,
    dayText,
    ampmText,
    hasDate: Boolean(dayText),
  };
}

// ---------------------------------------------------------------- 各 kind 的规则

/** 时间词：判断「有没有提到时间」用（解析失败也说明该问一句而不是当闲聊）。 */
const TIME_WORD_RE = /(\d{1,2}\s*[:：点时]|[零〇一二两三四五六七八九十]{1,3}\s*点|今天|明天|后天|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天]|\d{1,2}\s*月\s*\d{1,2})/;

/** alarm：「设个明天早上七点的闹钟」→ {hour, minute} */
function matchAlarm(t) {
  if (!/(闹钟|叫醒|叫我起床)/.test(t)) return null;
  const when = parseWhen(t, Date.now());
  if (!when) return { ask: '几点的闹钟？说个时间我就去设。' };
  const d = new Date(when.start);
  return { kind: 'alarm', args: { hour: d.getHours(), minute: d.getMinutes() }, say: '好，打开闹钟页面' };
}

/** calendar：「帮我加个日程，明天上午十点开会」→ {title, start, end, location?} */
function matchCalendar(t) {
  if (/(闹钟|叫醒)/.test(t)) return null; // 明确说的是闹钟，交给 alarm
  const strong =
    /(日程|日历|会议|事项)/.test(t) ||
    (/(开会|碰头|约见)/.test(t) && TIME_WORD_RE.test(t)) ||
    (/(安排|预约)/.test(t) && TIME_WORD_RE.test(t));
  if (!strong) return null;

  const when = parseWhen(t, Date.now());
  if (!when) return { ask: '这个日程什么时候？说个具体时间（比如明天上午十点）。' };

  let rest = t;
  // 去掉日期 / 时刻 / 上午下午 三段（分开删，因为原文顺序可能是「明天上午十点」）
  for (const piece of [when.dayText, when.ampmText, when.timeText]) {
    if (piece) rest = rest.split(piece).join(' ');
  }
  rest = rest
    .replace(/(?:帮我|给我|请|麻烦|你|能|可以)+/g, ' ')
    .replace(/(?:加|添加|新建|建|创建|安排|预约|记|排|设)(?:一个|个|一条|条)?/g, ' ')
    .replace(/(?:日程|日历|会议|安排|预约|事项)/g, ' ')
    .replace(/(?:凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)/g, ' ')
    .replace(/[，,。.、；;：:\s]+/g, ' ')
    .trim();

  // 地点：从「在 XX」抽
  let location = null;
  const loc = rest.match(/在\s*([^\s在]{2,20})/);
  if (loc) {
    location = stripTail(loc[1]);
    rest = rest.replace(loc[0], ' ').trim();
  }

  rest = rest
    .replace(/^(?:去|到|有|要|于|和|跟|跟人|在)/, '')
    .replace(/^(?:的|了|吧|呢|啊)+/, '')
    .trim();

  let title = stripTail(rest);
  if (!title || title.length < 2) title = '日程';

  const args = { title, start: when.start, end: when.end };
  if (location) args.location = location;
  return { kind: 'calendar', args, say: '好，给你打开日程编辑页' };
}

/** call：「打电话给 13800138000」→ {number}；只有名字 → 问号码（不申请通讯录权限）。 */
function matchCall(t) {
  if (!/^(?:打电话|打个电话|拨打|拨号|打给|呼叫|call|给)/i.test(t)) return null;
  const num = t.match(/(\+?\d[\d\s-]{4,19}\d)/);
  if (num) {
    const number = num[1].replace(/[\s-]/g, '');
    if (/^\+?\d{5,15}$/.test(number)) {
      return { kind: 'call', args: { number }, say: '好，打开拨号盘' };
    }
  }
  const name = t
    .replace(/^(?:打电话给|打个电话给|打电话|打个电话|拨打|拨号|打给|呼叫|call|给)\s*/i, '')
    .replace(/^(?:一下|个)/, '')
    .trim();
  if (name) {
    return {
      ask: `「${stripTail(name)}」这个名字我查不到号码（没申请通讯录权限），把号码念给我？`,
    };
  }
  return { ask: '要打给谁？把号码念给我就行。' };
}

/** sms：「给 13800138000 发短信说 我晚点到」→ {number, body} */
function matchSms(t) {
  if (!/(发短信|发个短信|发条短信|短信发|发信息)/.test(t)) return null;
  const num = t.match(/(\+?\d[\d\s-]{4,19}\d)/);
  const number = num ? num[1].replace(/[\s-]/g, '') : '';
  const bodyMatch = t.match(/(?:说|内容是|内容|讲)\s*(.+)$/);
  const body = bodyMatch ? stripTail(bodyMatch[1]) : '';
  if (!number) return { ask: '发给谁？把号码念给我（我没申请通讯录权限）。' };
  if (!body) return { ask: '短信内容是什么？' };
  return { kind: 'sms', args: { number, body }, say: '好，打开短信界面' };
}

/** navigate：「导航去南京南站」→ {destination}（裸「去 X」不收，避免把闲聊当地名） */
function matchNavigate(t) {
  let dest = '';
  const m1 = t.match(/^(?:导航|带我去|领我去|我要去|想去)\s*(?:到|去)?\s*(.+)$/);
  const m2 = t.match(/^(?:去|到)?\s*(.+?)\s*怎么(?:走|去)$/);
  if (m1) dest = m1[1];
  else if (m2) dest = m2[1];
  else if (/^(?:导航|带我去|领我去|我要去|想去)\s*(?:到|去)?\s*$/.test(t)) {
    return { ask: '去哪？说个目的地我就给你开导航。' };
  } else return null;

  dest = normalizeDestination(dest);
  if (!dest) return { ask: '去哪？说个目的地我就给你开导航。' };
  return { kind: 'navigate', args: { destination: dest }, say: '好，打开地图' };
}

/** open_url：「打开 https://example.com」→ {url} */
function matchOpenUrl(t) {
  const m = t.match(/^(?:打开|访问|浏览|开一下)\s*((?:https?:\/\/)?[\w-]+(?:\.[\w-]+)+(?::\d+)?(?:\/\S*)?)$/i);
  if (!m) return null;
  let url = m[1];
  if (!/^https?:\/\//i.test(url)) url = 'https://' + url;
  return { kind: 'open_url', args: { url }, say: '好，用浏览器打开' };
}

/** search_web：只有明确说「用浏览器搜 / 上网搜」才走这条，别的一律不当搜索。 */
function matchSearchWeb(t) {
  const m = t.match(/(?:用|在)?浏览器(?:里|上)?\s*(?:搜|搜索|查|查找)\s*(?:一下)?\s*(.+)$/);
  const m2 = t.match(/^(?:上网|网上|上网去)\s*(?:搜|搜索|查)\s*(?:一下)?\s*(.+)$/);
  const q = m ? m[1] : m2 ? m2[1] : '';
  const query = stripTail(q);
  if (!query) {
    if (m || m2) return { ask: '搜什么？' };
    return null;
  }
  return { kind: 'search_web', args: { query }, say: '好，用浏览器搜一下' };
}

/** open_app：「打开微信」→ {appName} */
function matchOpenApp(t) {
  const m = t.match(/^(?:打开|开一下|开启|启动|运行|拉起|进入)\s*(?:一下)?\s*(.+)$/);
  if (!m) return null;
  let name = stripTail(m[1]).replace(/^(?:这个|那个)?\s*(?:app|应用|软件|程序)$/i, '').trim();
  name = name.replace(/\s*(?:app|应用|软件|程序)$/i, '').trim();
  if (!name) return { ask: '打开哪个应用？' };
  if (name.length > 20) return { ask: '哪个应用？说个名字。' };
  return { kind: 'open_app', args: { appName: name }, say: `好，打开「${name}」` };
}

const MATCHERS = [matchCalendar, matchAlarm, matchCall, matchSms, matchNavigate, matchOpenUrl, matchSearchWeb, matchOpenApp];

/** 整句当一条命令来解析（规则层）。返回 {kind,args,say} / {ask} / null。 */
function parseCommandRules(text) {
  const t = stripLead(stripTail(norm(text)));
  if (!t) return null;
  for (const match of MATCHERS) {
    const r = match(t);
    if (r) return r;
  }
  return null;
}

// ---------------------------------------------------------------- 混合意图切分

/**
 * 「闲聊 + 指令」切分。
 *
 * 语音转写没有标点，只能靠关键词 + 位置：在「对了 / 顺便 / 另外 / 还有 / 然后 / 帮我」等
 * 候选词处试切，只有**一侧能被规则完整解析成命令、另一侧不是命令**时才算切成功。
 * 两侧都是命令 → conflict（一次只执行一个，回问）。宁可漏切（整句当闲聊），不可误切。
 */
const SPLIT_MARKERS = ['对了', '顺便', '另外', '还有', '然后再', '然后', '再帮我', '帮我', '麻烦你', '还有个事'];

function splitMixed(text) {
  const raw = norm(text);
  for (const marker of SPLIT_MARKERS) {
    let from = 0;
    while (true) {
      const idx = raw.indexOf(marker, from);
      if (idx < 0) break;
      from = idx + marker.length;
      if (idx < 4) continue; // 左半段太短，八成是「帮我打开微信」这种整句
      const left = raw.slice(0, idx).trim();
      const right = raw.slice(idx).trim();
      if (!left || !right) continue;
      const leftCmd = parseCommandRules(left);
      const rightCmd = parseCommandRules(right);
      const leftIsCmd = Boolean(leftCmd && leftCmd.kind);
      const rightIsCmd = Boolean(rightCmd && rightCmd.kind);
      if (leftIsCmd && rightIsCmd) {
        return { conflict: true, left: leftCmd, right: rightCmd };
      }
      if (!leftIsCmd && rightIsCmd) {
        return { chat: left, commandText: right, command: rightCmd };
      }
      if (leftIsCmd && !rightIsCmd) {
        return { chat: right, commandText: left, command: leftCmd };
      }
    }
  }
  return null;
}

// ---------------------------------------------------------------- 校验（规则/模型共用）

/** 把模型/规则给的东西按白名单与参数要求校验一遍；缺参数就返回要问的话。 */
function validate(kind, args) {
  if (!KIND_SET.has(kind)) return { ok: false, ask: '这个我做不了，要不要你自己来？' };
  const a = args && typeof args === 'object' ? args : {};

  if (kind === 'call') {
    const number = String(a.number || '').replace(/[\s-]/g, '');
    if (/^\+?\d{5,15}$/.test(number)) return { ok: true, args: { number } };
    return { ok: false, ask: a.contact ? '谁？把号码念给我（我没申请通讯录权限）。' : '要打给谁？把号码念给我就行。' };
  }
  if (kind === 'sms') {
    const number = String(a.number || '').replace(/[\s-]/g, '');
    const body = stripTail(String(a.body || ''));
    if (!/^\+?\d{5,15}$/.test(number)) return { ok: false, ask: '发给谁？把号码念给我。' };
    if (!body) return { ok: false, ask: '短信内容是什么？' };
    return { ok: true, args: { number, body } };
  }
  if (kind === 'navigate') {
    const destination = normalizeDestination(String(a.destination || ''));
    if (!destination) return { ok: false, ask: '去哪？说个目的地我就给你开导航。' };
    return { ok: true, args: { destination } };
  }
  if (kind === 'alarm') {
    const hour = Number(a.hour);
    const minute = Number(a.minute);
    if (!Number.isInteger(hour) || hour < 0 || hour > 23 || !Number.isInteger(minute) || minute < 0 || minute > 59) {
      return { ok: false, ask: '几点的闹钟？说个具体时间。' };
    }
    return { ok: true, args: { hour, minute } };
  }
  if (kind === 'open_app') {
    const appName = stripTail(String(a.appName || ''));
    const pkg = String(a.package || '').trim();
    if (!appName && !pkg) return { ok: false, ask: '打开哪个应用？说个名字。' };
    return { ok: true, args: pkg ? { package: pkg } : { appName } };
  }
  if (kind === 'calendar') {
    const title = stripTail(String(a.title || ''));
    const start = Number(a.start);
    if (!title) return { ok: false, ask: '日程叫什么？' };
    if (!Number.isFinite(start) || start <= 0) return { ok: false, ask: '这个日程什么时候？说个具体时间。' };
    const end = Number(a.end);
    const out = { title, start, end: Number.isFinite(end) && end > start ? end : start + 60 * 60 * 1000 };
    const location = stripTail(String(a.location || ''));
    if (location) out.location = location;
    return { ok: true, args: out };
  }
  if (kind === 'open_url') {
    const url = String(a.url || '').trim();
    if (!/^https?:\/\/[^\s]+$/i.test(url)) return { ok: false, ask: '哪个网址？' };
    return { ok: true, args: { url } };
  }
  if (kind === 'search_web') {
    const query = stripTail(String(a.query || ''));
    if (!query) return { ok: false, ask: '搜什么？' };
    return { ok: true, args: { query } };
  }
  return { ok: false, ask: '这个我做不了，要不要你自己来？' };
}

// ---------------------------------------------------------------- 模型

const MODEL_BASE = String(process.env.CHANNEL_MODEL_BASE || 'https://api.deepseek.com').replace(/\/+$/, '');
const MODEL_NAME = String(process.env.CHANNEL_MODEL_NAME || 'deepseek-chat');
const MODEL_TIMEOUT_MS = Number(process.env.CHANNEL_MODEL_TIMEOUT_MS || 8000);

/** 模型总开关（CHANNEL_MODEL=off 关掉；没配 key 时上层也不会调）。 */
function modelEnabled() {
  return String(process.env.CHANNEL_MODEL || 'on').toLowerCase() !== 'off';
}

async function callModel(messages, apiKey, maxTokens, jsonMode) {
  if (!apiKey) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), MODEL_TIMEOUT_MS);
  try {
    const body = {
      model: MODEL_NAME,
      messages,
      temperature: 0,
      max_tokens: maxTokens,
      stream: false,
    };
    if (jsonMode) body.response_format = { type: 'json_object' };
    const res = await fetch(`${MODEL_BASE}/chat/completions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!res.ok) return null;
    const payload = await res.json();
    const text = payload && payload.choices && payload.choices[0] && payload.choices[0].message
      ? payload.choices[0].message.content
      : null;
    return typeof text === 'string' ? text : null;
  } catch (e) {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

const INTENT_SYSTEM = [
  '你是一个手机助手的意图解析器。只输出 JSON，不要输出任何解释。',
  '允许的 intent（只能选这些，否则一律 none）：',
  'call（打电话，args.number 必须是号码）、sms（发短信，args.number+args.body）、',
  'navigate（导航，args.destination）、alarm（闹钟，args.hour+args.minute，24 小时制整数）、',
  'open_app（打开应用，args.appName 中文名）、calendar（加日程，args.title+args.start 毫秒时间戳+args.end）、',
  'open_url（打开网页，args.url）、search_web（用浏览器搜索，args.query）。',
  '用户没有明确要求执行上述动作时，必须输出 {"intent":"none"}。',
  '参数不全时输出 {"intent":"ask","question":"要问用户的一句话"}，绝对不要猜参数。',
  '输出格式严格为：{"intent":"..."} 或 {"intent":"<kind>","args":{...},"say":"一句中文短话"}。',
].join('\n');

/** 模型兜底解析：返回 {kind,args,say} / {ask} / null。 */
async function parseCommandByModel(text, apiKey) {
  if (!modelEnabled() || !apiKey) return null;
  const raw = await callModel(
    [
      { role: 'system', content: INTENT_SYSTEM },
      { role: 'user', content: `现在：${new Date().toISOString()}\n用户说：${text}` },
    ],
    apiKey,
    220,
    true
  );
  if (!raw) return null;
  let obj;
  try {
    obj = JSON.parse(raw);
  } catch (e) {
    return null;
  }
  if (!obj || typeof obj !== 'object') return null;
  const intent = String(obj.intent || 'none');
  if (intent === 'none') return null;
  if (intent === 'ask') {
    const question = stripTail(String(obj.question || ''));
    return question ? { ask: question } : null;
  }
  const verdict = validate(intent, obj.args);
  if (!verdict.ok) return verdict.ask ? { ask: verdict.ask } : null;
  const say = stripTail(String(obj.say || ''));
  return { kind: intent, args: verdict.args, say: say || defaultSay(intent, verdict.args) };
}

const REPLY_SYSTEM = [
  '你是手机助手「砚砚」，说话简短口语化，中文，一句话，不超过 40 个字。',
  '只回答用户问的事，不要提你在操作手机，不要编造执行结果。',
].join('\n');

/** 闲聊回答（给混合意图的「先回答」用）；失败返回 null。 */
async function replyChat(text, apiKey) {
  if (!modelEnabled() || !apiKey) return null;
  const raw = await callModel(
    [
      { role: 'system', content: REPLY_SYSTEM },
      { role: 'user', content: text },
    ],
    apiKey,
    120,
    false
  );
  if (!raw) return null;
  const line = stripTail(norm(raw).replace(/\s+/g, ''));
  return line || null;
}

function defaultSay(kind, args) {
  switch (kind) {
    case 'call':
      return '好，打开拨号盘';
    case 'sms':
      return '好，打开短信界面';
    case 'navigate':
      return '好，打开地图';
    case 'alarm':
      return '好，打开闹钟页面';
    case 'open_app':
      return `好，打开「${args.appName || args.package || '应用'}」`;
    case 'calendar':
      return '好，给你打开日程编辑页';
    case 'open_url':
      return '好，用浏览器打开';
    case 'search_web':
      return '好，用浏览器搜一下';
    default:
      return '好';
  }
}

// ---------------------------------------------------------------- 入口

/**
 * 解析一条用户文本。
 *
 * 返回：
 *   { type:'none', chat }                       整句是闲聊（上层去生成回答）
 *   { type:'ask', ask, chat }                    需要问用户（不执行）
 *   { type:'command', kind, args, say, chat, source }
 *                                               执行一条命令；chat 非空表示同句里还有闲聊要先回答
 */
async function parseIntent(text, opts) {
  const options = opts || {};
  const apiKey = options.apiKey || null;
  const raw = norm(text);
  if (!raw) return { type: 'none', chat: '' };

  // 1) 先看是不是「闲聊 + 指令」混在一句里（切分在整句解析之前，否则命令规则会把闲聊吃掉）
  const mixed = splitMixed(raw);
  if (mixed) {
    if (mixed.conflict) {
      return {
        type: 'ask',
        ask: '这句里有两件事，我先做哪个？',
        chat: '',
        conflict: true,
      };
    }
    const cmd = mixed.command;
    const verdict = validate(cmd.kind, cmd.args);
    if (verdict.ok) {
      return {
        type: 'command',
        kind: cmd.kind,
        args: verdict.args,
        say: cmd.say || defaultSay(cmd.kind, verdict.args),
        chat: stripMixedChat(mixed.chat),
        source: 'rule-split',
      };
    }
    // 切出来的命令缺参数：不执行，只在回答后问一句
    return { type: 'ask', ask: verdict.ask, chat: stripMixedChat(mixed.chat) };
  }

  // 2) 整句就是一条命令
  const whole = parseCommandRules(raw);
  if (whole) {
    if (whole.kind) {
      const verdict = validate(whole.kind, whole.args);
      if (verdict.ok) {
        return {
          type: 'command',
          kind: whole.kind,
          args: verdict.args,
          say: whole.say || defaultSay(whole.kind, verdict.args),
          chat: '',
          source: 'rule',
        };
      }
      return { type: 'ask', ask: verdict.ask, chat: '' };
    }
    if (whole.ask) return { type: 'ask', ask: whole.ask, chat: '' };
  }

  // 3) 规则不中 → 模型兜底（严格 JSON + 白名单校验）
  const fromModel = await parseCommandByModel(raw, apiKey);
  if (fromModel) {
    if (fromModel.kind) {
      return {
        type: 'command',
        kind: fromModel.kind,
        args: fromModel.args,
        say: fromModel.say || defaultSay(fromModel.kind, fromModel.args),
        chat: '',
        source: 'model',
      };
    }
    if (fromModel.ask) return { type: 'ask', ask: fromModel.ask, chat: '' };
  }

  return { type: 'none', chat: raw };
}

/** 切出来的闲聊片段：去掉结尾标点、去掉切分关键词，保留原话。 */
function stripMixedChat(chat) {
  return stripLead(stripTail(String(chat || '')))
    .replace(/^(?:对了|另外|还有|然后|顺便|麻烦你)+/g, '')
    .replace(/[，,、；;：:\s]+$/g, '')
    .trim();
}

module.exports = {
  KINDS,
  KIND_SET,
  DEFAULT_CITY,
  parseIntent,
  parseCommandRules,
  splitMixed,
  validate,
  normalizeDestination,
  parseWhen,
  replyChat,
  defaultSay,
  stripLead,
  stripTail,
  norm,
  modelEnabled,
  MODEL_NAME,
};
