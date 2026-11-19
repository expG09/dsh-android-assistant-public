package com.yanyan.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** 一次命令的执行结果（对应协议 `commandResult{id, ok, detail, launched}`）。 */
data class CommandResult(
    val id: String,
    val ok: Boolean,
    val detail: String,
    val launched: Boolean,
)

/**
 * 主界面那条「最近一次命令结果」的数据源。
 *
 * 按 decision.md：主界面只留关键状态，所以这里只保存**一行**结果提示，
 * 不做消息列表（对话在调试页/聊天页）。
 */
object CommandHub {
    @Volatile
    var lastLine: String = "还没有执行过命令"
        private set
    @Volatile
    var lastAt: Long = 0L
        private set

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (String) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    internal fun publish(line: String) {
        lastLine = line
        lastAt = System.currentTimeMillis()
        main.post { listeners.forEach { it(line) } }
    }
}

/**
 * M5 命令执行器：**每个 kind 一个函数**，不散在界面代码里。
 *
 * 白名单只有 8 种（见 docs/dev/M5 模块开发文档.md + docs/dev/命令表.md）：
 * call / sms / navigate / alarm / open_app / calendar / open_url / search_web。
 * 不在名单里的 kind 一律回 `ok:false detail:"unsupported"`，不崩溃。
 *
 * 原则：
 * - 只「拉起界面并把参数填好」，最后一步永远由人按（call 不申请 CALL_PHONE、只跳拨号盘）；
 * - `launched` 如实回报：Intent 没解析到、或 startActivity 抛异常都算 false；
 * - 日志只写 kind/id/ok/detail，不写参数正文（号码、地址这类用户内容不进 logcat）。
 */
object CommandExecutor : ChannelListener {
    private const val TAG = "CommandExecutor"

    /** 允许的 kind（白名单）。 */
    private val KINDS = setOf(
        "call",
        "sms",
        "navigate",
        "alarm",
        "open_app",
        "calendar",
        "open_url",
        "search_web",
    )

    /** 银行/支付类：即使名字匹配上也不直接开，先问一句（confirmed=true 才开）。 */
    private const val DETAIL_CONFIRM = "confirm_required"

    private var app: Context? = null

