package com.yanyan.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 通道本地的连接状态（协议里没有这个字段，纯界面用）。 */
enum class ChannelPhase { NOT_PAIRED, CONNECTING, CONNECTED, RECONNECTING, STOPPED }

/** 界面侧监听：只关心「状态」和「收到的消息」，不碰协议细节。 */
interface ChannelListener {
    fun onPhaseChanged(phase: ChannelPhase, detail: String) {}
    fun onReady(serverTime: Long) {}
    fun onIncomingText(id: String, text: String) {}
    fun onIncomingChunk(id: String, delta: String) {}
    fun onIncomingDone(id: String) {}
    fun onServerError(code: String, message: String) {}

    /** M4：电脑端识别完成。text 是识别文本，durationMs 是音频时长，costMs 是电脑端耗时。 */
    fun onAsrResult(id: String, text: String, durationMs: Long, costMs: Long) {}

    /** M4：电脑端识别失败（code 见 server.js：no_key / incomplete / too_large / ...）。 */
    fun onAsrError(id: String, code: String, message: String) {}

    /** M5：电脑下发了一条命令（`command{id,kind,args,say}`）。执行器在 [ChannelHub] 上订阅这一条。 */
    fun onIncomingCommand(id: String, kind: String, args: JSONObject, say: String) {}

    /**
     * M6a：电脑开始念一段话（`speakStart{id, replyId, text, voice, mime, sampleRate, channels}`）。
     * `id` 是这一段语音的标识；`replyId` 是它对应的那条文字消息（用于挂播放图标）。
     */
    fun onSpeakStart(
        id: String,
        replyId: String?,
        text: String,
        voice: String,
        sampleRate: Int,
        channels: Int
    ) {}

    /** M6a：一段 PCM 音频（base64）。按 seq 顺序到，收一块就写一块。 */
    fun onSpeakChunk(id: String, seq: Int, dataBase64: String) {}

    /** M6a：这一段念完了。 */
    fun onSpeakDone(id: String) {}

    /** M6a：电脑要求收尾（合成失败 / 其它原因），本端停播即可，不要回 speakStop。 */
    fun onSpeakStopRequested(id: String) {}
}

/**
 * 进程级事件中枢。
 *
 * ChannelClient 由 KeepAliveService 持有（一个进程只有一个），界面不直接抓 client，
 * 而是从这里读当前状态、注册监听；所有回调都派发到主线程。
 */
object ChannelHub {
    @Volatile
    var phase: ChannelPhase = ChannelPhase.STOPPED
        private set

    @Volatile
    var detail: String = "未启动"
        private set

    @Volatile
    var serverTime: Long = 0L
        private set

