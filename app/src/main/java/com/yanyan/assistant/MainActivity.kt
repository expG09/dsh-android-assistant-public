package com.yanyan.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * M6 主界面：只做两件事 —— 显示关键状态、放几个功能按钮（见 decision.md「主界面」）。
 *
 * 状态条：监听 / 通道 / 录音中 N 秒 / 最近一条命令；
 * 功能：快捷栏（可收起，收起状态持久化）、按住说话、进入聊天。
 * 配置项（通知文案、悬浮窗开关、权限、日志…）全部在 设置 二级页，主屏一个都不留。
 */
class MainActivity : BaseActivity(), ChannelListener {

    private lateinit var tvListenDot: TextView
    private lateinit var tvListenState: TextView
    private lateinit var tvListenDetail: TextView
    private lateinit var tvChannelDot: TextView
    private lateinit var tvChannelState: TextView
    private lateinit var tvChannelDetail: TextView
    private lateinit var tvRecordState: TextView
    private lateinit var tvLastCommand: TextView
    private lateinit var btnServiceStart: android.widget.Button

    private lateinit var btnHold: android.widget.Button
    private lateinit var tvHoldHint: TextView

    private lateinit var btnQuickToggle: ImageButton
    private lateinit var hsvQuickSlots: android.widget.HorizontalScrollView
    private lateinit var llQuickSlots: LinearLayout
    private lateinit var tvQuickHint: TextView
    private lateinit var tvQuickCollapsed: TextView

    /** 这次按下是我起的头（松手就结束）还是点了正在进行的唤醒录音（点一下停）。 */
    private var pressRecording = false
    private var wakeStopOnUp = false

    private val main = Handler(Looper.getMainLooper())

