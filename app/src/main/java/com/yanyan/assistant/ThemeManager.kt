package com.yanyan.assistant

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue

/**
 * M6 深浅色：两套主题手动切换（设置里选，也留一个界面快捷入口）。
 *
 * 约定（见 M6 文档「外观」）：
 * - 只有两套配色：浅色（暖白底）+ 深色（近黑灰底，不做纯黑）；
 * - 手动选择立即生效并落盘，重启后保持；额外给一个「跟随系统」的选项；
 * - 所有界面颜色都从主题属性 `yy*` 取（见 res/values/attrs.xml），布局里不写死颜色。
 *
 * 生效方式：每个 Activity 在 setContentView 之前 [apply]；如果运行中主题变了，
 * [BaseActivity.onResume] 会发现并自动 recreate，所以「切完立刻变」。
 */
object ThemeManager {

    fun mode(ctx: Context): Int = LocalStore.themeMode(ctx)

    fun setMode(ctx: Context, mode: Int) {
        LocalStore.setThemeMode(ctx, mode)
    }

    /** 当前到底用哪套主题：手动选了就按手动的，选「跟随系统」才看系统 uiMode。 */
    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        LocalStore.THEME_DARK -> true
        LocalStore.THEME_SYSTEM ->
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

        else -> false
    }

    /** 在 setContentView 之前调用。 */
    fun apply(activity: Activity) {
        activity.setTheme(if (isDark(activity)) R.style.AppThemeDark else R.style.AppThemeLight)
    }

    /** 浅色 ↔ 深色互换（界面上的快捷入口用）。 */
    fun toggle(ctx: Context) {
        setMode(ctx, if (isDark(ctx)) LocalStore.THEME_LIGHT else LocalStore.THEME_DARK)
    }

    fun modeLabel(ctx: Context): String = when (mode(ctx)) {
        LocalStore.THEME_DARK -> "深色"
        LocalStore.THEME_SYSTEM -> "跟随系统（当前${if (isDark(ctx)) "深色" else "浅色"}）"
        else -> "浅色"
    }

    /**
     * 取主题色：`ThemeManager.color(this, R.attr.yyText)`。
     * 布局里等价写法是 `?attr/yyText`。
     */
    fun color(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(attr, tv, true)) return 0xFF000000.toInt()
        return if (tv.resourceId != 0) {
            ctx.resources.getColor(tv.resourceId, ctx.theme)
        } else {
            tv.data
        }
    }
}