    private val listeners = CopyOnWriteArrayList<ChannelListener>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: ChannelListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: ChannelListener) {
        listeners.remove(listener)
    }

    internal fun publishPhase(newPhase: ChannelPhase, newDetail: String) {
        phase = newPhase
        detail = newDetail
        main.post { listeners.forEach { it.onPhaseChanged(newPhase, newDetail) } }
    }

    internal fun publishReady(time: Long) {
        serverTime = time
        main.post { listeners.forEach { it.onReady(time) } }
    }

    internal fun publishText(id: String, text: String) {
        main.post { listeners.forEach { it.onIncomingText(id, text) } }
    }

    internal fun publishChunk(id: String, delta: String) {
        main.post { listeners.forEach { it.onIncomingChunk(id, delta) } }
    }

    internal fun publishDone(id: String) {
        main.post { listeners.forEach { it.onIncomingDone(id) } }
    }

    internal fun publishServerError(code: String, message: String) {
        main.post { listeners.forEach { it.onServerError(code, message) } }
    }

    internal fun publishAsrResult(id: String, text: String, durationMs: Long, costMs: Long) {
        main.post { listeners.forEach { it.onAsrResult(id, text, durationMs, costMs) } }
    }

    internal fun publishAsrError(id: String, code: String, message: String) {
        main.post { listeners.forEach { it.onAsrError(id, code, message) } }
    }

    /** M5：把电脑下发的命令派发给执行器（主线程，执行器会在主线程拼 Intent）。 */
    internal fun publishCommand(id: String, kind: String, args: JSONObject, say: String) {
        main.post { listeners.forEach { it.onIncomingCommand(id, kind, args, say) } }
    }

    /** M6a：语音回复的四个回调（都在主线程）。 */
    internal fun publishSpeakStart(
        id: String,
        replyId: String?,
        text: String,
        voice: String,
        sampleRate: Int,
        channels: Int
    ) {
        main.post { listeners.forEach { it.onSpeakStart(id, replyId, text, voice, sampleRate, channels) } }
    }

    internal fun publishSpeakChunk(id: String, seq: Int, dataBase64: String) {
        main.post { listeners.forEach { it.onSpeakChunk(id, seq, dataBase64) } }
    }

    internal fun publishSpeakDone(id: String) {
        main.post { listeners.forEach { it.onSpeakDone(id) } }
    }

    internal fun publishSpeakStopRequested(id: String) {
        main.post { listeners.forEach { it.onSpeakStopRequested(id) } }
    }
}

/**
 * M2 通道客户端：WebSocket 连接 + 首帧 hello + 心跳 + 断线退避重连。
 *
 * 生命周期：由 KeepAliveService 创建 / start / stop（服务在，通道就在；不新增 Service）。
 * 协议：proto 2（M4 起；M2 的 proto 1 服务端仍兼容），
 *       实现 hello/ready/text/chunk/done/ping/pong/error + audioStart/audioChunk/audioEnd
 *       + 收 asrResult/asrError + M5 的 command（收）/commandResult（发）；
 *       file/approval 等预留帧一律回 error{code:"unsupported"} 且不断连。
 */
class ChannelClient(private val appContext: Context) {

    companion object {
        private const val TAG = "ChannelClient"

        /** 本端协议版本：2 = M4（文本 + 音频上传 + ASR 结果）。 */
        const val PROTO = 2

        /** 心跳间隔：30 秒发一次 ping */
        private const val HEARTBEAT_MS = 30_000L

        /** 90 秒没收到任何帧就判死，主动断开重连 */
        private const val DEAD_MS = 90_000L

        /** 看门狗轮询间隔 */
        private const val WATCHDOG_MS = 5_000L

        /** 发完 hello 后多久没等到 ready 就重来（服务端也有 10 秒 hello 超时） */
        private const val HANDSHAKE_MS = 15_000L

        /** 重连退避：1s、2s、4s、8s、16s、30s（上限 30s） */
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

        /** 本模块不实现、但协议里已经预留的帧（M5 之后只剩 file / approval） */
        private val RESERVED = setOf("file", "approval")
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接，读超时交给自己的心跳看门狗
        .build()

    private var started = false
    private var webSocket: WebSocket? = null
    private var socketOpen = false
    private var connectSeq = 0
    private var attempt = 0
    private var lastFrameAt = 0L
    private var reconnectScheduled = false
    /** 「立刻重连」的防抖标志：界面连点也只会重开一条，避免把好连接顶成 1006。 */
    private var reconnectPending = false
    /** 这次排队重连是否强制换链路（配对成功 / 换地址 / 撤销配对）。 */
    private var reconnectForce = false
    private val messageSeq = AtomicInteger(0)

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        openSocket()
    }

