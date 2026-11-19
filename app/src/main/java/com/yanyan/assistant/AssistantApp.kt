package com.yanyan.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 进程级入口：只做一件 M3a 需要的事——维护「有没有界面在前台」。
 *
 * Android 14+ 规定只有当前可见的应用才能打开麦克风、才能把前台服务标成
 * microphone 类型；开机广播或覆盖安装把进程拉起来时界面并不在前台，
 * 这时不能偷偷开麦。用 Activity 计数而不是只看 MainActivity，
 * 是为了从状态页跳到配对页/调试页时仍然算「前台」。
 */
class AssistantApp : Application() {

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        ListenController.init(this)
        // M6：聊天页的数据源只在进程里挂一次（只听现成事件、只存消息，不碰任何功能链路）
        ChatStore.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                AppVisibility.hasVisibleActivity = true
                ListenController.onAppForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) AppVisibility.hasVisibleActivity = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
