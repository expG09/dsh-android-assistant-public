# channel-server（通道 · 电脑端，M2 + M4 + M5 + M6a）

最小的 Node + `ws` 通道服务：配对码、WebSocket 握手、文本往返、心跳与判死；
M4 起收「音频上传」，调 DeepSeek ASR 把语音转成文字回给手机；
M5 起把用户文本**解析成命令**下发手机（白名单 8 种），再把手机的执行结果说成一句人话；
M6a 起把回复**按句念出来**（DeepSeek TTS → `speakStart/speakChunk/speakDone`），手机边收边播、可重播、可打断。

## 启动

```bat
cd android-assistant\channel-server
npm install          :: 第一次：装 ws（本机走 127.0.0.1:7890 代理，npm 缓存请留在工作区）

:: 方式一：环境变量（推荐，key 不进任何文件）
set DEEPSEEK_API_KEY=sk-xxxx
node server.js

:: 方式二：channel-server\.env 里放一行 DEEPSEEK_API_KEY=sk-xxxx（已被 .gitignore 排除）
node server.js

:: 联调：额外打开 /dev/*（含 /dev/text，可把一段文本当成手机发来的 text 喂进主流程）
node server.js --dev
```

密钥优先级：**环境变量 > `.env`**；启动日志只写「来源=环境变量/.env」，**不写值、不写前后缀**。
没配 key 时服务照常起，但收到音频会回 `asrError{code:"no_key"}`、意图解析只剩规则层（不静默）。

启动后会打印：

```
2026-11-15 17:32:04.958 channel-server 启动，监听 0.0.0.0:8788，proto=1..2，文本处理=M5 意图解析 + 命令下发
2026-11-15 17:32:04.959 ASR：已加载 DeepSeek API Key（来源=.env）
2026-11-15 17:32:04.959 意图解析：规则优先 → 模型兜底（deepseek-chat）；命令白名单 8 种：calendar/alarm/call/sms/navigate/open_url/search_web/open_app；默认城市=南京；事件记录=data\events.jsonl
2026-11-15 17:32:04.959 配对码: 676278（5 分钟内有效，用一次即失效）
2026-11-15 17:32:04.959 开发模式已开启：POST /dev/inject、POST /dev/pair-code 可用
```

- 默认监听 `0.0.0.0:8788`（端口可用 `--port=8788` 或 `CHANNEL_PORT=8788` 改）。
- 手机与电脑同一局域网时，手机配对页地址填 `ws://<电脑局域网 IP>:8788`；
  手机连不上局域网就用 USB 反向转发：
  ```bat
  adb reverse tcp:8788 tcp:8788
  ```
  这时手机端填 `ws://127.0.0.1:8788`。
- 配对码 5 分钟过期、**用一次就失效**；要再配一台设备，重启服务（或开发模式 `POST /dev/pair-code`）拿新码。