    /** reconnectNow() 排队的那一次（与退避重连分开，避免互相取消）。 */
    private val reconnectNowRunnable = Runnable {
        reconnectPending = false
        openSocket(reconnectForce)
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            sendJson(JSONObject().put("type", "ping"))
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            val now = System.currentTimeMillis()
            val idle = now - lastFrameAt
            if (socketOpen) {
                val limit = if (ChannelHub.phase == ChannelPhase.CONNECTED) DEAD_MS else HANDSHAKE_MS
                if (idle > limit) {
                    // 只记原因和时长，不记帧内容
                    Log.w(TAG, "看门狗：${idle / 1000} 秒没有帧，断开重连")
                    dropAndReconnect("心跳超时")
                }
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ------------------------------------------------------------ 启停

    /** 由 KeepAliveService 在服务 onCreate 里调用。 */
    fun start() {
        if (started) return
        started = true
        attempt = 0
        main.post { openSocket() }
        main.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
        main.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    /** 由 KeepAliveService 在服务 onDestroy 里调用。 */
    fun stop() {
        started = false
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(reconnectNowRunnable)
        main.removeCallbacks(heartbeatRunnable)
        main.removeCallbacks(watchdogRunnable)
        reconnectScheduled = false
        reconnectPending = false
        socketOpen = false
        try {
            webSocket?.close(1000, "client stop")
        } catch (t: Throwable) {
            webSocket?.cancel()
        }
        webSocket = null
        ChannelHub.publishPhase(ChannelPhase.STOPPED, "已停止")
    }

    /**
     * 重新连接。默认（force=false）只在「没有连接」时才真的重开：
     *   - 已有一条在握手/已连上 → 直接忽略（服务启动时网络回调会误触一次，以前这里会
     *     把刚建好的连接顶掉，服务端只看到 code=1006，真机就表现为「一秒一断」）；
     *   - 150ms 防抖，连点也只排一次；
     *   - 旧连接用正常 close(1000) 收尾（服务端日志是 1000，不再是 1006）。
     * 配对成功 / 换地址 / 撤销配对这类「必须换链路」的场景传 force=true
     * （openSocket 会先正常关闭旧连接再重开）。
     */
    fun reconnectNow(force: Boolean = false) {
        if (!started) {
            start()
            return
        }
        if (!force && webSocket != null) {
            Log.i(TAG, "已有一条连接在建立/运行中，忽略这次重连请求")
            return
        }
        if (reconnectPending) {
            Log.i(TAG, "重连请求已在排队，忽略重复调用")
            return
        }
        reconnectForce = force
        reconnectPending = true
        attempt = 0
        main.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        main.postDelayed(reconnectNowRunnable, 150)
    }

    /** 网络恢复（ConnectivityManager 回调）：立刻试一次，不等退避计时。 */
    fun onNetworkAvailable() {
        if (!started) return
        // 连接还开着（或正在握手）就别动它：网络回调抖动时重开会把好连接顶成 1006。
        if (socketOpen) return
        if (ChannelHub.phase == ChannelPhase.CONNECTED || ChannelHub.phase == ChannelPhase.CONNECTING) return
        Log.i(TAG, "网络恢复，立刻重连")
        reconnectNow()
    }

    fun onNetworkLost() {
        if (!started) return
        if (ChannelHub.phase == ChannelPhase.CONNECTED) {
            ChannelHub.publishPhase(ChannelPhase.RECONNECTING, "网络已断开，等待恢复…")
        }
    }

    // ------------------------------------------------------------ 发送

    /**
     * 发一条 text 帧，返回是否交给 WebSocket 成功。
     * 消息正文不进日志（这里只有 id）。
     */
    fun sendText(text: String): Boolean {
        if (ChannelHub.phase != ChannelPhase.CONNECTED) return false
        val id = "m${System.currentTimeMillis()}-${messageSeq.incrementAndGet()}"
        val frame = JSONObject()
            .put("type", "text")
            .put("id", id)
            .put("text", text)
        val ok = sendJson(frame)
        if (ok) Log.i(TAG, "已发送 text id=$id")
        return ok
    }

    // ------------------------------------------------------------ M4：音频上传

    /**
     * 一段音频开始上传。size = WAV 文件总字节数，durationMs = 录音时长。
     * 调用方（AudioUploader）负责分块；这里只做帧编码，不落日志正文。
     */
    fun sendAudioStart(id: String, size: Long, durationMs: Long): Boolean {
        if (ChannelHub.phase != ChannelPhase.CONNECTED) return false
        val ok = sendJson(
            JSONObject()
                .put("type", "audioStart")
                .put("id", id)
                .put("mime", "audio/wav")
                .put("size", size)
                .put("durationMs", durationMs)
        )
        if (ok) Log.i(TAG, "audioStart id=$id size=$size dur=$durationMs")
        return ok
    }

    /** 一块 base64 音频（原始 48 KiB → base64 恰好 64 KiB，符合「每块 ≤64 KiB」）。 */
    fun sendAudioChunk(id: String, seq: Int, dataBase64: String): Boolean {
        if (ChannelHub.phase != ChannelPhase.CONNECTED) return false
        return sendJson(
            JSONObject()
                .put("type", "audioChunk")
                .put("id", id)
                .put("seq", seq)
                .put("data", dataBase64)
        )
    }

    fun sendAudioEnd(id: String): Boolean {
        if (ChannelHub.phase != ChannelPhase.CONNECTED) return false
        val ok = sendJson(JSONObject().put("type", "audioEnd").put("id", id))
        if (ok) Log.i(TAG, "audioEnd id=$id")
        return ok
    }

    // ------------------------------------------------------------ M5：命令回执

    /**
     * 回执一条命令的执行结果（`commandResult{id, ok, detail, launched}`）。
     * `detail` 只放机器可读的短码（dialer_opened / app_not_found / ...），
     * 人话由电脑端说；这里不拼长句，也不记参数正文。
     */
    fun sendCommandResult(id: String, ok: Boolean, detail: String, launched: Boolean): Boolean {
        if (ChannelHub.phase != ChannelPhase.CONNECTED) return false
        val sent = sendJson(
            JSONObject()
                .put("type", "commandResult")
                .put("id", id)
                .put("ok", ok)
                .put("detail", detail)
                .put("launched", launched)
        )
        if (sent) Log.i(TAG, "已回 commandResult id=$id ok=$ok launched=$launched detail=$detail")
        return sent
    }

    // ------------------------------------------------------------ M6a：语音回复

    /**
     * 把本机的语音回复开关与音色告诉电脑（`speakConfig{enabled, voice}`）。
     * 每次连上（ready）与设置改动后都要发：电脑端据此决定要不要为回复合成语音。
     */
    fun sendSpeakConfig(enabled: Boolean, voice: String): Boolean {
        if (ChannelHub.phase != ChannelPhase.CONNECTED) return false
        val sent = sendJson(
            JSONObject()
                .put("type", "speakConfig")
                .put("enabled", enabled)
                .put("voice", voice)
        )
        if (sent) Log.i(TAG, "已发送 speakConfig enabled=$enabled voice=$voice")
        return sent
    }

    /** 用落盘的开关与音色发一次 speakConfig（ready 之后、设置改动之后都走这里）。 */
    fun sendSpeakConfigFromPrefs(): Boolean = sendSpeakConfig(
        LocalStore.speakEnabled(appContext),
        LocalStore.speakVoice(appContext)
    )

    /**
     * 用户打断（按录音 / 唤醒命中 / 点别的语音）：告诉电脑别再合成这一段了。
     * 手机会立即停播；这里只是让电脑侧也停止在途合成，省掉后半段的费用。
     */
    fun sendSpeakStop(id: String): Boolean {
        if (ChannelHub.phase != ChannelPhase.CONNECTED) return false
        val sent = sendJson(JSONObject().put("type", "speakStop").put("id", id))
        if (sent) Log.i(TAG, "已发送 speakStop id=$id")
        return sent
    }

    /** 设置页「试听当前音色」：让电脑用固定短句合成一段（不影响对话，也不产生文字消息）。 */
    fun sendSpeakRequest(voice: String): Boolean {
        if (ChannelHub.phase != ChannelPhase.CONNECTED) return false
        val id = "aud${System.currentTimeMillis()}"
        val sent = sendJson(
            JSONObject()
                .put("type", "speakRequest")
                .put("id", id)
                .put("voice", voice)
        )
        if (sent) Log.i(TAG, "已发送 speakRequest id=$id voice=$voice（试听）")
        return sent
    }

    private fun sendJson(frame: JSONObject): Boolean {
        val ws = webSocket ?: return false
        return try {
            ws.send(frame.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "发送失败 type=${frame.optString("type")}：${t.javaClass.simpleName}")
            false
        }
    }

    // ------------------------------------------------------------ 连接

    private fun openSocket(force: Boolean = false) {
        main.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        if (!started) return

        // 幂等保护：已经有连接在握手/运行中时，默认不再开第二条。
        // 服务启动阶段 ConnectivityManager 会立刻回调一次 onAvailable，
        // 以前这里会把 start() 刚建好的 socket 顶掉，服务端只看到 code=1006——
        // 真机上「一秒一断、反复重连」的起点就是这条路径。force=true 才允许换链路。
        if (!force && webSocket != null) {
            Log.i(TAG, "已有连接在建立/运行中，跳过这次 openSocket")
            return
        }

        val rawUrl = LocalStore.serverUrl(appContext)
        val token = LocalStore.token(appContext)
        if (rawUrl.isNullOrBlank() || token.isNullOrBlank()) {
            ChannelHub.publishPhase(ChannelPhase.NOT_PAIRED, "还没配对：去配对页输入电脑上的 6 位码")
            return
        }
        val wsUrl = normalizeWsUrl(rawUrl)
        if (wsUrl == null) {
            ChannelHub.publishPhase(ChannelPhase.STOPPED, "服务端地址不合法：$rawUrl")
            return
        }

        val seq = ++connectSeq
        socketOpen = false
        lastFrameAt = System.currentTimeMillis()
        // 换连接前先把旧的正常关掉：直接 cancel() 不发 close 帧，服务端只会记 code=1006，
        // 看起来像网络异常，真机上就误判成「反复重连」。正常 close(1000) 的代价只是一次握手。
        val previous = webSocket
        webSocket = null
        if (previous != null) {
            Log.i(TAG, "换连接：先正常关闭旧连接")
            try {
                previous.close(1000, "reopen")
            } catch (t: Throwable) {
                previous.cancel()
            }
        }
        ChannelHub.publishPhase(
            if (attempt == 0) ChannelPhase.CONNECTING else ChannelPhase.RECONNECTING,
            if (attempt == 0) "连接中…" else "重连中…（第 ${attempt + 1} 次尝试）"
        )
        try {
            val request = Request.Builder().url(wsUrl).build()
            webSocket = http.newWebSocket(request, WsListener(seq, token))
        } catch (t: Throwable) {
            Log.e(TAG, "建立 WebSocket 失败：${t.javaClass.simpleName}")
            scheduleReconnect("建连失败")
        }
    }

    private fun normalizeWsUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) return null
        return trimmed.trimEnd('/').ifEmpty { null }
    }

    private inner class WsListener(private val seq: Int, private val token: String) : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            main.post {
                if (seq != connectSeq) return@post
                socketOpen = true
                lastFrameAt = System.currentTimeMillis()
                Log.i(TAG, "WebSocket 已打开，发送 hello（token 不落日志）")
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("deviceId", LocalStore.deviceId(appContext))
                    .put("token", token)
                    .put("proto", PROTO)
                ws.send(hello.toString())
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            main.post {
                if (seq != connectSeq) return@post
                lastFrameAt = System.currentTimeMillis()
                handleFrame(text, ws)
            }
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            main.post {
                if (seq != connectSeq) return@post
                lastFrameAt = System.currentTimeMillis()
                // 音频走 JSON 的 audioChunk（base64），不收二进制帧
                Log.i(TAG, "收到二进制帧（${bytes.size} 字节），回 unsupported")
                sendUnsupported("binary")
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            main.post {
                if (seq != connectSeq) return@post
                Log.i(TAG, "WebSocket 已关闭 code=$code")
                // 4000 = 同一设备的新连接把这条顶掉了（服务端 replaced by new connection）。
                // 若立刻 1 秒重连，两个实例就会互相踢，真机上「一秒一断」正是这条路径。
                // 退到最长间隔再试，让其中一端先稳住。
                if (code == 4000) {
                    attempt = BACKOFF_MS.size - 1
                    Log.w(TAG, "被同一设备的新连接顶掉，退避到最长间隔再重连")
                }
                dropAndReconnect("连接关闭（$code）")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            main.post {
                if (seq != connectSeq) return@post
                Log.w(TAG, "WebSocket 失败：${t.javaClass.simpleName}")
                dropAndReconnect(t.javaClass.simpleName ?: "连接失败")
            }
        }
    }

    private fun dropAndReconnect(reason: String) {
        socketOpen = false
        val ws = webSocket
        webSocket = null
        try {
            ws?.cancel()
        } catch (t: Throwable) {
            /* ignore */
        }
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        if (!started) return
        if (reconnectScheduled) return
        val index = attempt.coerceIn(0, BACKOFF_MS.size - 1)
        val delay = BACKOFF_MS[index]
        attempt += 1
        reconnectScheduled = true
        ChannelHub.publishPhase(
            ChannelPhase.RECONNECTING,
            "重连中… ${delay / 1000} 秒后重试（$reason）"
        )
        Log.i(TAG, "安排重连：${delay / 1000} 秒后（$reason）")
        main.postDelayed(reconnectRunnable, delay)
    }

    // ------------------------------------------------------------ 收帧

    private fun handleFrame(text: String, ws: WebSocket) {
        val json = try {
            JSONObject(text)
        } catch (t: Exception) {
            Log.w(TAG, "收到非 JSON 帧，忽略（${text.length} 字节）")
            return
        }
        when (val type = json.optString("type")) {
            "ready" -> {
                attempt = 0
                val serverTime = json.optLong("serverTime", 0L)
                val proto = json.optInt("proto", PROTO)
                ChannelHub.publishPhase(ChannelPhase.CONNECTED, "通道就绪（proto $proto）")
                ChannelHub.publishReady(serverTime)
                Log.i(TAG, "通道就绪（proto=$proto）")
                // M6a：连上就告诉电脑本机的语音回复开关与音色（关着时电脑一次 TTS 请求都不会发）
                sendSpeakConfigFromPrefs()
            }

            "text" -> {
                val body = if (json.has("text")) json.optString("text") else json.optString("delta")
                ChannelHub.publishText(json.optString("id"), body)
            }

            "chunk" -> ChannelHub.publishChunk(json.optString("id"), json.optString("delta"))

            "done" -> ChannelHub.publishDone(json.optString("id"))

            "asrResult" -> {
                val id = json.optString("id")
                val text = json.optString("text")
                val durationMs = json.optLong("durationMs", 0L)
                val costMs = json.optLong("costMs", 0L)
                // 只记元数据，不记识别正文
                Log.i(TAG, "收到 asrResult id=$id textLen=${text.length} dur=$durationMs cost=$costMs")
                ChannelHub.publishAsrResult(id, text, durationMs, costMs)
            }

            "asrError" -> {
                val id = json.optString("id")
                val code = json.optString("code")
                Log.w(TAG, "收到 asrError id=$id code=$code")
                ChannelHub.publishAsrError(id, code, json.optString("message"))
            }

            // M5：电脑下发命令 → 交给执行器（执行器自己回 commandResult）
            "command" -> {
                val id = json.optString("id")
                val kind = json.optString("kind")
                val args = json.optJSONObject("args") ?: JSONObject()
                val say = json.optString("say")
                // 只记 kind/id，不记参数正文（号码、地址这类用户内容不进 logcat）
                Log.i(TAG, "收到 command id=$id kind=$kind argsKeys=${args.keys().asSequence().toList()}")
                ChannelHub.publishCommand(id, kind, args, say)
            }

            // commandResult 只该由手机发；电脑发来就是协议用错，记一条忽略
            "commandResult" -> {
                Log.w(TAG, "收到不该由电脑端发送的帧 type=commandResult，已忽略")
            }

            // M6a：电脑开始念一段话 / 一段 PCM / 念完 / 要求收尾
            "speakStart" -> {
                val id = json.optString("id")
                val replyId = if (json.has("replyId") && !json.isNull("replyId")) json.optString("replyId") else null
                val text = json.optString("text")
                val voice = json.optString("voice")
                val sampleRate = json.optInt("sampleRate", 24000)
                val channels = json.optInt("channels", 1)
                // 只记元数据，不记正文
                Log.i(
                    TAG,
                    "收到 speakStart id=$id replyId=${replyId ?: "-"} voice=$voice " +
                            "textLen=${text.length} rate=$sampleRate ch=$channels"
                )
                ChannelHub.publishSpeakStart(id, replyId, text, voice, sampleRate, channels)
            }

            "speakChunk" -> {
                val id = json.optString("id")
                ChannelHub.publishSpeakChunk(id, json.optInt("seq", 0), json.optString("data"))
            }

            "speakDone" -> ChannelHub.publishSpeakDone(json.optString("id"))

            // 电脑端要求收尾（合成失败）。手机端只停播，不回 speakStop（否则会来回打转）
            "speakStop" -> {
                val id = json.optString("id")
                Log.i(TAG, "收到电脑端 speakStop id=$id reason=${json.optString("reason")}")
                ChannelHub.publishSpeakStopRequested(id)
            }

            // 下面三个只该由手机发；电脑发来就是协议用错，记一条忽略
            "speakConfig", "speakRequest" -> {
                Log.w(TAG, "收到不该由电脑端发送的帧 type=$type，已忽略")
            }

            "ping" -> sendJson(JSONObject().put("type", "pong"))

            "pong" -> Unit

            "error" -> {
                val code = json.optString("code")
                val message = json.optString("message")
                Log.w(TAG, "服务端 error code=$code")
                ChannelHub.publishServerError(code, message)
                when (code) {
                    "auth_failed" -> onAuthFailed()
                    "busy" -> {
                        // 另一台设备在线：退到最长间隔再试，别把服务端刷爆
                        attempt = BACKOFF_MS.size - 1
                    }
                }
            }

            "hello" -> Unit // 不该由电脑端发，忽略

            else -> {
                if (type in RESERVED) {
                    Log.i(TAG, "收到预留帧 type=$type，回 unsupported（不断连）")
                } else {
                    Log.i(TAG, "收到未知帧 type=$type，回 unsupported（不断连）")
                }
                sendUnsupported(type)
            }
        }
    }

    /** 预留帧 / 未知帧：回一条 error{code:"unsupported"}，连接保持不动。 */
    private fun sendUnsupported(type: String) {
        sendJson(
            JSONObject()
                .put("type", "error")
                .put("code", "unsupported")
                .put("message", "frame type $type is not implemented in proto $PROTO")
        )
    }

    private fun onAuthFailed() {
        Log.w(TAG, "配对凭证已失效，清理本地 token")
        LocalStore.clearPairing(appContext)
        ChannelHub.publishPhase(ChannelPhase.NOT_PAIRED, "配对已失效：请重新配对（电脑端重启后拿新码）")
    }
}
