package com.yanyan.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 监听总开关的四态。开关关着 = OFF，此时本进程没有任何 AudioRecord。 */
enum class ListenState {
    OFF,
    LISTENING,
    PAUSED_MIC,
    ERROR,
}

/**
 * M3a：监听总开关 + 麦克风让让 + 按住说话。
 *
 * 设计约束（见 docs/decision.md「监听开关」「麦克风冲突策略」）：
 * - 开关默认关；关着时不创建 AudioRecord、不占麦克风，关掉必须 stop()+release()；
 * - 别的应用在录音时自动暂停采集，通知/界面显示「暂停监听（麦克风被占用）」，对方结束自动恢复；
 * - 不抢音频焦点、不发出任何会弄哑对方的声音。
 */
object ListenController {
    private const val TAG = "ListenController"

    /** 「麦克风让让」的防抖窗口：外部占用/恢复都要稳定这么久才切换，避免状态来回跳。 */
    private const val OCCUPY_DEBOUNCE_MS = 1_000L

    /** M3b：命中唤醒词后自动录音的时长上限（M3 文档 2026-11-16 放宽：8 秒 → 30 秒）。 */
    private const val WAKE_RECORD_MAX_MS = 30_000L

    /**
     * 唤醒后的录音「至少录 5 秒」才允许「连续静音 1500 ms」提前收尾：
     * 不然刚喊完「砚砚」后面没人说话，会在 1 秒多就收掉，录不到要说的那句话。
     * 静音阈值本身由 [AudioCaptureEngine.SILENCE_STOP_MS] 决定（已放宽到 1500 ms）。
     */
    private const val WAKE_RECORD_SILENCE_MIN_MS = 5_000L

    @Volatile
    var state: ListenState = ListenState.OFF
        private set

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var errorText: String? = null
        private set

    @Volatile
    var recording: Boolean = false
        private set

    @Volatile
    var lastResult: RecordingResult? = null
        private set

    /** M3b：这次录音是「听见唤醒词后自动开始」的（界面上要显示「听见了，正在录」）。 */
    @Volatile
    var wakeRecording: Boolean = false
        private set

    /** 最近一次命中的唤醒词（界面显示用）。 */
    @Volatile
    var lastWakeKeyword: String? = null
        private set

    @Volatile
    var lastWakeAt: Long = 0L
        private set

    private val main = Handler(Looper.getMainLooper())
    private val stateListeners = LinkedHashSet<(ListenState) -> Unit>()
    private val resultListeners = LinkedHashSet<(RecordingResult) -> Unit>()
    private val wakeListeners = LinkedHashSet<(String) -> Unit>()

    private var app: Context? = null
    private var engine: AudioCaptureEngine? = null
    private var wake: WakeWordEngine? = null
    private var audioManager: AudioManager? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var deviceCallback: android.media.AudioDeviceCallback? = null

    /** watchdog 重建的退避：10 分钟内最多重建 3 次，别死循环。 */
    private var stallRebuilds = 0
    private var stallWindowStart = 0L
    private var otherActive = false

    /** 最近一次回调是否是「外部占用 / 系统静音」，防抖定时器用它确认条件仍然成立。 */
    private var externalBusy = false
    private var pendingPause: Runnable? = null

    /**
     * 最近属于本应用的 AudioRecord sessionId（含刚 release 掉的）。
     * AudioRecordingCallback 的快照会滞后：本应用刚释放的 session 还会在下一两次回调里出现，
     * 如果只拿「当前 session」比对，就会把自己的旧 session 误判成「别的应用在录音」，
     * 导致恢复监听后立刻又暂停的抖动（实测踩到过）。
     */
    private val ownSessions = LinkedHashMap<Int, Long>()
    private var pendingResume: Runnable? = null

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    fun addStateListener(listener: (ListenState) -> Unit) {
        synchronized(stateListeners) { stateListeners.add(listener) }
    }

    fun removeStateListener(listener: (ListenState) -> Unit) {
        synchronized(stateListeners) { stateListeners.remove(listener) }
    }

    fun addResultListener(listener: (RecordingResult) -> Unit) {
        synchronized(resultListeners) { resultListeners.add(listener) }
    }

    fun removeResultListener(listener: (RecordingResult) -> Unit) {
        synchronized(resultListeners) { resultListeners.remove(listener) }
    }

