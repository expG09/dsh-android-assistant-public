# phone-bridge · 手机通道接进本机会话（带设置面板 + 托管 channel-server）

**一句话**：手机 App 说的话，通过 `channel-server` 转给本插件，插件把它当成**这个会话里的一条用户输入**投进去，
等这一轮答完，只把回复正文回给 `channel-server`；同时插件在设置页提供一节「**语音通道**」，
负责**启动 / 停止 / 看住 channel-server**、生成配对码、看设备、配语音 key、看日志，以及
**把手机消息钉死到一个指定会话**（没绑定就明确 409，不猜）。

```
手机 App ──WS 8788──> channel-server ──HTTP 127.0.0.1:8795/m7/ask──> phone-bridge（本插件）
        ▲                    ▲                                          │ agent.followup()
        │                    └── 面板托管：spawn / 退避拉起 / 看日志        ▼
        └── 设置页「语音通道」──> 插件 webServer /api/phone-bridge     绑定的那个会话
                                    │
                                    └──HTTP 127.0.0.1:8788/admin/*──> channel-server
```

## 这一版（2.0.0）改了什么

- **加了设置页一节「语音通道」**：通道状态/端口/启停、投递会话绑定、配对码（6 位 + 5 分钟倒计时）、设备列表、
  语音提供商与 API key（只回掩码）、channel-server 最后十几行日志。
- **投递会话改成显式绑定**：`<DSH_HOME>\phone-bridge\settings.json` 里存 `sessionId`（优先于 `cordis.patch.yml`）；
  **没有绑定就 409「未绑定投递会话」**，不再把消息投给「最近活跃的那个」。
- **加了 channel-server 进程托管**：spawn 插件自带的 `channel-server\server.js`；挂了按
  1s/2s/5s/10s/20s 退避拉起，最多 5 次；面板能启停；记录 exit code；插件卸载时把子进程收掉。
- **自包含**：channel-server 与它唯一依赖 `ws` 都在插件目录里，别人拿到插件目录不用再 `npm install`；
  首次运行缺 `PHONE_BRIDGE_TOKEN` 会自动生成并写回 `.env`（不覆盖已有值）。
- **投递动作本身一条没动**（只改「选哪个会话」）：`[phone] …` 前缀、`source.via = 'phone-bridge'`、`agent.followup()`、
  `agent/inbox/claimed` + `session/event` 收回复、8795 上的 `POST /m7/ask` 与 `GET /m7/ask/health` 全部原样保留。

## 怎么安装（给别人看）

**前置**：DSH Desktop。`channel-server` 就在插件目录里（`phone-bridge\channel-server`），
它唯一的依赖 `ws` 也随包 vendored 好了，**不需要 npm install**。

1. 把整个 `phone-bridge` 目录装进 DSH（**Plugins → Add plugin**，或
   `plugin_manager` 的 `install_bundle`；junction / 复制进 profile 的 `node_modules` 都能用，
   因为默认就在插件目录里找 channel-server）。
2. **重启桌面端**。
3. 打开 **设置 → 语音通道**：
   - 在「投递会话」填一个会话 id（`session-<uuid>` 或裸 uuid），点「绑定」——
     不绑定的话手机消息会被明确拒绝（409），不会乱投；
   - 在「通道」点「启动」，应看到 8788 开始监听；
   - 首次启动如果 `channel-server\.env` 里没有 `PHONE_BRIDGE_TOKEN`，插件会自动生成一个并写回，
     日志里会写「已生成新 token」（已有值不会被覆盖）。
4. 「配对」区点「生成配对码」，手机 App 里填这一步给出的**电脑端地址**（Tailscale `100.x` 优先，
   没有就局域网地址，再没有就是 `127.0.0.1`「仅本机」），再输 6 位配对码。
5. 手机发一句话，会话里应看到 `[phone] …`，`source.via` 仍是 `phone-bridge`。

**自包含说明**：

- channel-server 目录 = `<插件目录>\channel-server`（默认，优先级最高）；
- 共享密钥 / API key 都从 `<channel-server>\.env` 读，密钥不进仓库、不进包；
  `.env.example` 只放键名与说明（`PHONE_BRIDGE_TOKEN=` 留空，`DEEPSEEK_API_KEY=` 缺失不算错误）；
