# M3b 唤醒提示语音（「我听见了」）

命中唤醒词后随机播一句，播完才开麦。三段 mp3 随 APK 打包，**运行时不联网**。

| 文件 | 内容 | 时长 | 字节 |
|---|---|---|---|
| `wake_1.mp3` | 「我在」 | 1.152 s | 6,912 |
| `wake_2.mp3` | 「嗯？」 | 0.912 s | 5,472 |
| `wake_3.mp3` | 「在呢」 | 1.176 s | 7,056 |

- 来源：expG09 给的三段短语音（本轮直接收进 assets，见 `docs/report/M3.md` M3b 一节）。
- 播放：`MediaPlayer` + `AudioAttributes(USAGE_ASSISTANT, CONTENT_TYPE_SPEECH)`，不申请音频焦点；
  播完 / 失败 / 超时（1.8 s 兜底）都会 `release()`，失败只记日志，不挡录音。
- 开关：设置页「唤醒后应一声」（`wake_prompt_enabled`，默认 `true`）。关掉后**完全不播**。
- 随机：每次命中随机挑一句，并避免连续两次同一句；`logcat` 的 `播放唤醒提示` 行带各句累计次数。
