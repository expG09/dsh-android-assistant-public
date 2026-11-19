package com.yanyan.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 一次「按住说话」的结果。file 为空表示没留下文件（录音太短被丢弃）。 */
data class RecordingResult(
    val file: File?,
    val durationMs: Long,
    val bytes: Long,
    val reason: String,
    val tooShort: Boolean = false,
)

/**
 * M3a 录音链路：全进程唯一持有 AudioRecord 的地方。
 *
 * 两种模式共用同一条 AudioRecord，避免「按下按钮」那一刻才去初始化麦克风：
 * - MONITOR：监听开关打开后的常驻采集，帧读出来丢掉。
 *   M3b 接 KWS 时只换这里对帧的处理，不动 AudioRecord 的生命周期。
 * - RECORD：按住说话，把同一线程读到的 PCM 写成 16 kHz / 16-bit / 单声道 WAV。
 *
 * 生命周期由 [ListenController] 管：开关关掉必须 stop() + release()，不是只停读。
 */
class AudioCaptureEngine(
    private val onError: (String) -> Unit,
    private val onRecordDone: (RecordingResult) -> Unit,
    /** M3b：采集「持续没有有效数据」时通知上层重建（watchdog，只在真卡住时触发）。 */
    private val onStalled: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "AudioCapture"

        const val SAMPLE_RATE = 16_000
        const val BITS_PER_SAMPLE = 16

        /** 单次录音上限（M3 文档 2026-11-16 放宽：按住说话最长 60 秒）。 */
        const val MAX_RECORD_MS = 60_000L
        /** 连续静音多久自动收尾（M3 文档 2026-11-16 放宽：800 ms → 1500 ms，长句思考不截断）。 */
        const val SILENCE_STOP_MS = 1_500L
        /** 录音开始后至少录这么久才允许被静音判据收尾（避免刚按下就结束）。 */
        const val SILENCE_MIN_TOTAL_MS = 900L
        /** 静音判据：一帧 PCM 的 RMS 低于这个值算静音。 */
        const val SILENCE_RMS_THRESHOLD = 160.0
        /** 短于这个时长的录音没有意义，直接删掉。 */
        const val MIN_USEFUL_MS = 300L
        /** watchdog：连续这么久没有有效数据（read=0 或全零）且确认不在 RECORDING，才判定卡死。 */
        const val STALL_MS = 5_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    var recording: Boolean = false
        private set

    /** 当前录音已写入的时长（毫秒），UI 每 100ms 读它。 */
    @Volatile
    var recordedMs: Long = 0L
        private set

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private var recorder: WavWriter? = null
    private var pendingFile: File? = null
    private var stopRequested = false
    private var stopRequestReason = "released"
    private var totalSamples = 0L
    private var silenceSamples = 0L

    /** 本次录音的时长上限（按住说话 60 秒；唤醒后自动录 30 秒）。 */
    @Volatile
    private var maxRecordMs = MAX_RECORD_MS

    /** 本次录音「至少录这么久」才允许被静音判据收尾。 */
    @Volatile
    private var silenceMinTotalMs = SILENCE_MIN_TOTAL_MS

    /**
     * M3b：唤醒词的流式帧入口。采集线程在**没在录音**时把每一帧 PCM 交给它（KWS 解码）。
     * 只在监听开关打开、引擎在跑时才有值；关开关 / 让出麦克风时为 null。
     */
    @Volatile
    var frameSink: ((ShortArray, Int) -> Unit)? = null

    // ---- M3b 诊断 / watchdog 统计（采集线程独占写，只在心跳日志里读）
    private var framesRead = 0L
    private var bytesRead = 0L
    private var zeroReadCount = 0L
    private var lastValidDataAt = 0L
    private var lastHeartbeatAt = 0L
    private var rmsSum = 0.0
    private var rmsCount = 0L
    private var rmsPeak = 0.0
    private var stallDetected = false

    val isRunning: Boolean get() = running

    val audioSessionId: Int get() = audioRecord?.audioSessionId ?: 0

    /** 打开麦克风并起采集线程。返回 null 表示成功，否则是给人看的中文原因。 */
    // 权限在 ListenController.setEnabled 里先查过；这里再兜一层 SecurityException（见下面的 catch）
    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (running) return null
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return "设备不支持 16 kHz 单声道录音（minBufferSize=$minBuf）"
        val bufBytes = minOf(maxOf(minBuf * 2, 8192), 64000)
        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes
            )
        } catch (t: Throwable) {
            return "创建 AudioRecord 失败：${t.javaClass.simpleName}${t.message?.let { " - $it" } ?: ""}"
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            val state = ar.state
            ar.release()
            return "AudioRecord 初始化失败（state=$state）"
        }
        try {
            ar.startRecording()
        } catch (t: Throwable) {
            ar.release()
            return "无法开始录音：${t.javaClass.simpleName}${t.message?.let { " - $it" } ?: ""}"
        }
        audioRecord = ar
        running = true
        framesRead = 0L
        bytesRead = 0L
        zeroReadCount = 0L
        stallDetected = false
        lastValidDataAt = android.os.SystemClock.elapsedRealtime()
        lastHeartbeatAt = lastValidDataAt
        val t = Thread({ captureLoop(ar) }, "yanyan-capture")
        t.isDaemon = true
        thread = t
        t.start()
        Log.i(TAG, "麦克风已打开 session=${ar.audioSessionId} minBuf=$minBuf bufBytes=$bufBytes")
        return null
    }

    /** 真的停掉并释放麦克风。线程若卡在 read()，靠 AudioRecord.stop() 把它踢出来。 */
    fun stop() {
        val ar = audioRecord
        running = false
        try {
            ar?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord.stop 失败：${t.javaClass.simpleName}")
        }
        val t = thread
        if (t != null) {
            try {
                t.join(1500)
                if (t.isAlive) t.join(1000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "等待采集线程被中断")
            }
        }
        thread = null
        if (t != null && t.isAlive) {
            // 极罕见：read() 没被 stop() 踢出来。宁可不收尾也别和采集线程抢 writer。
            Log.e(TAG, "采集线程仍未退出，跳过收尾与 release")
            running = false
            return
        }
        // 采集线程已退出，这里收尾不会和它抢 recorder
        synchronized(lock) {
            if (recorder != null) finishRecordingLocked("engine_stopped")
            pendingFile = null
            stopRequested = false
        }
        try {
            ar?.release()
        } catch (t2: Throwable) {
            Log.w(TAG, "AudioRecord.release 失败：${t2.javaClass.simpleName}")
        }
        audioRecord = null
        recording = false
        Log.i(TAG, "麦克风已停止并释放")
    }

    /**
     * 请求开始一次录音；false 表示当前不满足条件（没在监听 / 已在录）。
     *
     * @param maxMs 本次录音的时长上限（按住说话 60 秒，唤醒后 30 秒）
     * @param silenceMinTotalMs 至少录这么久才允许「连续静音 1500 ms」提前收尾
     *        （按住说话 900 ms；唤醒后 5000 ms，保证刚喊完唤醒词后的停顿不会立刻收尾）
     */
    fun beginRecording(
        file: File,
        maxMs: Long = MAX_RECORD_MS,
        silenceMinTotalMs: Long = SILENCE_MIN_TOTAL_MS,
    ): Boolean {
        synchronized(lock) {
            if (!running) return false
            if (recorder != null || pendingFile != null || recording) return false
            pendingFile = file
            stopRequested = false
            stopRequestReason = "released"
            totalSamples = 0
            silenceSamples = 0
            recordedMs = 0
            this.maxRecordMs = maxMs
            this.silenceMinTotalMs = silenceMinTotalMs
            return true
        }
    }

    /** 当前这次录音的时长上限（给界面显示 / 60 秒还是 30 秒）。 */
    fun activeMaxMs(): Long = if (recording) maxRecordMs else MAX_RECORD_MS

    /**
     * 请求收尾。真正的写文件在采集线程里完成。
     * @param reason 收尾原因，会写进 [RecordingResult.reason]（"released" 松开按钮 / "manual_stop" 点停止）
     */
    fun requestStop(reason: String = "released") {
        synchronized(lock) {
            stopRequested = true
            stopRequestReason = reason
        }
    }

    // ------------------------------------------------------------ 采集线程

    private fun captureLoop(ar: AudioRecord) {
        val frame = ShortArray(1024)
        var zeroReads = 0
        Log.i(TAG, "采集线程启动")
        while (running) {
            // 1) 处理「开始录音」请求
            val pending = synchronized(lock) { pendingFile.also { pendingFile = null } }
            if (pending != null) {
                val writer = try {
                    WavWriter(pending)
                } catch (t: Throwable) {
                    Log.e(TAG, "打开 WAV 失败", t)
                    null
                }
                if (writer == null) {
                    postError("创建录音文件失败")
                } else {
                    synchronized(lock) {
                        recorder = writer
                        recording = true
                        totalSamples = 0
                        silenceSamples = 0
                        recordedMs = 0
                    }
                    Log.i(TAG, "开始写 WAV -> ${pending.name}")
                }
            }

            // 2) 松手请求（此时可能已经录了一点点）
            if (synchronized(lock) { recording && stopRequested }) {
                synchronized(lock) { finishRecordingLocked("released") }
                continue
            }

            // 3) 读一帧
            val n = try {
                ar.read(frame, 0, frame.size)
            } catch (t: Throwable) {
                if (running) {
                    Log.e(TAG, "读取麦克风失败", t)
                    postError("麦克风读取失败：${t.javaClass.simpleName}")
                }
                break
            }
            if (n < 0) {
                if (running) {
                    Log.e(TAG, "AudioRecord.read 返回 $n")
                    postError("麦克风读取错误（$n）")
                }
                break
            }
            if (n == 0) {
                zeroReads++
                if (zeroReads > 300) {
                    postError("麦克风长时间读不到数据")
                    break
                }
                markStallCheck(ar, 0, false)
                if (stallDetected) break
                continue
            }
            zeroReads = 0

            // M3b 诊断：统计帧与「有效数据」时间；全零帧也算没有数据
            var nonZero = false
            for (i in 0 until n) {
                if (frame[i].toInt() != 0) {
                    nonZero = true
                    break
                }
            }
            markStallCheck(ar, n, nonZero)
            // 心跳用的电平统计（dBFS，便于判断「通知音前后麦克风有没有被压低」）
            run {
                var sumSq = 0.0
                for (i in 0 until n) {
                    val v = frame[i].toDouble()
                    sumSq += v * v
                }
                val rms = kotlin.math.sqrt(sumSq / n) / 32768.0
                rmsSum += rms
                rmsCount++
                if (rms > rmsPeak) rmsPeak = rms
            }
            if (stallDetected) break

            if (!synchronized(lock) { recording }) {
                // M3b：没在录音时，这一帧交给唤醒词引擎；录音期间不喂，省 CPU 也不重复触发。
                frameSink?.invoke(frame, n)
                continue
            }

            // 4) 写样本
            synchronized(lock) {
                recorder?.write(frame, n)
                totalSamples += n
                recordedMs = totalSamples * 1000 / SAMPLE_RATE
            }

            // 5) 静音 / 时长判据
            var finalizeReason: String? = null
            synchronized(lock) {
                if (recording && recorder != null) {
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = frame[i].toDouble()
                        sum += v * v
                    }
                    val rms = sqrt(sum / n)
                    if (rms < SILENCE_RMS_THRESHOLD) silenceSamples += n else silenceSamples = 0
                    val totalMs = totalSamples * 1000 / SAMPLE_RATE
                    val silentMs = silenceSamples * 1000 / SAMPLE_RATE
                    if (stopRequested) {
                        finalizeReason = stopRequestReason
                    } else if (totalMs >= maxRecordMs) {
                        finalizeReason = "max_duration"
                    } else if (totalMs >= silenceMinTotalMs && silentMs >= SILENCE_STOP_MS) {
                        finalizeReason = "silence"
                    }
                }
            }
            if (finalizeReason != null) {
                synchronized(lock) { finishRecordingLocked(finalizeReason!!) }
            }
        }
        Log.i(TAG, "采集线程退出")
    }

    /** 必须在持锁时调用，且只由采集线程（或线程已退出后的主线程）调用。 */
    private fun finishRecordingLocked(reason: String) {
        val w = recorder ?: return
        recorder = null
        recording = false
        stopRequested = false
        maxRecordMs = MAX_RECORD_MS
        silenceMinTotalMs = SILENCE_MIN_TOTAL_MS
        val ms = totalSamples * 1000 / SAMPLE_RATE
        try {
            w.close()
        } catch (t: Throwable) {
            Log.e(TAG, "WAV 收尾失败", t)
        }
        recordedMs = ms
        val bytes = w.dataBytes
        Log.i(TAG, "录音结束 reason=$reason durationMs=$ms bytes=$bytes file=${w.file.name}")
        if (ms < MIN_USEFUL_MS) {
            w.file.delete()
            postResult(RecordingResult(null, ms, bytes, reason, tooShort = true))
        } else {
            postResult(RecordingResult(w.file, ms, bytes, reason))
        }
    }

    private fun postResult(result: RecordingResult) {
        main.post { onRecordDone(result) }
    }

    private fun postError(message: String) {
        main.post { onError(message) }
    }

    /**
     * M3b 采集诊断 + watchdog。
     *
     * 每 5 秒打一条心跳（帧数 / 字节 / 零读次数 / 距上次有效数据多久 / AudioRecord 状态），
     * 供「通知音前后唤醒失效」这类问题取证；只有「≥5 秒没有任何有效数据 **且**
     * getRecordingState() 已经不是 RECORDING」才判定为真卡死，通知上层重建。
     * 通知音那种「系统短暂压低/静音麦克风」不会命中这个条件（状态仍是 RECORDING），
     * 所以不会白断一次采集，也不会打断正在进行的录音。
     */
    private fun markStallCheck(ar: AudioRecord, n: Int, valid: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (n > 0) {
            framesRead++
            bytesRead += n.toLong() * 2
        } else {
            zeroReadCount++
        }
        if (valid) lastValidDataAt = now

        if (now - lastHeartbeatAt >= 5_000L) {
            lastHeartbeatAt = now
            val avgDbfs = if (rmsCount > 0) {
                20.0 * kotlin.math.log10((rmsSum / rmsCount).coerceAtLeast(1e-9))
            } else {
                -120.0
            }
            val peakDbfs = 20.0 * kotlin.math.log10(rmsPeak.coerceAtLeast(1e-9))
            Log.i(
                TAG,
                "采集心跳 frames=$framesRead bytes=$bytesRead zeroReads=$zeroReadCount " +
                        "无有效数据=${now - lastValidDataAt}ms recording=$recording " +
                        "state=${ar.recordingState} " +
                        String.format(java.util.Locale.CHINA, "电平avg=%.1fdBFS peak=%.1fdBFS", avgDbfs, peakDbfs)
            )
            rmsSum = 0.0
            rmsCount = 0L
            rmsPeak = 0.0
        }

        if (!stallDetected && !recording && now - lastValidDataAt >= STALL_MS) {
            val state = ar.recordingState
            if (state != AudioRecord.RECORDSTATE_RECORDING) {
                stallDetected = true
                Log.e(
                    TAG,
                    "采集疑似卡死：${now - lastValidDataAt} ms 没有有效数据，" +
                            "recordingState=$state（不是 RECORDING）→ 请求重建"
                )
                main.post { onStalled("mic_capture_stalled") }
            }
        }
    }

    // ------------------------------------------------------------ WAV

    /** 先占 44 字节头，收尾时回填长度；写的是 little-endian PCM16 单声道。 */
    private class WavWriter(val file: File) : Closeable {
        private val raf: RandomAccessFile
        var dataBytes: Long = 0L
            private set

        init {
            file.parentFile?.mkdirs()
            raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            raf.write(ByteArray(HEADER_BYTES))
        }

        fun write(samples: ShortArray, count: Int) {
            var i = 0
            while (i < count) {
                val n = minOf(count - i, 1024)
                val pcm = ByteArray(n * 2)
                var o = 0
                for (j in 0 until n) {
                    val v = samples[i + j].toInt()
                    pcm[o++] = (v and 0xFF).toByte()
                    pcm[o++] = ((v shr 8) and 0xFF).toByte()
                }
                raf.write(pcm, 0, o)
                i += n
            }
            dataBytes += count * 2L
        }

        override fun close() {
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt((36 + dataBytes).toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // 单声道
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2) // byteRate
            header.putShort(2) // blockAlign
            header.putShort(BITS_PER_SAMPLE.toShort())
            header.put("data".toByteArray())
            header.putInt(dataBytes.toInt())
            raf.seek(0)
            raf.write(header.array())
            raf.close()
        }

        companion object {
            const val HEADER_BYTES = 44
        }
    }
}
