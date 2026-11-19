package com.yanyan.assistant

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.content.res.ColorStateList
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * M6 聊天页（照 QQ 的形态）。
 *
 * 消息来源全部是现成事件（[ChatStore] 统一收口，本页只读不改）：
 * - 他（电脑）的回复靠右、我的靠左；气泡最大宽度约屏宽 75%，对侧留白，不占满整行；
 * - 动作图标都在**气泡外侧**（靠右的气泡图标贴左边、靠左的贴右边，垂直居中，图标 22dp / 可点 48dp）；
 * - 长按一条消息弹「复制」；语音（他的 TTS / 我的录音）点一下重播、播放中变停止；
 * - 命令结果与失败消息外侧挂重试图标，点一下用同样参数再执行一次。
 *
 * 底部输入区（QQ 形态）：左下角一个键在「语音 / 键盘」之间切；语音态输入框位置变成
 * 一条「按住 说话」；右侧键盘态是发送键、语音态是加号占位。
 */
class ChatActivity : BaseActivity(), TtsListener, ChannelListener {

    private lateinit var lvChat: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var etChat: EditText
    private lateinit var btnInputMode: ImageButton
    private lateinit var btnHold: TextView
    private lateinit var btnSend: ImageButton
    private lateinit var tvStatusDot: TextView

    private lateinit var adapter: ChatAdapter

    /** 语音态 / 键盘态。 */
    private var voiceMode = false

    /** 这次按下是我起的头（松手就结束）还是点了正在进行的唤醒录音（点一下停）。 */
    private var pressRecording = false
    private var wakeStopOnUp = false

    private val main = Handler(Looper.getMainLooper())

    /** 我的录音重播（读聊天缓存里的 WAV，不碰 TTS）。 */
    private var mePlayer: MediaPlayer? = null
    private var playingMeId = -1L

    private val ticker = object : Runnable {
        override fun run() {
            updateHoldLabel()
            if (::btnHold.isInitialized && ListenController.recording) main.postDelayed(this, 100)
        }
    }

