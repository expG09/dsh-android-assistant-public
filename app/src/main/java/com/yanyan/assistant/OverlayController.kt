package com.yanyan.assistant

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

/**
 * M5c：悬浮浮层（`SYSTEM_ALERT_WINDOW` + `WindowManager` 叠加，**不是** Activity、不是 Dialog、不新增 Service）。
 *
 * 形态（2026-11-16 第三轮，expG09 定：**第一版的竖装结构 + 宽度砍半**）：
 * **78dp 宽 × 约 82dp 高**（第一版是 148dp 宽、约 84dp 高）。
 *
 * ```
 * ┌──────────────┐
 * │   监听中      │  ← 上：宽的状态条（30dp，一行字）——**这一段是拖动区**
 * │ ┌──────────┐ │
 * │ │ 按住说话  │ │  ← 下：明显更高的按钮块（58×46dp，比上面窄）
 * │ └──────────┘ │
 * └──────────────┘
 * ```
 *
 * - 边距：左右 4dp、下 4dp 是窄边；上面状态条就是那条「宽的边」（贴到卡片顶边）。
 * - 底色是**第一版的黑底**（近黑半透明），按钮是**第一版的蓝**（只做点缀，不铺满），
 *   文字浅灰白；三态**只用文字**区分（空闲 / 监听中 / 录音 3s / 暂停 / 异常），
 *   不做彩色块、不做小圆点。配色常量集中在 [COLOR_CARD] / [COLOR_BUTTON] / [COLOR_TEXT]。
 * - 上边那条是**非按钮区**，只用来拖动 —— 否则整条浮层都是「按住说话」，一拖就误触录音（expG09 特别强调）。
 * - 下边按钮是「按住说话」：直接调 [ListenController.startRecording] / [ListenController.stopRecording]，
 *   与主界面按钮、唤醒后自动录音**同一条** M3→M4 链路，浮层自己不写文件、不上传、不识别。
 * - **最近一条命令结果不常驻**：另开一个不抢触摸的小窗口（[showStrip]），从条旁边弹出，2.5 秒自动收起。
 * - **最近一条命令结果不常驻**：另开一个不抢触摸的小窗口（[showStrip]），从条旁边弹出，2.5 秒自动收起。
 *
 * 位置：拖动松手离左右边缘 < 80dp 自动吸附、留 8dp 安全边距；y 方向始终避开状态栏/导航栏。
 * 落盘「边 + y」（[LocalStore.overlayEdge]），浮层尺寸以后变了也还贴得住边。
 *
 * 另外它是「后台可弹界面」的凭据：`SYSTEM_ALERT_WINDOW` 是 Android 10+ 后台启动 Activity 的豁免之一，
 * 见 [BackgroundLaunch]。
 *
 * 线程约束：所有方法都在主线程调用（服务 onCreate、界面回调都是主线程）。
 */
object OverlayController {
    private const val TAG = "Overlay"

    /** 浮层窗口标题：`dumpsys window windows` 里直接能看到，验收取证用。 */
    const val WINDOW_TITLE = "yanyan_overlay"

    /** 临时结果小条的窗口标题。 */
    const val STRIP_WINDOW_TITLE = "yanyan_overlay_strip"

    /**
     * 整条浮层尺寸（dp，**都是以 360dp 宽的手机为基准**，实际按屏宽等比放大，见 [scaleFor]）：
     * 第一版是 148dp 宽 × 约 84dp 高的竖装三行卡片 → 现在 **78dp 宽 × 约 84dp 高**，宽度砍半：
     * 上面一条 30dp 的状态条（拖动区），下面 58×48dp 的「按住说话」按钮（≥48dp 可点）。
     * 高度是「30 + 2 + 48 + 4（下边距）」算出来的定值，和 overlay_hud.xml 一致。
     */
    private const val CARD_W_DP = 78
    private const val CARD_H_DP = 84

    /** 状态条高度（窄的一条，一行字 + 少量内边距）。 */
    private const val STATUS_H_DP = 30

    /** 按钮尺寸：高度故意 ≥48dp（最小可点），宽度比状态条窄 → 就是 expG09 说的「上宽下高、下边更窄」。 */
    private const val BTN_W_DP = 58
    private const val BTN_H_DP = 48
    private const val GAP_DP = 2
    private const val PAD_DP = 4

