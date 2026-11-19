package com.yanyan.assistant

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** 唤醒引擎状态，给界面和日志看。 */
enum class WakeWordState {
    /** 没加载（监听开关关着 / 麦克风让给别人时） */
    IDLE,

    /** 正在从 assets 复制模型并初始化 */
    LOADING,

    /** 正在听 */
    READY,

    /** 加载失败（界面上要显示原因） */
    ERROR,
}

/**
 * M3b：sherpa-onnx 流式 KWS（[KeywordSpotter]）封装。
 *
 * 设计约束（见 docs/dev/M3 模块开发文档.md「M3b」与 docs/decision.md）：
 * - 模型随 APK 打进 assets，**首次启用时复制到私有目录**，绝不运行时联网下载；
 * - 只有「监听开关打开且麦克风真的在采」时才加载；关开关 / 让出麦克风时必须释放，
 *   关着的时候进程里不该有 KWS 在跑（验收要求能从 logcat 与 dumpsys 上看出来）；
 * - 直接吃 [AudioCaptureEngine] 的流式帧，不套 VAD、不做分段。
 *
 * 线程：初始化在独立线程（约 0.3–1 秒，不能卡主线程），[accept] 由采集线程调用，
 * 释放由主线程调用；两者共用 [lock]，避免 release 与 decode 撞车。
 */
