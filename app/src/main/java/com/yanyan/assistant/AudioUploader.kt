package com.yanyan.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 一段录音在「上传 → 识别 → 出结果」这条链路上的状态。 */
enum class AudioUploadPhase {
    /** 正在往电脑端发音频块 */
    UPLOADING,

    /** 音频已发完，等电脑端调 ASR 的结果 */
    WAITING_ASR,

    /** 拿到识别文本 */
    DONE,

    /** 上传/识别失败；用户可以点一下重试 */
    FAILED,

    /** 超过 8 MiB / 120 秒，本地拦下，根本没发请求 */
    BLOCKED,
}

/**
 * 调试页里的一条语音记录。
 *
 * key 是稳定标识（一次录音一条），wireId 是每次真正上传时生成的协议 id（要求全局唯一）。
 * 状态字段会在采集线程/上传线程里改，界面在主线程读，所以都标 @Volatile。
 */
class AudioUploadItem(
    val key: String,
    @Volatile var wireId: String,
    val file: File,
    val recordedMs: Long,
    val recordedBytes: Long,
    @Volatile var phase: AudioUploadPhase,
    @Volatile var text: String? = null,
    /** 给人看的状态/失败原因（不含音频内容）。 */
    @Volatile var note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var updatedAt: Long = System.currentTimeMillis(),
    /** 防止连点重试同时跑两遍 */
    @Volatile var attempting: Boolean = false,
)

/**
 * 进程级语音记录表：谁录的、传到哪一步、结果是什么。
 *
 * 上传不依赖界面（录音只可能从界面发起，但上传过程会继续，界面可以随时关掉再打开），
 * 所以状态放在这里，调试页打开时先读快照再订阅增量。
 */
object AudioUploadHub {
    private const val MAX_ITEMS = 20

    private val items = ArrayList<AudioUploadItem>()
    private val listeners = CopyOnWriteArrayList<(AudioUploadItem) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (AudioUploadItem) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: (AudioUploadItem) -> Unit) {
        listeners.remove(listener)
    }

    fun snapshot(): List<AudioUploadItem> = synchronized(items) { items.toList() }

    fun find(key: String): AudioUploadItem? =
        synchronized(items) { items.firstOrNull { it.key == key } }

    internal fun add(item: AudioUploadItem) {
        synchronized(items) {
            items.add(item)
            while (items.size > MAX_ITEMS) items.removeAt(0)
        }
        notifyChanged(item)
    }

    /** 状态变了：派发到主线程，界面只读不写。 */
    internal fun notifyChanged(item: AudioUploadItem) {
        item.updatedAt = System.currentTimeMillis()
        main.post { listeners.forEach { it(item) } }
    }
}

/**
 * M4：录音结束自动上传 + 电脑端 ASR 结果回填。
 *
 * 设计约束（见 docs/dev/M4 模块开发文档.md）：
 * - 录音结束自动触发；8 MiB / 120 秒在手机端先拦，**不发请求**；
 * - 状态依次「上传中…」「识别中…」「结果」，结果是识别文本；
 * - 失败显示「上传失败，点重试」，点一次重发一次；
 * - 断连导致的失败**不自动重传**（避免重复计费），等用户点。
 */
object AudioUploader : ChannelListener {
    private const val TAG = "AudioUpload"

    /** 硬上限：与 DeepSeek ASR 的限制一致（8 MiB / 120 秒）。 */
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_DURATION_MS = 120_000L

    /** 每块原始字节数：48 KiB，base64 后恰好 65536 字符（64 KiB），符合「每块 ≤64 KiB」。 */
    private const val CHUNK_BYTES = 48 * 1024

    /** 等电脑端识别结果的最长时间（服务端 30 秒超时、网络错误最多再重试一次）。 */
    private const val ASR_RESULT_TIMEOUT_MS = 75_000L

    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(0)
    private val items = HashMap<String, AudioUploadItem>()
    private val timeouts = HashMap<String, Runnable>()
    private var app: Context? = null