    /** M3b：唤醒词命中的回调（主线程）。 */
    fun addWakeListener(listener: (String) -> Unit) {
        synchronized(wakeListeners) { wakeListeners.add(listener) }
    }

    fun removeWakeListener(listener: (String) -> Unit) {
        synchronized(wakeListeners) { wakeListeners.remove(listener) }
    }

    fun hasMicPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ 总开关

    /**
     * 打开 / 关闭监听。返回值 false 表示没能打开（没权限或麦克风启动失败），调用方负责提示。
     * 必须在主线程调用（界面和通知都从这里刷）。
     */
    fun setEnabled(on: Boolean): Boolean {
        val ctx = app ?: run {
            Log.w(TAG, "setEnabled 时还没 init")
            return false
        }
        if (!on) {
            disable()
            return true
        }
        if (enabled && (state == ListenState.LISTENING || state == ListenState.PAUSED_MIC)) return true
        if (!AppVisibility.hasVisibleActivity) {
            // Android 14+ 不允许后台应用打开麦克风；界面不在前台就别假装能开
            Log.w(TAG, "界面不在前台，拒绝打开监听")
            return false
        }
        if (!hasMicPermission(ctx)) {
            Log.w(TAG, "没有 RECORD_AUDIO 权限，拒绝打开监听")
            return false
        }
        enabled = true
        LocalStore.setListenEnabled(ctx, true)
        registerMicCallback()
        val err = ensureEngine()
        if (err != null) {
            Log.e(TAG, "打开监听失败：$err")
            errorText = err
            state = ListenState.ERROR
            enabled = false
            LocalStore.setListenEnabled(ctx, false)
            unregisterMicCallback()
        } else {
            errorText = null
            state = if (otherActive) ListenState.PAUSED_MIC else ListenState.LISTENING
            if (otherActive) stopEngine()
        }
        notifyState()
        return enabled
    }

    /** 服务被停了 / 用户关开关：释放麦克风、注销回调，开关状态同时落盘。 */
    fun disable() {
        val ctx = app ?: return
        cancelPendingResume()
        cancelPendingPause()
        externalBusy = false
        WakePrompt.stop()
        // M6a：关监听时也别留着语音回复在响
        TtsPlayer.stopPlayback("listen_off")
        unregisterMicCallback()
        stopEngine()
        enabled = false
        otherActive = false
        errorText = null
        state = ListenState.OFF
        LocalStore.setListenEnabled(ctx, false)
        notifyState()
    }

    /** 进程/服务重启后按落盘的开关状态恢复。界面不在前台时不恢复，等界面回来再开。 */
    fun restoreFromPrefs() {
        val ctx = app ?: return
        if (!LocalStore.listenEnabled(ctx)) {
            state = ListenState.OFF
            return
        }
        if (!AppVisibility.hasVisibleActivity) {
            Log.i(TAG, "落盘的开关是开的，但界面不在前台：先不占麦克风，等界面回来再恢复")
            state = ListenState.OFF
            return
        }
        if (!hasMicPermission(ctx)) {
            LocalStore.setListenEnabled(ctx, false)
            state = ListenState.OFF
            return
        }
        if (!setEnabled(true)) {
            state = ListenState.OFF
            LocalStore.setListenEnabled(ctx, false)
            notifyState()
        }
    }

    /**
     * 界面回到前台：把落盘的开关补上（如果进程在后台被拉起过），
     * 并确保前台服务已经带上 microphone 类型（后台采集才不会被系统静音）。
     */
    fun onAppForeground() {
        val ctx = app ?: return
        if (enabled) {
            refreshServiceNotification()
            if (state == ListenState.PAUSED_MIC && !externalBusy) {
                cancelPendingResume()
                cancelPendingPause()
                val err = ensureEngine()
                if (err == null) {
                    errorText = null
                    state = ListenState.LISTENING
                } else {
                    errorText = err
                    state = ListenState.ERROR
                }
                notifyState()
            }
            return
        }
        if (LocalStore.listenEnabled(ctx) && hasMicPermission(ctx)) {
            restoreFromPrefs()
        }
    }

    // ------------------------------------------------------------ 按住说话

