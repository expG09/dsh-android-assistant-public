package com.yanyan.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * M6 聊天页的数据源（**纯界面层**：只听现有事件、只存消息，不改任何功能链路）。
 *
 * 消息从这几条现成的流里来：
 * - [ChannelHub]：电脑的回复（text / chunk 流式 / done）、连接状态、服务端错误；
 * - [AudioUploadHub]：我按住说话录的那段（上传中 / 识别中 / 识别结果 / 失败）；
 * - [CommandHub]：命令执行结果（带重试参数，重试时用同样参数再发一次）；
 * - 聊天页自己键盘发出的文字（[addMyText]）。
 *
 * 存在 `files/chat.json`（最多 300 条），进程重启后还在；写盘有 600ms 防抖、走后台线程。
 */
object ChatStore {
    private const val TAG = "ChatStore"
    private const val FILE_NAME = "chat.json"
    private const val MAX_MESSAGES = 300
    private const val KEEP_MERGE_MS = 20_000L

    const val SIDE_HIM = 0   // 他（电脑 / 砚砚）→ 靠右
    const val SIDE_ME = 1    // 我（用户）→ 靠左

    const val KIND_TEXT = 0
    const val KIND_VOICE = 1      // 我发的语音（有录音缓存，可重播）
    const val KIND_COMMAND = 2    // 命令执行结果（外侧挂重试图标）
    const val KIND_NOTICE = 3     // 系统提示（居中细字，不是气泡）
    const val KIND_FAILED = 4     // 失败（正文写原因，同一位置挂重试）

    data class Message(
        val id: Long,
        val side: Int,
        var kind: Int,
        var text: String,
        val at: Long,
        /** 电脑那条回复的 id（有 TTS 语音缓存时，气泡外侧挂播放图标）。 */
        var replyId: String? = null,
        /** 我的语音录音在聊天缓存里的文件名（[ChatAudioCache]）。 */
        var audioName: String? = null,
        var audioMs: Long = 0L,
        /** 语音上传失败时，点重试用它（[AudioUploader.retry]）。 */
        var uploadKey: String? = null,
        /** 命令结果的重试参数：kind + args 的 JSON 字符串。 */
        var commandKind: String? = null,
        var commandArgs: String? = null,
        var commandSay: String? = null,
        /** 最近一次更新的时间（流式回复 / 上传状态原地刷新时用）。 */
        var updatedAt: Long = at,
    )

