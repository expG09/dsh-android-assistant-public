package com.yanyan.assistant

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M2 调试页（也是 M6a 的「聊天页」）：一个输入框 + 一个消息列表 + 连接状态。
 * M4：语音消息也落在这个列表里（前缀「语音」），失败的那一行点一下就是重试。
 * M6a：电脑的回复右边挂一个播放图标 —— 点一下重播本地缓存的语音（不再请求 TTS）。
 *
 * 发出去的和收到的都带时间戳显示；断线时状态栏显示「重连中…」，不静默。
 * 这里只调 ChannelClient.sendText，连接本身由 KeepAliveService 持有；播放也在服务里（TtsPlayer）。
 */
class DebugActivity : BaseActivity(), ChannelListener, TtsListener {

    /** 消息列表的一行：文字 + 它对应的电脑回复 id（用于挂播放图标）。 */
    private class Row(var text: String, var replyId: String? = null)

    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var lvMessages: ListView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnReconnect: Button

    private val lines = ArrayList<Row>()
    private lateinit var adapter: MessageAdapter
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    /** chunk 流：id -> 正在拼接的那一行的下标（M5 的流式回复用） */
    private val streamingIndex = HashMap<String, Int>()

    /** M4：语音记录 key -> 消息列表那一行的下标（状态原地刷新，不刷屏） */
    private val audioIndex = HashMap<String, Int>()

    /** M4：语音记录的增量更新（上传中 → 识别中 → 结果 / 失败） */
    private val audioListener: (AudioUploadItem) -> Unit = { item ->
        runOnUiThread { renderAudioItem(item) }
    }