    /** 由 [KeepAliveService.onCreate] 调用：把执行器挂到通道上（服务在，执行器就在）。 */
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        ChannelHub.addListener(this)
        Log.i(TAG, "命令执行器就绪：白名单 ${KINDS.size} 种（${KINDS.joinToString("/")}）")
    }

    fun shutdown() {
        ChannelHub.removeListener(this)
        app = null
    }

    // ------------------------------------------------------------ 入口

    override fun onIncomingCommand(id: String, kind: String, args: JSONObject, say: String) {
        val ctx = app ?: return
        val result = try {
            execute(ctx, id, kind, args)
        } catch (t: Throwable) {
            // 任何异常都不许把进程带走：回一条失败结果，界面只显示一行
            Log.e(TAG, "执行命令异常 kind=$kind：${t.javaClass.simpleName}")
            CommandResult(id, false, "exception", false)
        }
        Log.i(
            TAG,
            "命令 kind=$kind id=$id ok=${result.ok} launched=${result.launched} detail=${result.detail}"
        )
        // 1) 回执给电脑（电脑据此回一句人话）
        val sent = KeepAliveService.channel?.sendCommandResult(
            result.id, result.ok, result.detail, result.launched
        ) ?: false
        if (!sent) Log.w(TAG, "命令回执没发出去（通道不在）id=$id")
        // 2) 主界面那一行结果提示
        CommandHub.publish(CommandText.hint(kind, result, args))
    }

    /**
     * M6：聊天页「重试」用 —— 用**同样的参数**在本地再执行一次。
     *
     * 复用与电脑下发时完全相同的一层（[execute] + [CommandText.hint]），所以行为口径一致；
     * 区别只有一个：这次不是电脑发起的，**不**发 commandResult（电脑端不认识这个 id，
     * 回执会变成一条无主结果），只更新「最近一条命令」那一行。
     */
    fun retryLocally(kind: String, args: JSONObject) {
        val ctx = app ?: return
        val id = "retry-" + System.currentTimeMillis()
        val result = try {
            execute(ctx, id, kind, args)
        } catch (t: Throwable) {
            Log.e(TAG, "重试命令异常 kind=$kind：${t.javaClass.simpleName}")
            CommandResult(id, false, "exception", false)
        }
        Log.i(TAG, "重试命令 kind=$kind ok=${result.ok} launched=${result.launched} detail=${result.detail}")
        CommandHub.publish(CommandText.hint(kind, result, args))
    }

    /** 真正执行；每个 kind 一个函数。 */
    private fun execute(ctx: Context, id: String, kind: String, args: JSONObject): CommandResult {
        if (kind !in KINDS) {
            Log.w(TAG, "不认识的命令 kind=$kind（白名单外，不执行）")
            return CommandResult(id, false, "unsupported", false)
        }
        return when (kind) {
            "call" -> doCall(ctx, id, args)
            "sms" -> doSms(ctx, id, args)
            "navigate" -> doNavigate(ctx, id, args)
            "alarm" -> doAlarm(ctx, id, args)
            "open_app" -> doOpenApp(ctx, id, args)
            "calendar" -> doCalendar(ctx, id, args)
            "open_url" -> doOpenUrl(ctx, id, args)
            "search_web" -> doSearchWeb(ctx, id, args)
            else -> CommandResult(id, false, "unsupported", false)
        }
    }

    // ------------------------------------------------------------ call / sms

    /** 打电话：`tel:` 只跳拨号盘（不申请 CALL_PHONE，绝不自动拨出）。 */
    private fun doCall(ctx: Context, id: String, args: JSONObject): CommandResult {
        val number = args.optString("number").replace(" ", "").trim()
        if (!Regex("^\\+?\\d{5,15}$").matches(number)) {
            return CommandResult(id, false, "invalid_args", false)
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        return deliver(ctx, id, listOf(Candidate(intent, "dialer_opened")), "no_dialer", "拨号盘")
    }

    /** 短信：`smsto:` 打开短信界面，正文预填（不自动发）。 */
    private fun doSms(ctx: Context, id: String, args: JSONObject): CommandResult {
        val number = args.optString("number").replace(" ", "").trim()
        val body = args.optString("body").trim()
        if (!Regex("^\\+?\\d{5,15}$").matches(number)) {
            return CommandResult(id, false, "invalid_args", false)
        }
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("smsto", number, null)
        ).apply {
            if (body.isNotEmpty()) putExtra("sms_body", body)
        }
        return deliver(ctx, id, listOf(Candidate(intent, "sms_opened")), "no_sms_app", "短信界面")
    }

    // ------------------------------------------------------------ navigate

    /**
     * 导航：先 `geo:0,0?q=`（系统弹可用地图，目的地已填）；
     * 没有东西响应再退高德 / 百度 scheme（见 docs/dev/命令表.md 第一节）。
     */
    private fun doNavigate(ctx: Context, id: String, args: JSONObject): CommandResult {
        val dest = args.optString("destination").trim()
        if (dest.isEmpty()) return CommandResult(id, false, "invalid_args", false)
        val q = Uri.encode(dest)

        val candidates = listOf(
            Candidate(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")), "maps_opened"),
            Candidate(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("androidamap://route/plan/?sourceApplication=dsh&dname=$q&dev=0&t=0")
                ),
                "amap_opened"
            ),
            Candidate(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("baidumap://map/direction?destination=$q&coord_type=gcj02&mode=driving")
                ),
                "baidu_maps_opened"
            ),
        )
        return deliver(ctx, id, candidates, "no_maps_app", "导航到「$dest」")
    }

    // ------------------------------------------------------------ alarm

    /** 闹钟：`AlarmClock.ACTION_SET_ALARM`，时间预填、**跳过 UI 关掉**（人确认保存才生效）。 */
    private fun doAlarm(ctx: Context, id: String, args: JSONObject): CommandResult {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return CommandResult(id, false, "invalid_args", false)
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "砚砚提醒")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false) // 停在闹钟界面，等人按保存
        }
        return deliver(ctx, id, listOf(Candidate(intent, "clock_opened")), "no_clock_app", "闹钟")
    }

    // ------------------------------------------------------------ open_app

    /**
     * 打开应用：按运行时的「已安装且有启动入口」索引找包名（见 [AppIndex]，现扫 + 30 秒复用）。
     * - 名字匹配不到 → `app_not_found`（不猜、不乱开）；
     * - 匹配到多个 → `app_ambiguous`；
     * - 银行/支付类 → `confirm_required`（电脑会先问一句，确认后带 confirmed=true 再来）。
     */
    private fun doOpenApp(ctx: Context, id: String, args: JSONObject): CommandResult {
        val confirmed = args.optBoolean("confirmed", false)
        val pkgArg = args.optString("package").trim()
        val nameArg = args.optString("appName").trim()

        var target: InstalledApp? = null
        if (pkgArg.isNotEmpty()) {
            target = AppIndex.byPackage(ctx, pkgArg)
            if (target == null) return CommandResult(id, false, "app_not_found", false)
        } else {
            if (nameArg.isEmpty()) return CommandResult(id, false, "invalid_args", false)
            when (val m = AppIndex.find(ctx, nameArg)) {
                is AppIndex.Match.Found -> target = m.app
                is AppIndex.Match.Ambiguous -> {
                    Log.i(TAG, "应用名「$nameArg」匹配到 ${m.candidates.size} 个，不猜")
                    return CommandResult(id, false, "app_ambiguous", false)
                }
                AppIndex.Match.None -> return CommandResult(id, false, "app_not_found", false)
            }
        }
        val app = target ?: return CommandResult(id, false, "app_not_found", false)

        if (AppIndex.isSensitive(app) && !confirmed) {
            Log.i(TAG, "「${app.label}」属于银行/支付类，先问一句再开")
            return CommandResult(id, false, DETAIL_CONFIRM, false)
        }

        val launchIntent = try {
            ctx.packageManager.getLaunchIntentForPackage(app.packageName)
        } catch (t: Throwable) {
            null
        }
        if (launchIntent == null) {
            // 刚被禁用/卸载掉的包（现扫的宽窗口内极少数情况）
            Log.w(TAG, "「${app.label}」没有启动入口")
            return CommandResult(id, false, "app_not_found", false)
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Log.i(TAG, "按名字打开应用：$nameArg → ${app.packageName}（扫描耗时 ${AppIndex.lastScanMs()} ms 级缓存）")
        return deliver(
            ctx,
            id,
            listOf(Candidate(launchIntent, "app_launched")),
            "launch_failed",
            "「${app.label}」"
        )
    }

    // ------------------------------------------------------------ calendar

    /** 日程：`ACTION_INSERT` 拉起日历编辑器，标题/时间/地点预填，等人按保存（不会自动保存）。 */
    private fun doCalendar(ctx: Context, id: String, args: JSONObject): CommandResult {
        val title = args.optString("title").trim()
        val start = args.optLong("start", 0L)
        if (title.isEmpty() || start <= 0L) return CommandResult(id, false, "invalid_args", false)
        var end = args.optLong("end", 0L)
        if (end <= start) end = start + 60 * 60 * 1000L
        val location = args.optString("location").trim()

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.Events.TITLE, title)
            if (location.isNotEmpty()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        }
        return deliver(ctx, id, listOf(Candidate(intent, "calendar_opened")), "no_calendar_app", "日程「$title」")
    }

    // ------------------------------------------------------------ 网页

    /** 打开网页：只接受 http/https，交系统浏览器。 */
    private fun doOpenUrl(ctx: Context, id: String, args: JSONObject): CommandResult {
        val url = args.optString("url").trim()
        if (!Regex("^https?://[^\\s]+$").matches(url)) {
            return CommandResult(id, false, "invalid_args", false)
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return deliver(ctx, id, listOf(Candidate(intent, "browser_opened")), "no_browser", "网页")
    }

    /** 用浏览器搜：交给系统搜索（`ACTION_WEB_SEARCH`），没有响应就退到百度搜索页。 */
    private fun doSearchWeb(ctx: Context, id: String, args: JSONObject): CommandResult {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return CommandResult(id, false, "invalid_args", false)
        val webSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
        }
        val baidu = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.baidu.com/s?wd=" + Uri.encode(query))
        )
        return deliver(
            ctx,
            id,
            listOf(
                Candidate(webSearch, "search_opened"),
                Candidate(baidu, "browser_search_opened")
            ),
            "no_browser",
            "搜索「$query」"
        )
    }

    // ------------------------------------------------------------ 小工具

    /** 一个「拉起界面」的候选：Intent + 成功时的 detail。 */
    private class Candidate(val intent: Intent, val okDetail: String)

    /**
     * 交付一次界面拉起（M5c 起后台也能真的弹出来）。
     *
     * 顺序：
     * 1) 按候选顺序找第一个**有应用能处理**的 Intent，都没有就回 failDetail（例如 no_dialer）；
     * 2) 现在能不能直接 startActivity：
     *    - 界面在前台，或「悬浮窗开关开着 + 系统权限在」→ 直接 `startActivity`，如实回 launched；
     *    - 否则（后台 + 没有这个特权）→ **不发 startActivity**（Android 10+ 会静默丢弃，白跑），
     *      改发一条可点击的通知，用户点通知由系统把界面拉起来；
     * 3) 通知也发不出去才回 `launch_failed`。
     *
     * 注意：这一层不改变各 kind 的语义（还是只拉起界面、参数填好，最后一步由人按）。
     */
    private fun deliver(
        ctx: Context,
        id: String,
        candidates: List<Candidate>,
        failDetail: String,
        label: String,
    ): CommandResult {
        val picked = candidates.firstOrNull { resolvable(ctx, it.intent) }
        if (picked == null) {
            Log.i(TAG, "没有应用能处理这个 Intent（$label）")
            return CommandResult(id, false, failDetail, false)
        }
        picked.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (!BackgroundLaunch.canStartActivityNow(ctx)) {
            val posted = BackgroundLaunch.postCommandNotification(ctx, label, picked.intent)
            Log.i(TAG, "后台且没开悬浮窗 → 不发 startActivity，改发通知（posted=$posted）$label")
            return CommandResult(
                id,
                false,
                if (posted) BackgroundLaunch.DETAIL_NOTIFIED else "launch_failed",
                false
            )
        }

        val ok = startNow(ctx, picked.intent)
        return CommandResult(id, ok, if (ok) picked.okDetail else failDetail, ok)
    }

    private fun resolvable(ctx: Context, intent: Intent): Boolean = try {
        ctx.packageManager.resolveActivity(intent, 0) != null
    } catch (t: Throwable) {
        false
    }

    /** 真的 startActivity；异常都算 false（`launched` 如实回报）。 */
    private fun startNow(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "ActivityNotFoundException action=${intent.action}")
        false
    } catch (t: Throwable) {
        Log.w(TAG, "startActivity 失败 action=${intent.action}：${t.javaClass.simpleName}")
        false
    }
}