## 用法

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/pair` | body `{code, deviceId}` → `{ok, deviceToken, proto}`；失败统一 `401 pair_failed`（不区分码错/过期） |
| `GET` | `/healthz` | `{ok, proto, protoSupported, pairedDevices, onlineDevice, echoMode, asrConfigured, asrKeySource, intentMode, intentModel, commandKinds, defaultCity, ttsConfigured, ttsVoices, speakEnabled, speakVoice}`（不含 key） |
| `POST` | `/dev/pair-code` | 仅 `CHANNEL_DEV=1`：重新生成并打印配对码，返回 `{code}` |
| `POST` | `/dev/inject` | 仅 `CHANNEL_DEV=1`：给当前在线连接注入一个帧，body `{type, payload}`，用于验证手机端对预留帧的处理 |
| `POST` | `/dev/text` | 仅 `CHANNEL_DEV=1`：把 `{text}` 当成「手机发来的一条 text」喂进主流程（意图解析 → 下发命令 → 收结果 → 回话），联调时不用真的对着手机喊 |
| `POST` | `/dev/speak` | 仅 `CHANNEL_DEV=1`：把 `{text, voice?}` 直接喂进语音回复链路（按句切分 → TTS → speak 三帧），用来验「长回复边合成边播」 |
| `POST` | `/dev/quit` | 仅 `CHANNEL_DEV=1`：优雅退出进程（沙箱里杀进程受限时用它换实例） |
| `GET` | `/admin/status` | 面板用：`{ok, running, pid, startedAt, uptimeSec, host, port, deviceCount, onlineDeviceId, onlineDeviceProto, pairCodeActive, pairCodeExpiresAt, lastEventAt, bridgeEnabled}` |
| `POST` | `/admin/pair` | 面板用：生成新配对码（与启动打印的是**同一份状态**），返回 `{code, expiresAt, ttlMs}`；旧码同时作废 |
| `GET` | `/admin/devices` | 面板用：`{count, devices:[{deviceId, pairedAt, online, lastActiveAt}], lastEventAt}`；读 `data/devices.json`，文件缺失按空列表；**不回 tokenHash** |
| WS | `/`（任意路径） | 首帧 `hello{deviceId, token, proto:1|2}` → 回 `ready{proto, serverTime}`（proto 原样回显） |

`/admin/*` 鉴权与安全边界：

- header `x-phone-bridge-key: <PHONE_BRIDGE_TOKEN>`（也接受 `Authorization: Bearer <token>`）；
  密钥来源：环境变量优先、`.env` 兜底（与 `bridge.js` / 插件同一把）。
- 缺密钥 / 错密钥 / 服务端没配密钥 → **一律 `401 {ok:false,error:"unauthorized"}`**，不回任何细节。
- 只接受**回环来源**（127.0.0.1 / ::1）；非回环来源回 404，不暴露端点存在。
- 不需要 `--dev`，面板不依赖 `/dev/*`。

## 协议

### proto 1（M2，仍然兼容）

| type | 方向 | 负载 |
|---|---|---|
| `hello` | 手机→电脑 | `{deviceId, token, proto:1}` |
| `ready` | 电脑→手机 | `{proto:1, serverTime}` |
| `text` | 双向 | `{id, text}` |
| `chunk` | 电脑→手机 | `{id, delta}` |
| `done` | 电脑→手机 | `{id}` |
| `ping` / `pong` | 双向 | `{}` |
| `error` | 双向 | `{code, message}` |

### proto 2（M4，在 proto 1 基础上加音频）

| type | 方向 | 负载 |
|---|---|---|
| `audioStart` | 手机→电脑 | `{id, mime:"audio/wav", size, durationMs}` |
| `audioChunk` | 手机→电脑 | `{id, seq, data(base64)}`，每块 ≤64 KiB（手机端原始 48 KiB → base64 64 KiB） |
| `audioEnd` | 手机→电脑 | `{id}` |
| `asrResult` | 电脑→手机 | `{id, text, durationMs, costMs}` |
| `asrError` | 电脑→手机 | `{id, code, message}` |

- `id` 由手机生成、全局唯一；同一 id 的分块按 `seq` 拼接，缺块/字节数对不上回 `asrError{code:"incomplete"}`。
- 上限 8 MiB / 120 秒：**手机端先拦**（不发请求）；电脑端再拦一次（`too_large` / `too_long`）。
- `proto:1` 的连接收到音频帧回 `error{code:"unsupported"}`，不断连；`proto:2` 才收音频。
- 同一连接同时只收一段音频（`busy` 兜底）。
- 识别成功发 `asrResult`，失败发 `asrError`，**不静默**；错误码：
  `no_key` / `incomplete` / `too_large` / `too_long` / `empty` / `unknown_id` / `duplicate_id` /
  `bad_frame` / `busy` / `server_error` / `auth_failed` / `rate_limited` / `timeout` /
  `network_error` / `upstream_error` / `bad_response`。

### proto 2（M5，在 proto 1 基础上加命令下发）

> 加帧不升版本：`proto` 仍是 1..2，`command` / `commandResult` 在 proto 1、2 上都能用。

| type | 方向 | 负载 |
|---|---|---|
| `command` | 电脑→手机 | `{id, kind, args, say}`；`kind` 只能是白名单 8 种 |
| `commandResult` | 手机→电脑 | `{id, ok, detail, launched}`；`detail` 是机器可读短码 |

白名单（手机端不认识的 `kind` 一律回 `ok:false detail:"unsupported"`，不崩溃）：

| kind | args | 手机行为 |
|---|---|---|
| `call` | `number` | `tel:` 跳拨号盘（**不申请 CALL_PHONE，不会自动拨出**） |
| `sms` | `number`, `body` | `smsto:` 打开短信，正文预填 |
| `navigate` | `destination` | `geo:0,0?q=`（失败退高德 / 百度 scheme） |
| `alarm` | `hour`, `minute` | `AlarmClock.ACTION_SET_ALARM`（`SKIP_UI=false`，停在闹钟页等人保存） |
| `open_app` | `appName`（或 `package`），敏感应用需 `confirmed:true` | 现扫 `PackageManager` 的「有 LAUNCHER 入口的应用」按名字匹配并启动 |
| `calendar` | `title`, `start`, `end`, `location?` | `ACTION_INSERT` 拉起日历编辑器，字段预填，**不自动保存** |
| `open_url` | `url` | 浏览器打开 |
| `search_web` | `query` | `ACTION_WEB_SEARCH`，无响应退百度搜索页 |

`commandResult.detail` 常见值：`dialer_opened` / `sms_opened` / `maps_opened` / `amap_opened` /
`baidu_maps_opened` / `clock_opened` / `app_launched` / `calendar_opened` / `browser_opened` /
`search_opened` / `browser_search_opened` / `app_not_found` / `app_ambiguous` / `confirm_required` /
`invalid_args` / `no_dialer` / `no_sms_app` / `no_maps_app` / `no_clock_app` / `no_calendar_app` /
`no_browser` / `launch_failed` / `unsupported` / `exception`。

其余帧 `file` / `approval` 本模块不实现，收到回 `error{code:"unsupported"}`，**不断连**。

- 心跳 30 秒一次；90 秒收不到任何帧就判死断开。
- 同一时刻只允许一台设备在线：第二台设备（不同 deviceId）配对成功、连上瞬间收到 `error{code:"busy"}` 并被断开；已在线的第一台不受影响。**同一台设备**重连会顶掉自己的旧连接。
- `onUserText(text, reply, conn, replyId)` 是「收到一条文本后怎么回」的唯一入口，M5 起是**意图解析**：
  1. 规则优先（`intent.js`：正则/关键词 + 中文地名归一化 + 时间解析）→ 规则不中再走模型（`deepseek-chat`，严格 JSON）；
  2. 闲聊/无意图 → 只回一句人话（模型能答就答），**不下发命令**；
  3. 缺参数 → 只回一句要问的话，**不下发命令**；
  4. 有命令 → 混合意图（聊天 + 指令同句）先回闲聊回答、隔 `CHANNEL_COMMAND_DELAY_MS` 再下发 `command`，
     纯命令直接下发；手机回 `commandResult` 后，把结果说成一句人话回 `text`。
  5. 一句话里两个命令 → 回问「先做哪个」，一个都不执行；银行/支付类 → 先问一句，60 秒内说「确认」才执行。
  6. 命令结果**不进对话正文**：另存一条 `data/events.jsonl` 事件记录（`{at,id,kind,args,ok,launched,detail}`），
     对话里只留那一句人话。

`--echo=text|stream` 仍保留：走 M2 的原样回显，不解析意图（联调用）。

### M6a 语音回复（在 M5 基础上，`proto` 仍是 1..2）

> 加帧不升版本；`speakStart/speakChunk/speakDone/speakStop` 只用于「念回复」，不改 M4 音频上传与 M5 命令协议。

| type | 方向 | 负载 |
|---|---|---|
| `speakStart` | 电脑→手机 | `{id, replyId, text, voice, mime:"audio/pcm", sampleRate:24000, channels:1, segments, kind}` |
| `speakChunk` | 电脑→手机 | `{id, seq, data(base64), mime:"audio/pcm"}`，每块 24 KiB 原始 PCM（0.5 秒） |
| `speakDone` | 电脑→手机 | `{id}` |
| `speakStop` | 手机→电脑 | `{id}`（用户打断：按录音 / 唤醒命中 / 点别的语音） |
| `speakConfig` | 手机→电脑 | `{enabled, voice}`（每次连上 ready 后发一次；改设置再发） |
| `speakRequest` | 手机→电脑 | `{id, voice}`（设置页「试听」；服务端用固定短句合成，**不受总开关限制**） |
| `speakStop` | 电脑→手机 | `{id, reason}`（合成失败时让手机收尾；手机只停播、不回帧，避免来回打转） |

格式（2026-11-16 实测 `POST /v1/tools/tts`，`format=pcm`）：
`content-type: audio/pcm; rate=24000; encoding=signed-integer; bits=16; channels=1`，
即 24 kHz / 16-bit / 单声道 小端 PCM（48 KB/秒）。`speakStart` 里带上参数，手机端优先用帧里的值。

规则：

- **只在回复文本确定后合成**：`text` 帧先发（同时看到字），紧接着 `speakStart` + 逐句合成；
  按 `。！？!?…\n` 切句，单句超 60 字再按 `，、；：` 切，**第一句再切到 ≤18 字**（首音更快）。
- 一句一合成、合成完就发（每块 24 KiB）：长回复是「边合成边播」，不是等整段合成完。
- 同一连接同一时刻只有一段语音：新回复顶掉旧的。
- **开关关着时一次 TTS 请求都不发**（`speakConfig.enabled=false` 直接跳过）；没配 key 也不发，只回文字。
- 合成失败不影响文字：已经发过 `speakStart` 就补一条 `speakStop{reason:"tts_failed"}` 并记日志，不弹错。
- 日志只有元数据：`tts start|seg|id` 行里是 `id / kind / voice / chars / bytes / chunks / cost`，
  **不写回复正文、不写音频、不写 key**。

## ASR（M4）

- 端点 `POST https://api.deepseek.com/v1/tools/asr`，`Authorization: Bearer <key>`，multipart 的 `file` 字段。
- 返回 `{request_id, text, duration_ms}`；`asrResult` 里回 `text / durationMs(=duration_ms) / costMs(电脑端耗时)`。
- 调用超时 30 秒；**只对网络错误重试一次**，HTTP 状态错误/超时不重试。
- 音频抵达后先落盘到 `channel-server/tmp/asr_*.wav`，识别完（成功或失败）立刻删除；启动时顺手清理超过 1 小时的残留。
- 日志只有一行元数据：`asr id=… size=… dur=… cost=… ok|fail code=…`
  **不写识别文本、不写音频内容、不写 key**。

## M5 意图解析（`intent.js`）

- **只认白名单 8 种 kind**，别的一律不执行（规则、模型两条路都要过 `validate()`）。
- 规则层：正则/关键词 + 动词开头判断（「导航去 / 打开 / 打电话给 / 加个日程 / 设个闹钟 / 用浏览器搜」），
  中文地名归一化（`建邺区 → 南京 建邺区`、`夫子庙 → 南京 夫子庙`；默认城市 `CHANNEL_DEFAULT_CITY`，默认「南京」）。
- 时间解析：`今天/明天/后天`、`周X/星期X`、`X月X日`、`上午/下午/晚上/中午 + X点/X:XX/X点半`，中文数字支持到 59。
- 模型兜底：`POST https://api.deepseek.com/chat/completions`（`CHANNEL_MODEL_NAME`，默认 `deepseek-chat`），
  `temperature=0`、`max_tokens≈200`、`response_format=json_object`，输出必须是
  `{"intent":"none"}` / `{"intent":"ask","question":"…"}` / `{"intent":"<kind>","args":{…},"say":"…"}`；
  超时 8 秒，失败就当规则层没命中。`CHANNEL_MODEL=off` 可整体关掉模型（只走规则）。
- 混合意图（闲聊 + 指令同句）：先回答、后执行；切分靠关键词 + 位置（「对了/顺便/另外/还有/然后/帮我」），
  只有**一侧是完整命令、另一侧不是命令**才切；两侧都是命令就回问；拿不准只聊天并问一句。
- 自测：`node test/intent-test.js`（34 条规则用例，不联网）。

## 会话桥（M7，`bridge.js`）

闲聊不再固定「自己调模型」：**先问本机 DSH 会话**（宿主插件 `phone-bridge`，回环 HTTP），
这样手机上问的是跟群里**同一个我**，不是另起一个没有上下文的调用。

- 只对**纯闲聊**（意图解析结果 `none`）走会话。带命令/待确认的话不进会话——那个会话手里有工具，
  把「打开微信」喂进去等于开一条新的执行通道，不在本模块范围内。
- 转发的是**用户原话**（不改写）；本地模型兜底时才用意图解析给出的闲聊部分。
- 插件不可用（连不上 / 401 / 500 / 504）就退回原来的「自己调模型」，**不崩**，日志写明原因：
  `答复来源=会话 耗时=53ms 字数=50` / `答复来源=本地模型 原因=ECONNREFUSED 耗时=2ms`。
- 密钥：`PHONE_BRIDGE_TOKEN`，环境变量优先，`channel-server/.env` 兜底（已在 `.gitignore` 里）；
  启动日志只写「密钥来源」与 URL，不写值。
- 只连回环地址：`PHONE_BRIDGE_URL` 指到非回环 host 直接按配置错误关掉（不外发）。
- 日志不写提问与回复正文，只写走了哪条、耗时、字数 / 错误码。

插件本体在 `../phone-bridge/`，接法与限制见那边的 README 与 `docs/report/M7.md`。

## 语音回复 TTS（M6a，`tts.js`）

- 端点 `POST https://api.deepseek.com/v1/tools/tts`，JSON `{text, voice_id, format:"pcm"}`，回音频流。
- 音色：`stella`（女声，默认）/ `tide`（男声）；由手机的 `speakConfig` 决定，每次回复前都取当前值。
- 单次 `text` ≤ 10,000 计费字符；这里按句切分，单句 ≤ 60 字，**不会超**。
- 超时 `CHANNEL_TTS_TIMEOUT_MS`（默认 20 秒）；**只对网络错误/超时重试一次**，HTTP 错误不重试。
- 实测首字节约 1.2–1.7 秒/句；句音频通常比合成耗时长，所以「边合成边播」是连续的（偶尔会有几十毫秒的空档）。
- 试听：`speakRequest` 用服务端固定短句（不接受客户端正文），这样别人拿到通道也没法用你的额度念任意长文。
- 接口小坑：`format=wav` 返回的 WAV 头里 `data` 块长度写成 `0xFFFFFFFF`（流式占位），
  用 Windows `SoundPlayer` 之类的严格解析器会报「不是有效 WAV」；本模块走 `format=pcm`，不受影响。
- 自测：
  ```bat
  node test/tts-test.js            :: 切句规则 + 真调 TTS，打印每句字数/字节/耗时（不打印正文与 key）
  node test/tts-test.js --no-net   :: 只测切句，不联网
  ```

## 数据与日志

- `data/devices.json`：`deviceId -> {tokenHash, pairedAt}`，**只存 SHA-256 哈希**，不存 token 原文（已在 `.gitignore` 里）。
- `data/events.jsonl`：M5 的**命令事件记录**（`{at,id,kind,args,ok,launched,detail}`），供「后续回忆」。
  这是产品功能的一部分，会含命令参数（号码、地址、标题）；只在电脑本地、已 `.gitignore`，不进对话正文。
- `tmp/`：临时音频，识别后即删（已在 `.gitignore` 里）。
- 日志只写：时间、事件、帧类型、`id`、消息字节数、deviceId、音频大小/时长/耗时、
  命令 `kind`/参数名/`ok`/`launched`/`detail`。
- **不写 token 原文，不写消息正文，不写识别文本，不写命令参数值，不写任何密钥内容**；`hello` 只打认证结果，不打 token。

## 环境变量 / 参数

| 变量 / 参数 | 默认 | 说明 |
|---|---|---|
| `--port=` / `CHANNEL_PORT` | `8788` | 监听端口 |
| `--host=` / `CHANNEL_HOST` | `0.0.0.0` | 监听地址 |
| `--dev` / `CHANNEL_DEV=1` | 关 | 打开 `/dev/*` 调试端点 |
| `--echo=text\|stream` / `CHANNEL_ECHO_MODE` | 关（M5 意图模式） | 只回显、不解析意图（联调用） |
| `DEEPSEEK_API_KEY` | — | ASR 与意图/回答用的密钥（优先级高于 `.env`）；只在内存里用，不落日志 |
| `CHANNEL_ASR_TIMEOUT_MS` | `30000` | 单次 ASR 调用超时 |
| `CHANNEL_AUDIO_MAX_BYTES` / `CHANNEL_AUDIO_MAX_DURATION_MS` | `8388608` / `120000` | 服务端音频上限 |
| `CHANNEL_TMP_DIR` | `./tmp` | 临时音频目录 |
| `CHANNEL_PAIR_TTL_MS` | `300000` | 配对码有效期 |
| `CHANNEL_HEARTBEAT_MS` | `30000` | 发 ping 的间隔 |
| `CHANNEL_DEAD_MS` | `90000` | 判死阈值 |
| `CHANNEL_DATA_DIR` | `./data` | devices.json / events.jsonl 落盘目录 |
| `CHANNEL_MODEL` | `on` | `off` = 关掉模型兜底与闲聊回答（只走规则） |
| `CHANNEL_MODEL_NAME` / `CHANNEL_MODEL_BASE` | `deepseek-chat` / `https://api.deepseek.com` | 意图解析与闲聊回答用的模型 |
| `CHANNEL_MODEL_TIMEOUT_MS` | `8000` | 模型单次超时 |
| `CHANNEL_DEFAULT_CITY` | `南京` | 地名归一化时补的城市前缀 |
| `CHANNEL_COMMAND_DELAY_MS` | `900` | 混合意图里「先回答、后执行」的间隔 |
| `CHANNEL_COMMAND_TIMEOUT_MS` | `20000` | 等手机 `commandResult` 的超时（超时回一句「手机没回执行结果」） |
| `CHANNEL_CONFIRM_TTL_MS` | `60000` | 银行/支付类「确认打开」的有效期 |
| `CHANNEL_EVENTS_FILE` | `./data/events.jsonl` | 命令事件记录文件 |
| `CHANNEL_TTS_ENDPOINT` | `https://api.deepseek.com/v1/tools/tts` | TTS 端点 |
| `CHANNEL_TTS_TIMEOUT_MS` | `20000` | 单句 TTS 调用超时（网络错误/超时重试一次） |
| `PHONE_BRIDGE_ENABLED` | on | `0`/`false` = 关掉会话桥，闲聊全部走本地模型 |
| `PHONE_BRIDGE_URL` | `http://127.0.0.1:8795/m7/ask` | 宿主插件端点；**必须是回环地址**，否则按配置错误关掉 |
| `PHONE_BRIDGE_PORT` / `PHONE_BRIDGE_PATH` | `8795` / `/m7/ask` | 没给 `PHONE_BRIDGE_URL` 时用它俩拼 |
| `PHONE_BRIDGE_TOKEN` | — | 与插件共享的密钥（环境变量优先，`.env` 兜底）；没有它就不启用会话桥，`/admin/*` 也一律 401 |
| `PHONE_BRIDGE_TOKEN_ENV` | `PHONE_BRIDGE_TOKEN` | 换一个键名去读密钥 |
| `PHONE_BRIDGE_TIMEOUT_MS` | `180000` | 等会话回答的上限，超时退回本地模型 |

## 本机联调客户端

`test/client.js` 只用于验收，不是交付功能的一部分：

```bat
:: 配对 + 连接 + 发一条 text（会打印收发的帧，token 已打码）
node test/client.js --code 595158 --device-id dev-a --text "hello" --hold 3000

:: M4：proto 2 上传一段 WAV，打印 asrResult（文本只出现在这个测试客户端的输出里）
node test/client.js --code 595158 --device-id dev-audio --proto 2 --audio sample.wav --hold 15000

:: 第二个设备连入，期望收到 busy 并被断开（验收 5）
node test/client.js --code 123456 --device-id dev-b --hold 3000

:: 不发 pong，验证 90 秒判死
node test/client.js --code 123456 --device-id dev-dead --no-respond --hold 130000

:: 发预留帧，验证服务端回 unsupported 且不断连
node test/client.js --code 123456 --device-id dev-res --send-reserved --hold 2000
```

M5 的意图解析自测（不联网、不调模型）：

```bat
node test/intent-test.js
```

M5 的端到端联调（`--dev` 启动后；走的是「手机发 text」的同一条路径）：

```bat
curl -s -X POST -H "Content-Type: application/json" --data-binary @req.json http://127.0.0.1:8788/dev/text
:: req.json: {"text":"导航去南京南站"}
```

M6a 的语音回复联调（`--dev` 启动后；手机端连着、语音回复开关开着）：

```bat
:: 1) 让电脑用固定短句合成一段（试听 / 验证 speak 三帧）
node test/client.js --code 123456 --device-id dev-speak --proto 2 --speak-config=on --voice=tide --speak-request --hold 9000

:: 2) 注入一段长回复，看是不是按句合成、边合成边播（不需要模型恰好答出 200 字）
curl -s -X POST -H "Content-Type: application/json" --data-binary @req.json http://127.0.0.1:8788/dev/speak
:: req.json: {"text":"第一句短一点。后面几句话说慢一点，用来观察是不是边合成边播……"}

:: 3) 试打断：收到 5 块 speakChunk 就发 speakStop，服务端日志应出现「已停止在途合成」
node test/client.js --code 123456 --device-id dev-stop --proto 2 --speak-config=on --dev-speak="第一句。第二句长一些。" --stop-after=5 --hold 12000
```

注意：手机端是**现扫** `PackageManager`（30 秒内复用一次结果），所以刚装/刚卸的应用不用重启 app 就能找到/找不到。