    /** 开始一次录音。返回 null 表示已开始，否则是拒绝原因（给界面 toast 用）。 */
    fun startRecording(): String? {
        val ctx = app ?: return "还没初始化"
        if (!enabled) return "监听开关是关的"
        when (state) {
            ListenState.OFF -> return "监听开关是关的"
            ListenState.PAUSED_MIC -> return "麦克风被其他应用占用，正在暂停监听"
            ListenState.ERROR -> return "监听异常：${errorText ?: "未知"}"
            ListenState.LISTENING -> Unit
        }
        if (recording) return "正在录音"
        var e = engine
        if (e == null || !e.isRunning) {
            val err = ensureEngine()
            if (err != null) {
                errorText = err
                state = ListenState.ERROR
                notifyState()
                return "麦克风打不开：$err"
            }
            e = engine
        }
        val target = e ?: return "麦克风没准备好"
        val file = newRecordingFile(ctx) ?: return "创建录音文件失败"
        // M6a：一按录音就立刻停掉正在播的语音回复，并让电脑停止在途合成（打断优先级最高）
        TtsPlayer.interrupt("record_start")
        if (!target.beginRecording(file)) return "录音没能开始（麦克风状态异常）"
        RecordingStore.markActive(file)
        recording = true
        lastResult = null
        Log.i(TAG, "按住说话开始：${file.name}")
        return null
    }

    fun stopRecording() {
        engine?.requestStop()
    }

    /**
     * M3b：用户点「停止录音」/ 通知栏的「停止录音」。
     * 立刻收尾，走与自动收尾完全相同的流程（写 WAV → 上传 → 识别）。
     * 返回 false 表示当前没有在录音。
     */
    fun stopRecordingManually(): Boolean {
        if (!recording) return false
        Log.i(TAG, "手动停止录音 → 立即收尾并上传")
        engine?.requestStop("manual_stop")
        return true
    }

    /** 录音线程回报的实时时长（毫秒），仅在 recording 时有意义。 */
    fun liveRecordedMs(): Long = engine?.recordedMs ?: 0L

    /** 本次录音的时长上限（按住说话 60 秒 / 唤醒后 30 秒），给界面显示。 */
    fun liveMaxMs(): Long = engine?.activeMaxMs() ?: AudioCaptureEngine.MAX_RECORD_MS

    // ------------------------------------------------------------ M3b 唤醒词

    /**
     * M6a：播报期间挂起唤醒词检测（KWS），播完/被打断后恢复。
     *
     * 只停「喂给 KWS 的帧」（[AudioCaptureEngine.frameSink] 里那个判断），
     * **采集链路、录音、词表都不动**——所以按住说话、唤醒后录音照样能用。
     * 成对调用：TtsPlayer（流式播放 / 重播）与 WakePrompt（「我在」提示音）的每条结束路径都会恢复。
     */
    @Volatile
    var wakeDetectionSuspended: Boolean = false
        private set

    fun suspendWakeDetection(reason: String) {
        if (wakeDetectionSuspended) return
        wakeDetectionSuspended = true
        Log.i(TAG, "播报开始 → 暂停唤醒词检测（$reason）")
    }

    fun resumeWakeDetection(reason: String) {
        if (!wakeDetectionSuspended) return
        // 播报还在继续（例如语音回复刚被打断、提示音接上了）就先不恢复，由最后结束的那个再来一次
        if (TtsPlayer.isSpeaking || WakePrompt.isPlaying) {
            Log.i(
                TAG,
                "播报还没结束（TtsPlayer=${TtsPlayer.isSpeaking} 提示音=${WakePrompt.isPlaying}），" +
                        "暂不恢复唤醒词检测（$reason）"
            )
            return
        }
        wakeDetectionSuspended = false
        Log.i(TAG, "播报结束 → 恢复唤醒词检测（$reason）")
    }

    /** 唤醒词引擎状态文案（给状态页用）。 */
    fun wakeWordStateText(): String {
        val w = wake
        if (w != null && (enabled || w.state != WakeWordState.IDLE)) return w.stateText()
        return "未加载（监听开关关着时不占内存、不上麦克风）"
    }

    /** KWS 当前的统计对象（只读，给界面/报告用）。 */
    fun wakeEngine(): WakeWordEngine? = wake