    /** 尺寸自适应基准屏宽（dp）：窄于它按 1.0 倍，宽屏（平板/折叠）按比例放大，最多 1.5 倍。 */
    private const val BASE_SCREEN_W_DP = 360f
    private const val MAX_SCALE = 1.5f

    /** 边缘吸附阈值：离屏幕左右边缘 < 80dp 就吸过去（decision.md 定）。 */
    private const val SNAP_EDGE_DP = 80

    /** 吸附后离屏幕边缘留的安全边距。 */
    private const val SAFE_MARGIN_DP = 8

    /** 顶部/底部安全区，别让浮层压到状态栏、导航栏/手势条上。 */
    private const val TOP_SAFE_DP = 24
    private const val BOTTOM_SAFE_DP = 64

    /** 临时小条：固定尺寸（内容单行省略），离浮层 6dp。 */
    private const val STRIP_W_DP = 176
    private const val STRIP_H_DP = 32
    private const val STRIP_GAP_DP = 6
    private const val STRIP_SHOW_MS = 2_500L

    private const val TICK_IDLE_MS = 1_000L
    private const val TICK_RECORD_MS = 200L

    /** 点一下状态区（没拖动）就弹最近一条命令：按下到松手不超过这么久才算「点一下」。 */
    private const val TAP_MAX_MS = 400L

    /**
     * 配色（**集中在这里，别散到布局里**）——就是第一版那套：
     * 近黑半透明底 + 蓝色按钮 + 浅灰白文字。
     *
     * 三态**只用文字区分**：空闲 / 监听中 / 录音 3s（暂停、异常也各有字），
     * 所以这里没有「按状态换颜色」的常量：整条永远是黑底，不做彩色块、不做圆点。
     * 以后要加点缀（比如录音时描边高亮），改 [applyPalette] 一处即可。
     */
    private const val COLOR_CARD = 0xE6161C24.toInt()      // 第一版黑底：近黑、半透明但压得住背景
    private const val COLOR_BUTTON = 0xFF2F6FED.toInt()    // 第一版按钮蓝（只做点缀，不铺满）
    private const val COLOR_TEXT = 0xFFFFE8EEF6.toInt()    // 状态文字：浅灰白

    private var app: Context? = null
    private var wm: WindowManager? = null

    private var params: WindowManager.LayoutParams? = null
    private var root: View? = null
    private var tvState: TextView? = null
    private var btnHold: Button? = null
    private var cardBg: GradientDrawable? = null

