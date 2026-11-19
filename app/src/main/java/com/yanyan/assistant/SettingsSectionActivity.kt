package com.yanyan.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M6 设置的八个二级分类页。
 *
 * 一个 Activity + 一个 [section] 参数（见 [intent]），按分类现场搭卡片：
 * 语音 / 录音与上传 / 通道与配对 / 应用与快捷栏 / 悬浮窗 / 权限状态 / 日志与诊断 / 外观。
 *
 * 所有改动都落到 [LocalStore] 或调用**现成的**控制器方法（ListenController / KeepAliveService /
 * OverlayController / TtsPlayer…），不改任何功能链路；改完立即 rebuild，返回主界面依然生效。
 */
class SettingsSectionActivity : BaseActivity(), ChannelListener, TtsListener {

    companion object {
        const val EXTRA_SECTION = "extra_section"

        const val SECTION_VOICE = 1
        const val SECTION_RECORDING = 2
        const val SECTION_CHANNEL = 3
        const val SECTION_APPS = 4
        const val SECTION_OVERLAY = 5
        const val SECTION_PERMISSIONS = 6
        const val SECTION_LOGS = 7
        const val SECTION_APPEARANCE = 8

        private const val REQ_MIC = 2001
        private const val REQ_NOTIFICATION = 2002

        fun intent(ctx: android.content.Context, section: Int): Intent =
            Intent(ctx, SettingsSectionActivity::class.java).putExtra(EXTRA_SECTION, section)
    }