- 托管进程用到的 node 依赖只有 `ws`，它连同 `LICENSE` 在 `channel-server\node_modules\ws`，MIT 许可；
- 开发时如果 channel-server 放在插件目录外面，可在 patch 里把 `channelServerDir` 填成**绝对路径**。

## 配置

配置在 `cordis.patch.yml` 的 `config:` 里；不改本包时在 profile 的 `cordis.patch.yml` 里按 id `phone-bridge` 覆盖同一行。

| 字段 | 默认 | 说明 |
|---|---|---|
| `host` | `127.0.0.1` | 桥端监听地址，**只能是回环**；非回环直接拒绝启动。 |
| `port` | `8795` | 桥端端口。 |
| `path` | `/m7/ask` | 提问端点；健康检查是 `<path>/health`。 |
| `token` | 空 | 共享密钥。留空则依次找环境变量、`tokenFile`、`<channel-server>\.env`，**都没有就自动生成一个写回 `.env`**。 |
| `tokenEnv` | `PHONE_BRIDGE_TOKEN` | 密钥的键名。 |
| `tokenFile` | 空 | 留空 = 用插件算出的 `<channel-server>\.env`；要指别处才填。 |
| `sessionId` | 空 | 兜底的投递会话（设置文件里没配过时才用它）；**两处都空 = 未绑定 = 手机消息不投递**。 |
| `settingsFile` | 空 | 投递会话的设置文件；留空 = `<DSH_HOME>\phone-bridge\settings.json`（DSH_HOME 缺省 `~/.dsh`）。 |
| `requestTimeoutMs` | `180000` | 等一轮回答的上限，超了回 504。 |
| `channelPort` | `8788` | channel-server 端口；面板探测和 `/admin/*` 都走 `127.0.0.1:这个端口`。 |
| `autoStart` | `true` | 插件加载时自动拉起 channel-server；`false` = 只在面板里手动启停。 |
| `maxRestarts` | `5` | 自动拉起次数上限；稳定跑满 60 秒后退避次数清零。 |
| `nodePath` | 空 | 托管进程用哪个 node。留空 = 宿主是 node 就用它，Electron 用 `ELECTRON_RUN_AS_NODE=1`，兜底 PATH 里的 `node`。 |
| `channelServerDir` | 空 | channel-server 目录的**绝对路径**。留空 = 插件自带的 `<插件目录>\channel-server`（自包含，复制/junction 都行）；开发时 channel-server 在仓库别处才填。填相对路径会被忽略并回落（面板状态带原因）；填绝对路径但没有 `server.js` ⇒ `script-missing`。`.env`（token / key）也从这个目录读。 |

**没有共享密钥时不会直接失败**：首次运行会自动生成 `PHONE_BRIDGE_TOKEN` 并写进
`<channel-server>\.env`；只有「生成后连 `.env` 都写不进去」才降级成内存 token，
并在日志与面板状态里说明（桥端照常可用，但 channel-server 子进程通过环境变量拿到同一个 token）。

## 设置页「语音通道」

| 区块 | 面板做什么 | 实际动作 |
|---|---|---|
| 通道 | 在跑/停了、端口、托管还是外部、pid、最近退出 code、自动拉起次数 | `GET /api/phone-bridge/status`；启动/停止 → `POST /channel/start`、`POST /channel/stop` |
| 投递会话 | 当前绑定（已绑定：`session-…` / 未绑定警告）、输入框、「绑定」、「清除绑定」 | `GET/POST /api/phone-bridge/session` → 写/读 `DSH_HOME\phone-bridge\settings.json` |
| 配对 | 点「生成配对码」→ **响应一到立刻**显示 6 位码与 mm:ss 倒计时（不等下一次轮询）；旁边一行「电脑端地址」列出手机该填的地址（点一下复制） | 代理 `POST /admin/pair` |
| 设备 | 标识 / 首次配对 / 最近活跃 / 在线 | 代理 `GET /admin/devices`（响应里没有 tokenHash） |
| 语音 | 提供商（**DeepSeek 默认** / Edge / 本地）+ API key 输入；回显永远是掩码（前 4…后 4） | 写 `channel-server\.env` 的 `CHANNEL_TTS_PROVIDER` 与 `DEEPSEEK_API_KEY` |
| 日志 | channel-server 最后十几行（含面板的启动/停止/配对动作） | 托管进程 stdout/stderr 的环形缓冲（200 行，**写进缓冲前先掩码**） |

