package com.yanyan.assistant

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个快捷槽位。
 *
 * `pkg == null` 表示空槽（界面上显示「+」）。`label` 落盘时一起存一份：
 * 应用被卸载后 PackageManager 就取不到名字了，存下来才能在界面上说清楚「哪一格没用了」。
 */
data class QuickSlot(val pkg: String?, val label: String?)

/**
 * M5b 主页快捷栏：固定 [COUNT] 个槽位，存 [LocalStore]（重装/杀进程后仍在）。
 *
 * 没有自己维护「应用列表」：选应用时直接复用 [AppIndex] 现扫的结果（只含 LAUNCHER 入口），
 * 所以装上/卸载的应用立刻反映出来，不需要监听 PACKAGE_ADDED/REMOVED。
 */
object QuickSlots {
    private const val TAG = "QuickSlots"

    /** 默认 6 格。存放时按这个长度对齐，短了补空槽。 */
    const val COUNT = 6

    /** 读出全部槽位（永远 [COUNT] 个，缺的补空槽；配置损坏也只会退化成空槽，不会崩）。 */
    fun load(ctx: Context): List<QuickSlot> {
        val out = MutableList(COUNT) { QuickSlot(null, null) }
        val raw = LocalStore.quickSlotsRaw(ctx)
        if (raw.isNullOrBlank()) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until minOf(arr.length(), COUNT)) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("pkg").trim().takeIf { it.isNotEmpty() } ?: continue
                val label = o.optString("label").trim().takeIf { it.isNotEmpty() } ?: pkg
                out[i] = QuickSlot(pkg, label)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "槽位配置解析失败，按空槽处理：${t.javaClass.simpleName} ${t.message}")
        }
        return out
    }

    /**
     * 把第 [index] 格换成 [app]（null = 清空），落盘并返回新列表。
     *
     * 同一个应用只占一格：如果它已经出现在别的格子里，两个格子互换，
     * 不会出现「同一个 app 占两格」或「换完多出一个空槽」。
     */
    fun setSlot(ctx: Context, index: Int, app: InstalledApp?): List<QuickSlot> {
        val slots = load(ctx).toMutableList()
        if (index !in 0 until COUNT) return slots
        if (app == null) {
            slots[index] = QuickSlot(null, null)
        } else {
            val existing = slots.indexOfFirst {
                it.pkg != null && it.pkg.equals(app.packageName, ignoreCase = true)
            }
            if (existing >= 0 && existing != index) {
                slots[existing] = slots[index]
            }
            slots[index] = QuickSlot(app.packageName, app.label)
        }
        save(ctx, slots)
        return slots
    }

    /** 只覆盖写「槽位」这一个键，不碰 LocalStore 里的其他配置。 */
    private fun save(ctx: Context, slots: List<QuickSlot>) {
        val arr = JSONArray()
        for (i in 0 until COUNT) {
            val s = slots.getOrNull(i)
            if (s?.pkg == null) {
                arr.put(JSONObject.NULL)
            } else {
                arr.put(
                    JSONObject()
                        .put("pkg", s.pkg)
                        .put("label", s.label ?: s.pkg)
                )
            }
        }
        LocalStore.setQuickSlotsRaw(ctx, arr.toString())
    }

    /** 现扫到的「有启动入口的应用」，给换槽位列表用（与 M5 open_app 共用一份索引）。 */
    fun installedApps(ctx: Context): List<InstalledApp> =
        AppIndex.rescan(ctx, "快捷栏换应用")

    /** 一次点击的结果：成功就什么都不说（不确认、不弹菜单）；失败要说清楚原因。 */
    sealed class LaunchResult {
        object Ok : LaunchResult()
        data class NotInstalled(val label: String) : LaunchResult()
        data class Failed(val label: String, val reason: String) : LaunchResult()
    }

    /** 单击立刻拉起应用。没有启动入口（卸载 / 被禁用）时如实返回，不抛异常。 */
    fun launch(ctx: Context, slot: QuickSlot): LaunchResult {
        val pkg = slot.pkg ?: return LaunchResult.NotInstalled(slot.label ?: "")
        val label = slot.label ?: pkg
        val intent = try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)
        } catch (t: Throwable) {
            Log.w(TAG, "查询「$label」启动入口失败：${t.javaClass.simpleName}")
            null
        } ?: return LaunchResult.NotInstalled(label)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            Log.i(TAG, "快捷栏拉起：$label（$pkg）")
            LaunchResult.Ok
        } catch (t: Throwable) {
            Log.w(TAG, "拉起「$label」失败：${t.javaClass.simpleName} ${t.message}")
            LaunchResult.Failed(label, t.javaClass.simpleName)
        }
    }
}