    /** 上传是 IO 活，单独一条线程串行做；不占主线程也不跟采集线程抢。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yanyan-audio-upload").apply { isDaemon = true }
    }

    /** 由 KeepAliveService.onCreate 调用（服务在，上传链路就在，不新增 Service）。 */
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        ChannelHub.addListener(this)
        ListenController.addResultListener { result -> onRecordingDone(result) }
        // M3b：启动时先做一次兜底清理（过期 / 超 200 MB），再打日志
        RecordingStore.prune(app!!)
        val (count, bytes) = RecordingStore.usage(app!!)
        Log.i(
            TAG,
            "已就绪：录音结束自动上传（上限 ${MAX_BYTES / 1024 / 1024} MiB / " +
                    "${MAX_DURATION_MS / 1000} 秒，分块 ${CHUNK_BYTES / 1024} KiB）；" +
                    "录音目录现存 $count 段 / ${bytes / 1024} KB"
        )
    }

    // ------------------------------------------------------------ 入口

    private fun onRecordingDone(result: RecordingResult) {
        val file = result.file
        if (file == null) {
            Log.i(TAG, "录音太短已丢弃，不触发上传")
            return
        }
        val item = AudioUploadItem(
            key = newKey(),
            wireId = "",
            file = file,
            recordedMs = result.durationMs,
            recordedBytes = file.length(),
            phase = AudioUploadPhase.UPLOADING,
            note = "上传中…",
        )
        synchronized(items) { items[item.key] = item }
        AudioUploadHub.add(item)
        Log.i(
            TAG,
            "录音结束，开始上传 size=${item.recordedBytes} dur=${result.durationMs} file=${file.name}"
        )
        item.attempting = true
        executor.execute { attemptUpload(item) }
    }

    /** 用户点「上传失败，点重试」：只重发这一条，重发一次就一次。 */
    fun retry(key: String) {
        val item = synchronized(items) { items[key] } ?: return
        if (item.phase != AudioUploadPhase.FAILED && item.phase != AudioUploadPhase.BLOCKED) return
        if (item.attempting) return
        item.attempting = true
        item.text = null
        publish(item, AudioUploadPhase.UPLOADING, "上传中…")
        executor.execute { attemptUpload(item) }
    }

    // ------------------------------------------------------------ 上传主流程（上传线程）

    private fun attemptUpload(item: AudioUploadItem) {
        RecordingStore.markActive(item.file)
        try {
            if (!item.file.isFile || item.file.length() <= WAV_HEADER_BYTES) {
                fail(item, "录音文件读不到")
                return
            }
            val bytes = item.file.length()
            val durationMs = wavDurationMs(item.file)

            // 先在本地拦：超限不发请求，避免白跑一趟 ASR
            if (bytes > MAX_BYTES) {
                block(
                    item,
                    "音频 ${formatSize(bytes)}，超过 ${MAX_BYTES / 1024 / 1024} MB 上限，已本地拦下（未上传）"
                )
                return
            }
            if (durationMs > MAX_DURATION_MS) {
                block(
                    item,
                    "音频 ${durationMs / 1000} 秒，超过 ${MAX_DURATION_MS / 1000} 秒上限，已本地拦下（未上传）"
                )
                return
            }

            val channel = KeepAliveService.channel
            if (channel == null || ChannelHub.phase != ChannelPhase.CONNECTED) {
                fail(item, "通道没连上，上传失败")
                return
            }

            val wireId = newWireId()
            item.wireId = wireId
            publish(item, AudioUploadPhase.UPLOADING, "上传中…")
            if (!channel.sendAudioStart(wireId, bytes, durationMs)) {
                fail(item, "上传失败")
                return
            }

            var seqNo = 0
            var sent = 0L
            item.file.inputStream().use { input ->
                val buf = ByteArray(CHUNK_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (ChannelHub.phase != ChannelPhase.CONNECTED) {
                        fail(item, "连接断开，上传中断")
                        return
                    }
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                    if (!channel.sendAudioChunk(wireId, seqNo, b64)) {
                        fail(item, "上传中断，上传失败")
                        return
                    }
                    seqNo += 1
                    sent += n
                }
            }
            if (sent != bytes) {
                fail(item, "上传中断，上传失败")
                return
            }
            if (!channel.sendAudioEnd(wireId)) {
                fail(item, "上传中断，上传失败")
                return
            }
            Log.i(TAG, "音频已发完 id=$wireId chunks=$seqNo bytes=$sent")
            publish(item, AudioUploadPhase.WAITING_ASR, "识别中…")
            scheduleTimeout(item)
        } catch (t: Throwable) {
            Log.e(TAG, "上传异常：${t.javaClass.simpleName}")
            fail(item, "上传失败")
        } finally {
            item.attempting = false
            // 音频已经发完（或失败退出），不再占用文件；成功识别后由 onAsrResult 删除
            RecordingStore.unmarkActive(item.file)
        }
    }

    // ------------------------------------------------------------ 电脑端结果

    override fun onAsrResult(id: String, text: String, durationMs: Long, costMs: Long) {
        cancelTimeout(id)
        val item = findByWireId(id) ?: return
        Log.i(TAG, "识别完成 id=$id textLen=${text.length} dur=$durationMs cost=$costMs")
        item.text = text
        publish(item, AudioUploadPhase.DONE, null)
        // M3b：识别成功即删本地 WAV（失败/被拦的保留，重试成功后再删）
        if (RecordingStore.deleteAfterSuccess(item.file)) {
            app?.let { RecordingStore.prune(it) }
        }
    }

    override fun onAsrError(id: String, code: String, message: String) {
        cancelTimeout(id)
        val item = findByWireId(id) ?: return
        Log.w(TAG, "识别失败 id=$id code=$code")
        fail(item, "识别失败（${asrErrorLabel(code)}）")
    }

    override fun onPhaseChanged(phase: ChannelPhase, detail: String) {
        // 断连时在飞的这条判失败，但绝不自动重传（重复计费的风险由用户点重试来承担）
        if (phase == ChannelPhase.CONNECTED) return
        val inFlight = synchronized(items) {
            items.values.filter {
                it.phase == AudioUploadPhase.UPLOADING || it.phase == AudioUploadPhase.WAITING_ASR
            }
        }
        for (item in inFlight) {
            cancelTimeout(item.wireId)
            fail(item, "连接断开，上传中断")
        }
    }

    private fun asrErrorLabel(code: String): String = when (code) {
        "no_key" -> "电脑端没配密钥"
        "incomplete" -> "音频没收全"
        "too_large" -> "音频超过 8 MB"
        "too_long" -> "音频超过 120 秒"
        "unknown_id" -> "电脑端没收到这段音频"
        "duplicate_id" -> "上传 id 重复"
        "busy" -> "电脑端还在处理上一段"
        "auth_failed" -> "电脑端密钥无效"
        "rate_limited" -> "电脑端被限流"
        "timeout" -> "电脑端调用超时"
        "network_error" -> "电脑端连不上 ASR"
        "upstream_error", "bad_response" -> "电脑端调用失败"
        "server_error" -> "电脑端内部错误"
        else -> code.ifBlank { "未知原因" }
    }

    // ------------------------------------------------------------ 状态/结果发布

    private fun publish(item: AudioUploadItem, phase: AudioUploadPhase, note: String?) {
        item.phase = phase
        item.note = note
        AudioUploadHub.notifyChanged(item)
    }

    private fun fail(item: AudioUploadItem, reason: String) {
        Log.w(TAG, "上传/识别失败 key=${item.key} phase=${item.phase} reason=$reason")
        publish(item, AudioUploadPhase.FAILED, reason)
    }

    private fun block(item: AudioUploadItem, reason: String) {
        // 记实际文件大小（不是录音那一刻的大小），方便核对拦截判据
        Log.i(TAG, "本地拦截 key=${item.key} size=${item.file.length()} reason=$reason")
        publish(item, AudioUploadPhase.BLOCKED, reason)
    }

    // ------------------------------------------------------------ 超时

    private fun scheduleTimeout(item: AudioUploadItem) {
        val wireId = item.wireId
        val runnable = Runnable {
            synchronized(timeouts) { timeouts.remove(wireId) }
            if (item.wireId == wireId && item.phase == AudioUploadPhase.WAITING_ASR) {
                fail(item, "识别超时，没等到电脑回应")
            }
        }
        val old = synchronized(timeouts) {
            val previous = timeouts.put(wireId, runnable)
            previous
        }
        old?.let { main.removeCallbacks(it) }
        main.postDelayed(runnable, ASR_RESULT_TIMEOUT_MS)
    }

    private fun cancelTimeout(wireId: String) {
        val runnable = synchronized(timeouts) { timeouts.remove(wireId) } ?: return
        main.removeCallbacks(runnable)
    }

    // ------------------------------------------------------------ 小工具

    private fun findByWireId(wireId: String): AudioUploadItem? =
        synchronized(items) { items.values.firstOrNull { it.wireId == wireId } }

    private fun newKey(): String = "k" + System.currentTimeMillis() + "-" + seq.incrementAndGet()

    /** 协议要求 id 全局唯一；重试会换一个新的 wireId（旧 id 的结果不会被张冠李戴）。 */
    private fun newWireId(): String = "a" + System.currentTimeMillis() + "-" + seq.incrementAndGet()

    /**
     * 从 WAV 头里读 data 块长度算时长（16000 Hz * 16-bit 单声道 = 32000 B/s）。
     *
     * 不能假设「data 在偏移 40」：真实 WAV 可以带 LIST/INFO 等附加块（ffmpeg 生成的就有），
     * 所以按 RIFF 结构逐块走到 `data`。读不到就按「文件长度 - 44」兜底，交给大小判据。
     */
    private fun wavDurationMs(file: File): Long {
        val total = file.length()
        if (total <= WAV_HEADER_BYTES) return 0L
        var dataBytes = total - WAV_HEADER_BYTES
        try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(12)
                if (raf.read(head) == 12 &&
                    String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
                ) {
                    var offset = 12L
                    while (offset + 8 <= total) {
                        raf.seek(offset)
                        val hdr = ByteArray(8)
                        if (raf.read(hdr) != 8) break
                        val chunkId = String(hdr, 0, 4, Charsets.US_ASCII)
                        val chunkSize = leInt(hdr, 4)
                        if (chunkId == "data") {
                            val available = total - offset - 8
                            dataBytes = if (chunkSize in 1..available) chunkSize else available
                            break
                        }
                        // 块按 2 字节对齐：奇数长度后面有一个补齐字节
                        offset += 8 + chunkSize + (chunkSize and 1L)
                        if (chunkSize < 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读 WAV 头失败：${t.javaClass.simpleName}（按文件长度估算）")
        }
        if (dataBytes <= 0) return 0L
        return dataBytes * 1000 / (AudioCaptureEngine.SAMPLE_RATE * 2L)
    }

    /** 小端 4 字节（WAV 里的长度字段）→ 无符号 Long。 */
    private fun leInt(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFF) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                ((buf[offset + 2].toLong() and 0xFF) shl 16) or
                ((buf[offset + 3].toLong() and 0xFF) shl 24)

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private const val WAV_HEADER_BYTES = 44
}