    private val listenStateListener: (ListenState) -> Unit = {
        runOnUiThread {
            refreshListenUi()
            main.removeCallbacks(recordTicker)
            main.post(recordTicker)
        }
    }
    private val recordResultListener: (RecordingResult) -> Unit = { result ->
        runOnUiThread {
            toast(
                when {
                    result.tooShort -> "录音太短，已丢弃（按住不放再说）"
                    result.file == null -> "没留下录音文件（${reasonLabel(result.reason)}）"
                    else -> "录音已保存：${String.format(Locale.CHINA, "%.1f", result.durationMs / 1000.0)} 秒，正在上传"
                }
            )
        }
    }
    private val wakeListener: (String) -> Unit = { keyword ->
        runOnUiThread {
            tvRecordState.text = "听见「$keyword」，正在录音…"
            main.removeCallbacks(recordTicker)
            main.post(recordTicker)
        }
    }
    private val commandListener: (String) -> Unit = { line ->
        runOnUiThread { renderCommandLine(line) }
    }

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            main.postDelayed(this, 1000)
        }
    }
    private val recordTicker = object : Runnable {
        override fun run() {
            updateRecordState()
            if (ListenController.recording) main.postDelayed(this, 100)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_NOTIFICATION = 1001
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupTopBar(getString(R.string.app_name), showBack = false)
        addTopBarAction(
            if (ThemeManager.isDark(this)) R.drawable.ic_sun else R.drawable.ic_moon,
            "切换深浅色"
        ) {
            ThemeManager.toggle(this)
            recreate()
        }
        addTopBarAction(R.drawable.ic_settings, "设置") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        tvListenDot = findViewById(R.id.tv_listen_dot)
        tvListenState = findViewById(R.id.tv_listen_state)
        tvListenDetail = findViewById(R.id.tv_listen_detail)
        tvChannelDot = findViewById(R.id.tv_channel_dot)
        tvChannelState = findViewById(R.id.tv_channel_state)
        tvChannelDetail = findViewById(R.id.tv_channel_detail)
        tvRecordState = findViewById(R.id.tv_record_state)
        tvLastCommand = findViewById(R.id.tv_last_command)
        btnServiceStart = findViewById(R.id.btn_service_start)
        btnHold = findViewById(R.id.btn_hold_talk)
        tvHoldHint = findViewById(R.id.tv_hold_hint)
        btnQuickToggle = findViewById(R.id.btn_quick_toggle)
        hsvQuickSlots = findViewById(R.id.hsv_quick_slots)
        llQuickSlots = findViewById(R.id.ll_quick_slots)
        tvQuickHint = findViewById(R.id.tv_quick_hint)
        tvQuickCollapsed = findViewById(R.id.tv_quick_collapsed)

        // 卡片底与按钮色都按主题来（布局里不写死）
        findViewById<LinearLayout>(R.id.card_status).background = rounded(c(R.attr.yySurface), 18)
        findViewById<LinearLayout>(R.id.btn_chat_entry).background =
            rounded(c(R.attr.yySurface), 18)
        findViewById<ImageView>(R.id.iv_chat_entry).imageTintList =
            android.content.res.ColorStateList.valueOf(c(R.attr.yyAccent))
        btnHold.setTextColor(c(R.attr.yyHoldText))
        btnHold.background = rounded(c(R.attr.yyHoldBg), 24)
        btnQuickToggle.imageTintList =
            android.content.res.ColorStateList.valueOf(c(R.attr.yyIcon))
        btnQuickToggle.setOnClickListener { toggleQuickBar() }
        findViewById<LinearLayout>(R.id.btn_chat_entry).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        tvHoldHint.setOnClickListener {
            if (!ListenController.enabled) {
                startActivity(
                    SettingsSectionActivity.intent(this, SettingsSectionActivity.SECTION_VOICE)
                )
            }
        }

        // ---------------------------------------------------------- 监听与录音
        ListenController.init(applicationContext)
        ListenController.addStateListener(listenStateListener)
        ListenController.addResultListener(recordResultListener)
        ListenController.addWakeListener(wakeListener)
        CommandHub.addListener(commandListener)
        renderCommandLine(CommandHub.lastLine)
        attachHoldToTalk()

        // ---------------------------------------------------------- 快捷栏
        AppIndex.rescan(this, "首屏渲染快捷栏")
        applyQuickBarCollapsed()
        renderQuickSlots()

        btnServiceStart.setOnClickListener {
            ensureNotificationPermission()
            try {
                KeepAliveService.start(this)
                toast("已请求启动常驻服务")
            } catch (t: Throwable) {
                toast("启动失败：${t.javaClass.simpleName} ${t.message}")
            }
            refreshStatus()
        }

        // 打开 App 就把服务拉起来：不让人多点一次（与 M1 的可见性原则一致）
        if (!ServiceState.running) {
            ensureNotificationPermission()
            try {
                KeepAliveService.start(this)
            } catch (t: Throwable) {
                toast("自动启动失败：${t.javaClass.simpleName} ${t.message}")
            }
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        ChannelHub.addListener(this)
        syncListenWithPermission()
        refreshStatus()
        refreshListenUi()
        updateRecordState()
        if (CommandHub.lastAt > 0L) renderCommandLine(CommandHub.lastLine)
        // 回到主界面时重扫一次，卸载/新装立刻反映到槽位（格子还在，状态如实）
        AppIndex.rescan(this, "回到主界面刷新快捷栏")
        renderQuickSlots()
        main.removeCallbacks(refreshTask)
        main.post(refreshTask)
    }

    override fun onPause() {
        ChannelHub.removeListener(this)
        main.removeCallbacks(refreshTask)
        main.removeCallbacks(recordTicker)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(refreshTask)
        main.removeCallbacks(recordTicker)
        ListenController.removeStateListener(listenStateListener)
        ListenController.removeResultListener(recordResultListener)
        ListenController.removeWakeListener(wakeListener)
        CommandHub.removeListener(commandListener)
        super.onDestroy()
    }

    override fun onPhaseChanged(phase: ChannelPhase, detail: String) {
        runOnUiThread { refreshStatus() }
    }

    override fun onReady(serverTime: Long) {
        runOnUiThread { refreshStatus() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            toast(if (granted) "通知权限已授予" else "通知权限被拒：常驻通知不会显示在通知栏")
            refreshStatus()
        }
    }

    // ------------------------------------------------------------ 状态

    private fun refreshStatus() {
        refreshListenUi()
        updateChannelUi()
        updateRecordState()
        btnServiceStart.visibility = if (ServiceState.running) View.GONE else View.VISIBLE
    }

    private fun refreshListenUi() {
        val state = ListenController.state
        val enabled = ListenController.enabled
        tvListenState.text = when {
            !enabled -> "未监听"
            state == ListenState.LISTENING -> "正在监听"
            state == ListenState.PAUSED_MIC -> "暂停监听（麦克风被占用）"
            state == ListenState.ERROR -> "监听异常"
            else -> "准备中…"
        }
        tvListenDot.setTextColor(
            when {
                !enabled -> c(R.attr.yyTextSecondary)
                state == ListenState.LISTENING -> c(R.attr.yyOk)
                state == ListenState.PAUSED_MIC -> c(R.attr.yyWarn)
                state == ListenState.ERROR -> c(R.attr.yyDanger)
                else -> c(R.attr.yyWarn)
            }
        )
        tvListenDetail.text = if (enabled) ListenController.wakeWordStateText() else "开关在 设置 › 语音"
        refreshHoldHint()
    }

    private fun updateChannelUi() {
        val phase = ChannelHub.phase
        tvChannelState.text = when (phase) {
            ChannelPhase.CONNECTED -> "通道已连上"
            ChannelPhase.CONNECTING -> "通道连接中"
            ChannelPhase.RECONNECTING -> "通道重连中"
            ChannelPhase.NOT_PAIRED -> "通道未配对"
            ChannelPhase.STOPPED -> "通道未运行"
        }
        tvChannelDot.setTextColor(
            when (phase) {
                ChannelPhase.CONNECTED -> c(R.attr.yyOk)
                ChannelPhase.CONNECTING, ChannelPhase.RECONNECTING -> c(R.attr.yyWarn)
                ChannelPhase.NOT_PAIRED -> c(R.attr.yyDanger)
                else -> c(R.attr.yyTextSecondary)
            }
        )
        tvChannelDetail.text = ChannelHub.detail
    }

    private fun updateRecordState() {
        if (!ListenController.recording) {
            tvRecordState.text = "录音：空闲"
            updateHoldButton()
            return
        }
        val ms = ListenController.liveRecordedMs()
        val maxSec = ListenController.liveMaxMs() / 1000.0
        tvRecordState.text = if (ListenController.wakeRecording) {
            "录音中 ${String.format(Locale.CHINA, "%.1f", ms / 1000.0)} 秒 / 上限 ${String.format(Locale.CHINA, "%.0f", maxSec)} 秒 · 点「停止」立刻收尾"
        } else {
            "录音中 ${String.format(Locale.CHINA, "%.1f", ms / 1000.0)} 秒 / 上限 ${String.format(Locale.CHINA, "%.0f", maxSec)} 秒 · 松手结束"
        }
        updateHoldButton()
    }

    /** 只有「唤醒词触发的自动录音」按钮才变「停止」；按住说话期间文案保持「按住说话」。 */
    private fun updateHoldButton() {
        if (!::btnHold.isInitialized) return
        val showStop = ListenController.recording && ListenController.wakeRecording && !pressRecording
        btnHold.text = if (showStop) "停止" else getString(R.string.btn_hold_talk)
        btnHold.background = rounded(
            c(if (showStop) R.attr.yyDanger else R.attr.yyHoldBg), 24
        )
        btnHold.alpha = if (ListenController.enabled) 1f else 0.55f
    }

    private fun refreshHoldHint() {
        if (!::tvHoldHint.isInitialized) return
        tvHoldHint.text = if (!ListenController.enabled) {
            "监听开关没开，按住说话用不了。点这里去 设置 › 语音 打开。"
        } else {
            "按住说话：最长 60 秒，连续静音 1.5 秒自动结束；唤醒词自动录音时按钮变「停止」。"
        }
        tvHoldHint.setTextColor(if (ListenController.enabled) c(R.attr.yyTextSecondary) else c(R.attr.yyWarn))
    }

    /** 权限被用户在系统设置里撤销时，把开关和状态一起降级成「关」，不能显示在听其实没听。 */
    private fun syncListenWithPermission() {
        if (!ListenController.enabled) return
        if (!ListenController.hasMicPermission(this)) {
            ListenController.disable()
            toast("麦克风权限已被收回，监听已关闭")
        }
    }

    private fun renderCommandLine(line: String) {
        if (!::tvLastCommand.isInitialized) return
        tvLastCommand.text =
            if (CommandHub.lastAt <= 0L) "最近命令：还没有执行过命令" else "最近命令：$line"
    }

    // ------------------------------------------------------------ 按住说话

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHoldToTalk() {
        btnHold.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!ListenController.enabled) {
                        toast("监听开关没开：设置 › 语音 里打开")
                        refreshHoldHint()
                        return@setOnTouchListener false
                    }
                    if (ListenController.recording) {
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
                    main.removeCallbacks(recordTicker)
                    main.post(recordTicker)
                    updateHoldButton()
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
                    main.removeCallbacks(recordTicker)
                    updateRecordState()
                    true
                }

                else -> false
            }
        }
    }

    // ------------------------------------------------------------ 快捷栏（可收起）

    private fun toggleQuickBar() {
        val collapsed = !LocalStore.quickBarCollapsed(this)
        LocalStore.setQuickBarCollapsed(this, collapsed)
        applyQuickBarCollapsed()
        toast(if (collapsed) "快捷栏已收起" else "快捷栏已展开")
    }

    private fun applyQuickBarCollapsed() {
        val collapsed = LocalStore.quickBarCollapsed(this)
        hsvQuickSlots.visibility = if (collapsed) View.GONE else View.VISIBLE
        tvQuickHint.visibility = if (collapsed) View.GONE else View.VISIBLE
        tvQuickCollapsed.visibility = if (collapsed) View.VISIBLE else View.GONE
        btnQuickToggle.rotation = if (collapsed) 180f else 0f
        btnQuickToggle.contentDescription = if (collapsed) "展开快捷栏" else "收起快捷栏"
    }

    private fun renderQuickSlots() {
        val slots = QuickSlots.load(this)
        val installed = AppIndex.all(this).associateBy { it.packageName }
        llQuickSlots.removeAllViews()
        val content = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(dp(240))
        val cellW = (content / QuickSlots.COUNT).coerceIn(dp(48), dp(88))
        var filled = 0
        var missing = 0
        for (i in 0 until QuickSlots.COUNT) {
            val slot = slots[i]
            val app = slot.pkg?.let { installed[it] }
            if (slot.pkg != null) filled++
            if (slot.pkg != null && app == null) missing++
            llQuickSlots.addView(buildQuickSlot(i, slot, app, cellW))
        }
        Log.i(TAG, "快捷栏渲染：${QuickSlots.COUNT} 格，已配置 $filled，已卸载 $missing")
    }

    private fun buildQuickSlot(index: Int, slot: QuickSlot, app: InstalledApp?, widthPx: Int): View {
        val empty = slot.pkg == null
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isClickable = true
            isLongClickable = true
            background = rounded(c(R.attr.yySurfaceAlt), 12)
        }

        val iconBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        val icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42)).apply { gravity = Gravity.CENTER }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val mark = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER }
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(c(R.attr.yyTextSecondary))
            visibility = View.GONE
        }
        iconBox.addView(icon)
        iconBox.addView(mark)

        // 名字固定一行：长名字省略，已卸载写在这一行里（decision.md 2026-11-16 的遗留问题）
        val name = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            gravity = Gravity.CENTER
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(c(R.attr.yyText))
        }

        when {
            empty -> {
                mark.text = "＋"
                mark.visibility = View.VISIBLE
                name.text = getString(R.string.quick_slot_empty)
                name.setTextColor(c(R.attr.yyTextSecondary))
                cell.contentDescription = "第 ${index + 1} 格，空，点一下选应用"
            }

            app == null -> {
                mark.text = "!"
                mark.visibility = View.VISIBLE
                mark.setTextColor(c(R.attr.yyWarn))
                name.text = "${slot.label ?: slot.pkg} · 已卸载"
                name.setTextColor(c(R.attr.yyTextSecondary))
                cell.alpha = 0.55f
                cell.contentDescription = "第 ${index + 1} 格，${slot.label}，已卸载"
            }

            else -> {
                icon.setImageDrawable(loadAppIcon(app.packageName))
                name.text = app.label
                cell.contentDescription = "第 ${index + 1} 格，${app.label}，点一下打开，长按更换"
            }
        }

        cell.addView(iconBox)
        cell.addView(name)

        cell.setOnClickListener {
            if (empty) {
                showAppPicker(index)
                return@setOnClickListener
            }
            when (val r = QuickSlots.launch(this, slot)) {
                QuickSlots.LaunchResult.Ok -> Unit
                is QuickSlots.LaunchResult.NotInstalled -> {
                    toast(getString(R.string.quick_slot_gone, r.label))
                    AppIndex.rescan(this, "点击的槽位已不在手机上")
                    renderQuickSlots()
                }

                is QuickSlots.LaunchResult.Failed ->
                    toast(getString(R.string.quick_slot_launch_failed, r.label, r.reason))
            }
        }
        cell.setOnLongClickListener {
            showAppPicker(index)
            true
        }
        return cell
    }

    private fun loadAppIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (t: Throwable) {
        null
    }

    /** 长按 / 空槽点击：已安装应用列表（带搜索），选一个即替换并立即生效。 */
    private fun showAppPicker(index: Int) {
        AppPicker.showSlot(this, index) { renderQuickSlots() }
    }

    // ------------------------------------------------------------ 小工具

    private fun reasonLabel(reason: String): String = when (reason) {
        "released" -> "松开按钮"
        "manual_stop" -> "手动停止"
        "max_duration" -> "到时长上限"
        "silence" -> "连续静音 ${AudioCaptureEngine.SILENCE_STOP_MS / 1000.0} 秒"
        "mic_occupied" -> "麦克风被占用"
        "engine_stopped" -> "监听已关闭"
        else -> reason
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
    }
}