    private data class PendingCommand(val kind: String, val args: String, val say: String, val at: Long)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "yanyan-chat-store").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val messages = ArrayList<Message>()
    private var nextId = 1L
    private var app: Context? = null
    private var loaded = false

    /** 流式回复：通道 id → 消息 id（同一条回复边收边拼，不刷屏）。 */
    private val streaming = HashMap<String, Long>()

    /** 最近一条命令（命令结果到达时，把重试参数挂到那条结果消息上）。 */
    private var lastCommand: PendingCommand? = null

    private val saveTick = Runnable { writeAsync() }

    // ------------------------------------------------------------ 初始化

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        load(c)
        ChannelHub.addListener(channelListener)
        AudioUploadHub.addListener(audioListener)
        CommandHub.addListener(commandListener)
        Log.i(TAG, "聊天存储就绪：已载入 ${messages.size} 条历史")
    }

    fun snapshot(): List<Message> = synchronized(messages) { messages.toList() }

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 聊天页键盘发出的文字（发送成功后由界面调）。 */
    fun addMyText(text: String) {
        if (text.isBlank()) return
        append(
            Message(
                id = newId(), side = SIDE_ME, kind = KIND_TEXT,
                text = text.trim(), at = System.currentTimeMillis()
            )
        )
    }

    /** 点命令结果的「重试」前先报告一次，让新结果也能再重试。 */
    fun expectCommand(kind: String, args: String, say: String) {
        lastCommand = PendingCommand(kind, args, say, System.currentTimeMillis())
    }

    // ------------------------------------------------------------ 通道事件

    private val channelListener = object : ChannelListener {
        override fun onReady(serverTime: Long) {
            lastNotice = null
            notice("已连上电脑")
        }

        override fun onIncomingText(id: String, text: String) {
            if (text.isBlank()) return
            Log.i(TAG, "收到整段回复 id=$id 长度=${text.length}")
            val streamedId = streaming.remove(id)
            val streamed = streamedId?.let { findById(it) }
            if (streamed != null) {
                streamed.text = text
                streamed.updatedAt = System.currentTimeMillis()
                notifyChanged()
                scheduleSave()
                return
            }
            addHimText(id, text)
        }

        override fun onIncomingChunk(id: String, delta: String) {
            if (delta.isEmpty()) return
            val msgId = streaming[id]
            val msg = msgId?.let { findById(it) }
            if (msg != null) {
                msg.text += delta
                msg.updatedAt = System.currentTimeMillis()
                Log.i(TAG, "流式续写 id=$id 累计=${msg.text.length}")
                notifyChanged()
            } else {
                val m = Message(
                    id = newId(), side = SIDE_HIM, kind = KIND_TEXT,
                    text = delta, at = System.currentTimeMillis(),
                    replyId = id.ifBlank { null }
                )
                streaming[id] = m.id
                append(m)
            }
        }

        override fun onIncomingDone(id: String) {
            streaming.remove(id)
            scheduleSave()
        }

        override fun onServerError(code: String, message: String) {
            notice("服务端错误 $code：$message")
        }

        override fun onPhaseChanged(phase: ChannelPhase, detail: String) {
            when (phase) {
                ChannelPhase.RECONNECTING -> noticeOnce("正在重连电脑…")
                ChannelPhase.NOT_PAIRED -> noticeOnce("通道未配对：去 设置 › 通道与配对")
                ChannelPhase.STOPPED -> noticeOnce("通道已停止")
                else -> Unit
            }
        }

        override fun onIncomingCommand(id: String, kind: String, args: JSONObject, say: String) {
            lastCommand = PendingCommand(kind, args.toString(), say, System.currentTimeMillis())
        }
    }

    /**
     * 电脑回了一句人话：如果紧跟在命令结果后面、说的又是同一件事，就并入那条命令消息
     * （保留重试参数、挂上 TTS 语音），不重复显示两条几乎一样的气泡。
     */
    private fun addHimText(id: String, text: String) {
        val now = System.currentTimeMillis()
        val last = synchronized(messages) { messages.lastOrNull() }
        if (last != null && last.kind == KIND_COMMAND && last.replyId == null &&
            now - last.at <= KEEP_MERGE_MS
        ) {
            val a = normalize(last.text)
            val b = normalize(text)
            val same = a == b ||
                    (a.length >= 6 && b.contains(a)) ||
                    (b.length >= 6 && a.contains(b))
            if (same) {
                if (text.length >= last.text.length) last.text = text
                last.replyId = id.ifBlank { null }
                last.updatedAt = now
                // 合并后的重试参数不能丢
                if (last.commandKind == null) lastCommand?.let {
                    last.commandKind = it.kind
                    last.commandArgs = it.args
                    last.commandSay = it.say
                }
                notifyChanged()
                scheduleSave()
                return
            }
        }
        append(
            Message(
                id = newId(), side = SIDE_HIM, kind = KIND_TEXT,
                text = text, at = now, replyId = id.ifBlank { null }
            )
        )
    }

    private fun normalize(s: String): String =
        s.replace(Regex("[\\s，。！？、,.!?：:；;（）()「」\"']"), "")

    // ------------------------------------------------------------ 命令结果

    private val commandListener: (String) -> Unit = { line ->
        val cmd = lastCommand
        append(
            Message(
                id = newId(), side = SIDE_HIM, kind = KIND_COMMAND,
                text = line, at = System.currentTimeMillis(),
                commandKind = cmd?.kind,
                commandArgs = cmd?.args,
                commandSay = cmd?.say
            )
        )
    }

    // ------------------------------------------------------------ 我的语音（录音 → 上传 → 识别）

    private val audioListener: (AudioUploadItem) -> Unit = { item ->
        upsertAudio(item)
    }

    private fun upsertAudio(item: AudioUploadItem) {
        val existing = synchronized(messages) {
            messages.firstOrNull { it.uploadKey == item.key }
        }
        val text = when (item.phase) {
            AudioUploadPhase.UPLOADING -> "正在上传…"
            AudioUploadPhase.WAITING_ASR -> "识别中…"
            AudioUploadPhase.DONE -> item.text?.takeIf { it.isNotBlank() } ?: "（没听清，这次没有文字）"
            AudioUploadPhase.FAILED, AudioUploadPhase.BLOCKED ->
                "语音没发出去：${item.note ?: "上传失败"}"
        }
        val failed = item.phase == AudioUploadPhase.FAILED || item.phase == AudioUploadPhase.BLOCKED
        if (existing != null) {
            existing.text = text
            existing.kind = if (failed) KIND_FAILED else KIND_VOICE
            existing.audioMs = item.recordedMs
            existing.updatedAt = System.currentTimeMillis()
            notifyChanged()
            if (item.phase == AudioUploadPhase.DONE) scheduleSave()
            return
        }
        val msg = Message(
            id = newId(), side = SIDE_ME,
            kind = if (failed) KIND_FAILED else KIND_VOICE,
            text = text, at = item.createdAt,
            audioMs = item.recordedMs,
            uploadKey = item.key
        )
        append(msg, save = item.phase == AudioUploadPhase.DONE)
        // 录音刚落地就先复制一份聊天缓存（原文件识别成功后会被删，见 ChatAudioCache 注释）
        val c = app ?: return
        val src = item.file
        io.execute {
            val dst = ChatAudioCache.cacheRecording(c, src)
            if (dst != null) main.post {
                msg.audioName = dst.name
                notifyChanged()
            }
        }
    }

    // ------------------------------------------------------------ 追加 / 通知 / 落盘

    fun notice(text: String) {
        append(Message(id = newId(), side = SIDE_HIM, kind = KIND_NOTICE, text = text, at = System.currentTimeMillis()))
    }

    private var lastNotice: String? = null

    private fun noticeOnce(text: String) {
        if (text == lastNotice) return
        lastNotice = text
        notice(text)
    }

    private fun append(msg: Message, save: Boolean = true) {
        synchronized(messages) {
            messages.add(msg)
            while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        }
        notifyChanged()
        if (save) scheduleSave()
    }

    private fun findById(id: Long): Message? = synchronized(messages) { messages.firstOrNull { it.id == id } }

    private fun newId(): Long = synchronized(messages) { nextId++ }

    private fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveTick)
        main.postDelayed(saveTick, 600L)
    }

    private fun writeAsync() {
        val c = app ?: return
        val json = serialize()
        io.execute {
            try {
                File(c.filesDir, FILE_NAME).writeText(json)
            } catch (t: Throwable) {
                Log.w(TAG, "聊天记录写盘失败：${t.javaClass.simpleName}")
            }
        }
    }

    private fun serialize(): String {
        val arr = JSONArray()
        synchronized(messages) {
            messages.forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("side", m.side)
                    put("kind", m.kind)
                    put("text", m.text)
                    put("at", m.at)
                    m.replyId?.let { put("replyId", it) }
                    m.audioName?.let { put("audioName", it) }
                    put("audioMs", m.audioMs)
                    m.uploadKey?.let { put("uploadKey", it) }
                    m.commandKind?.let { put("commandKind", it) }
                    m.commandArgs?.let { put("commandArgs", it) }
                    m.commandSay?.let { put("commandSay", it) }
                })
            }
        }
        return arr.toString()
    }

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.isFile) return
        try {
            val arr = JSONArray(f.readText())
            synchronized(messages) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val m = Message(
                        id = o.optLong("id", nextId++),
                        side = if (o.optInt("side", SIDE_HIM) == SIDE_ME) SIDE_ME else SIDE_HIM,
                        kind = o.optInt("kind", KIND_TEXT),
                        text = o.optString("text"),
                        at = o.optLong("at", System.currentTimeMillis()),
                        replyId = o.optString("replyId").takeIf { it.isNotEmpty() },
                        audioName = o.optString("audioName").takeIf { it.isNotEmpty() },
                        audioMs = o.optLong("audioMs", 0L),
                        uploadKey = o.optString("uploadKey").takeIf { it.isNotEmpty() },
                        commandKind = o.optString("commandKind").takeIf { it.isNotEmpty() },
                        commandArgs = o.optString("commandArgs").takeIf { it.isNotEmpty() },
                        commandSay = o.optString("commandSay").takeIf { it.isNotEmpty() },
                        updatedAt = o.optLong("at", System.currentTimeMillis())
                    )
                    messages.add(m)
                    if (m.id >= nextId) nextId = m.id + 1
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "聊天记录读取失败（按空历史处理）：${t.javaClass.simpleName}")
            synchronized(messages) { messages.clear() }
        }
    }
}