几点说明：

- **投递会话没有「跟随最近活跃」**：sessionId 有值就只投那一个；清空就是未绑定，
  `/m7/ask` 回 409「未绑定投递会话」。会话 id 在 DSH 里很好复制，宁可拒绝也不猜。
- **会话 id 形状**：只接受 `session-<uuid>` 或裸 `<uuid>`；其它形状面板回 400 + 中文原因。
- **绑定优先级**：`<DSH_HOME>\phone-bridge\settings.json` > `cordis.patch.yml` 的 `sessionId` > 空。
  设置文件不存在 / 坏 JSON / 缺字段都当「这一层没配过」，回落 patch，不会让插件加载失败。
- **外部启动的通道**：面板能看状态、生成配对码，但「停止」只停面板拉起的进程。
- **电脑端地址**：优先列非回环 IPv4，Tailscale 的 `100.x` 排最前（最多 3 个）；一个都没有时退回
  `127.0.0.1` 并注明「仅本机」。这一行只有 IP + 端口，不含任何密钥。
- **key 生效时机**：通道正在跑时改 key，要「停止 → 启动」才生效；如果环境变量里已有
  `DEEPSEEK_API_KEY`，它优先于 `.env`，面板会显式警告（此时面板写入不会生效）。
- **提供商默认 DeepSeek**；Edge / 本地是「未实现」的配置入口——只写进 `.env`，
  channel-server 的 TTS 现在只实现 DeepSeek，选它们不会改变实际播报（见「范围」）。

## 接口

### 桥端（**保持不变**）

```
POST http://127.0.0.1:8795/m7/ask
x-phone-bridge-key: <共享密钥>
{"text": "今天几号", "sessionId": "可选"}
→ 200 {"ok":true,"reply":"...","sessionId":"...","reason":"completed","interrupted":false}
```

| 状态 | 含义 |
|---|---|
| 200 | 答完了，`reply` 是正文 |
| 400 | 文本为空 / 超 4000 字 / JSON 坏了 |
| 401 | 没带密钥、密钥不对（**任何路径都先鉴权**） |
| 404 | 指定的 `sessionId` 没有活着的会话 |
| 405 | 方法不对（只收 POST；健康检查收 GET/HEAD） |
| 409 | **未绑定投递会话**（面板「投递会话」里先绑定一个） |
| 413 | 请求体超过 8 KiB |
| 502 | 投递本身失败 |
| 504 | 投进去了，但这一轮没在超时内答完 |

`GET /m7/ask/health`（同样要密钥）回 `{"ok":true,"sessions":[...],"pinned":null}`。

### 面板接口（挂在宿主 webServer 的 `/api/phone-bridge`）

```
GET  /api/phone-bridge/status         通道 + 投递会话 + 配对 + 语音 + 地址 + 日志尾部
GET  /api/phone-bridge/session        当前绑定：{ sessionId, bound, mode: 'pinned'|'unbound', hint, file }
POST /api/phone-bridge/session        { sessionId }（空串 / null = 清除）→ 同上结构
GET  /api/phone-bridge/address        手机该填的电脑端地址：{ addresses:[{url,host,tailscale,loopback,note}] }
POST /api/phone-bridge/channel/start  启动托管进程
POST /api/phone-bridge/channel/stop   停止托管进程
POST /api/phone-bridge/pair           → channel-server POST /admin/pair（响应立刻回给面板）
GET  /api/phone-bridge/devices        → channel-server GET /admin/devices
POST /api/phone-bridge/voice          {provider, apiKey?} → 写 .env，只回掩码
```

`status.addresses` 与 `/address` 是同一份：非回环 IPv4，Tailscale `100.x` 最前，最多 3 个；
没有非回环地址时返回 `127.0.0.1` + `loopback:true` + note「仅本机」；不含任何密钥。