    /**
     * M3b：环境里有没有别的声音在响（只在状态文案上提示，**不停采集**）。
     * 依据 `AudioManager.isMusicActive()`：媒体类播放（音乐/视频/语音消息）会命中；
     * 短促的系统通知音不一定命中，所以这只是「尽力提示」，不是精确的占用判断。
     */
    fun musicActive(): Boolean = try {
        app?.getSystemService(AudioManager::class.java)?.isMusicActive == true
    } catch (t: Throwable) {
        false
    }

    /**
     * 命中唤醒词（主线程）：先「应一声」（开关开着时），再立刻用 M3a 的同一条录音链路录（最长 30 秒）。
     * 不另写录音，录完照旧由 M4 的 [AudioUploader] 自动上传、识别。
     */
    private fun onWakeDetected(keyword: String) {
        if (!enabled) return
        if (state != ListenState.LISTENING) {
            Log.i(TAG, "命中唤醒词「$keyword」，但当前状态是 $state，不录音")
            return
        }
        if (recording) {
            Log.i(TAG, "命中唤醒词「$keyword」，但已在录音，忽略")
            return
        }
        // M6a：手机没有回声消除，喇叭里正念到「砚砚」时 KWS 会真的命中（自听回环）。
        // 正在播的这段回复正文里就有这个唤醒词 → 判为回环：不停播、不应声、不录音。
        if (TtsPlayer.isSelfEchoWhileSpeaking(keyword)) {
            Log.i(TAG, "命中唤醒词「$keyword」，但正在播的回复里就有这个词 → 判为自听回环，忽略（不停播、不录音）")
            return
        }
        val ctx = app ?: return
        lastWakeKeyword = keyword
        lastWakeAt = System.currentTimeMillis()
        // M6a：听见唤醒词就当他「开口」了 —— 立刻停掉正在播的语音回复，并让电脑停止在途合成。
        // 放在播提示音之前：先让砚砚闭嘴，再应一声，两个声音不重叠。
        TtsPlayer.interrupt("wake_hit")
        // 可见反馈（立刻）：通知文案 + 状态页 + 一条 toast
        try {
            android.widget.Toast.makeText(ctx, "听见「$keyword」", android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(TAG, "唤醒提示 toast 失败：${t.javaClass.simpleName}")
        }
        val listeners = synchronized(wakeListeners) { wakeListeners.toList() }
        for (l in listeners) l(keyword)

        if (LocalStore.wakePromptEnabled(ctx)) {
            // 播完（或失败/超时）再开麦，提示音不盖住录音起始
            WakePrompt.playRandom(ctx) { main.post { beginWakeRecording(keyword) } }
        } else {
            Log.i(TAG, "唤醒语音提示开关关着：不播任何声音，直接开始录音")
            beginWakeRecording(keyword)
        }
    }

    /** 真正开始「唤醒后自动录音」。提示音播完后调用；这期间状态可能已经变了，要重新校验。 */
    private fun beginWakeRecording(keyword: String) {
        if (!enabled || state != ListenState.LISTENING || recording) {
            Log.i(TAG, "提示播完后状态已变（enabled=$enabled state=$state recording=$recording），不再开录音")
            return
        }
        val ctx = app ?: return
        val e = engine
        if (e == null || !e.isRunning) {
            Log.w(TAG, "命中唤醒词「$keyword」，但麦克风没在采（engine=null），忽略")
            return
        }
        val file = newRecordingFile(ctx)
        if (file == null) {
            Log.e(TAG, "唤醒后创建录音文件失败")
            return
        }
        if (!e.beginRecording(file, WAKE_RECORD_MAX_MS, WAKE_RECORD_SILENCE_MIN_MS)) {
            Log.w(TAG, "唤醒后录音没能开始（麦克风状态异常）")
            return
        }
        RecordingStore.markActive(file)
        wakeRecording = true
        recording = true
        lastResult = null
        Log.i(
            TAG,
            "听见唤醒词「$keyword」→ 自动录音（上限 ${WAKE_RECORD_MAX_MS / 1000} 秒，" +
                    "静音 ${AudioCaptureEngine.SILENCE_STOP_MS / 1000.0} 秒且已录满 ${WAKE_RECORD_SILENCE_MIN_MS / 1000} 秒后收尾，" +
                    "可手动停止）file=${file.name}"
        )
        notifyState()
    }

    // ------------------------------------------------------------ 状态文案

    fun stateLabel(): String = when (state) {
        ListenState.OFF -> "已关闭（未占用麦克风）"
        ListenState.LISTENING -> when {
            wakeRecording -> "听见了「${lastWakeKeyword ?: "砚砚"}」，正在录…"
            recording -> "正在监听（录音中）"
            else -> "正在监听"
        }
        ListenState.PAUSED_MIC -> "暂停监听（麦克风被占用）"
        ListenState.ERROR -> "监听异常：${errorText ?: "未知"}"
    }

    /** 常驻通知该显示的文案；null 表示「不用监听状态覆盖用户的默认文案」。 */
    fun notificationStateText(): String? = when (state) {
        ListenState.OFF -> null
        ListenState.LISTENING -> when {
            wakeRecording -> "听见了「${lastWakeKeyword ?: "砚砚"}」，正在录…（可点「停止录音」收尾）"
            recording -> "正在录音…（点「停止录音」收尾）"
            musicActive() -> "正在监听（环境有声音，尽力在听）"
            else -> "正在监听"
        }
        ListenState.PAUSED_MIC -> "暂停监听（麦克风被占用）"
        ListenState.ERROR -> "监听异常（${errorText ?: "未知"}）"
    }

    // ------------------------------------------------------------ 内部

    private fun ensureEngine(): String? {
        val existing = engine
        if (existing != null && existing.isRunning) {
            startWakeEngine(existing)
            return null
        }
        existing?.stop()
        val e = AudioCaptureEngine(
            onError = { msg -> main.post { onEngineError(msg) } },
            onRecordDone = { result -> main.post { onEngineRecordDone(result) } },
            onStalled = { reason -> main.post { onCaptureStalled(reason) } }
        )
        val err = e.start()
        if (err != null) {
            engine = null
            return err
        }
        engine = e
        rememberOwnSession(e.audioSessionId)
        startWakeEngine(e)
        return null
    }

    /** M3b：把 KWS 挂到采集引擎上并开始加载；加载是异步的，失败只影响唤醒不影响录音。 */
    private fun startWakeEngine(e: AudioCaptureEngine) {
        val ctx = app ?: return
        val w = wake ?: WakeWordEngine(ctx) { keyword -> main.post { onWakeDetected(keyword) } }
            .also { wake = it }
        e.frameSink = { samples, count ->
            // M6a：播报期间不把帧喂给 KWS（否则会把自己念的「砚砚」当成用户喊话）
            if (!wakeDetectionSuspended) w.accept(samples, count)
        }
        w.start()
        Log.i(TAG, "唤醒词引擎已启动（异步加载）：${w.stateText()}")
    }

    private fun stopEngine() {
        rememberOwnSession(engine?.audioSessionId ?: 0)
        engine?.frameSink = null
        engine?.stop()
        engine = null
        recording = false
        wakeRecording = false
        // 让让逻辑也把 KWS 一起停：麦克风都还给别人了，不该还有 KWS 在 decode
        wake?.release()
    }

    private fun rememberOwnSession(sessionId: Int) {
        if (sessionId == 0) return
        synchronized(ownSessions) {
            val now = SystemClock.elapsedRealtime()
            ownSessions[sessionId] = now
            val iterator = ownSessions.entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value > 30_000L) iterator.remove()
            }
        }
    }

    private fun isOwnSession(sessionId: Int): Boolean {
        if (sessionId == 0) return false
        if (sessionId == (engine?.audioSessionId ?: 0)) return true
        synchronized(ownSessions) {
            val seenAt = ownSessions[sessionId] ?: return false
            return SystemClock.elapsedRealtime() - seenAt < 30_000L
        }
    }

    private fun cancelPendingResume() {
        val runnable = pendingResume ?: return
        pendingResume = null
        main.removeCallbacks(runnable)
    }

    private fun cancelPendingPause() {
        val runnable = pendingPause ?: return
        pendingPause = null
        main.removeCallbacks(runnable)
    }

    /**
     * 外部占用必须持续 ≥1 秒才真的暂停：AudioRecordingCallback 的快照会抖
     * （本应用刚 release 的 session、系统内部短命采集都会闪过），按回调逐帧切换会来回跳。
     */
    private fun schedulePause(occupied: Boolean) {
        if (pendingPause != null || state != ListenState.LISTENING) return
        val runnable = Runnable {
            pendingPause = null
            if (!enabled || state != ListenState.LISTENING || !externalBusy) return@Runnable
            Log.i(
                TAG,
                if (occupied) "外部录音持续 1 秒 → 暂停监听并释放麦克风"
                else "本应用采集被系统静音持续 1 秒 → 暂停监听"
            )
            if (occupied) stopEngine() // 让让：真把麦克风还给对方
            errorText = null
            state = ListenState.PAUSED_MIC
            notifyState()
        }
        pendingPause = runnable
        main.postDelayed(runnable, OCCUPY_DEBOUNCE_MS)
    }

    /** 外部占用消失也要稳定 1 秒才恢复，避免「暂停↔恢复」闪烁。 */
    private fun scheduleResume() {
        if (pendingResume != null || state != ListenState.PAUSED_MIC) return
        val runnable = Runnable {
            pendingResume = null
            if (!enabled || state != ListenState.PAUSED_MIC || externalBusy) return@Runnable
            Log.i(TAG, "外部占用结束 / 静音解除超过 1 秒 → 自动恢复监听")
            val err = ensureEngine()
            if (err != null) {
                errorText = err
                state = ListenState.ERROR
            } else {
                errorText = null
                state = ListenState.LISTENING
            }
            notifyState()
        }
        pendingResume = runnable
        main.postDelayed(runnable, OCCUPY_DEBOUNCE_MS)
    }

    private fun onEngineError(message: String) {
        Log.e(TAG, "录音引擎错误：$message")
        stopEngine()
        recording = false
        if (!enabled) return
        errorText = message
        state = ListenState.ERROR
        notifyState()
    }

    /**
     * M3b watchdog：采集真的卡死（≥5 秒无有效数据且 AudioRecord 已不在 RECORDING）时，
     * 释放并重建一次 AudioRecord。10 分钟内最多 3 次，超过就认命置「监听异常」，避免死循环。
     *
     * 注意：通知音/系统短暂压低麦克风**不会**走到这里（那时 AudioRecord 仍是 RECORDING），
     * 所以正常场景不会被这里打断录音或白断采集。
     */
    private fun onCaptureStalled(reason: String) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - stallWindowStart > 10 * 60 * 1000L) {
            stallWindowStart = now
            stallRebuilds = 0
        }
        if (stallRebuilds >= 3) {
            Log.e(TAG, "采集失联重建已达上限（3 次/10 分钟，reason=$reason），不再重试，置异常")
            errorText = "麦克风反复失联（已重建 3 次）"
            stopEngine()
            state = ListenState.ERROR
            notifyState()
            return
        }
        stallRebuilds++
        Log.i(TAG, "watchdog：采集失联（$reason）→ 重建 AudioRecord（第 $stallRebuilds 次）")
        stopEngine()
        notifyState()
        main.postDelayed({
            if (!enabled || state != ListenState.LISTENING) return@postDelayed
            val err = ensureEngine()
            if (err != null) {
                errorText = err
                state = ListenState.ERROR
            } else {
                errorText = null
            }
            Log.i(TAG, "watchdog 重建结果：${if (err == null) "成功" else "失败：$err"}")
            notifyState()
        }, 600L)
    }

    private fun onEngineRecordDone(result: RecordingResult) {
        recording = false
        wakeRecording = false
        lastResult = result
        // 录完就不算「正在用」了，交给上传链路；失败保留、成功由 AudioUploader 删
        RecordingStore.unmarkActive(result.file)
        // 一次录音结束，清掉 KWS 里的残留状态，接着听下一句
        wake?.resetStream()
        Log.i(
            TAG,
            "录音完成 reason=${result.reason} durationMs=${result.durationMs} " +
                    "bytes=${result.bytes} tooShort=${result.tooShort} file=${result.file?.absolutePath}"
        )
        val listeners = synchronized(resultListeners) { resultListeners.toList() }
        for (l in listeners) l(result)
        notifyState()
    }

    private fun notifyState() {
        val current = state
        val listeners = synchronized(stateListeners) { stateListeners.toList() }
        for (l in listeners) l(current)
        refreshServiceNotification()
    }

    private fun refreshServiceNotification() {
        val ctx = app ?: return
        if (!ServiceState.running) return
        try {
            KeepAliveService.startWithAction(ctx, KeepAliveService.ACTION_REFRESH)
        } catch (t: Throwable) {
            Log.w(TAG, "刷新常驻通知失败：${t.javaClass.simpleName}")
        }
    }

    private fun newRecordingFile(ctx: Context): File? {
        val dir = File(ctx.filesDir, "recordings")
        if (!dir.exists() && !dir.mkdirs()) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA).format(Date())
        return File(dir, "rec_$stamp.wav")
    }

    // ------------------------------------------------------------ 麦克风让让

    private fun registerMicCallback() {
        val ctx = app ?: return
        if (recordingCallback != null) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val ours = configs.firstOrNull { isOurs(it) }
                // 只有「非本应用 且 有真实 session」的采集才算外部占用
                val others = configs.filter { !isOurs(it) && it.clientAudioSessionId != 0 }
                val other = others.firstOrNull()
                // isClientSilenced() 是 API 29 才有的，minSdk 26 要判版本
                val silenced = ours != null &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        ours.isClientSilenced()
                Log.i(
                    TAG,
                    "录音配置变化：共 ${configs.size} 路（session=${configs.joinToString {
                        it.clientAudioSessionId.toString()
                    }}），外部 ${others.size} 路，本应用被静音=$silenced" +
                            (other?.let { "（session=${it.clientAudioSessionId}）" } ?: "")
                )
                onOccupancyChanged(other != null, silenced)
            }
        }
        try {
            am.registerAudioRecordingCallback(callback, main)
            audioManager = am
            recordingCallback = callback
            // M3b 诊断：设备增减（耳机/蓝牙/USB 声卡切换）记日志，排查「唤醒突然失效」
            val devCb = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                    Log.i(TAG, "音频设备新增：${addedDevices.joinToString { "${it.type}:${it.productName}" }}")
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
                    Log.i(TAG, "音频设备移除：${removedDevices.joinToString { "${it.type}:${it.productName}" }}")
                }
            }
            am.registerAudioDeviceCallback(devCb, main)
            deviceCallback = devCb
            Log.i(TAG, "已注册 AudioRecordingCallback")
        } catch (t: Throwable) {
            Log.w(TAG, "注册 AudioRecordingCallback 失败：${t.javaClass.simpleName}")
        }
    }

    private fun unregisterMicCallback() {
        val am = audioManager
        val cb = recordingCallback
        val dev = deviceCallback
        audioManager = null
        recordingCallback = null
        deviceCallback = null
        if (am == null) return
        if (dev != null) {
            try {
                am.unregisterAudioDeviceCallback(dev)
            } catch (t: Throwable) {
                Log.w(TAG, "注销 AudioDeviceCallback 失败：${t.javaClass.simpleName}")
            }
        }
        if (cb == null) return
        try {
            am.unregisterAudioRecordingCallback(cb)
            Log.i(TAG, "已注销 AudioRecordingCallback")
        } catch (t: Throwable) {
            Log.w(TAG, "注销 AudioRecordingCallback 失败：${t.javaClass.simpleName}")
        }
    }

    /**
     * 只把「别的应用」当占用；本应用自己的采集（包括刚释放的 session）要滤掉。
     * 公开 API 里拿不到外部录音的包名/uid（getClientUid / getClientPackageName 是 SystemApi），
     * 唯一可用的标识是 clientAudioSessionId。
     */
    private fun isOurs(config: AudioRecordingConfiguration): Boolean =
        isOwnSession(config.clientAudioSessionId)

    private fun onOccupancyChanged(occupied: Boolean, silenced: Boolean) {
        if (!enabled) {
            externalBusy = occupied || silenced
            return
        }
        val busy = occupied || silenced
        externalBusy = busy
        if (state == ListenState.ERROR || state == ListenState.OFF) return
        if (busy) {
            cancelPendingResume()
            // 已经暂停时，如果这次是「别的应用真的在录」，把可能还留着的麦克风也放掉
            if (state == ListenState.PAUSED_MIC) {
                if (occupied && engine?.isRunning == true) {
                    Log.i(TAG, "暂停期间外部录音仍在 → 确保麦克风已释放")
                    stopEngine()
                }
                return
            }
            if (occupied) otherActive = true else otherActive = false
            schedulePause(occupied)
        } else {
            otherActive = false
            cancelPendingPause()
            if (state == ListenState.PAUSED_MIC) {
                scheduleResume()
            }
        }
    }
}

