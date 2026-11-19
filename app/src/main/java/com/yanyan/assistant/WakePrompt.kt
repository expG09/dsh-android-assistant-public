package com.yanyan.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.random.Random

/**
 * M3b：命中唤醒词后「应一声」的短语音提示。
 *
 * 三段录音（`assets/wake/wake_1..3.mp3`，0.9–1.2 秒）随 APK 打包，不联网：
 * - 每次命中随机挑一段，并避免连续两次同一句（不然听起来像卡住了）；
 * - 播完（或失败/超时）才回调开录音，提示音不会盖住录音起始；
 * - 播放失败只记日志，不影响录音流程；
 * - 走 `USAGE_ASSISTANT` 通道（系统助手类的提示通道），不申请音频焦点、不打断别的应用。
 *
 * 由 [LocalStore.wakePromptEnabled] 控制开关，关掉时调用方根本不会走这里（完全静音）。
 */
object WakePrompt {
    private const val TAG = "WakePrompt"

    private const val DIR = "wake"
    private val FILES = arrayOf("wake_1.mp3", "wake_2.mp3", "wake_3.mp3")

    /** 播放失败/卡住时的兜底：最多等这么久就继续开录音。 */
    private const val MAX_WAIT_MS = 1_800L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var pendingDone: (() -> Unit)? = null
    private var timeoutTask: Runnable? = null

    /** 上一次播的是哪一句（只用于避免连续重复）。 */
    @Volatile
    var lastFile: String? = null
        private set

    /** 提示音正在播（唤醒词检测据此挂起/恢复）。 */
    val isPlaying: Boolean
        get() = player?.isPlaying == true

    /** 命中次数里各句分别播了几次（给报告/调试看随机性）。 */
    @Volatile
    var playCounts: String = ""
        private set
    private val counts = IntArray(FILES.size)

    /**
     * 随机播一句，播完（或失败）回调 [onDone]（主线程）。
     * 调用方要保证 device 仍在校验状态：回调里会重新检查开关/状态。
     */
    fun playRandom(ctx: Context, onDone: () -> Unit) {
        val app = ctx.applicationContext
        stopQuietly()
        // M6a：提示音（「我在 / 嗯？ / 在呢」）同样是我们的喇叭声，播的时候别让 KWS 听自己
        ListenController.suspendWakeDetection("wake_prompt")
        val idx = pickIndex()
        val name = FILES[idx]
        lastFile = name
        counts[idx] += 1
        playCounts = FILES.indices.joinToString(" / ") { "${FILES[it]}=${counts[it]}" }
        var done = false
        val finish = {
            if (!done) {
                done = true
                main.post {
                    cancelTimeout()
                    releasePlayer()
                    // M6a：提示音结束（播完 / 失败 / 超时都走这里）→ 恢复唤醒词检测
                    ListenController.resumeWakeDetection("wake_prompt_done")
                    onDone()
                }
            }
        }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val afd = app.assets.openFd("$DIR/$name")
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "提示音播放失败 what=$what extra=$extra（不影响录音）")
                finish()
                true
            }
            player = mp
            mp.prepareAsync()
            scheduleTimeout(finish)
            Log.i(TAG, "播放唤醒提示 $name（第 ${counts[idx]} 次；统计 $playCounts）")
        } catch (t: Throwable) {
            Log.w(TAG, "提示音启动失败：${t.javaClass.simpleName} - ${t.message}（不影响录音）")
            finish()
        }
    }

    /** 立刻停掉正在播的提示（关开关 / 服务停时用）。 */
    fun stop() {
        stopQuietly()
        ListenController.resumeWakeDetection("wake_prompt_stop")
    }

    private fun pickIndex(): Int {
        if (FILES.size == 1) return 0
        var i = Random.nextInt(FILES.size)
        if (lastFile != null) {
            // 最多重抽两次，保证连续两次一般不会同一句
            var guard = 0
            while (FILES[i] == lastFile && guard < 2) {
                i = Random.nextInt(FILES.size)
                guard++
            }
        }
        return i
    }

    private fun scheduleTimeout(finish: () -> Unit) {
        cancelTimeout()
        val runnable = Runnable {
            timeoutTask = null
            Log.w(TAG, "提示音超时（>${MAX_WAIT_MS} ms），直接继续录音")
            finish()
        }
        timeoutTask = runnable
        main.postDelayed(runnable, MAX_WAIT_MS)
    }

    private fun cancelTimeout() {
        val t = timeoutTask ?: return
        timeoutTask = null
        main.removeCallbacks(t)
    }

    private fun stopQuietly() {
        cancelTimeout()
        releasePlayer()
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try {
            if (p.isPlaying) p.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "停止提示音失败：${t.javaClass.simpleName}")
        }
        try {
            p.release()
        } catch (t: Throwable) {
            Log.w(TAG, "释放提示音失败：${t.javaClass.simpleName}")
        }
    }
}
