# M3b 唤醒词模型（sherpa-onnx 流式 KWS）

这些文件随 APK 打包，**首次打开「监听开关」时复制到应用私有目录**（`files/kws-model/`），
不联网下载。

## 来源

- 模型：`sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile`
  - 发布页：https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models
  - 文件名：`sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2`（15,292,881 字节）
  - 上游说明（模型卡）：https://www.modelscope.cn/pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01
    —— zipformer，3.3M 参数，训练数据 WenetSpeech L（约 1 万小时），建模单元为拼音（声母 + 韵母）。
  - 许可：Apache License 2.0（模型卡 frameworks/license 字段）。

放进 assets 的只有推理必需的 5 个文件（int8 量化版，与文档要求一致）：

| 文件 | 字节 |
|---|---|
| `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 3,990,821 |
| `decoder-epoch-12-avg-2-chunk-16-left-64.onnx` | 675,349 |
| `joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 65,242 |
| `tokens.txt` | 1,627 |
| `keywords.txt`（本模块生成） | 见下 |

合计约 **4.6 MB**（文档要求 ≤20 MB）。

> 说明：官方发布包里 decoder 只有 fp32 版本（675 KB，没有 int8），encoder / joiner 用的都是 int8。

## keywords.txt 怎么来的

用官方 `scripts/text2token.py` + `--tokens-type ppinyin`（sherpa-onnx 2.0.1 的
`sherpa_onnx.text2token`，依赖 pypinyin / sentencepiece）生成，输入见 `keywords_raw.txt`：

```
大肥鱼 :2.0 @大肥鱼
砚砚 :2.0 @砚砚
言言 :1.5 @言言
眼眼 :1.5 @眼眼
```

输出（就是 `keywords.txt` 的内容）：

```
d à f éi y ú :2.0 @大肥鱼
y àn y àn :2.0 @砚砚
y án y án :1.5 @言言
y ǎn y ǎn :1.5 @眼眼
```

- 主词两个：「大肥鱼」= `d à f éi y ú`（三音节，expG09 要求加的外号）、
  「砚砚」= `y àn y àn`；`:2.0` 是加权分（提高命中率）。
- 谐音变体只留两个，都是「砚砚」的**声调变体**：`yán yán`（言言）、`yǎn yǎn`（眼眼）。
  之前一版还带 `yān yān`（烟烟），按「每多一个词都抬误触发」的实测结论砍掉了。
- 「燕燕」的拼音和「砚砚」完全一样（都是 yàn yàn），生成出来的 token 序列一模一样，
  写进表里只是重复项、没有收益，所以没有放——它喊出来由「砚砚」那条负责命中。
- 每个 token 都在 `tokens.txt` 里校验存在（生成脚本会断言，缺一个就报 unknown）。
- 命中任何一个都算唤醒，界面/通知显示 `@` 后面的原词。

## 运行参数（写在 `WakeWordEngine.kt` 里）

- `keywordsThreshold = 0.25`、`keywordsScore = 2.0`、`maxActivePaths = 4`、`numTrailingBlanks = 2`
- 帧：16 kHz / 16-bit / 单声道，采集线程每 1024 采样（64 ms）喂一次，命中后 <100 ms 出结果（PC 上实测）。