    private val storeListener: () -> Unit = {
        runOnUiThread { refresh(scrollBottom = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        setupTopBar("聊天", showBack = true)
        tvStatusDot = addTopBarText("●", R.attr.yyTextSecondary, 12f)
        addTopBarAction(R.drawable.ic_settings, "设置") {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        lvChat = findViewById(R.id.lv_chat)
        tvEmpty = findViewById(R.id.tv_chat_empty)
        etChat = findViewById(R.id.et_chat)
        btnInputMode = findViewById(R.id.btn_input_mode)
        btnHold = findViewById(R.id.btn_hold_talk_chat)
        btnSend = findViewById(R.id.btn_chat_send)

        styleInputBar()

        adapter = ChatAdapter()
        lvChat.adapter = adapter
        lvChat.setOnItemLongClickListener { _, _, position, _ ->
            val msg = adapter.itemAt(position) ?: return@setOnItemLongClickListener false
            if (msg.kind == ChatStore.KIND_NOTICE) return@setOnItemLongClickListener false
            showCopyDialog(msg)
            true
        }

        ChatStore.init(applicationContext)
        ChatStore.addListener(storeListener)
        refresh(scrollBottom = true)

        // 输入区：语音 / 键盘切换
        btnInputMode.setOnClickListener { setVoiceMode(!voiceMode) }
        btnSend.setOnClickListener {
            if (voiceMode) {
                toast("语音模式下按住左边那条说话，松手就发")
            } else {
                sendTyped()
            }
        }
        etChat.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) lvChat.post { scrollToBottom() }
        }

        attachHoldToTalk()
        setVoiceMode(false)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        ChannelHub.addListener(this)
        TtsPlayer.addListener(this)
        updateStatus()
        refresh(scrollBottom = false)
        main.post(ticker)
        // 进来就把服务拉起来（与主界面一致：不让人多点一次；服务已在跑就什么都不做）
        if (!ServiceState.running) {
            try {
                KeepAliveService.start(this)
            } catch (t: Throwable) {
                toast("启动常驻服务失败：${t.javaClass.simpleName}")
            }
        }
    }

    override fun onPause() {
        stopMyAudio()
        main.removeCallbacks(ticker)
        ChannelHub.removeListener(this)
        TtsPlayer.removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        ChatStore.removeListener(storeListener)
        stopMyAudio()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 外观（主题色）

    private fun styleInputBar() {
        etChat.setTextColor(c(R.attr.yyText))
        etChat.setHintTextColor(c(R.attr.yyTextSecondary))
        etChat.background = outlined(c(R.attr.yySurfaceAlt), c(R.attr.yyDivider), 22)
        btnInputMode.imageTintList = ColorStateList.valueOf(c(R.attr.yyIcon))
        btnSend.imageTintList = ColorStateList.valueOf(c(R.attr.yyAccent))
        btnHold.setTextColor(c(R.attr.yyHoldText))
        updateHoldLabel()
    }

    /** 语音态 / 键盘态：输入框与「按住说话」互换，右侧键换成加号。 */
    private fun setVoiceMode(voice: Boolean) {
        voiceMode = voice
        etChat.visibility = if (voice) View.GONE else View.VISIBLE
        btnHold.visibility = if (voice) View.VISIBLE else View.GONE
        btnInputMode.setImageResource(if (voice) R.drawable.ic_keyboard else R.drawable.ic_mic)
        btnInputMode.contentDescription = if (voice) "切换到键盘输入" else "切换到按住说话"
        btnSend.setImageResource(if (voice) R.drawable.ic_plus else R.drawable.ic_send)
        btnSend.contentDescription = if (voice) "更多" else "发送"
        btnSend.imageTintList = ColorStateList.valueOf(c(R.attr.yyAccent))
        if (voice) {
            hideKeyboard()
            main.removeCallbacks(ticker)
            main.post(ticker)
        }
    }

    private fun updateHoldLabel() {
        if (!::btnHold.isInitialized) return
        val recording = ListenController.recording
        if (recording) {
            val sec = String.format(Locale.CHINA, "%.1f", ListenController.liveRecordedMs() / 1000.0)
            btnHold.text = "松手结束 · $sec 秒"
            btnHold.background = rounded(c(R.attr.yyDanger), 22)
        } else {
            btnHold.text = "按住 说话"
            btnHold.background = rounded(c(R.attr.yyAccent), 22)
        }
    }

    // ------------------------------------------------------------ 按住说话（复用 M3 录音链路）

    private fun attachHoldToTalk() {
        btnHold.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (ListenController.recording) {
                        // 唤醒词已经触发了录音：点一下等于「停止」
                        wakeStopOnUp = true
                        pressRecording = false
                    } else {
                        val error = ListenController.startRecording()
                        if (error != null) {
                            toast(error)
                            return@setOnTouchListener false
                        }
                        pressRecording = true
                        wakeStopOnUp = false
                    }
                    main.removeCallbacks(ticker)
                    main.post(ticker)
                    updateHoldLabel()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when {
                        pressRecording -> ListenController.stopRecording()
                        wakeStopOnUp -> {
                            if (ListenController.stopRecordingManually()) toast("正在收尾并上传…")
                        }
                    }
                    pressRecording = false
                    wakeStopOnUp = false
                    v.performClick()
                    updateHoldLabel()
                    main.removeCallbacks(ticker)
                    true
                }

                else -> false
            }
        }
    }

    // ------------------------------------------------------------ 键盘发送

    private fun sendTyped() {
        val text = etChat.text.toString().trim()
        if (text.isEmpty()) return
        if (ChannelHub.phase != ChannelPhase.CONNECTED) {
            toast("还没连上电脑：去 设置 › 通道与配对")
            return
        }
        val ok = KeepAliveService.channel?.sendText(text) ?: false
        if (!ok) {
            toast("发送失败，连接可能刚断")
            return
        }
        ChatStore.addMyText(text)
        etChat.setText("")
        refresh(scrollBottom = true)
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(etChat.windowToken, 0)
        } catch (t: Throwable) {
            // 忽略：没有输入法服务也能用
        }
    }

    // ------------------------------------------------------------ 状态点

    override fun onPhaseChanged(phase: ChannelPhase, detail: String) {
        runOnUiThread { updateStatus() }
    }

    override fun onReady(serverTime: Long) {
        runOnUiThread { updateStatus() }
    }

    private fun updateStatus() {
        if (!::tvStatusDot.isInitialized) return
        val phase = ChannelHub.phase
        val text = when (phase) {
            ChannelPhase.CONNECTED -> "已连上"
            ChannelPhase.CONNECTING -> "连接中"
            ChannelPhase.RECONNECTING -> "重连中"
            ChannelPhase.NOT_PAIRED -> "未配对"
            ChannelPhase.STOPPED -> "未运行"
        }
        val color = when (phase) {
            ChannelPhase.CONNECTED -> c(R.attr.yyOk)
            ChannelPhase.RECONNECTING, ChannelPhase.CONNECTING -> c(R.attr.yyWarn)
            ChannelPhase.NOT_PAIRED -> c(R.attr.yyDanger)
            else -> c(R.attr.yyTextSecondary)
        }
        tvStatusDot.text = "● $text"
        tvStatusDot.setTextColor(color)
    }

    // ------------------------------------------------------------ 列表

    private fun refresh(scrollBottom: Boolean) {
        val list = ChatStore.snapshot()
        adapter.submit(list)
        // 流式回复是在原来那一行上边长出来的，显式让 ListView 重新绑一次可见行，
        // 免得只换了文字没重量尺寸（长文本会只显示第一行）。
        lvChat.invalidateViews()
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (scrollBottom) scrollToBottom()
    }

    private fun scrollToBottom() {
        if (adapter.count == 0) return
        lvChat.setSelection(adapter.count - 1)
    }

    private fun showCopyDialog(msg: ChatStore.Message) {
        val text = if (msg.text.isBlank()) "（空消息）" else msg.text
        AlertDialog.Builder(this)
            .setTitle("这条消息")
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("砚砚助手", text))
                toast("已复制")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 语音播放

    override fun onTtsChanged(replyId: String?) {
        runOnUiThread { adapter.notifyDataSetChanged() }
    }

    private fun onPlayTts(replyId: String) {
        if (TtsPlayer.isPlaying(replyId)) {
            TtsPlayer.stopPlayback("chat_stop")
        } else {
            stopMyAudio()
            if (!TtsPlayer.replay(replyId)) toast("这条回复没有可重播的语音缓存")
        }
        adapter.notifyDataSetChanged()
    }

    private fun onPlayMine(msg: ChatStore.Message) {
        if (playingMeId == msg.id) {
            stopMyAudio()
            return
        }
        val name = msg.audioName
        if (name == null) {
            toast("这段录音没有本地缓存了")
            return
        }
        val file = ChatAudioCache.fileFor(this, name)
        if (!file.isFile) {
            toast("这段录音没有本地缓存了")
            return
        }
        stopMyAudio()
        TtsPlayer.stopPlayback("chat_my_voice")
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener { stopMyAudio() }
            mePlayer = mp
            playingMeId = msg.id
        } catch (t: Throwable) {
            toast("播放失败：${t.javaClass.simpleName}")
            stopMyAudio()
            return
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopMyAudio() {
        val p = mePlayer
        mePlayer = null
        playingMeId = -1L
        if (p != null) {
            try {
                p.stop()
            } catch (t: Throwable) {
                // 已经播完 / 没 start 过，忽略
            }
            try {
                p.release()
            } catch (t: Throwable) {
                // 忽略
            }
        }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------ 重试

    private fun retryCommand(msg: ChatStore.Message) {
        val kind = msg.commandKind
        val argsText = msg.commandArgs
        if (kind.isNullOrEmpty() || argsText.isNullOrEmpty()) {
            toast("这条结果没有可重试的参数")
            return
        }
        if (!ServiceState.running) {
            toast("常驻服务没在跑：先回主界面点「启动」")
            return
        }
        val args = try {
            JSONObject(argsText)
        } catch (t: Throwable) {
            JSONObject()
        }
        ChatStore.expectCommand(kind, args.toString(), msg.commandSay ?: "")
        CommandExecutor.retryLocally(kind, args)
        toast("已用同样的参数重试")
    }

    private fun retryUpload(msg: ChatStore.Message) {
        val key = msg.uploadKey ?: return
        AudioUploader.retry(key)
        toast("正在重试上传…")
    }

    // ------------------------------------------------------------ 行渲染

    private inner class ChatAdapter : BaseAdapter() {
        private val data = ArrayList<ChatStore.Message>()

        fun submit(list: List<ChatStore.Message>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): ChatStore.Message? = data.getOrNull(position)

        override fun getCount(): Int = data.size

        override fun getItem(position: Int): Any = data[position]

        override fun getItemId(position: Int): Long = data[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            buildRow(data[position])
    }

    private fun buildRow(msg: ChatStore.Message): View {
        if (msg.kind == ChatStore.KIND_NOTICE) return buildNoticeRow(msg)
        val him = msg.side == ChatStore.SIDE_HIM

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or (if (him) Gravity.END else Gravity.START)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bubble = buildBubble(msg)

        if (him) {
            val play = ttsIconFor(msg)
            val retry = commandRetryIconFor(msg)
            if (play != null) actions.addView(play)
            if (retry != null) actions.addView(retry)
            if (actions.childCount > 0) row.addView(actions)
            row.addView(bubble)
        } else {
            val play = myVoiceIconFor(msg)
            val retry = uploadRetryIconFor(msg)
            row.addView(bubble)
            if (play != null) actions.addView(play)
            if (retry != null) actions.addView(retry)
            if (actions.childCount > 0) row.addView(actions)
        }
        return row
    }

    private fun buildNoticeRow(msg: ChatStore.Message): View = TextView(this).apply {
        text = msg.text
        textSize = 12f
        setTextColor(c(R.attr.yyTextSecondary))
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(8), dp(24), dp(8))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun buildBubble(msg: ChatStore.Message): LinearLayout {
        val him = msg.side == ChatStore.SIDE_HIM
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(9))
            // 靠右的气泡圆角在右侧收小，靠左的在左侧收小（一眼看出朝向）
            background = bubbleDrawable(
                if (him) c(R.attr.yyBubbleHim) else c(R.attr.yyBubbleMe),
                tallOnRight = him
            )
        }

        val maxBubble = (resources.displayMetrics.widthPixels * 0.75f).toInt()
        val maxText = (maxBubble - dp(24)).coerceAtLeast(dp(120))

        // 气泡里的小标签：命令结果 / 失败原因 / 语音时长
        val label = bubbleLabel(msg)
        if (label != null) {
            bubble.addView(label.first, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) })
        }

        val body = TextView(this).apply {
            text = msg.text
            textSize = 15f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(if (him) c(R.attr.yyBubbleHimText) else c(R.attr.yyBubbleMeText))
            maxWidth = maxText
        }
        if (msg.kind == ChatStore.KIND_FAILED) {
            body.setTextColor(c(R.attr.yyDanger))
        }
        // 显式 WRAP_CONTENT：LinearLayout 竖直方向的默认宽度是 MATCH_PARENT，
        // 与「气泡 wrap_content」会打成一个循环，流式回复变长后重新量尺寸容易卡在旧宽度上。
        bubble.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 失败气泡描一圈红边，与「重试」图标呼应
        if (msg.kind == ChatStore.KIND_FAILED) {
            bubble.background = outlined(c(R.attr.yyBubbleMe), c(R.attr.yyDanger), 14)
        }
        return bubble
    }

    private fun bubbleDrawable(color: Int, tallOnRight: Boolean): android.graphics.drawable.GradientDrawable {
        val big = dp(14).toFloat()
        val small = dp(4).toFloat()
        val radii = if (tallOnRight) {
            // 靠右：右上/右下收小
            floatArrayOf(big, big, small, small, small, small, big, big)
        } else {
            // 靠左：左上/左下收小
            floatArrayOf(small, small, big, big, big, big, small, small)
        }
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadii = radii
        }
    }

    /** 气泡内的小标签：文案 + 颜色。 */
    private fun bubbleLabel(msg: ChatStore.Message): Pair<TextView, Int>? {
        val him = msg.side == ChatStore.SIDE_HIM
        val (text, colorAttr) = when {
            msg.kind == ChatStore.KIND_COMMAND ->
                "命令结果" to R.attr.yyAccent

            msg.kind == ChatStore.KIND_FAILED ->
                "没成功" to R.attr.yyDanger

            him -> {
                val replyId = msg.replyId ?: return null
                val ms = ttsDurationMs(replyId)
                if (ms <= 0) return null
                "语音 ${formatDuration(ms)}" to R.attr.yyTextSecondary
            }

            else -> {
                if (msg.audioMs <= 0) return null
                "语音 ${formatDuration(msg.audioMs)}" to R.attr.yyTextSecondary
            }
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(c(colorAttr))
        }
        return tv to c(colorAttr)
    }

    // ------------------------------------------------------------ 外侧动作图标

    private fun actionButton(iconRes: Int, desc: String, tint: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(13), dp(13), dp(13), dp(13)) // 图标 22dp，可点区 48dp
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
            background = borderless()
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    /** 他发的：有 TTS 语音缓存 → 播放 / 停止。 */
    private fun ttsIconFor(msg: ChatStore.Message): View? {
        val replyId = msg.replyId ?: return null
        val playing = TtsPlayer.isPlaying(replyId)
        if (!playing && !TtsPlayer.hasAudio(replyId)) return null
        return if (playing) {
            actionButton(R.drawable.ic_stop, "停止播放", c(R.attr.yyAccent)) { onPlayTts(replyId) }
        } else {
            actionButton(R.drawable.ic_play, "重播这条语音", c(R.attr.yyAccent)) { onPlayTts(replyId) }
        }
    }

    /** 命令结果 / 失败：外侧挂重试。 */
    private fun commandRetryIconFor(msg: ChatStore.Message): View? {
        if (msg.kind != ChatStore.KIND_COMMAND && msg.kind != ChatStore.KIND_FAILED) return null
        if (msg.commandKind.isNullOrEmpty()) return null
        return actionButton(R.drawable.ic_retry, "用同样参数重试", c(R.attr.yyWarn)) {
            retryCommand(msg)
        }
    }

    /** 我的语音：聊天缓存还在就能重播。 */
    private fun myVoiceIconFor(msg: ChatStore.Message): View? {
        val name = msg.audioName ?: return null
        val exists = ChatAudioCache.fileFor(this, name).isFile
        if (!exists) return null
        val playing = playingMeId == msg.id && mePlayer != null
        return if (playing) {
            actionButton(R.drawable.ic_stop, "停止播放", c(R.attr.yyAccent)) { onPlayMine(msg) }
        } else {
            actionButton(R.drawable.ic_play, "重播我的语音", c(R.attr.yyAccent)) { onPlayMine(msg) }
        }
    }

    /** 我的语音没发出去：外侧挂重试（重新上传）。 */
    private fun uploadRetryIconFor(msg: ChatStore.Message): View? {
        if (msg.kind != ChatStore.KIND_FAILED) return null
        if (msg.uploadKey.isNullOrEmpty()) return null
        return actionButton(R.drawable.ic_retry, "重试上传", c(R.attr.yyWarn)) { retryUpload(msg) }
    }

    // ------------------------------------------------------------ 时长

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000.0
        return if (ms < 60_000) String.format(Locale.CHINA, "%.1f″", sec)
        else String.format(Locale.CHINA, "%d:%02d", (ms / 60_000).toInt(), ((ms / 1000) % 60).toInt())
    }

    /** 他这条回复的 TTS 语音总时长（读本地 WAV 头；没有就 0）。 */
    private fun ttsDurationMs(replyId: String): Long {
        var total = 0L
        for (id in TtsPlayer.audioIds(replyId)) {
            val f = TtsStore.wavFile(this, id)
            total += wavDurationMs(f)
        }
        return total
    }

    private fun wavDurationMs(file: File): Long {
        if (!file.isFile || file.length() <= 44) return 0L
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(44)
                if (raf.read(head) < 44) return 0L
                val byteRate = leInt(head, 28)
                val dataSize = leInt(head, 40)
                val bytes = if (dataSize > 0) dataSize else (file.length() - 44).toInt()
                if (byteRate <= 0) 0L else bytes * 1000L / byteRate
            }
        } catch (t: Throwable) {
            0L
        }
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
}