    companion object {
        private const val TAG = "DebugActivity"
        private const val MAX_LINES = 300
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        setupTopBar(getString(R.string.title_debug), showBack = true)

        tvStatus = findViewById(R.id.tv_conn_status)
        tvDetail = findViewById(R.id.tv_conn_detail)
        lvMessages = findViewById(R.id.lv_messages)
        etMessage = findViewById(R.id.et_message)
        btnSend = findViewById(R.id.btn_send)
        btnReconnect = findViewById(R.id.btn_reconnect)

        adapter = MessageAdapter()
        lvMessages.adapter = adapter

        btnSend.setOnClickListener { sendMessage() }
        btnReconnect.setOnClickListener {
            KeepAliveService.requestChannelReconnect(this)
            toast("正在重连…")
            render()
        }

        // M4：失败/被拦下的「语音」行，点一下重发一次（不做自动重传）
        lvMessages.setOnItemClickListener { _, _, position, _ ->
            val key = audioIndex.entries.firstOrNull { it.value == position }?.key
                    ?: return@setOnItemClickListener
            val item = AudioUploadHub.find(key) ?: return@setOnItemClickListener
            if (item.phase == AudioUploadPhase.FAILED || item.phase == AudioUploadPhase.BLOCKED) {
                AudioUploader.retry(key)
                toast("正在重试…")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ChannelHub.addListener(this)
        AudioUploadHub.addListener(audioListener)
        TtsPlayer.addListener(this)
        if (!ServiceState.running) {
            try {
                KeepAliveService.start(this)
            } catch (t: Throwable) {
                toast("启动常驻服务失败：${t.javaClass.simpleName}")
            }
        }
        // 界面重新打开时先把已有的语音记录补上（上传在后台继续，不依赖这个页面）
        AudioUploadHub.snapshot().forEach { renderAudioItem(it) }
        render()
        adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        ChannelHub.removeListener(this)
        AudioUploadHub.removeListener(audioListener)
        TtsPlayer.removeListener(this)
        super.onPause()
    }

    // ------------------------------------------------------------ 发送

    private fun sendMessage() {
        val text = etMessage.text.toString()
        if (text.isEmpty()) return
        if (ChannelHub.phase != ChannelPhase.CONNECTED) {
            toast("还没连上，先去配对页配对（或点「重新连接」）")
            return
        }
        val ok = KeepAliveService.channel?.sendText(text) ?: false
        if (ok) {
            appendLine("我：$text")
            etMessage.setText("")
        } else {
            toast("发送失败，连接可能刚断，稍后再试")
        }
    }

    // ------------------------------------------------------------ 状态与消息

    private fun render() {
        val phase = ChannelHub.phase
        tvStatus.text = when (phase) {
            ChannelPhase.CONNECTED -> "已连上"
            ChannelPhase.CONNECTING -> "连接中…"
            ChannelPhase.RECONNECTING -> "重连中…"
            ChannelPhase.NOT_PAIRED -> "未配对"
            ChannelPhase.STOPPED -> "通道未运行"
        }
        val serverTimeText = if (ChannelHub.serverTime > 0L) {
            " · 服务端时间 ${fullFormat.format(Date(ChannelHub.serverTime))}"
        } else {
            ""
        }
        tvDetail.text = ChannelHub.detail + serverTimeText
    }

    private fun appendLine(body: String, replyId: String? = null) {
        lines.add(Row("${timeFormat.format(Date())}  $body", replyId))
        trimLines()
        adapter.notifyDataSetChanged()
        if (lines.isNotEmpty()) lvMessages.setSelection(lines.size - 1)
    }

    /** 只留最近 MAX_LINES 行；正在流式拼接的行下标与语音行下标跟着一起平移。 */
    private fun trimLines() {
        if (lines.size <= MAX_LINES) return
        val drop = lines.size - MAX_LINES
        repeat(drop) { lines.removeAt(0) }
        val adjusted = HashMap<String, Int>()
        streamingIndex.forEach { (id, index) ->
            val moved = index - drop
            if (moved >= 0) adjusted[id] = moved
        }
        streamingIndex.clear()
        streamingIndex.putAll(adjusted)
        val adjustedAudio = HashMap<String, Int>()
        audioIndex.forEach { (key, index) ->
            val moved = index - drop
            if (moved >= 0) adjustedAudio[key] = moved
        }
        audioIndex.clear()
        audioIndex.putAll(adjustedAudio)
    }

    // ------------------------------------------------------------ M4 语音消息

    /**
     * 一条语音记录显示成一行，前缀「语音」，与普通消息同一套样式。
     * 失败 / 被本地拦下的行，用户点一下就能重试一次（不自动重传）。
     */
    private fun renderAudioItem(item: AudioUploadItem) {
        val line = audioLine(item)
        val index = audioIndex[item.key]
        if (index != null && index < lines.size) {
            lines[index].text = line
            adapter.notifyDataSetChanged()
            return
        }
        lines.add(Row(line))
        audioIndex[item.key] = lines.size - 1
        trimLines()
        adapter.notifyDataSetChanged()
        if (lines.isNotEmpty()) lvMessages.setSelection(lines.size - 1)
    }

    private fun audioLine(item: AudioUploadItem): String {
        val stamp = timeFormat.format(Date(item.createdAt))
        val prefix = getString(R.string.debug_voice_prefix)
        val body = when (item.phase) {
            AudioUploadPhase.UPLOADING -> "$prefix：${item.note ?: "上传中…"}"
            AudioUploadPhase.WAITING_ASR -> "$prefix：识别中…"
            AudioUploadPhase.DONE ->
                "$prefix：${item.text?.takeIf { it.isNotBlank() } ?: "（没识别出文字）"}"

            AudioUploadPhase.FAILED, AudioUploadPhase.BLOCKED ->
                "$prefix：${item.note ?: "上传失败"}，点重试"
        }
        return "$stamp  $body"
    }

    override fun onPhaseChanged(phase: ChannelPhase, detail: String) {
        render()
    }

    override fun onReady(serverTime: Long) {
        appendLine("— 已连上，服务端时间 ${fullFormat.format(Date(serverTime))} —")
        render()
    }

    override fun onIncomingText(id: String, text: String) {
        streamingIndex.remove(id)
        // M6a：replyId 记下来 —— 这条回复的语音到了之后，这一行右边出现播放图标
        appendLine("电脑：$text", id)
    }

    override fun onIncomingChunk(id: String, delta: String) {
        val index = streamingIndex[id]
        if (index == null || index >= lines.size) {
            lines.add(Row("${timeFormat.format(Date())}  电脑：$delta", id))
            streamingIndex[id] = lines.size - 1
            trimLines()
        } else {
            lines[index].text += delta
        }
        adapter.notifyDataSetChanged()
        if (lines.isNotEmpty()) lvMessages.setSelection(lines.size - 1)
    }

    override fun onIncomingDone(id: String) {
        streamingIndex.remove(id)
    }

    override fun onServerError(code: String, message: String) {
        appendLine("！服务端错误 $code：$message")
    }

    /**
     * M5：电脑下发了一条命令。
     *
     * 只在这里记一行「命令（技术信息，调试页用）」；**不**把命令结果写进对话正文——
     * 按 M5 文档「命令结果不进对话正文，另存事件记录」：结果走 logcat / 电脑端事件日志，
     * 对话里体现为电脑回的那一句人话（`电脑：…`）。
     */
    override fun onIncomingCommand(id: String, kind: String, args: org.json.JSONObject, say: String) {
        appendLine("${getString(R.string.debug_command_prefix)}：$kind $args（$say）")
    }

    // ------------------------------------------------------------ M6a 播放图标

    override fun onTtsChanged(replyId: String?) {
        runOnUiThread { adapter.notifyDataSetChanged() }
    }

    /** 点播放图标：还在播 → 停；否则读本地缓存重播（不会向电脑再要一次语音）。 */
    private fun onPlayIconClicked(replyId: String) {
        if (TtsPlayer.isPlaying(replyId)) {
            TtsPlayer.stopPlayback("ui_stop")
            Log.i(TAG, "用户点了停止：replyId=$replyId")
            return
        }
        val ok = TtsPlayer.replay(replyId)
        Log.i(TAG, "用户点了重播：replyId=$replyId ${if (ok) "开始读本地缓存" else "没有缓存"}")
        if (!ok) toast(getString(R.string.tts_nothing_to_replay))
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------ 列表

    private inner class MessageAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@DebugActivity)

        override fun getCount(): Int = lines.size

        override fun getItem(position: Int): Any = lines[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_debug_line, parent, false)
            val row = lines[position]
            view.findViewById<TextView>(R.id.tv_line).text = row.text

            val btnPlay = view.findViewById<ImageButton>(R.id.btn_play)
            val replyId = row.replyId
            val playing = replyId != null && TtsPlayer.isPlaying(replyId)
            val hasAudio = replyId != null && TtsPlayer.hasAudio(replyId)
            if (replyId != null && (playing || hasAudio)) {
                btnPlay.visibility = View.VISIBLE
                btnPlay.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
                btnPlay.contentDescription = getString(
                    if (playing) R.string.tts_stop_desc else R.string.tts_play_desc
                )
                btnPlay.setOnClickListener { onPlayIconClicked(replyId) }
            } else {
                btnPlay.visibility = View.GONE
                btnPlay.setOnClickListener(null)
            }
            return view
        }
    }

}