class WakeWordEngine(
    private val app: Context,
    private val onWake: (String) -> Unit,
) {
    companion object {
        private const val TAG = "Kws"

        /** assets 下的模型目录。 */
        const val ASSET_DIR = "kws"

        /**
         * assets 内容版本。模型 / tokens / keywords 任何一项变了就把这里改一下，
         * 下次启用时会重新复制到私有目录（旧目录原地覆盖）。
         */
        const val ASSET_VERSION = "2026-11-16-wenetspeech-3.3m-int8-kw2"

        private const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val KEYWORDS = "keywords.txt"

        private val REQUIRED = listOf(ENCODER, DECODER, JOINER, TOKENS, KEYWORDS)

        /** 关键词表（和 assets/kws/keywords.txt 同源，仅用于界面显示）。 */
        const val KEYWORD_DISPLAY = "大肥鱼 / 砚砚 / 言言 / 眼眼"

        /** 命中阈值。越低越容易触发（也越容易误触发），真机实测后调过。 */
        private const val KEYWORDS_THRESHOLD = 0.25f

        /** 关键词加权分：给唤醒词加偏置，抵抗嘈杂环境。 */
        private const val KEYWORDS_SCORE = 2.0f
    }

    private val lock = Any()

    @Volatile
    var state: WakeWordState = WakeWordState.IDLE
        private set

    @Volatile
    var errorText: String? = null
        private set

    /** 私有目录里的模型位置（加载成功后才有意义）。 */
    @Volatile
    var modelDir: File? = null
        private set

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    /** 加载过程中被关掉开关：loader 自己把刚建好的模型释放掉，不留后台残留。 */
    @Volatile
    private var cancelRequested = false

    /** 采集线程每 64 ms 喂进来的 PCM（16 kHz / 16-bit 单声道）。 */
    fun accept(samples: ShortArray, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            val s = spotter
            val st = stream
            if (s == null || st == null || state != WakeWordState.READY) return
            val f = FloatArray(count)
            for (i in 0 until count) f[i] = samples[i] / 32768.0f
            try {
                st.acceptWaveform(f, AudioCaptureEngine.SAMPLE_RATE)
                while (s.isReady(st)) {
                    s.decode(st)
                    val r = s.getResult(st)
                    val keyword = r?.keyword
                    if (!keyword.isNullOrEmpty()) {
                        Log.i(
                            TAG,
                            "命中唤醒词「$keyword」 tokens=${r.tokens?.joinToString(" ")} " +
                                    "timestamps=${r.timestamps?.joinToString(",")}"
                        )
                        // 复位，避免同一句话重复触发；后续帧继续听（录音期间 ListenController 不再喂帧）
                        s.reset(st)
                        onWake(keyword)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "KWS 解码异常：${t.javaClass.simpleName} - ${t.message}")
            }
        }
    }

    /** 清空解码状态（一次录音结束后调用，避免残留半截关键词）。 */
    fun resetStream() {
        synchronized(lock) {
            val s = spotter
            val st = stream
            if (s == null || st == null) return
            try {
                s.reset(st)
            } catch (t: Throwable) {
                Log.w(TAG, "KWS reset 失败：${t.javaClass.simpleName}")
            }
        }
    }

    /** 开始加载。已经加载/正在加载时直接返回。 */
    fun start() {
        synchronized(lock) {
            if (state == WakeWordState.READY || state == WakeWordState.LOADING) return
            state = WakeWordState.LOADING
            errorText = null
            cancelRequested = false
        }
        val t = Thread({ loadBlocking() }, "yanyan-kws-loader")
        t.isDaemon = true
        t.priority = Thread.NORM_PRIORITY - 1
        t.start()
    }

    private fun loadBlocking() {
        val startedAt = System.currentTimeMillis()
        try {
            val dir = installModel()
            modelDir = dir
            val feat = FeatureConfig()
            feat.sampleRate = AudioCaptureEngine.SAMPLE_RATE
            feat.featureDim = 80
            val transducer = OnlineTransducerModelConfig()
            transducer.encoder = File(dir, ENCODER).absolutePath
            transducer.decoder = File(dir, DECODER).absolutePath
            transducer.joiner = File(dir, JOINER).absolutePath
            val modelConfig = OnlineModelConfig()
            modelConfig.transducer = transducer
            modelConfig.tokens = File(dir, TOKENS).absolutePath
            modelConfig.numThreads = 2
            modelConfig.debug = false
            modelConfig.provider = "cpu"
            modelConfig.modelType = "zipformer2"
            val config = KeywordSpotterConfig()
            config.featConfig = feat
            config.modelConfig = modelConfig
            config.maxActivePaths = 4
            config.keywordsFile = File(dir, KEYWORDS).absolutePath
            config.keywordsScore = KEYWORDS_SCORE
            config.keywordsThreshold = KEYWORDS_THRESHOLD
            config.numTrailingBlanks = 2

            // 模型在私有目录（绝对路径），必须把 AssetManager 传 null：
            // 传 non-null 时原生层会先走 asset 读取，对绝对路径直接 fatal（真机实测踩到，进程 abort）。
            val spot = KeywordSpotter(null, config)
            val st = spot.createStream("")
            val cancelled = synchronized(lock) {
                if (cancelRequested) {
                    true
                } else {
                    spotter = spot
                    stream = st
                    state = WakeWordState.READY
                    false
                }
            }
            if (cancelled) {
                // 加载期间开关被关掉了：刚建好的模型立刻释放，不留残留
                try {
                    st.release()
                    spot.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "取消加载时释放 KWS 失败：${t.javaClass.simpleName}")
                }
                Log.i(TAG, "加载被取消（开关已关），已释放刚创建的模型")
                return
            }
            Log.i(
                TAG,
                "KWS 就绪：${System.currentTimeMillis() - startedAt} ms，模型目录=$dir，" +
                        "关键词=$KEYWORD_DISPLAY，threshold=$KEYWORDS_THRESHOLD score=$KEYWORDS_SCORE"
            )
        } catch (t: Throwable) {
            synchronized(lock) {
                spotter = null
                stream = null
                state = WakeWordState.ERROR
                errorText = "${t.javaClass.simpleName}${t.message?.let { " - $it" } ?: ""}"
            }
            Log.e(TAG, "KWS 加载失败：${errorText}")
        }
    }

    /**
     * 释放模型与流，回到 IDLE。调用前应确保采集线程已停（没人再喂帧）。
     * 加载线程不在这里 join（不能卡主线程）：置 cancelRequested，由 loader 自己收尾。
     */
    fun release() {
        val spot: KeywordSpotter?
        val st: OnlineStream?
        synchronized(lock) {
            spot = spotter
            st = stream
            spotter = null
            stream = null
            cancelRequested = true
            if (state != WakeWordState.ERROR) state = WakeWordState.IDLE
        }
        try {
            st?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "释放 KWS stream 失败：${t.javaClass.simpleName}")
        }
        try {
            spot?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "释放 KWS 失败：${t.javaClass.simpleName}")
        }
        if (spot != null || st != null) Log.i(TAG, "KWS 已释放")
    }

    fun stateText(): String = when (state) {
        WakeWordState.IDLE -> "未加载（监听开关关着时不占内存、不上麦克风）"
        WakeWordState.LOADING -> "正在加载「砚砚」唤醒词…"
        WakeWordState.READY -> "已加载，正在听「$KEYWORD_DISPLAY」"
        WakeWordState.ERROR -> "加载失败：${errorText ?: "未知原因"}"
    }

    // ------------------------------------------------------------ assets → 私有目录

    /**
     * 首次启用（或 assets 版本变化）时把模型从 assets 复制到 filesDir/kws-model。
     * 复制到临时文件再 rename，避免中途被杀留下半个模型。
     */
    private fun installModel(): File {
        val dir = File(app.filesDir, "kws-model")
        val marker = File(dir, "assets_version.txt")
        if (marker.isFile && marker.readText().trim() == ASSET_VERSION &&
            REQUIRED.all { File(dir, it).isFile && File(dir, it).length() > 0 }
        ) {
            Log.i(TAG, "模型已在私有目录（版本 $ASSET_VERSION），跳过复制")
            return dir
        }
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建模型目录 $dir")
        for (name in REQUIRED) {
            val tmp = File(dir, "$name.tmp")
            val dst = File(dir, name)
            app.assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
        }
        marker.writeText(ASSET_VERSION)
        Log.i(
            TAG,
            "模型已从 assets 复制到私有目录：$dir（共 ${REQUIRED.sumOf { File(dir, it).length() } / 1024} KB）"
        )
        return dir
    }
}