    private var stripView: View? = null
    private var stripParams: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())

    // 拖动状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var moveStartX = 0
    private var moveStartY = 0
    private var downAt = 0L
    private var dragging = false

    /** 这一次按钮按下是不是「按住说话」触发的录音（false = 按下时已在录音）。 */
    private var pressedToRecord = false

    /** 按下那一刻这段录音是不是唤醒词触发的（只有这种情况，点一下才算「停止」）。 */
    private var wakeRecordingAtDown = false

    /** 屏宽自适应系数（1.0 = 360dp 宽的手机，上限 1.5）与文字系数（上限 1.25）。 */
    private var scale = 1f
    private var textScale = 1f

    /** 录音中的明暗闪动：每 200ms 翻一次。 */
    private var pulseOn = false

    private val stateListener: (ListenState) -> Unit = { main.post { restartTicker() } }

    /**
     * M6（2026-11-16 expG09 反馈「界面跟着陀螺仪转」）：屏幕方向 / 尺寸变了，浮层按新的屏幕重摆一次。
     *
     * 浮层本身**没有方向**：它是 `TYPE_APPLICATION_OVERLAY` + `gravity=TOP|START`，位置是绝对像素；
     * 所以这里不监听陀螺仪、不自己转，只在系统通知「配置变了」时重新量屏宽、重算尺寸与位置，
     * 保证旋转/分屏后不会卡在屏幕外。Activity 那边统一 portrait（见 AndroidManifest）。
     */
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            main.post { reclampAfterConfigChange() }
        }

        override fun onLowMemory() = Unit
    }

    private val commandListener: (String) -> Unit = { line ->
        main.post {
            if (root != null) showStrip(app?.getString(R.string.overlay_cmd_prefix, line) ?: line)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (root == null) return
            updateState()
            main.postDelayed(this, if (ListenController.recording) TICK_RECORD_MS else TICK_IDLE_MS)
        }
    }

    private val stripHide = Runnable {
        hideStrip()
        Log.i(TAG, "临时结果小条已自动收起")
    }

    fun isVisible(): Boolean = root != null

    /**
     * 「现在真的能画在别的应用上」= 开关开着 + 系统权限还在。
     *
     * [BackgroundLaunch] 用这个判断后台能不能直接弹界面 —— 口径与设置页文案一致：
     * 开关关掉就等于「未开启悬浮窗」。
     */
    fun isActive(ctx: Context): Boolean =
        LocalStore.overlayEnabled(ctx) && Settings.canDrawOverlays(ctx)

    /** 按落盘的开关恢复浮层（服务起来 / 开机广播拉起进程时走这里）。 */
    fun syncFromPrefs(ctx: Context) {
        val c = ctx.applicationContext
        main.post {
            if (isActive(c)) {
                val err = show(c)
                if (err != null) Log.w(TAG, "恢复浮层失败：$err") else Log.i(TAG, "已按落盘的开关显示浮层")
            } else if (isVisible()) {
                Log.i(TAG, "开关或权限没了，收起浮层")
                hide()
            }
        }
    }

    /**
     * 显示浮层。返回 null 表示成功，否则是失败原因（给界面 toast）。
     * 已经在显示时直接返回 null（不重复 addView）。
     */
    fun show(ctx: Context): String? {
        val c = ctx.applicationContext
        if (root != null) return null
        if (!Settings.canDrawOverlays(c)) return "系统没给悬浮窗权限"

        val wmgr = c.getSystemService(WindowManager::class.java) ?: return "拿不到 WindowManager"
        val view = try {
            LayoutInflater.from(c).inflate(R.layout.overlay_hud, null)
        } catch (t: Throwable) {
            Log.e(TAG, "加载浮层布局失败：${t.javaClass.simpleName}")
            return "加载浮层失败"
        }

        // 尺寸自适应：以 360dp 宽的手机为 1.0 倍，宽屏（平板/折叠）按比例放大（上限 1.5 倍）
        scale = scaleFor(c)
        textScale = scale.coerceAtMost(1.25f)

        val p = WindowManager.LayoutParams(
            dpS(c, CARD_W_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setTitle(WINDOW_TITLE)
        }

        // 先把引用挂上（位置计算 / clamp 要用 c、params）
        app = c
        wm = wmgr
        params = p
        root = view

        applySizes(view)
        applySavedPosition(p)
        clampCard(p)

        try {
            wmgr.addView(view, p)
        } catch (t: Throwable) {
            Log.e(TAG, "addView 失败：${t.javaClass.simpleName} ${t.message}")
            clearRefs()
            return "浮层加不上去（${t.javaClass.simpleName}）"
        }

        tvState = view.findViewById(R.id.tv_overlay_state)
        btnHold = view.findViewById(R.id.btn_overlay_hold)
        cardBg = (view.background?.mutate() as? GradientDrawable)
        applyPalette()
        // 屏幕方向 / 尺寸变化时重新摆放（浮层不自己转，只在配置变了之后重算）
        try {
            c.registerComponentCallbacks(configCallbacks)
        } catch (t: Throwable) {
            Log.w(TAG, "注册屏幕变化回调失败：${t.javaClass.simpleName}")
        }

        attachDrag(view)
        btnHold?.let { attachHoldToTalk(it) }
        // 贴边吸附后，浮层会压在系统「返回手势」那条边上：把手势排除区让出来，
        // 否则从最左边/最右边起手拖，会被系统当成返回手势吃掉（实测点不出来）。
        excludeFromSystemGestures(view)

        restartTicker()
        updateState()
        Log.i(
            TAG,
            "浮层已显示：${CARD_W_DP}dp × ${CARD_H_DP}dp（×${String.format(Locale.CHINA, "%.2f", scale)} 屏宽自适应，" +
                    "实际 ${dpS(c, CARD_W_DP)}×${dpS(c, CARD_H_DP)}px），位置 x=${p.x} y=${p.y}"
        )
        return null
    }

    /**
     * 所有尺寸都从 dp 常量按屏宽缩放后落到视图上（布局里的值是手机基准，这里覆盖）。
     * 按钮高度保证 ≥ 48dp（最小可点），平板/折叠屏上整条等比放大不会变小。
     */
    private fun applySizes(view: View) {
        val c = app ?: return
        // 根视图宽度由窗口参数给（dpS(CARD_W_DP)），这里只落内边距
        view.setPadding(dpS(c, PAD_DP), 0, dpS(c, PAD_DP), dpS(c, PAD_DP))

        val tv = view.findViewById<TextView>(R.id.tv_overlay_state)
        tv?.let {
            val lp = it.layoutParams
            lp.height = dpS(c, STATUS_H_DP)
            it.layoutParams = lp
            it.textSize = 9f * textScale
        }
        val btn = view.findViewById<Button>(R.id.btn_overlay_hold)
        btn?.let {
            val lp = it.layoutParams as? android.widget.LinearLayout.LayoutParams
            if (lp != null) {
                lp.width = dpS(c, BTN_W_DP)
                // 最小可点：高度不低于 48dp
                lp.height = maxOf(dpS(c, BTN_H_DP), dpRaw(c, 48))
                lp.topMargin = dpS(c, GAP_DP)
                it.layoutParams = lp
            }
            it.textSize = 10f * textScale
        }
        Log.i(
            TAG,
            "浮层尺寸：屏宽 ${screenWidth(c) / c.resources.displayMetrics.density}dp → 缩放 " +
                    String.format(Locale.CHINA, "%.2f", scale) +
                    "；状态条 ${dpS(c, STATUS_H_DP)}px、按钮 ${dpS(c, BTN_W_DP)}×${maxOf(dpS(c, BTN_H_DP), dpRaw(c, 48))}px"
        )
    }

    /** 把配色常量刷到视图上（黑底 + 蓝按钮 + 浅灰白文字，与第一版一致）。 */
    private fun applyPalette() {
        cardBg?.setColor(COLOR_CARD)
        tvState?.setTextColor(COLOR_TEXT)
        try {
            btnHold?.backgroundTintList = ColorStateList.valueOf(COLOR_BUTTON)
            btnHold?.setTextColor(0xFFFFFFFF.toInt())
        } catch (t: Throwable) {
            Log.w(TAG, "按钮配色设置失败：${t.javaClass.simpleName}")
        }
    }

    /**
     * 把整条浮层排除出系统手势区（Android 10+）。
     * 只影响「从浮层上起手的滑动」，不会挡住别处的返回手势；失败也不影响其他功能。
     */
    private fun excludeFromSystemGestures(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.post {
            try {
                view.systemGestureExclusionRects =
                    listOf(Rect(0, 0, view.width, view.height))
                Log.i(TAG, "已把浮层排除出系统手势区：${view.width}×${view.height}")
            } catch (t: Throwable) {
                Log.w(TAG, "设置手势排除区失败：${t.javaClass.simpleName}")
            }
        }
    }

    /** 立刻移除浮层与临时小条并释放（关开关、服务被停、权限被收走都走这里）。 */
    fun hide() {
        val view = root
        main.removeCallbacks(tick)
        main.removeCallbacks(stripHide)
        ListenController.removeStateListener(stateListener)
        CommandHub.removeListener(commandListener)
        try {
            app?.unregisterComponentCallbacks(configCallbacks)
        } catch (t: Throwable) {
            Log.w(TAG, "注销屏幕变化回调失败：${t.javaClass.simpleName}")
        }
        hideStrip()
        if (view != null) {
            try {
                wm?.removeView(view)
                Log.i(TAG, "浮层已移除")
            } catch (t: Throwable) {
                Log.w(TAG, "removeView 失败：${t.javaClass.simpleName}")
            }
        }
        clearRefs()
    }

    // ------------------------------------------------------------ 拖动（左侧状态区）

    /**
     * 拖动只挂在**整条浮层**上：右半边按钮自己吃掉触摸做按住说话，
     * 所以实际可拖的热区 = 左边状态文字那一片（约 32dp × 36dp）+ 内边距。
     */
    private fun attachDrag(view: View) {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        view.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    moveStartX = p.x
                    moveStartY = p.y
                    downAt = SystemClock.elapsedRealtime()
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        hideStrip()
                    }
                    if (dragging) {
                        p.x = moveStartX + dx.toInt()
                        p.y = moveStartY + dy.toInt()
                        clampCard(p)
                        updateLayout()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val held = SystemClock.elapsedRealtime() - downAt
                    if (dragging) {
                        snapToEdge()
                    } else if (held < TAP_MAX_MS) {
                        // 点一下状态区：把最近一条命令弹出来看一眼（2.5 秒后自动收起）
                        showStrip(currentCommandText())
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    // ------------------------------------------------------------ 按住说话 / 点一下停止（下方按钮）

    /**
     * 按钮有两种语义，靠「**按下之前这段录音是不是唤醒词触发的**」区分（expG09 2026-11-16 追加，第二次精确化）：
     *
     * | 按下前的状态 | 按钮显示 | 按下做什么 | 松手/点击做什么 |
     * |---|---|---|---|
     * | 空闲 / 监听中（没在录） | 「按住说话」 | 开始录音（`pressedToRecord=true`） | 松手 = `stopRecording()`（reason=released） |
     * | **唤醒词触发的自动录音**（`ListenController.wakeRecording=true`） | 「停止」 | 什么都不做 | 点一下 = `stopRecordingManually()`（reason=manual_stop，立刻收尾上传） |
     * | 别的来源在录（主界面按钮/通知栏） | 「按住说话」 | 不重启录音 | 什么都不做（别抢别人的录音） |
     *
     * 关键：判定依据是 **`wakeRecording`（这段录音由唤醒触发）**，不是「正在录音」——
     * 按住说话期间按钮文案保持「按住说话」，不会被改成「停止」。
     */
    @Suppress("ClickableViewAccessibility")
    private fun attachHoldToTalk(btn: Button) {
        btn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    wakeRecordingAtDown = ListenController.recording && ListenController.wakeRecording
                    if (ListenController.recording) {
                        // 已经在录：只有「唤醒触发的自动录音」这一种，这一下才算「点停止」
                        pressedToRecord = false
                        Log.i(
                            TAG,
                            if (wakeRecordingAtDown) "按钮按下时是唤醒触发的自动录音 → 这次是「点一下停止」"
                            else "按钮按下时已在录音（非唤醒触发）→ 不打断，也不重启录音"
                        )
                    } else {
                        pressedToRecord = true
                        startRecordingFromOverlay()
                    }
                    updateState()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (pressedToRecord) {
                        if (ListenController.recording) ListenController.stopRecording()
                    } else if (wakeRecordingAtDown) {
                        val stopped = ListenController.stopRecordingManually()
                        Log.i(TAG, "浮层点「停止」：${if (stopped) "已立刻收尾并上传" else "当前没有在录音"}")
                    }
                    pressedToRecord = false
                    wakeRecordingAtDown = false
                    v.performClick()
                    updateState()
                    true
                }

                else -> false
            }
        }
    }

    /** 与主界面「按住说话」同一个入口：M3 采集 → M4 自动上传识别。 */
    private fun startRecordingFromOverlay() {
        val c = app ?: return
        val err = ListenController.startRecording()
        if (err != null) {
            Log.i(TAG, "浮层按住说话被拒：$err")
            val msg = if (!ListenController.enabled) {
                "监听开关是关的：先打开 App 里的监听开关，浮层才能录音"
            } else {
                err
            }
            try {
                Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "toast 失败：${t.javaClass.simpleName}")
            }
            restartTicker()
            return
        }
        Log.i(TAG, "浮层按住说话开始（复用 M3 录音链路）")
        restartTicker()
    }

    // ------------------------------------------------------------ 位置 / 吸附

    private fun applySavedPosition(p: WindowManager.LayoutParams) {
        val c = app ?: return
        val w = cardWidthPx(c)
        val margin = dp(c, SAFE_MARGIN_DP)
        val screenW = screenWidth(c)
        val saved = LocalStore.overlayPosition(c)
        val edge = LocalStore.overlayEdge(c)

        if (saved == null) {
            // 第一次：贴右边、竖直约 30%
            p.x = screenW - w - margin
            p.y = (screenHeight(c) * 0.30f).toInt()
            Log.i(TAG, "第一次显示浮层，默认贴右：x=${p.x} y=${p.y}")
            return
        }
        p.y = saved.second
        p.x = when (edge) {
            LocalStore.EDGE_LEFT -> margin
            LocalStore.EDGE_RIGHT -> screenW - w - margin
            else -> saved.first
        }
        Log.i(TAG, "按记住的位置显示浮层：x=${p.x} y=${p.y}（edge=${edge ?: "没存过"}）")
    }

    /** 松手后：靠左/靠右吸附 + 落盘（边 + y）。 */
    private fun snapToEdge() {
        val c = app ?: return
        val p = params ?: return
        val w = cardWidthPx(c)
        val screenW = screenWidth(c)
        val edgePx = dp(c, SNAP_EDGE_DP)
        val margin = dp(c, SAFE_MARGIN_DP)

        val beforeX = p.x
        val edge = when {
            p.x < edgePx -> {
                p.x = margin
                LocalStore.EDGE_LEFT
            }

            screenW - (p.x + w) < edgePx -> {
                p.x = screenW - w - margin
                LocalStore.EDGE_RIGHT
            }

            else -> LocalStore.EDGE_FREE
        }
        clampCard(p)
        updateLayout()
        LocalStore.setOverlayPosition(c, p.x, p.y, edge)
        Log.i(
            TAG,
            "拖动结束 x: $beforeX → ${p.x}（吸附阈值 ${SNAP_EDGE_DP}dp，边距 ${SAFE_MARGIN_DP}dp，edge=$edge），" +
                    "位置已记住 y=${p.y}"
        )
    }

    private fun updateLayout() {
        val view = root ?: return
        val p = params ?: return
        try {
            wm?.updateViewLayout(view, p)
        } catch (t: Throwable) {
            Log.w(TAG, "updateViewLayout 失败：${t.javaClass.simpleName}")
        }
    }

    /** 别让浮层被拖出屏幕，也别压到状态栏/导航栏。 */
    private fun clampCard(p: WindowManager.LayoutParams) {
        val c = app ?: return
        val w = cardWidthPx(c)
        val h = cardHeightPx(c)
        val maxX = (screenWidth(c) - w).coerceAtLeast(0)
        val minY = dp(c, TOP_SAFE_DP)
        val maxY = (screenHeight(c) - h - dp(c, BOTTOM_SAFE_DP)).coerceAtLeast(minY)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(minY, maxY)
    }

    /**
     * 屏幕配置变了（旋转 / 分屏 / 改分辨率）之后重新摆一次浮层。
     * 只做「按新屏宽重算尺寸 + 回到记住的边 + 夹回屏幕内」，不改开关、不碰录音。
     */
    private fun reclampAfterConfigChange() {
        val c = app ?: return
        val p = params ?: return
        val view = root ?: return
        hideStrip()
        scale = scaleFor(c)
        textScale = scale.coerceAtMost(1.25f)
        p.width = dpS(c, CARD_W_DP)
        applySizes(view)
        applySavedPosition(p)
        clampCard(p)
        updateLayout()
        Log.i(TAG, "屏幕配置变化：浮层已按新尺寸重摆 x=${p.x} y=${p.y}")
    }

    // ------------------------------------------------------------ 临时结果小条（不常驻、不撑大浮层）

    private fun currentCommandText(): String {
        val c = app ?: return ""
        return if (CommandHub.lastAt > 0L) {
            c.getString(R.string.overlay_cmd_prefix, CommandHub.lastLine)
        } else {
            c.getString(R.string.overlay_no_command)
        }
    }

    /**
     * 从浮层旁边弹一小条，[STRIP_SHOW_MS] 后自动收起。
     * 小条是**独立窗口**且 `FLAG_NOT_TOUCHABLE`，既不影响浮层尺寸，也不挡触摸。
     */
    private fun showStrip(text: String) {
        val c = app ?: return
        if (root == null) return
        if (text.isEmpty()) return
        hideStrip()
        val wmgr = wm ?: return
        val strip = try {
            LayoutInflater.from(c).inflate(R.layout.overlay_strip, null) as TextView
        } catch (t: Throwable) {
            Log.w(TAG, "加载小条布局失败：${t.javaClass.simpleName}")
            return
        }
        strip.text = text
        strip.textSize = 11f * textScale
        val stripW = dpS(c, STRIP_W_DP)
        val stripH = dpS(c, STRIP_H_DP)
        val sp = WindowManager.LayoutParams(
            stripW,
            stripH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setTitle(STRIP_WINDOW_TITLE)
        }
        placeStrip(sp, stripW, stripH)
        try {
            wmgr.addView(strip, sp)
        } catch (t: Throwable) {
            Log.w(TAG, "小条 addView 失败：${t.javaClass.simpleName}")
            return
        }
        stripView = strip
        stripParams = sp
        main.removeCallbacks(stripHide)
        main.postDelayed(stripHide, STRIP_SHOW_MS)
        Log.i(TAG, "临时结果小条已弹出（${STRIP_SHOW_MS}ms 后自动收起）：x=${sp.x} y=${sp.y}")
    }

    /** 小条放在浮层远离屏幕边缘的一侧，竖直居中对齐。 */
    private fun placeStrip(sp: WindowManager.LayoutParams, stripW: Int, stripH: Int) {
        val c = app ?: return
        val p = params ?: return
        val w = cardWidthPx(c)
        val h = cardHeightPx(c)
        val gap = dpS(c, STRIP_GAP_DP)
        val screenW = screenWidth(c)
        val cardOnRightHalf = p.x + w / 2 >= screenW / 2
        sp.x = if (cardOnRightHalf) p.x - stripW - gap else p.x + w + gap
        sp.y = p.y + (h - stripH) / 2
        val pad = dpS(c, 4)
        sp.x = sp.x.coerceIn(pad, (screenW - stripW - pad).coerceAtLeast(pad))
        val minY = dp(c, TOP_SAFE_DP)
        val maxY = (screenHeight(c) - stripH - dp(c, BOTTOM_SAFE_DP)).coerceAtLeast(minY)
        sp.y = sp.y.coerceIn(minY, maxY)
    }

    private fun hideStrip() {
        main.removeCallbacks(stripHide)
        val strip = stripView ?: return
        stripView = null
        stripParams = null
        try {
            wm?.removeView(strip)
        } catch (t: Throwable) {
            Log.w(TAG, "移除小条失败：${t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ 状态表现

    private fun restartTicker() {
        ListenController.addStateListener(stateListener)
        CommandHub.addListener(commandListener)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** 状态**只用文字**表达：空闲 / 监听中 / 录音 3s / 暂停 / 异常（底色始终是黑底）。 */
    private fun updateState() {
        val recording = ListenController.recording
        val state = ListenController.state
        val label = when {
            recording -> "录音 ${ListenController.liveRecordedMs() / 1000}s"
            state == ListenState.LISTENING -> "监听中"
            state == ListenState.PAUSED_MIC -> "暂停"
            state == ListenState.ERROR -> "异常"
            else -> "空闲"
        }
        tvState?.text = label
        // 只有「唤醒词触发的自动录音」按钮才变「停止」；按住说话期间文案保持「按住说话」。
        val appCtx = app
        val showStop = recording && ListenController.wakeRecording
        btnHold?.text = if (showStop) {
            appCtx?.getString(R.string.overlay_stop_btn) ?: "停止"
        } else {
            appCtx?.getString(R.string.overlay_hold_btn) ?: "按住说话"
        }
        btnHold?.alpha = if (ListenController.enabled) 1f else 0.55f
    }

    // ------------------------------------------------------------ 小工具

    private fun clearRefs() {
        root = null
        wm = null
        params = null
        tvState = null
        btnHold = null
        cardBg = null
        stripView = null
        stripParams = null
    }

    private fun cardWidthPx(c: Context): Int = dpS(c, CARD_W_DP)

    /** 高度用布局量出来的真实值（窗口是 WRAP_CONTENT），没量到就用常量估（= 30+2+48+4）。 */
    private fun cardHeightPx(c: Context): Int =
        root?.height?.takeIf { it > 0 } ?: dpS(c, CARD_H_DP)

    private fun screenWidth(c: Context): Int = c.resources.displayMetrics.widthPixels

    private fun screenHeight(c: Context): Int = c.resources.displayMetrics.heightPixels

    /** 屏宽自适应：360dp 宽的手机 = 1.0 倍，平板/折叠按比例放大，最多 1.5 倍。 */
    private fun scaleFor(c: Context): Float {
        val dm = c.resources.displayMetrics
        val screenWdp = dm.widthPixels / dm.density
        return (screenWdp / BASE_SCREEN_W_DP).coerceIn(1f, MAX_SCALE)
    }

    /** dp → px（**不**带自适应系数，用于 48dp 这种硬性最小值）。 */
    private fun dpRaw(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    /** dp → px，并按屏宽自适应（所有控件尺寸都走这个）。 */
    private fun dpS(c: Context, v: Int): Int = (v * scale * c.resources.displayMetrics.density).toInt()

    private fun dp(c: Context, v: Int): Int = dpRaw(c, v)
}
