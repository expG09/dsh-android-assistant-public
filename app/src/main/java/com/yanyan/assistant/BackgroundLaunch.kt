package com.yanyan.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * M5c：后台「能不能直接弹界面」的判断，以及不能弹时的降级（发通知）。
 *
 * 背景（docs/report/M5.md 第十节遗留 1）：Android 10+ 禁止后台应用 startActivity，
 * 系统会静默丢弃，`launched=true` 也看不出真相。系统给的豁免之一就是
 * `SYSTEM_ALERT_WINDOW`（悬浮窗）——所以「悬浮窗开关开着 + 系统权限在」时，
 * 后台收到 command 可以**真的**把界面拉起来；否则退回一条可点击的通知。
 *
 * 通知为什么能弹界面：点通知是用户操作，系统自己发送 PendingIntent，
 * 不受「后台启动 Activity」限制。所以降级后用户点通知照样进得去。
 */
object BackgroundLaunch {
    private const val TAG = "BgLaunch"

    private const val CHANNEL_ID = "command_alert"
    private const val NOTIFICATION_ID = 2002

    /** 手机端回给电脑的 detail：后台被拦、已改成发通知（不是失败，只是没直接弹）。 */
    const val DETAIL_NOTIFIED = "blocked_background"

    /**
     * 现在能不能直接 startActivity 把界面弹出来。
     *
     * - 界面就在前台 → 能；
     * - 否则要有「已开启的悬浮窗特权限」：开关开着 + `Settings.canDrawOverlays()` 为真。
     *
     * 注意这个口径要和设置页文案一致：开关关掉 = 「未开启悬浮窗」，那就必须走通知降级。
     */
    fun canStartActivityNow(ctx: Context): Boolean =
        AppVisibility.hasVisibleActivity || OverlayController.isActive(ctx)

    /**
     * 降级：把「本来要拉起的那个界面」做成一条通知。
     * 用户点通知 → 系统发送 PendingIntent → 界面被拉起。
     *
     * 固定 notificationId：连续几条命令只留最新一条，不刷屏。
     */
    fun postCommandNotification(ctx: Context, label: String, intent: Intent): Boolean {
        val c = ctx.applicationContext
        return try {
            ensureChannel(c)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                c,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = "没有悬浮窗权限，后台不能直接弹出界面；点这里打开$label。"
            val notification = NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_assistant)
                .setContentTitle("砚砚：要打开$label")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            // Android 13+：没有 POST_NOTIFICATIONS 时 notify() 会抛 SecurityException。
            // 显式检查（MainActivity 会在启动时申请），没权限就老实返回 false，让调用方回执里说清楚。
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "没有通知权限，降级通知发不出去：$label")
                return false
            }
            NotificationManagerCompat.from(c).notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "后台不能直接弹界面，已发通知：$label")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "发通知失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    /** 用户点掉/执行完后清掉这条提醒（不是必须，失败也不管）。 */
    fun clearCommandNotification(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx.applicationContext).cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "清通知失败：${t.javaClass.simpleName}")
        }
    }

    private fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台命令提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "没开悬浮窗时，后台收到的命令会在这里提醒你点一下"
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }
}
