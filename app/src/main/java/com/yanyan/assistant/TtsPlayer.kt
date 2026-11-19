package com.yanyan.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 语音播放状态变化的监听（界面用它刷新播放图标 / 试听提示）。 */
interface TtsListener {
    /** 某条回复的语音状态变了（开始播 / 播完 / 被打断 / 缓存有了）。replyId 可能为 null（试听）。 */
    fun onTtsChanged(replyId: String?)
}

/**
 * M6a：语音回复的播放器（服务内，界面不在前台也照常播）。
 *
 * 链路：电脑 → `speakStart`（PCM 参数）→ `speakChunk`（base64 PCM，边收边播）→ `speakDone`。
 *
 * 实现要点：
 * - **流式播放**：AudioTrack（MODE_STREAM，24 kHz / 16-bit / 单声道，USAGE_ASSISTANT 不抢音频焦点）。
 *   收到一块就写一块，第一块到了就开始响；写盘与写音轨都在独立线程，不堵主线程。
 * - **边收边存**：PCM 落盘到 `<speakId>.pcm`，收完加 WAV 头转成 `<speakId>.wav`，供「点播放图标重播」，
 *   重播走 MediaPlayer 读本地文件，**不会再向电脑请求 TTS**。
 * - **打断**：录音开始 / 唤醒命中 / 点别的语音 → [interrupt] 立刻停播（AudioTrack.pause + 清队列），
 *   并把 `speakStop{id}` 发给电脑，停掉它那边在途的合成。
 * - **不新增 Service**：[init] 由 KeepAliveService 调用，播放器只是服务里的一个普通对象。
 */
object TtsPlayer : ChannelListener {
    private const val TAG = "TtsPlayer"

    /** 接口实测参数；speakStart 里带了就以帧里的为准。 */
    private const val DEFAULT_SAMPLE_RATE = 24000
    private const val DEFAULT_CHANNELS = 1
    private const val BYTES_PER_SAMPLE = 2

    /** AudioTrack 缓冲：2 秒。够吸收「上一句播完、下一句还没合成好」的空档。 */
    private const val TRACK_BUFFER_BYTES = 96 * 1024

    /** 收到 LOSS_TRANSIENT_CAN_DUCK 时压到这个音量（通知音很短，不停播只让路）。 */
    private const val FOCUS_DUCK_VOLUME = 0.75f

    /** 结束标记（队列里的哨兵）。 */
    private val END = ByteArray(0)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<TtsListener>()

    private var app: Context? = null

    /**
     * M6a 修正：音频焦点。
     *
     * 之前一版**完全不申请焦点**，也没有 [AudioManager.OnAudioFocusChangeListener]：
     * 念最后一句时来一条系统通知（短促的 transient 事件），AudioTrack 的写入被系统打断，
     * 写循环拿到 n<=0 就当成「这段结束了」收尾 —— 表现就是「语音直接没了」。
     *
     * 现在的口径：
     * - 整段回复开始播放前申请 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`（只在念这几秒里持有），播完 abandon；
     * - `LOSS_TRANSIENT` → 暂停（`AudioTrack.pause()`，写线程会阻塞在 write 上）；
     * - `LOSS_TRANSIENT_CAN_DUCK` → 压到 25% 继续念（通知音很短，不制造断点）；
     * - 焦点回来（`GAIN`）→ **自动续播**（`AudioTrack.play()` 从暂停位置继续），不重来、不丢尾巴；
     * - `LOSS`（永久，例如切到别的音乐 App）→ 停播 + 释放焦点，并清掉在途合成。
     */
    private var audioManager: AudioManager? = null