`POST /session` 只接受 `session-<uuid>` 或裸 `<uuid>`；其它形状回 **400** +
`{"error":"bad-session-id","message":"格式不对，应该是 session-xxx 或 uuid"}`。

### 投递会话的设置文件

默认落点（**跟着 DSH 走，重装/升级插件不会丢**）：

```
<DSH_HOME>\phone-bridge\settings.json
DSH_HOME = process.env.DSH_HOME || os.homedir() + ".dsh"
Windows 默认：C:\Users\<user>\.dsh\phone-bridge\settings.json

{ "sessionId": "session-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" }
```

- 解析顺序：`config.settingsFile`（填了就用它）> `DSH_HOME\phone-bridge\settings.json`；
- 目录不存在就 `mkdir -p` 建出来；**建不出来不会让插件加载失败**：降级到
  `<插件目录>\data\settings.json`，面板状态里会带 `settingsFallback: true` + 原因（`fallbackReason`）；
- 面板「投递会话」区会显示当前用的是哪个文件（只显示路径）；
- 优先于 `cordis.patch.yml` 的 `sessionId`；文件不存在 / 坏 JSON / 缺字段都当「这一层没配过」，回落 patch；
- 清空（写 `""`）就是未绑定 ⇒ `/m7/ask` 409；
- 写文件是原子替换（临时文件 + rename），保留文件里的其它键。

安全（硬约束）：

- **同源校验**：Host 必须是回环；带了 Origin/Referer 必须与 Host 同源（**端口也比**）；
  能拿到 DSH 的信任栅栏（`connection.requestRejection`）时先过它；拿不到就只靠同源这一道。
- **只走回环**：访问 channel-server 的 `/admin/*` 固定 `127.0.0.1:<channelPort>`，
  带共享密钥（`x-phone-bridge-key` 与 `Authorization: Bearer` 都给，服务端认哪个都行）。
- **key 只出掩码**：任何接口响应、日志、面板状态都不含 key 明文；写 `.env` 是原子替换（临时文件 + rename）。
- **不拼参数**：托管入口路径固定、参数只有配置里的整数端口，面板无法传命令/路径/参数。

宿主没有 `webServer` 服务时面板接口不挂（通道托管照常）；webServer 晚于插件就绪时会每 500ms
补试一次（最多 20 次），挂上即成。端口上已有外部实例时，自动拉起和面板「启动」都不会去抢。

## 进程托管

- 入口：`<插件目录>\channel-server\server.js`（自包含，默认就是它；开发时可配置别处）。找不到就是
  `script-missing`，面板显示路径与原因，**不抛错**。
- 启动：`spawn(node, [server.js, --port=<配置>], { cwd: channel-server 目录, stdio: ['ignore','pipe','pipe'] })`；
  子进程会通过环境变量拿到插件解析/生成的同一个 `PHONE_BRIDGE_TOKEN`（即使 `.env` 写不进去也一致）。
- 依赖：channel-server 只需要 `ws`；它连同 `LICENSE`（MIT）已经 vendor 在 `channel-server\node_modules\ws`。
- 退出：记录 `lastExitCode` / `lastExitAt`；非主动停止时按退避自动拉起（最多 `maxRestarts` 次）；
  稳定 60 秒后次数清零；次数用尽后面板显示「自动拉起次数用尽」，点「启动」可重置。
- 停止：先 `kill()`，2.5 秒没退再 `SIGKILL`；停不掉会如实报 `kill-failed`。
- 插件卸载：`dispose()` 收掉子进程，避免桌面端退出后留孤儿占着 8788。

## 自检

**任意目录**都能跑（路径按 `selfcheck.mjs` 自己的位置算）：

```
node <插件目录>\selfcheck.mjs
```

