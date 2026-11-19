package com.yanyan.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.util.Locale

/** 一个「能起界面」的应用：包名 + 显示名（label）。 */
data class InstalledApp(val packageName: String, val label: String)

/**
 * 中文名 → 包名 的运行时索引（M5 `open_app` 用）。
 *
 * 做法（expG09 2026-11-16 定的简化方案）：
 * - **每次要用就现扫一遍 PackageManager**：只取「有 LAUNCHER Activity」的应用 + 显示名，
 *   当场做名字匹配，用完即弃；
 * - 只留一个**极短的复用窗口**（[WINDOW_MS] = 30 秒）：30 秒内连续下发命令不重复扫；
 * - **不落盘、不注册 PACKAGE_ADDED/REMOVED 广播**——所以永远不会「装上了查不到」「卸载了还在表里」，
 *   也就不用维护增量更新的正确性；扫一次几百毫秒，比整条语音链路短得多。
 *
 * 过滤：`com.vivo.*widget`、`com.taobao.widgetvivo` 这类桌面卡片没有 LAUNCHER Activity，
 * 天然被 queryIntentActivities(MAIN/LAUNCHER) 排除，不会得到一个「点了没反应」的结果。
 */
object AppIndex {
    private const val TAG = "AppIndex"

    /** 复用窗口：30 秒内的连续命令共用一次扫描结果。 */
    private const val WINDOW_MS = 30_000L

    private val lock = Any()
    private var snapshot: List<InstalledApp> = emptyList()
    private var scannedAt = 0L
    private var lastScanCostMs = 0L

    /**
     * 别名表：**只做兜底**（口语叫法 vs 应用商店名对不上时），不承担「中文名 → 包名」的主映射。
     * 主映射永远来自运行时扫到的 label；这里每个包名都要先确认「这次扫到了且有启动入口」才采用。
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "b站" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
        "bilibili" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
        "哔哩哔哩" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
        "小破站" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
        "微信" to listOf("com.tencent.mm"),
        "wechat" to listOf("com.tencent.mm"),
        "weixin" to listOf("com.tencent.mm"),
        "qq" to listOf("com.tencent.mobileqq"),
        "tim" to listOf("com.tencent.tim"),
        "支付宝" to listOf("com.eg.android.AlipayGphone"),
        "高德" to listOf("com.autonavi.minimap"),
        "高德地图" to listOf("com.autonavi.minimap"),
    )

    /** 银行 / 支付类：不放进「一句话直接打开」的直通，命中先问一句。 */
    private val SENSITIVE_PACKAGES = setOf(
        "com.nbbank",
        "com.eg.android.AlipayGphone",
        "com.unionpay.tsmservice",
    )
    private val SENSITIVE_NAME_RE = Regex("银行|支付|钱包|网银|银联|证券|股票|理财")

    // ------------------------------------------------------------ 扫描

    /** 当前快照（超过 30 秒或还没扫过 → 现扫）。 */
    fun all(ctx: Context): List<InstalledApp> {
        synchronized(lock) {
            val fresh = snapshot.isNotEmpty() && System.currentTimeMillis() - scannedAt <= WINDOW_MS
            if (!fresh) scanLocked(ctx.applicationContext, "过期或未扫")
            return snapshot
        }
    }

    /** 名字匹配失败时强制重扫一次（可能是刚装的、上一次扫得太早）。 */
    fun rescan(ctx: Context, reason: String): List<InstalledApp> {
        synchronized(lock) {
            scanLocked(ctx.applicationContext, reason)
            return snapshot
        }
    }

    fun byPackage(ctx: Context, pkg: String): InstalledApp? =
        all(ctx).firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }

    fun isSensitive(app: InstalledApp): Boolean =
        app.packageName in SENSITIVE_PACKAGES || SENSITIVE_NAME_RE.containsMatchIn(app.label)

    private fun scanLocked(ctx: Context, reason: String) {
        val started = System.currentTimeMillis()
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, InstalledApp>()
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(main, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "查询可启动应用失败：${t.javaClass.simpleName}")
            emptyList()
        }
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName) continue // 不索引自己
            if (out.containsKey(pkg)) continue   // 一个包多个入口只留一条
            val label = try {
                ri.loadLabel(pm)?.toString()?.trim()
            } catch (t: Throwable) {
                null
            }
            if (label.isNullOrEmpty()) continue
            out[pkg] = InstalledApp(pkg, label)
        }
        snapshot = out.values.sortedBy { it.label }
        scannedAt = System.currentTimeMillis()
        lastScanCostMs = scannedAt - started
        Log.i(TAG, "现扫可启动应用（$reason）：${snapshot.size} 个，耗时 $lastScanCostMs ms（30 秒内复用）")
    }

    // ------------------------------------------------------------ 匹配

    sealed class Match {
        data class Found(val app: InstalledApp) : Match()
        data class Ambiguous(val candidates: List<InstalledApp>) : Match()
        object None : Match()
    }

    /**
     * 按「用户说出来的名字」找应用。
     *
     * 顺序：别名（必须这次扫到同名包） → label 精确 → 包名精确 → label 包含 → 名字包含某 label → 包名包含。
     * 全不中 → 强制重扫一次再查 → 仍然没有就 None（绝不猜）。
     */
    fun find(ctx: Context, rawName: String): Match {
        val ctxRef = ctx.applicationContext
        val q = normalizeName(rawName)
        if (q.isEmpty()) return Match.None
        var result = findOnce(all(ctxRef), q)
        if (result is Match.None) {
            result = findOnce(rescan(ctxRef, "查不到「$rawName」→ 强制重扫"), q)
        }
        return result
    }

    private fun findOnce(list: List<InstalledApp>, q: String): Match {
        // 1) 别名 → 包名（这次扫到了才认）
        ALIASES[q]?.let { pkgs ->
            for (pkg in pkgs) {
                val hit = list.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
                if (hit != null) return Match.Found(hit)
            }
        }
        // 2) label 精确
        val exact = list.filter { normalizeName(it.label) == q }
        if (exact.size == 1) return Match.Found(exact[0])
        if (exact.size > 1) return Match.Ambiguous(exact)
        // 3) 包名精确
        list.firstOrNull { it.packageName.equals(q, ignoreCase = true) }?.let { return Match.Found(it) }
        // 4) label 包含用户说的名字（"哔哩" → "哔哩哔哩"）
        if (q.length >= 2) {
            val contains = list.filter { normalizeName(it.label).contains(q) }
            if (contains.size == 1) return Match.Found(contains[0])
            if (contains.size > 1) return Match.Ambiguous(contains)
        }
        // 5) 用户说的名字里包含某个 label（"微信app" → "微信"）
        val reverse = list.filter { it.label.length >= 2 && q.contains(normalizeName(it.label)) }
        if (reverse.size == 1) return Match.Found(reverse[0])
        if (reverse.size > 1) return Match.Ambiguous(reverse)
        // 6) 包名包含（"tencent.mm" 这种）
        if (q.length >= 3) {
            val pkgHit = list.filter { it.packageName.lowercase(Locale.ROOT).contains(q) }
            if (pkgHit.size == 1) return Match.Found(pkgHit[0])
            if (pkgHit.size > 1) return Match.Ambiguous(pkgHit)
        }
        return Match.None
    }

    private fun normalizeName(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(" ", "").replace("\u3000", "")

    /** 最近一次扫描耗时（毫秒），给日志/报告取证用。 */
    fun lastScanMs(): Long = synchronized(lock) { lastScanCostMs }

    /** 当前快照的时间戳（调试用）。 */
    fun scannedAt(): Long = synchronized(lock) { scannedAt }
}