    @Volatile
    private var focusHeld = false

    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handleFocusChange(change)
    }

    /** replyId -> 该条回复的语音段 id（按到达顺序；重播时依次播）。 */
    private val replyAudio = LinkedHashMap<String, MutableList<String>>()
    private val lock = Any()

    /** 正在收/播的那一段。 */
    @Volatile
    private var session: StreamSession? = null

    /** 正在播的那段话的原文（用于识别「自己把自己喊醒」的回环，见 [isSelfEchoWhileSpeaking]）。 */
    @Volatile
    private var speakingText: String = ""

    /** 正在重播的队列。 */
    @Volatile
    private var replay: ReplayQueue? = null

    // ------------------------------------------------------------ 生命周期

    /** 由 KeepAliveService.onCreate 调用（服务在，播放器就在；不新增 Service / 通知）。 */
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        audioManager = ctx.applicationContext.getSystemService(AudioManager::class.java)
        ChannelHub.addListener(this)
        // 启动时按保留策略清一次（过期 / 超 500 MB），别让缓存无限长
        Thread({
            val a = app ?: return@Thread
            val removed = TtsStore.prune(a)
            val (count, bytes) = TtsStore.usage(a)
            Log.i(TAG, "已就绪：语音缓存 $count 个 / ${bytes / 1024} KB（本次清理 $removed 个）")
        }, "yanyan-tts-prune").apply { isDaemon = true }.start()
    }

    /** 服务停了：停播、放掉播放器，不再向上回报任何帧。 */
    fun shutdown() {
        interruptInternal("service_stop", notifyServer = false)
        stopReplay()
        abandonFocus("service_stop")
        ListenController.resumeWakeDetection("service_stop")
        ChannelHub.removeListener(this)
        app = null
    }

    fun addListener(listener: TtsListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: TtsListener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------ 状态查询（界面用）

    /** 正在流式播（收到 speakChunk 边收边播）时为 true。 */
    val streaming: Boolean
        get() = session?.playing == true

    /** 正在重播本地缓存时为 true。 */
    val replaying: Boolean
        get() = replay?.playing == true

    /** 有任何一段声音在播（流式或重播）——唤醒词检测据此挂起/恢复。 */
    val isSpeaking: Boolean
        get() = streaming || replaying

    /**
     * 这次唤醒命中是不是「自己念出来的声音又被麦克风听见」。
     *
     * 手机没有回声消除（KWS 直接吃麦克风，不是通话通路），所以喇叭里念到「砚砚」时
     * KWS 会真的命中。判据是：正在播的那段回复正文里就有这个唤醒词 → 这次命中判为回环，
     * 不停播、不录音（否则每句提到自己名字的回复都会把自己打断）。
     * 反过来，回复正文里没有唤醒词时，麦克风不可能从我们自己的声音里听到它，
     * 那种命中一定来自用户/环境 → 照常「立刻停播并开录」。
     */
    fun isSelfEchoWhileSpeaking(keyword: String): Boolean {
        if (!streaming) return false
        val text = speakingText
        if (text.isEmpty()) return false
        return keyword.isNotEmpty() && text.contains(keyword)
    }

    fun isPlaying(replyId: String?): Boolean {
        if (replyId == null) return streaming || replaying
        val s = session
        if (s != null && s.replyId == replyId && s.playing) return true
        val r = replay
        return r != null && r.replyId == replyId && r.playing
    }

    /** 这条回复有没有可重播的本地音频。 */
    fun hasAudio(replyId: String?): Boolean = audioIds(replyId).isNotEmpty()

    /** 这条回复的语音段数（缓存里的）。 */
    fun audioIds(replyId: String?): List<String> {
        if (replyId == null) return emptyList()
        val ctx = app ?: return emptyList()
        val ids = synchronized(lock) { replyAudio[replyId]?.toList().orEmpty() }
        return ids.filter { TtsStore.hasWav(ctx, it) }
    }

    // ------------------------------------------------------------ 打断 / 停止

    /**
     * 用户打断：按录音、唤醒命中、点别的语音。
     * 立刻停播（包括正在写的队列），并给电脑发 `speakStop{id}` 停掉在途合成。
     */
    fun interrupt(reason: String) {
        interruptInternal(reason, notifyServer = true)
        stopReplay()
    }

    private fun interruptInternal(reason: String, notifyServer: Boolean) {
        val cur = session
        if (cur != null) {
            val id = cur.id
            cur.abort(reason)
            if (notifyServer) {
                val sent = KeepAliveService.channel?.sendSpeakStop(id) ?: false
                Log.i(TAG, "打断（$reason）→ 已停播 id=$id，speakStop ${if (sent) "已发送" else "没发出去（通道不在）"}")
            } else {
                Log.i(TAG, "停播（$reason）id=$id（不回报电脑）")
            }
        }
    }

    /** 设置页关掉开关 / 清缓存前调用：停播当前这一段（不发 speakStop，因为不是用户打断对话）。 */
    fun stopPlayback(reason: String) {
        interruptInternal(reason, notifyServer = false)
        stopReplay()
    }

    // ------------------------------------------------------------ 重播（读本地缓存）

    /**
     * 点消息上的播放图标：按 replyId 播本地缓存的 WAV（[audioIds] 里的顺序），**不请求 TTS**。
     * @return false 表示这条消息没有可播的缓存
     */
    fun replay(replyId: String?): Boolean {
        val ctx = app ?: return false
        val ids = audioIds(replyId)
        if (ids.isEmpty()) return false
        val files = ids.map { TtsStore.wavFile(ctx, it) }.filter { it.isFile }
        if (files.isEmpty()) return false
        // 点别的语音：先把正在播的停掉（流式那段要让电脑也别再合成）
        interruptInternal("replay_other", notifyServer = true)
        stopReplay()
        val queue = ReplayQueue(replyId, files)
        replay = queue
        requestFocus()
        // 重播的也是我们的声音：同样暂停唤醒词检测
        ListenController.suspendWakeDetection("replay_start")
        Log.i(TAG, "重播（本地缓存）replyId=${replyId ?: "-"} 段数=${files.size}，不产生新的 TTS 请求")
        notifyChanged(replyId)
        playNext(queue)
        return true
    }

    private fun playNext(queue: ReplayQueue) {
        if (queue.stopped || queue.index >= queue.files.size) {
            finishReplay(queue)
            return
        }
        val file = queue.files[queue.index]
        queue.index += 1
        // 换下一段时把上一段可能压低的音量与暂停状态复位
        queue.ducked = false
        queue.pausedByFocus = false
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setVolume(1f, 1f)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { playNext(queue) }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "重播失败 what=$what extra=$extra，跳过这一段的下一段")
                playNext(queue)
                true
            }
            queue.player = mp
            mp.prepareAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "重播启动失败：${t.javaClass.simpleName}")
            playNext(queue)
        }
    }

    private fun finishReplay(queue: ReplayQueue) {
        queue.playing = false
        releaseReplayPlayer(queue)
        if (replay === queue) replay = null
        abandonFocusIfIdle("重播结束")
        ListenController.resumeWakeDetection("replay_end")
        Log.i(TAG, "重播结束 replyId=${queue.replyId ?: "-"}")
        notifyChanged(queue.replyId)
    }

    private fun stopReplay() {
        val q = replay ?: return
        replay = null
        q.stopped = true
        q.playing = false
        releaseReplayPlayer(q)
        abandonFocusIfIdle("重播停止")
        ListenController.resumeWakeDetection("replay_stop")
        Log.i(TAG, "重播被停止 replyId=${q.replyId ?: "-"}")
        notifyChanged(q.replyId)
    }

    /** 没有在播的东西了才真的释放焦点（流式与重播共用一份焦点）。 */
    private fun abandonFocusIfIdle(reason: String) {
        if (session != null || replay != null) return
        abandonFocus(reason)
    }

    private fun releaseReplayPlayer(queue: ReplayQueue) {
        val p = queue.player ?: return
        queue.player = null
        try {
            if (p.isPlaying) p.stop()
        } catch (t: Throwable) {
            /* ignore */
        }
        try {
            p.release()
        } catch (t: Throwable) {
            /* ignore */
        }
    }

    // ------------------------------------------------------------ 通道回调（主线程）

    override fun onSpeakStart(
        id: String,
        replyId: String?,
        text: String,
        voice: String,
        sampleRate: Int,
        channels: Int
    ) {
        val ctx = app ?: return
        if (!LocalStore.speakEnabled(ctx)) {
            // 正常不会发生：开关关着电脑端一次 TTS 请求都不会发。这里兜底别出声即可
            Log.w(TAG, "收到 speakStart 但本地开关是关的，忽略 id=$id")
            return
        }
        // 点别的语音 / 上一段还没播完：先停掉它，并让电脑别再合成了
        interruptInternal("new_speak", notifyServer = true)
        stopReplay()
        startStream(id, replyId, sampleRate, channels, voice, text)
    }

    override fun onSpeakChunk(id: String, seq: Int, dataBase64: String) {
        val cur = session ?: return
        if (cur.id != id || cur.aborted) return
        if (dataBase64.isEmpty()) return
        val bytes = try {
            Base64.decode(dataBase64, Base64.DEFAULT)
        } catch (t: Throwable) {
            Log.w(TAG, "speakChunk 解码失败 id=$id seq=$seq：${t.javaClass.simpleName}")
            return
        }
        cur.enqueue(bytes)
    }

    override fun onSpeakDone(id: String) {
        val cur = session ?: return
        if (cur.id != id) return
        cur.finish()
    }

    override fun onSpeakStopRequested(id: String) {
        val cur = session ?: return
        if (cur.id != id) return
        // 电脑端要求收尾（它那边合成失败了）：停播，但不要再回 speakStop，避免来回打转
        Log.i(TAG, "电脑端要求收尾，停播 id=$id")
        cur.abort("server_stop")
    }

    override fun onPhaseChanged(phase: ChannelPhase, detail: String) {
        // 掉线了：在途的那段收不完了，就地收尾（已收到的部分照样转成 WAV，仍可重播）
        if (phase != ChannelPhase.CONNECTED) {
            val cur = session ?: return
            Log.i(TAG, "通道断开（$detail），停播 id=${cur.id}")
            cur.abort("disconnected")
        }
    }

    // ------------------------------------------------------------ 音频焦点（播放侧自愈）

    /** 申请焦点：短句用 GAIN_TRANSIENT_MAY_DUCK，够长也别升级成 GAIN（不抢别的 App 的音乐）。 */
    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        val am = audioManager ?: return false
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // 收到 CAN_DUCK 时**不要**让系统直接暂停我们：我们只把音量压下去，不停播
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val result = try {
            am.requestAudioFocus(req)
        } catch (t: Throwable) {
            Log.w(TAG, "申请音频焦点失败：${t.javaClass.simpleName}")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = if (focusHeld) req else null
        Log.i(
            TAG,
            "申请音频焦点 GAIN_TRANSIENT_MAY_DUCK：" + if (focusHeld) "拿到" else "没拿到（照常播，可能被系统压低）"
        )
        return focusHeld
    }

    private fun abandonFocus(reason: String) {
        if (!focusHeld) return
        focusHeld = false
        val req = focusRequest
        focusRequest = null
        try {
            val am = audioManager
            if (am != null) {
                if (req != null) am.abandonAudioFocusRequest(req) else am.abandonAudioFocus(focusListener)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "释放音频焦点失败：${t.javaClass.simpleName}")
        }
        Log.i(TAG, "释放音频焦点（$reason）")
    }

    /**
     * 系统音频焦点变化（通知音 / 来电 / 别的音乐 App）。
     * 关键：**短暂丢掉时不能收尾**，暂停等 GAIN 回来续播；只有永久 LOSS 才停。
     */
    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "音频焦点回来了（GAIN）→ 自动续播")
                session?.resumeFromFocus()
                resumeReplayFromFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 通话中才该停（等对方说完）；非通话时多半是通知音，暂停听起来像"播一下断一下"，
                // 所以改成压低继续——2026-11-20 按用户反馈调整。
                if (isInCall()) {
                    Log.i(TAG, "音频焦点被短暂拿走（LOSS_TRANSIENT，通话中）→ 暂停，等 GAIN 续播")
                    session?.pauseForFocus()
                    pauseReplayForFocus()
                } else {
                    Log.i(TAG, "音频焦点被短暂拿走（LOSS_TRANSIENT，非通话）→ 压低继续，不打断")
                    session?.duckForFocus()
                    duckReplayForFocus()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "音频焦点被短暂拿走（CAN_DUCK）→ 压到 ${(FOCUS_DUCK_VOLUME * 100).toInt()}% 继续念")
                session?.duckForFocus()
                duckReplayForFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "音频焦点被永久拿走（LOSS）→ 停播并清理")
                val cur = session
                if (cur != null) {
                    interruptInternal("focus_loss", notifyServer = true)
                }
                stopReplay()
                abandonFocus("focus_loss")
            }
            else -> Log.i(TAG, "收到未知音频焦点事件 $change，忽略")
        }
    }

    /** 是否在通话中（含 VoIP）。不在通话时，短暂失去焦点多半是通知音，压低继续比暂停自然。 */
    private fun isInCall(): Boolean {
        val mode = audioManager?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun pauseReplayForFocus() {
        val q = replay ?: return
        val p = q.player ?: return
        if (q.pausedByFocus || !p.isPlaying) return
        try {
            p.pause()
            q.pausedByFocus = true
            Log.i(TAG, "重播暂停（等音频焦点回来续播）replyId=${q.replyId ?: "-"}")
        } catch (t: Throwable) {
            Log.w(TAG, "重播暂停失败：${t.javaClass.simpleName}")
        }
    }

    private fun resumeReplayFromFocus() {
        val q = replay ?: return
        val p = q.player ?: return
        if (!q.pausedByFocus) return
        q.pausedByFocus = false
        try {
            p.start()
            Log.i(TAG, "焦点回来，续播本地缓存 replyId=${q.replyId ?: "-"}")
        } catch (t: Throwable) {
            Log.w(TAG, "重播续播失败：${t.javaClass.simpleName}")
        }
    }

    private fun duckReplayForFocus() {
        val q = replay ?: return
        val p = q.player ?: return
        if (q.ducked) return
        q.ducked = true
        try {
            p.setVolume(FOCUS_DUCK_VOLUME, FOCUS_DUCK_VOLUME)
        } catch (t: Throwable) {
            /* ignore */
        }
    }

    // ------------------------------------------------------------ 流式播放一段

    private fun startStream(id: String, replyId: String?, sampleRate: Int, channels: Int, voice: String, text: String) {
        val ctx = app ?: return
        val rate = if (sampleRate in 8000..48000) sampleRate else DEFAULT_SAMPLE_RATE
        val ch = if (channels == 2) 2 else DEFAULT_CHANNELS
        val s = StreamSession(ctx, id, replyId, rate, ch, voice)
        speakingText = text
        session = s
        // M6a：我们的播报会被自己的麦克风听见 —— 播报期间暂停唤醒词检测
        ListenController.suspendWakeDetection("tts_start")
        notifyChanged(replyId)
        s.start()
    }

    /** 一段语音的流式会话：AudioTrack 播放 + PCM 落盘 + 收尾成 WAV。 */
    private class StreamSession(
        val ctx: Context,
        val id: String,
        val replyId: String?,
        val sampleRate: Int,
        val channels: Int,
        private val voice: String
    ) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val pcmFile: File = TtsStore.pcmFile(ctx, id)
        private val wavFile: File = TtsStore.wavFile(ctx, id)

        @Volatile
        var aborted = false
            private set

        @Volatile
        var playing = false
            private set

        private var track: AudioTrack? = null

        @Volatile
        private var writtenBytes = 0L

        private var doneSignalled = false
        private var startedAt = 0L

        /** 被系统短暂拿走焦点时暂停（写线程会阻塞在 AudioTrack.write，位置不会丢）。 */
        @Volatile
        private var pausedByFocus = false

        /** 收到 CAN_DUCK 时把音量压下去，焦点回来再还原。 */
        @Volatile
        private var ducked = false

        fun start() {
            startedAt = System.currentTimeMillis()
            // 先申请焦点再起播放器：这样「念到一半来通知」时系统会告诉我们（GAIN/LOSS），而不是闷掉
            TtsPlayer.requestFocus()
            TtsStore.ensureDir(ctx)
            TtsStore.markActive(pcmFile)
            TtsStore.markActive(wavFile)
            playing = true
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferBytes = maxOf(minBuf.coerceAtLeast(1), TtsPlayer.TRACK_BUFFER_BYTES)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(
                                if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO
                                else AudioFormat.CHANNEL_OUT_MONO
                            )
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
                track = t
                t.play()
            } catch (t: Throwable) {
                Log.e(TAG, "AudioTrack 创建失败（${sampleRate}Hz/${channels}ch）：${t.javaClass.simpleName}")
                aborted = true
                playing = false
                // 没有写入线程，收尾要手动走一次（否则这段会话和占位文件会一直挂着）
                main.post { TtsPlayer.onStreamEnded(this, 0L, false) }
                return
            }
            Thread({ writeLoop() }, "yanyan-tts-play").apply { isDaemon = true }.start()
            Log.i(TAG, "开始播放 id=$id replyId=${replyId ?: "-"} voice=$voice ${sampleRate}Hz/${channels}ch（边收边播）")
        }

        fun enqueue(bytes: ByteArray) {
            if (aborted) return
            queue.offer(bytes)
        }

        fun finish() {
            if (aborted) return
            doneSignalled = true
            queue.offer(END)
        }

        /** 立刻停播：AudioTrack.pause 让正在阻塞的 write 立即返回，队列清空后收尾。 */
        fun abort(reason: String) {
            if (aborted) return
            aborted = true
            playing = false
            queue.clear()
            queue.offer(END)
            try {
                track?.pause()
                track?.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "暂停播放器失败：${t.javaClass.simpleName}")
            }
            Log.i(TAG, "停播 id=$id（$reason）已写 ${writtenBytes / 1024} KB")
        }

        fun markFinished() {
            playing = false
        }

        /** 焦点被短暂拿走：暂停但**不收尾**，位置留在 AudioTrack 里等 GAIN。 */
        fun pauseForFocus() {
            if (aborted || !playing) return
            if (pausedByFocus) return
            pausedByFocus = true
            try {
                track?.pause()
                Log.i(TAG, "播放暂停（等音频焦点回来续播）id=$id 已写 ${writtenBytes / 1024} KB")
            } catch (t: Throwable) {
                Log.w(TAG, "焦点暂停失败：${t.javaClass.simpleName}")
            }
        }

        /** 焦点回来：从暂停的位置继续（AudioTrack.play 不会重头开始）。 */
        fun resumeFromFocus() {
            if (aborted || !playing) return
            if (ducked) {
                ducked = false
                try {
                    track?.setVolume(1f)
                } catch (t: Throwable) {
                    /* ignore */
                }
            }
            if (!pausedByFocus) return
            pausedByFocus = false
            try {
                track?.play()
                Log.i(TAG, "焦点回来，续播 id=$id（从暂停位置继续，已写 ${writtenBytes / 1024} KB）")
            } catch (t: Throwable) {
                Log.w(TAG, "续播失败：${t.javaClass.simpleName}")
            }
        }

        /** CAN_DUCK：通知音很短，压音量继续念，不制造断点。 */
        fun duckForFocus() {
            if (aborted || !playing) return
            if (ducked || pausedByFocus) return
            ducked = true
            try {
                track?.setVolume(FOCUS_DUCK_VOLUME)
                Log.i(TAG, "播放压低到 ${(FOCUS_DUCK_VOLUME * 100).toInt()}% 继续（id=$id）")
            } catch (t: Throwable) {
                Log.w(TAG, "降低音量失败：${t.javaClass.simpleName}")
            }
        }

        private fun writeLoop() {
            var out: FileOutputStream? = null
            var ended = false
            /** 写音轨失败（不是「播完」也不是「被打断」），只用于日志区分。 */
            var alsoFailed = false
            try {
                out = FileOutputStream(pcmFile, false)
                while (true) {
                    val chunk = queue.poll(500, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (aborted) break
                        continue
                    }
                    if (chunk === END || chunk.isEmpty()) {
                        // abort() 也会塞一个 END 进队列让写入线程退出：那种情况下这不是「播完」
                        ended = !aborted
                        break
                    }
                    if (aborted) break
                    out.write(chunk)
                    writtenBytes += chunk.size
                    val t = track ?: break
                    var off = 0
                    var zeroWrites = 0
                    while (off < chunk.size) {
                        if (aborted) break
                        val n = t.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING)
                        if (n < 0) {
                            // 负值是真错误（例如播放器被系统回收）：这一段的音频到此为止
                            Log.w(TAG, "AudioTrack.write 返回 $n，停止写入 id=$id")
                            alsoFailed = true
                            break
                        }
                        if (n == 0) {
                            // 焦点被拿走 / 播放器还没就绪时可能返回 0：等一下再写，
                            // 以前这里直接 break，整段语音就这么没了（充电通知那个 bug 的表象）
                            zeroWrites += 1
                            if (zeroWrites > 500) {
                                Log.w(TAG, "AudioTrack.write 连续返回 0 达 10 秒，放弃这一段 id=$id")
                                alsoFailed = true
                                break
                            }
                            Thread.sleep(20)
                            continue
                        }
                        zeroWrites = 0
                        off += n
                    }
                    if (aborted) break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "写入失败 id=$id：${t.javaClass.simpleName}")
                alsoFailed = true
                ended = false
            } finally {
                if (alsoFailed) Log.w(TAG, "本段音频没写完（id=$id），已写 ${writtenBytes / 1024} KB")
                try {
                    out?.flush()
                    out?.close()
                } catch (t: Throwable) {
                    /* ignore */
                }
                // 自然结束：先等缓冲里的音频真正播出去，再 pause/flush/release。
                // MODE_STREAM 的 write() 只表示「数据进了缓冲区」，不代表已经发声；
                // 旧实现收到 END 立刻 releaseTrack()（pause + flush），会把缓冲里
                // 最多 TRACK_BUFFER_BYTES（96 KB ≈ 2 秒 @24k/16bit）的音频直接丢掉——
                // 真机现象就是「最后一句 / 最后半句没播」。
                val trackRef = track
                if (ended && trackRef != null) {
                    drainTrack(trackRef)
                }
                releaseTrack()
                val bytes = writtenBytes
                main.post { TtsPlayer.onStreamEnded(this, bytes, ended) }
            }
        }

        /**
         * 等已经写进 AudioTrack 缓冲的音频真正播出去。
         *
         * 用 `playbackHeadPosition`（已播帧数）追上「已写入帧数」作为完成条件。
         * 音频焦点被短暂拿走时播放头不走，所以给一个上界（缓冲时长 + 2 秒）；
         * 真的暂停了就不再死等，超时后按已写字节收尾（已收到的部分仍会转成 WAV）。
         */
        private fun drainTrack(t: AudioTrack) {
            if (aborted || writtenBytes <= 0) return
            if (t.state != AudioTrack.STATE_INITIALIZED) return
            val totalFrames = writtenBytes / (channels * 2)
            val started = System.currentTimeMillis()
            val bufferMs = TtsPlayer.TRACK_BUFFER_BYTES * 1000L / (sampleRate.toLong() * channels * 2)
            val deadline = started + bufferMs + 2000L
            var lastHead = -1L
            while (System.currentTimeMillis() < deadline) {
                if (aborted) break
                val head = t.playbackHeadPosition.toLong()
                if (head >= totalFrames) {
                    Log.i(
                        TAG,
                        "等待播放完成 id=$id head=$head/$totalFrames 帧，用时 ${System.currentTimeMillis() - started} ms"
                    )
                    return
                }
                if (head == lastHead) {
                    // 播放头不动（暂停 / 焦点被拿走）：最多再等 5 秒，避免把收尾线程挂死
                    if (System.currentTimeMillis() - started > bufferMs + 5000L) break
                }
                lastHead = head
                Thread.sleep(20)
            }
            Log.w(
                TAG,
                "等待播放完成超时 id=$id head=${t.playbackHeadPosition}/$totalFrames 帧，按已写字节收尾"
            )
        }

        private fun releaseTrack() {
            val t = track ?: return
            track = null
            try {
                if (t.state == AudioTrack.STATE_INITIALIZED) {
                    t.pause()
                    t.flush()
                    t.stop()
                }
            } catch (e: Throwable) {
                /* ignore */
            }
            try {
                t.release()
            } catch (e: Throwable) {
                /* ignore */
            }
        }
    }

    private fun onStreamEnded(s: StreamSession, bytes: Long, naturalEnd: Boolean) {
        if (session === s) {
            session = null
            speakingText = ""
        }
        s.markFinished()
        abandonFocusIfIdle("一段语音结束")
        val ctx = app
        var wav: File? = null
        val seconds = if (ctx != null) {
            bytes.toDouble() / (s.sampleRate * s.channels * BYTES_PER_SAMPLE).toDouble()
        } else {
            0.0
        }
        if (ctx != null) {
            TtsStore.unmarkActive(TtsStore.pcmFile(ctx, s.id))
            TtsStore.unmarkActive(TtsStore.wavFile(ctx, s.id))
            if (bytes > 0) {
                wav = TtsStore.finalizeWav(ctx, s.id, s.sampleRate, s.channels)
            } else {
                TtsStore.delete(ctx, s.id)
            }
        }
        if (wav != null && s.replyId != null) {
            synchronized(lock) {
                val list = replyAudio.getOrPut(s.replyId) { ArrayList() }
                if (!list.contains(s.id)) list.add(s.id)
                while (list.size > 30) list.removeAt(0)
            }
        }
        val reason = if (naturalEnd) "播完" else "被打断/中断"
        Log.i(
            TAG,
            "语音段结束 id=${s.id} replyId=${s.replyId ?: "-"} $reason 音频时长 " +
                    String.format(java.util.Locale.CHINA, "%.1f", seconds) + " 秒" +
                    (if (wav != null) "，已缓存 ${wav.name}" else "，没留下缓存")
        )
        if (ctx != null) TtsStore.prune(ctx)
        // 播报结束（播完 / 被打断 / 服务端 speakStop / 出错）都在这里收口：恢复唤醒词检测
        ListenController.resumeWakeDetection("tts_end")
        notifyChanged(s.replyId)
    }

    private fun notifyChanged(replyId: String?) {
        main.post { listeners.forEach { it.onTtsChanged(replyId) } }
    }

    /** 「立即清理」之后：通知界面刷新播放图标（缓存没了的图标要消失）。 */
    fun notifyCacheCleared() {
        val ids = synchronized(lock) { replyAudio.keys.toList() }
        main.post { listeners.forEach { l -> ids.forEach { l.onTtsChanged(it) } } }
    }

    // ------------------------------------------------------------ 重播队列

    private class ReplayQueue(val replyId: String?, val files: List<File>) {
        var index = 0
        var stopped = false
        var playing = true
        var player: MediaPlayer? = null
        /** 被系统短暂拿走焦点时暂停，等 GAIN 回来接着播。 */
        var pausedByFocus = false
        var ducked = false
    }
}