/**
 * 把 `(kind, 结果)` 翻成主界面那一行中文提示。
 * 与电脑端的说法保持一致（电脑端也有一份同样的映射，用来回复对话）。
 */
object CommandText {
    fun hint(kind: String, r: CommandResult, args: JSONObject): String {
        val name = args.optString("appName").ifEmpty { args.optString("package") }
        val dest = args.optString("destination")
        val query = args.optString("query")
        if (!r.ok) {
            return when (r.detail) {
                "unsupported" -> "手机端还不认识「$kind」这个命令，没执行"
                "app_not_found" -> if (name.isNotEmpty()) "没找到这个应用「$name」" else "没找到这个应用"
                "app_ambiguous" -> if (name.isNotEmpty()) "有多个叫「$name」的应用，我没敢猜" else "有多个同名应用，我没敢猜"
                "confirm_required" -> if (name.isNotEmpty()) "「$name」是支付/银行类，先确认一下再开" else "这类应用先确认一下再开"
                "blocked_background" -> "后台被系统拦住，已发通知：点通知打开"
                "invalid_args" -> "参数不对，没执行"
                "no_maps_app" -> "手机上没找到地图应用"
                "no_dialer" -> "手机上没找到拨号应用"
                "no_sms_app" -> "手机上没找到短信应用"
                "no_clock_app" -> "手机上没找到闹钟应用"
                "no_calendar_app" -> "手机上没找到日历应用"
                "no_browser" -> "手机上没找到浏览器"
                "launch_failed" -> "试了一下没拉起来"
                "exception" -> "执行时出错了，没拉起来"
                else -> "没执行成功（${r.detail}）"
            }
        }
        return when (kind) {
            "call" -> "拨号盘已打开，号码填好了"
            "sms" -> "短信界面已打开，正文填好了"
            "navigate" -> if (dest.isNotEmpty()) "地图已打开，目的地「$dest」填好了" else "地图已打开"
            "alarm" -> "闹钟页面已打开，时间填好了"
            "open_app" -> if (name.isNotEmpty()) "「$name」已打开" else "应用已打开"
            "calendar" -> "日程页面已打开，标题和时间填好了"
            "open_url" -> "网页已在浏览器打开"
            "search_web" -> if (query.isNotEmpty()) "浏览器里已经搜了「$query」" else "浏览器已打开"
            else -> "已执行"
        }
    }
}