    private var section = SECTION_VOICE
    private var content: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())

    /** 跳过系统悬浮窗授权页之后，回来要再检查一次并给明确结果。 */
    private var pendingOverlayRequest = false

    // 试听（语音分类里）
    private var tvAuditionState: TextView? = null
    private var auditionText = "点「试听」会让电脑端合成一句固定的话，手机收到就播。"
    private var auditionPending = false
    private var auditionInFlight = false

    private val auditionTimeout = Runnable {
        if (!auditionPending) return@Runnable
        auditionPending = false
        auditionText = "试听失败（电脑端没合成，看看电脑端日志）"
        refreshAuditionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page)
        section = intent.getIntExtra(EXTRA_SECTION, SECTION_VOICE)
        setupTopBar(titleFor(section), showBack = true)
        content = findViewById(R.id.ll_content)
        build()
    }

    override fun onResume() {
        super.onResume()
        ChannelHub.addListener(this)
        TtsPlayer.addListener(this)
        resolvePendingOverlayRequest()
        build()
    }

    override fun onPause() {
        ChannelHub.removeListener(this)
        TtsPlayer.removeListener(this)
        main.removeCallbacks(auditionTimeout)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> {
                if (granted) {
                    toast("麦克风权限已授予")
                    applyListenEnabled(true)
                } else {
                    toast("没给麦克风权限，监听保持关闭")
                    build()
                }
            }

            REQ_NOTIFICATION -> {
                toast(if (granted) "通知权限已授予" else "通知权限被拒：常驻通知不会显示")
                build()
            }
        }
    }

    // ------------------------------------------------------------ 页面骨架

    private fun titleFor(section: Int): String = when (section) {
        SECTION_VOICE -> "语音"
        SECTION_RECORDING -> "录音与上传"
        SECTION_CHANNEL -> "通道与配对"
        SECTION_APPS -> "应用与快捷栏"
        SECTION_OVERLAY -> "悬浮窗"
        SECTION_PERMISSIONS -> "权限状态"
        SECTION_LOGS -> "日志与诊断"
        else -> "外观"
    }

    private fun build() {
        val root = content ?: return
        root.removeAllViews()
        tvAuditionState = null
        when (section) {
            SECTION_VOICE -> buildVoice(root)
            SECTION_RECORDING -> buildRecording(root)
            SECTION_CHANNEL -> buildChannel(root)
            SECTION_APPS -> buildApps(root)
            SECTION_OVERLAY -> buildOverlay(root)
            SECTION_PERMISSIONS -> buildPermissions(root)
            SECTION_LOGS -> buildLogs(root)
            SECTION_APPEARANCE -> buildAppearance(root)
        }
    }

    /** 一张带标题的卡片：标题 + 里面自由加条目，统一加到页面上。 */
    private fun card(parent: LinearLayout, title: String, fill: (LinearLayout) -> Unit): LinearLayout {
        val c = newCard()
        cardTitle(c, title)
        fill(c)
        parent.addView(c)
        return c
    }

    // ------------------------------------------------------------ 1. 语音

    private fun buildVoice(root: LinearLayout) {
        card(root, "监听总开关") { c ->
            switchRow(c, "监听（关闭时不占用麦克风）", ListenController.enabled) { on ->
                onListenSwitch(on)
            }
            cardHint(
                c,
                "状态：${ListenController.stateLabel()}\n唤醒词引擎：${ListenController.wakeWordStateText()}"
            )
        }

        card(root, "唤醒词") { c ->
            cardHint(c, "内置唤醒词：${wakeWords()}")
            cardHint(c, "灵敏度：标准（引擎阈值固定；M7 再开放调节）")
            cardHint(c, "唤醒词只在监听开关打开时加载，关掉开关会立即释放麦克风。")
        }

        card(root, "提示音") { c ->
            switchRow(
                c,
                "命中唤醒词后应一声（「我在 / 嗯？ / 在呢」）",
                LocalStore.wakePromptEnabled(this)
            ) { on ->
                LocalStore.setWakePromptEnabled(this, on)
                toast(if (on) "已开启：命中唤醒词会应一声" else "已关闭：命中唤醒词不出声")
            }
            cardHint(c, "播报期间会暂停唤醒检测，避免自己把自己叫醒。")
        }

        card(root, "语音回复（播报）") { c ->
            switchRow(c, "回复文字的同时念出来", LocalStore.speakEnabled(this)) { on ->
                onSpeakSwitch(on)
            }
            cardHint(
                c,
                if (LocalStore.speakEnabled(this)) {
                    "开着：电脑按句合成，手机边收边播；开始录音或喊唤醒词会立刻停播。"
                } else {
                    "关着：只有文字，电脑端一次语音请求都不发（不产生费用）。"
                }
            )
            val idx = if (LocalStore.speakVoice(this) == LocalStore.VOICE_TIDE) 1 else 0
            radioOptions(c, listOf("stella · 女声（默认）", "tide · 男声"), idx) { i ->
                val voice = if (i == 1) LocalStore.VOICE_TIDE else LocalStore.VOICE_STELLA
                LocalStore.setSpeakVoice(this, voice)
                KeepAliveService.channel?.sendSpeakConfigFromPrefs()
                toast("音色已改为 $voice")
            }
            outlineButton(c, "试听当前音色") { audition() }
            tvAuditionState = bodyText(c, auditionText, 12f, R.attr.yyTextSecondary)
            actionRow(c, "语音回复详情（试听 / 缓存）", null) {
                startActivity(Intent(this, VoiceSettingsActivity::class.java))
            }
        }
    }

    private fun wakeWords(): String = try {
        assets.open("kws/keywords_raw.txt").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                line.substringBefore(":").trim().takeIf { it.isNotEmpty() }
            }.toList().joinToString("、")
        }
    } catch (t: Throwable) {
        "砚砚"
    }

    private fun onListenSwitch(on: Boolean) {
        if (!on) {
            ListenController.disable()
            toast("已关闭监听，麦克风已释放")
            build()
            return
        }
        if (ListenController.hasMicPermission(this)) {
            applyListenEnabled(true)
        } else {
            AlertDialog.Builder(this)
                .setTitle("需要麦克风权限")
                .setMessage(
                    "只有「监听开关」打开时砚砚才会使用麦克风，关掉开关会立即释放，不会在后台偷偷录音。\n\n" +
                            "现在打开开关，需要先授予录音权限。"
                )
                .setPositiveButton("继续") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                }
                .setNegativeButton("取消") { _, _ ->
                    toast("监听保持关闭")
                    build()
                }
                .setOnCancelListener { build() }
                .show()
        }
    }

    private fun applyListenEnabled(on: Boolean) {
        val ok = ListenController.setEnabled(on)
        if (!ok && on) {
            toast("打开监听失败：${ListenController.errorText ?: "没有麦克风权限"}")
        }
        build()
    }

    private fun onSpeakSwitch(on: Boolean) {
        LocalStore.setSpeakEnabled(this, on)
        val sent = KeepAliveService.channel?.sendSpeakConfigFromPrefs() ?: false
        if (!on) {
            TtsPlayer.stopPlayback("switch_off")
            toast("已关闭语音回复：之后只有文字")
        } else {
            toast(if (sent) "已开启语音回复" else "已开启（通道没连上，连上后自动生效）")
        }
        build()
    }

    private fun audition() {
        val channel = KeepAliveService.channel
        if (channel == null || ChannelHub.phase != ChannelPhase.CONNECTED) {
            auditionText = "通道没连上：先去 通道与配对 连上电脑再试听"
            refreshAuditionState()
            return
        }
        TtsPlayer.stopPlayback("audition_restart")
        auditionPending = true
        auditionInFlight = false
        auditionText = "试听中…（电脑合成完就播）"
        val ok = channel.sendSpeakRequest(LocalStore.speakVoice(this))
        if (!ok) {
            auditionPending = false
            auditionText = "试听失败：通道好像断开了"
        } else {
            main.removeCallbacks(auditionTimeout)
            main.postDelayed(auditionTimeout, 20_000L)
        }
        refreshAuditionState()
    }

    private fun refreshAuditionState() {
        tvAuditionState?.text = auditionText
    }

    // ------------------------------------------------------------ 2. 录音与上传

    private fun buildRecording(root: LinearLayout) {
        card(root, "录音占用") { c ->
            val (count, bytes) = RecordingStore.usage(this)
            val (chatCount, chatBytes) = ChatAudioCache.usage(this)
            bodyText(
                c,
                "录音文件：$count 段 · ${formatSize(bytes)}\n" +
                        "聊天语音缓存：$chatCount 段 · ${formatSize(chatBytes)}"
            )
            outlineButton(c, "立即清理（录音 + 语音缓存 + 聊天语音）") { clearAllMedia() }
            cardHint(c, "录音策略固定：识别成功即删；失败保留待重试；超过 7 天或 200 MB 自动清最旧。")
        }

        card(root, "语音缓存保留期") { c ->
            val keep = LocalStore.ttsKeepDays(this)
            val idx = when (keep) {
                30 -> 1
                0 -> 2
                else -> 0
            }
            radioOptions(c, listOf("保留 7 天（默认）", "保留 30 天", "不自动删"), idx) { i ->
                val days = when (i) {
                    1 -> 30
                    2 -> 0
                    else -> 7
                }
                LocalStore.setTtsKeepDays(this, days)
                TtsStore.prune(this)
                toast("保留期已改为 ${if (days == 0) "不自动删" else "$days 天"}")
                build()
            }
            val (count, bytes) = TtsStore.usage(this)
            cardHint(c, "当前占用：$count 段 · ${formatSize(bytes)}")
            cardHint(c, "只作用于语音回复的缓存（重播用的 WAV）；总量超过 500 MB 也会按最旧删，正在播的不动。")
        }

        card(root, "上传失败重试") { c ->
            cardHint(c, "策略：不自动重传（避免重复计费）。失败的那条在聊天页/调试页外侧有重试图标，点一下重发一次。")
            val last = AudioUploadHub.snapshot().lastOrNull()
            bodyText(
                c,
                if (last == null) "还没有语音记录" else
                    "最近一条：${uploadPhaseLabel(last.phase)}" +
                            (last.text?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            )
        }
    }

    private fun clearAllMedia() {
        val n = RecordingStore.clearAll(this)
        TtsPlayer.stopPlayback("clear_cache")
        val t = TtsStore.clearAll(this)
        TtsPlayer.notifyCacheCleared()
        val chat = ChatAudioCache.clearAll(this)
        toast("已清理：录音 $n 段、语音缓存 $t 个、聊天语音 $chat 段")
        build()
    }

    // ------------------------------------------------------------ 3. 通道与配对

    private fun buildChannel(root: LinearLayout) {
        card(root, "连接状态") { c ->
            bodyText(
                c,
                "状态：${channelPhaseLabel(ChannelHub.phase)}\n" +
                        "详情：${ChannelHub.detail}\n" +
                        "服务端时间：" + (if (ChannelHub.serverTime > 0L) {
                            fullFormat.format(Date(ChannelHub.serverTime))
                        } else {
                            "—"
                        })
            )
            outlineButton(c, "重新连接") {
                KeepAliveService.requestChannelReconnect(this)
                toast("正在重连…")
            }
        }

        card(root, "电脑地址") { c ->
            val et = editRow(
                c,
                "电脑端地址（ws://ip:8788）",
                LocalStore.serverUrl(this) ?: "",
                "ws://192.168.1.100:8788"
            )
            outlineButton(c, "保存地址并重连") {
                val url = normalizeUrl(et.text.toString())
                if (url == null) {
                    toast("地址要写成 ws://ip:8788")
                    return@outlineButton
                }
                LocalStore.setServerUrl(this, url)
                // 地址变了必须换链路：force=true（先正常关闭旧连接）
                KeepAliveService.requestChannelReconnect(this, force = true)
                toast("地址已保存，正在重连")
                build()
            }
            cardHint(c, "同局域网直接填电脑 IP；用 adb reverse 时填 ws://127.0.0.1:8788。")
        }

        card(root, "配对") { c ->
            actionRow(c, "打开配对页（填 6 位配对码）", null) {
                startActivity(Intent(this, PairActivity::class.java))
            }
            bodyText(
                c,
                "配对状态：${if (LocalStore.paired(this)) "已配对（token 在加密存储里）" else "未配对"}\n" +
                        "本机 deviceId：${LocalStore.deviceId(this)}"
            )
            if (LocalStore.paired(this)) {
                outlineButton(c, "撤销配对") { confirmRevokePairing() }
            }
        }

        card(root, "常驻服务与通知") { c ->
            bodyText(c, serviceStatusText())
            outlineButton(c, if (ServiceState.running) "停止常驻服务" else "启动常驻服务") {
                toggleService()
            }
            outlineButton(c, if (ServiceState.paused) "继续" else "暂停") {
                togglePause()
            }
            val etTitle = editRow(c, "通知标题", LocalStore.notifTitle(this), "砚砚值守中")
            val etText = editRow(c, "通知文案", LocalStore.notifText(this), "常驻服务正在运行")
            outlineButton(c, "保存通知文案") {
                LocalStore.prefs(this).edit()
                    .putString(LocalStore.KEY_NOTIF_TITLE, etTitle.text.toString().trim())
                    .putString(LocalStore.KEY_NOTIF_TEXT, etText.text.toString().trim())
                    .apply()
                if (ServiceState.running) {
                    KeepAliveService.startWithAction(this, KeepAliveService.ACTION_REFRESH)
                }
                toast("通知文案已保存")
            }
        }
    }

    private fun confirmRevokePairing() {
        AlertDialog.Builder(this)
            .setTitle("撤销配对？")
            .setMessage("撤销后通道会断开，要重新输入电脑端打印的 6 位配对码才能连上。")
            .setPositiveButton("撤销") { _, _ ->
                LocalStore.clearPairing(this)
                // 凭证已被清掉，必须强制换链路
                KeepAliveService.requestChannelReconnect(this, force = true)
                toast("已撤销配对")
                build()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleService() {
        if (ServiceState.running) {
            stopService(Intent(this, KeepAliveService::class.java))
            ServiceState.running = false
            LocalStore.prefs(this).edit().putBoolean(LocalStore.KEY_RUNNING, false).apply()
            toast("已停止常驻服务")
        } else {
            try {
                KeepAliveService.start(this)
                toast("已请求启动常驻服务")
            } catch (t: Throwable) {
                toast("启动失败：${t.javaClass.simpleName}")
            }
        }
        build()
    }

    private fun togglePause() {
        if (!ServiceState.running) {
            toast("服务没在跑，先点「启动常驻服务」")
            return
        }
        KeepAliveService.startWithAction(
            this,
            if (ServiceState.paused) KeepAliveService.ACTION_RESUME else KeepAliveService.ACTION_PAUSE
        )
        toast(if (ServiceState.paused) "已继续" else "已暂停")
        build()
    }

    private fun serviceStatusText(): String {
        val state = when {
            !ServiceState.running -> "未运行"
            ServiceState.paused -> "运行中（已暂停）"
            else -> "运行中"
        }
        val since = if (ServiceState.running && ServiceState.startedAt > 0L) {
            "\n启动于：${fullFormat.format(Date(ServiceState.startedAt))}"
        } else {
            ""
        }
        return "状态：$state$since"
    }

    private fun normalizeUrl(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        val withScheme = when {
            input.startsWith("ws://") || input.startsWith("wss://") -> input
            input.startsWith("http://") -> "ws://" + input.removePrefix("http://")
            input.startsWith("https://") -> "wss://" + input.removePrefix("https://")
            else -> "ws://$input"
        }
        val parsed = try {
            java.net.URI(withScheme)
        } catch (e: Exception) {
            return null
        }
        if (parsed.host.isNullOrBlank()) return null
        return withScheme.trimEnd('/')
    }

    // ------------------------------------------------------------ 4. 应用与快捷栏

    private fun buildApps(root: LinearLayout) {
        card(root, "快捷栏") { c ->
            switchRow(
                c,
                "收起快捷栏（主界面同一个开关）",
                LocalStore.quickBarCollapsed(this)
            ) { on ->
                LocalStore.setQuickBarCollapsed(this, on)
                toast(if (on) "快捷栏已收起" else "快捷栏已展开")
            }
            val slots = QuickSlots.load(this)
            val installed = AppIndex.all(this).associateBy { it.packageName }
            for (i in 0 until QuickSlots.COUNT) {
                val slot = slots[i]
                val label = when {
                    slot.pkg == null -> "未设置"
                    installed[slot.pkg] == null -> "${slot.label ?: slot.pkg}（已卸载）"
                    else -> installed[slot.pkg]!!.label
                }
                actionRow(c, "第 ${i + 1} 格", label) {
                    AppPicker.showSlot(this, i) { build() }
                }
            }
            cardHint(c, "点一格即可换应用；主界面长按格子也能换，空槽点一下就能选。")
        }

        card(root, "场景 → 应用") { c ->
            val map = LocalStore.sceneMap(this)
            val installed = AppIndex.all(this).associateBy { it.packageName }
            val scenes = listOf(
                LocalStore.SCENE_NAVIGATE to "导航",
                LocalStore.SCENE_BROWSER to "浏览器",
                LocalStore.SCENE_MUSIC to "音乐",
                LocalStore.SCENE_SMS to "短信"
            )
            for ((key, label) in scenes) {
                val pkg = map[key]
                val appLabel = when {
                    pkg == null -> "未指定（交系统默认）"
                    else -> installed[pkg]?.label ?: pkg
                }
                actionRow(c, label, appLabel) { pickSceneApp(key, label, pkg) }
            }
            cardHint(c, "当前版本命令执行仍交给系统选择器；这里先把你的偏好记下来，接入执行放到 M7。")
        }

        card(root, "怎么用") { c ->
            cardHint(c, "主界面：点格子直接打开，长按换应用；右上角箭头收起/展开，收起状态会记住。")
            cardHint(c, "浮层会压到快捷栏的问题已通过「可收起」解决（decision.md 2026-11-16）。")
        }
    }

    private fun pickSceneApp(scene: String, label: String, currentPkg: String?) {
        val installed = AppIndex.all(this).associateBy { it.packageName }
        val currentLabel = currentPkg?.let { installed[it]?.label ?: it }
        AppPicker.choose(this, "「$label」用哪个应用", currentLabel) { app ->
            LocalStore.setSceneApp(this, scene, app?.packageName)
            toast(if (app == null) "已清空「$label」的指定" else "「$label」已指定为「${app.label}」")
            build()
        }
    }

    // ------------------------------------------------------------ 5. 悬浮窗

    private fun buildOverlay(root: LinearLayout) {
        card(root, "悬浮窗") { c ->
            switchRow(
                c,
                "开启悬浮窗（可拖动，任意界面按住说话）",
                LocalStore.overlayEnabled(this)
            ) { on -> onOverlaySwitch(on) }

            val granted = Settings.canDrawOverlays(this)
            val enabled = LocalStore.overlayEnabled(this)
            bodyText(
                c,
                "开关：${if (enabled) "开" else "关"}\n" +
                        "系统权限：${if (granted) "已给" else "没给"}\n" +
                        "浮层：${if (OverlayController.isVisible()) "在屏幕上" else "没显示"}"
            )

            // decision.md 要求这句话固定可见，不许含糊
            warnBox(c, getString(R.string.hint_overlay_degrade))

            outlineButton(c, "打开系统悬浮窗授权页") {
                pendingOverlayRequest = true
                requestOverlayPermission()
            }
            outlineButton(c, "重置浮层位置") {
                LocalStore.clearOverlayPosition(this)
                val wasEnabled = LocalStore.overlayEnabled(this) && Settings.canDrawOverlays(this)
                OverlayController.hide()
                if (wasEnabled) {
                    val err = OverlayController.show(this)
                    if (err != null) toast("浮层没显示出来：$err")
                }
                toast("浮层位置已重置（下次贴右边、竖直约 30%）")
                build()
            }
            cardHint(c, "拖上边状态条可移动，拖到左右边缘自动吸附，位置会记住。")
        }
    }

    private fun onOverlaySwitch(on: Boolean) {
        if (!on) {
            LocalStore.setOverlayEnabled(this, false)
            OverlayController.hide()
            toast("悬浮窗已关闭：后台命令改成发通知")
            build()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            applyOverlayOpen()
        } else {
            LocalStore.setOverlayEnabled(this, false)
            pendingOverlayRequest = true
            requestOverlayPermission()
        }
        build()
    }

    private fun applyOverlayOpen() {
        LocalStore.setOverlayEnabled(this, true)
        val err = OverlayController.show(this)
        if (err != null) {
            LocalStore.setOverlayEnabled(this, false)
            toast("悬浮窗没开起来：$err")
        } else {
            toast(getString(R.string.hint_overlay_on))
        }
    }

    private fun resolvePendingOverlayRequest() {
        if (!pendingOverlayRequest) return
        pendingOverlayRequest = false
        if (Settings.canDrawOverlays(this)) {
            applyOverlayOpen()
        } else {
            toast("没给悬浮窗权限：后台命令只能发通知，要随时按住说话得先打开 App")
        }
    }

    private fun requestOverlayPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            toast("请在系统页面里打开「允许显示在其他应用上层」，回来我就开浮层")
            return
        } catch (e: Exception) {
            // 个别 ROM 只认不带 package 的入口
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            toast("请在系统页面里允许悬浮窗，回来我就开浮层")
        } catch (e: Exception) {
            pendingOverlayRequest = false
            toast("打不开系统悬浮窗设置：${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ 6. 权限状态

    private fun buildPermissions(root: LinearLayout) {
        card(root, "权限状态") { c ->
            val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            permissionRow(
                c, "麦克风（录音）",
                if (micGranted) "已给" else "没给",
                micGranted, "申请"
            ) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }

            val notifOn = NotificationManagerCompat.from(this).areNotificationsEnabled()
            permissionRow(
                c, "通知（常驻通知）",
                if (notifOn) "已给" else "没给", notifOn, "去设置"
            ) {
                openAppNotificationSettings()
            }

            val overlayOn = Settings.canDrawOverlays(this)
            permissionRow(
                c, "悬浮窗（后台弹界面）",
                if (overlayOn) "已给" else "没给", overlayOn, "去设置"
            ) {
                pendingOverlayRequest = true
                requestOverlayPermission()
            }

            val pm = getSystemService(PowerManager::class.java)
            val ignoring = pm?.isIgnoringBatteryOptimizations(packageName) == true
            permissionRow(
                c, "电池优化白名单",
                if (ignoring) "已在白名单" else "未加入", ignoring, "申请"
            ) {
                requestIgnoreBatteryOptimizations()
            }

            permissionRow(c, "前台服务 / 网络", "已给（安装时授予）", true, null) { }

            actionRow(c, "打开应用详情页（手动改权限）", null) { openAppDetails() }
            cardHint(c, "系统里显示「不再询问」的权限，只能去系统设置里手动打开。")
        }
    }

    private fun permissionRow(
        card: LinearLayout,
        title: String,
        status: String,
        ok: Boolean,
        actionLabel: String?,
        action: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(4), 0, dp(4))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(c(R.attr.yyText))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        texts.addView(
            TextView(this).apply {
                text = status
                textSize = 12f
                setTextColor(c(if (ok) R.attr.yyOk else R.attr.yyDanger))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        )
        row.addView(texts, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        if (actionLabel != null) {
            val btn = android.widget.Button(this).apply {
                text = actionLabel
                textSize = 13f
                isAllCaps = false
                minimumHeight = dp(48)
                minimumWidth = dp(72)
                setTextColor(c(R.attr.yyAccent))
                background = outlined(c(R.attr.yySurface), c(R.attr.yyAccent), 12)
                setOnClickListener { action() }
            }
            row.addView(btn)
        }
        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            toast("打不开应用详情页：${e.javaClass.simpleName}")
        }
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
            return
        } catch (e: Exception) {
            // 个别 ROM 不支持，退到应用详情
        }
        openAppDetails()
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            toast("已经在电池优化白名单里了")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
            return
        } catch (e: Exception) {
            // 继续走兜底
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            toast("打不开电池优化设置：${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ 7. 日志与诊断

    private fun buildLogs(root: LinearLayout) {
        card(root, "版本与设备") { c ->
            bodyText(
                c,
                "应用：DSH砚砚助手 ${versionName()}（${versionCode()}）\n" +
                        "系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）\n" +
                        "设备：${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "服务：${if (ServiceState.running) "运行中" else "未运行"}"
            )
        }

        card(root, "通道与往返") { c ->
            bodyText(
                c,
                "状态：${channelPhaseLabel(ChannelHub.phase)}\n" +
                        "详情：${ChannelHub.detail}\n" +
                        "最近一次语音往返：${lastRoundTripText()}"
            )
            outlineButton(c, "重新连接") {
                KeepAliveService.requestChannelReconnect(this)
                toast("正在重连…")
            }
        }

        card(root, "最近事件") { c ->
            bodyText(
                c,
                "最近命令：" + (if (CommandHub.lastAt > 0L) CommandHub.lastLine else "还没有执行过命令") +
                        "\n最近录音：" + (ListenController.lastResult?.let {
                            String.format(Locale.CHINA, "%.1f 秒，结束原因 %s", it.durationMs / 1000.0, it.reason)
                        } ?: "还没有") +
                        "\n最近语音：" + (AudioUploadHub.snapshot().lastOrNull()?.let {
                            "${uploadPhaseLabel(it.phase)}${it.text?.takeIf { t -> t.isNotBlank() }?.let { t -> " · $t" } ?: ""}"
                        } ?: "还没有")
            )
            actionRow(c, "打开调试页（原始消息时间线）", null) {
                startActivity(Intent(this, DebugActivity::class.java))
            }
            outlineButton(c, "刷一下") { build() }
        }

        card(root, "导出诊断") { c ->
            cardHint(c, "导出一段文字（版本 / 权限 / 通道 / 最近事件），方便贴到聊天里排查。")
            outlineButton(c, "导出诊断（分享文本）") { exportDiagnostics() }
        }
    }

    private fun lastRoundTripText(): String {
        val done = AudioUploadHub.snapshot().filter { it.phase == AudioUploadPhase.DONE }.lastOrNull()
            ?: return "还没有识别成功的语音"
        val ms = (done.updatedAt - done.createdAt).coerceAtLeast(0L)
        return String.format(Locale.CHINA, "%.1f 秒（录音结束 → 识别结果）", ms / 1000.0)
    }

    private fun exportDiagnostics() {
        val text = buildString {
            appendLine("DSH砚砚助手 ${versionName()}（${versionCode()}）")
            appendLine("Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）· ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("通道：${channelPhaseLabel(ChannelHub.phase)} · ${ChannelHub.detail}")
            appendLine("监听：${ListenController.stateLabel()} · 唤醒词：${ListenController.wakeWordStateText()}")
            appendLine("语音回复：${if (LocalStore.speakEnabled(this@SettingsSectionActivity)) "开" else "关"} · 音色 ${LocalStore.speakVoice(this@SettingsSectionActivity)}")
            appendLine("最近命令：${if (CommandHub.lastAt > 0L) CommandHub.lastLine else "还没有"}")
            appendLine("最近一次语音往返：${lastRoundTripText()}")
            appendLine("录音占用：${RecordingStore.usage(this@SettingsSectionActivity).let { "${it.first} 段 / ${formatSize(it.second)}" }}")
            append("时间：${fullFormat.format(Date())}")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "砚砚助手诊断")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, "导出诊断"))
        } catch (e: Exception) {
            toast("没有可用的分享应用：${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ 8. 外观

    private fun buildAppearance(root: LinearLayout) {
        card(root, "深浅色") { c ->
            val idx = when (ThemeManager.mode(this)) {
                LocalStore.THEME_DARK -> 1
                LocalStore.THEME_SYSTEM -> 2
                else -> 0
            }
            radioOptions(
                c,
                listOf("浅色（暖白底）", "深色（近黑灰底）", "跟随系统"),
                idx
            ) { i ->
                val mode = when (i) {
                    1 -> LocalStore.THEME_DARK
                    2 -> LocalStore.THEME_SYSTEM
                    else -> LocalStore.THEME_LIGHT
                }
                ThemeManager.setMode(this, mode)
                toast("已切换到${ThemeManager.modeLabel(this)}，立即生效")
                recreate()
            }
            cardHint(c, "手动选择立即生效并记住，重启后保持；主界面右上角的日/月图标也能一键切。")
            cardHint(c, "深色偏黑灰（不做纯黑），浅色偏暖白；气泡两套主题各配一组底色。")
        }

        card(root, "现在这套") { c ->
            bodyText(
                c,
                "当前：${ThemeManager.modeLabel(this)}\n" +
                        "字号跟系统缩放走；关键可点区不小于 48dp，手机与平板都按 dp 自适应。"
            )
        }
    }

    // ------------------------------------------------------------ 实时刷新

    override fun onPhaseChanged(phase: ChannelPhase, detail: String) {
        runOnUiThread {
            if (section == SECTION_VOICE || section == SECTION_CHANNEL || section == SECTION_LOGS) build()
        }
    }

    override fun onTtsChanged(replyId: String?) {
        runOnUiThread {
            if (auditionPending && TtsPlayer.streaming) {
                auditionPending = false
                auditionInFlight = true
                main.removeCallbacks(auditionTimeout)
                auditionText = "试听播放中…"
            } else if (auditionInFlight && !TtsPlayer.streaming) {
                auditionInFlight = false
                auditionText = "试听播放完毕"
            }
            refreshAuditionState()
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun channelPhaseLabel(phase: ChannelPhase): String = when (phase) {
        ChannelPhase.CONNECTED -> "已连上"
        ChannelPhase.CONNECTING -> "连接中"
        ChannelPhase.RECONNECTING -> "重连中"
        ChannelPhase.NOT_PAIRED -> "未配对"
        ChannelPhase.STOPPED -> "通道未运行"
    }

    private fun uploadPhaseLabel(phase: AudioUploadPhase): String = when (phase) {
        AudioUploadPhase.UPLOADING -> "上传中"
        AudioUploadPhase.WAITING_ASR -> "识别中"
        AudioUploadPhase.DONE -> "识别完成"
        AudioUploadPhase.FAILED -> "失败"
        AudioUploadPhase.BLOCKED -> "本地拦下"
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    private fun versionCode(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (t: Throwable) {
        0L
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
}
