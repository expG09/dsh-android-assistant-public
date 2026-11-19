package com.yanyan.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启：收到开机广播后重新拉起前台服务。
 * 系统侧需要用户在设置里允许「自启动 / 后台高耗电」，这属于系统限制，不是 App 缺陷。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                action == ACTION_QUICKBOOT ||
                action == ACTION_HTC_QUICKBOOT ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot) return
        if (!LocalStore.autoStartEnabled(context)) {
            Log.i(TAG, "开机自启已关闭，跳过 action=$action")
            return
        }
        try {
            Log.i(TAG, "开机自启拉起服务 action=$action")
            KeepAliveService.start(context)
        } catch (t: Throwable) {
            // Android 12+ 对后台启动前台服务有限制，失败只记录，不崩
            Log.w(TAG, "开机自启失败：${t.javaClass.simpleName} ${t.message}")
        }
    }
}