它会用临时目录跑：密钥掩码 / `.env` 读写、投递会话设置文件读写与优先级（设置文件 > patch）、
id 校验（非法 400）、未绑定 → 409、绑定 → 投递 → 清除 → 又 409、坏 JSON/缺字段当未配置、
默认落点（DSH_HOME 临时目录 / 假 homedir / config 优先 / 建不出目录降级）、
配对码乐观更新与同码不覆盖/过期清空、电脑端地址枚举（Tailscale 优先 / 仅本机兜底 / 不含密钥）、
默认提供商 deepseek、`channelServerDir` 定位（自包含默认 / 配置优先 / 相对路径回落 / 没有 server.js 报 missing / 多路径列举 / .env 同目录）、
首次运行自动生成 token（缺失生成并写回 / 二次启动复用 / 已有值不覆盖 / 写失败降级内存 token）、
包内容与忽略规则（.env.example、vendored ws + LICENSE、根 .gitignore）、
托管入口不存在与 spawn 失败的降级、面板同源校验与各接口降级、8795 两个端点、
`[phone] ` 前缀与 `source.via='phone-bridge'`、client.js 自注册与 `settings.section`（label「语音通道」）、
包内 JSON。另外静态检查：

```
node --experimental-vm-modules <workspace>\tools\check-esm-syntax.mjs index.js
node --experimental-vm-modules <workspace>\tools\check-esm-syntax.mjs client.js
node <workspace>\tools\check-client-script.cjs client.js
```

## 安全边界

- **只监听回环**：8795 的 `host` 非回环直接拒绝；面板接口靠宿主 webServer + 同源校验。
- **不是命令通道**：桥端只接受一段用户文本；托管只接受「启动/停止」两个固定动作。
- **日志不写正文**：桥端只写方法/状态/耗时；托管日志来自 channel-server 自己的 stdout（它本身也不写正文/密钥），
  进缓冲前再掩码一次。
- **`source.via` 不动**：仍是 `'phone-bridge'`。qqbot 的出站白名单靠它挡手机轮次进群，
  这一版没有改 qqbot 的任何逻辑，也没有改这个值。

## 依赖的 channel-server 版本

`channel-server` 现在就在插件目录里（`phone-bridge\channel-server`），自带的管理端点：

```
GET  /admin/status   （回环 + PHONE_BRIDGE_TOKEN）
POST /admin/pair     → { ok, code, expiresAt, ttlMs }
GET  /admin/devices  → { ok, count, devices:[{deviceId, pairedAt, online, lastActiveAt}], lastEventAt }
```

面板的「配对 / 设备 / 在线状态」依赖它们。若外部另指了一个旧版 channel-server（没有 `/admin`），
面板会显示 `admin-missing` 提示，通道状态与启停仍可用。

## 范围与已知限制

- **语音链路不动**：channel-server 的 TTS 仍只实现 DeepSeek；「Edge / 本地」只是配置入口，不改变现有实现。
- **未绑定就是拒绝**：这是刻意的（宁可 409，不把手机消息投错会话）；绑定要手填会话 id。
- 面板拿不到「外部启动」的进程句柄：只能看、不能停。
- 日志缓冲从插件加载（或面板启动托管）开始算；插件重启前的旧日志看不到。
- 同时手动启动 channel-server 与面板托管会抢 8788：托管那次会 spawn 失败并记进日志，不会崩；建议二选一。
- 手机与群同时说话仍可能在同一会话里排队（与旧版一致）。

## 文件

```
index.js          宿主插件：桥端（原逻辑）+ 面板挂载 + 托管接线
client.js         设置页「语音通道」（自注册脚本，不是 ESM）
lib/env.mjs       .env 读写、密钥掩码、密钥来源
lib/process.mjs   channel-server 进程托管
lib/panel.mjs     /api/phone-bridge 接口（同源校验、admin 代理、key 写入、会话绑定）
lib/settings.mjs  投递会话设置文件（默认 <DSH_HOME>\phone-bridge\settings.json）读写与优先级
selfcheck.mjs     本地预检（不装插件、不重启；任意目录可跑）
cordis.patch.yml  插件行与配置
locale/*.json     Plugins 页的标题/描述
channel-server/   自包含的通道服务（server.js / bridge.js / intent.js / tts.js）
                  .env.example 是模板；.env、data/、tmp/ 是本机运行时数据（已 gitignore）
                  node_modules/ws 是唯一依赖（vendored，MIT，带 LICENSE）

（运行时数据不在包里：投递会话绑定写在 <DSH_HOME>\phone-bridge\settings.json，
  DSH_HOME 缺省 ~/.dsh；目录建不出来时降级到 <插件目录>\data\settings.json）
```
