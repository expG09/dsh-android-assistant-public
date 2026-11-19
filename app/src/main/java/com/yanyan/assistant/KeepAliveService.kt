package com.yanyan.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * M1 常驻前台服务 + M2 通道宿主。
 *
 * 服务自己只负责：活着、发一条带「暂停/继续」动作的常驻通知、把状态写给界面。
 * M2 的 ChannelClient 是普通类，由这里创建并驱动（不新增 Service、不新增前台通知）。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"

        const val ACTION_START = "com.yanyan.assistant.action.START"
        const val ACTION_PAUSE = "com.yanyan.assistant.action.PAUSE"
        const val ACTION_RESUME = "com.yanyan.assistant.action.RESUME"
        const val ACTION_STOP = "com.yanyan.assistant.action.STOP"
        const val ACTION_REFRESH = "com.yanyan.assistant.action.REFRESH"

        /** M3b：录音中从通知栏立刻收尾（与界面上的「停止录音」同一个入口）。 */
        const val ACTION_STOP_RECORDING = "com.yanyan.assistant.action.STOP_RECORDING"

        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1001

        /** 通道实例（进程内单例）：界面通过它触发重连，不自己 new 连接。 */
        @Volatile
        var channel: ChannelClient? = null
            private set

        /** 从任意上下文拉起服务（会走 startForegroundService，5 秒内必须 startForeground）。 */
        fun startWithAction(ctx: Context, action: String) {
            val intent = Intent(ctx, KeepAliveService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun start(ctx: Context) = startWithAction(ctx, ACTION_START)

        /**
         * M2：配对成功（token 已落盘）后调用。
         * 服务已经在跑 → 让通道立刻重连；服务还没起来 → 拉起服务，onCreate 里读 token 自动连上。
         *
         * force=true 用于「必须换链路」的场景（配对成功 / 改地址 / 撤销配对）：
         * 通道会先正常关闭旧连接再重开。普通「重新连接」按钮用默认 false——
         * 已经有连接在跑时会忽略，避免把健康连接顶成 1006。
         */
        fun requestChannelReconnect(ctx: Context, force: Boolean = false) {
            val existing = channel
            if (existing != null) {
                existing.reconnectNow(force)
            } else {
                start(ctx)
            }
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        ServiceState.running = true
        ServiceState.paused = LocalStore.prefs(this).getBoolean(LocalStore.KEY_PAUSED, false)
        // 以本次进程/服务真正起来的时刻为准：force-stop 或重启后不该再显示上一次的启动时间
        ServiceState.startedAt = System.currentTimeMillis()
        persistState()
        createChannel()
        // M3a：服务先以 specialUse 成为前台（这一步在后台也允许），
        // 之后再按落盘的开关状态尝试开麦——界面不在前台时不允许开（Android 14+ 规则）。
        ListenController.init(applicationContext)
        startForegroundCompat()
        ListenController.restoreFromPrefs()

        // M2：通道由服务持有并驱动（服务在，通道就在）。
        // 真机联调修复：一个进程里只能有一条通道。万一服务被系统重建、上一次的实例还挂着，
        // 先明确停掉它——绝不允许两条 WebSocket 抢同一个设备位（服务端会把旧连接顶掉 4000，
        // 两端再各自重连就成一秒一断的循环）。界面只通过 ChannelHub 看状态，不自己建连接。
        channel?.stop()
        val client = ChannelClient(applicationContext)
        channel = client
        registerNetworkCallback()
        client.start()

        // M4：录音结束自动上传 + ASR 结果回填（也是普通对象，不新增 Service）
        AudioUploader.init(applicationContext)

        // M5：电脑下发的命令由这里执行（普通对象；应用列表按需现扫，不落盘、不注册广播）
        CommandExecutor.init(applicationContext)

        // M6a：语音回复的播放器（普通对象；服务在就能播，不需要界面在前台，不新增 Service/通知）
        TtsPlayer.init(applicationContext)

        // M5c：按落盘的开关恢复悬浮浮层（普通对象 + WindowManager，不新增 Service/通知）
        OverlayController.syncFromPrefs(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> {
                persistRunning(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> Unit
            ACTION_STOP_RECORDING -> ListenController.stopRecordingManually()
            else -> Unit
        }
        persistState()
        startForegroundCompat()
        // 被系统杀掉后尽量自动回来
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        // 服务没了就没人替监听状态做可见提示，按可见性原则一起关掉，麦克风立即释放
        ListenController.disable()
        // M6a：语音回复的播放器跟着服务一起停（它只是服务里的普通对象）
        TtsPlayer.shutdown()
        // M5c：浮层也跟着收起来（进程还在但服务停了，就别留一个没人管的卡片）
        OverlayController.hide()
        unregisterNetworkCallback()
        CommandExecutor.shutdown()
        channel?.stop()
        channel = null
        ServiceState.running = false
        persistRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setPaused(paused: Boolean) {
        ServiceState.paused = paused
        persistState()
    }

    // ------------------------------------------------------------ M2：网络变化驱动重连

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "网络可用，通知通道重连")
                channel?.onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "网络断开，通知通道")
                channel?.onNetworkLost()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (t: Throwable) {
            // 拿不到网络回调不影响主流程：通道自己的心跳看门狗还会兜底
            Log.w(TAG, "注册网络回调失败：${t.javaClass.simpleName}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (t: Throwable) {
            Log.w(TAG, "注销网络回调失败：${t.javaClass.simpleName}")
        }
    }

    private fun persistState() {
        LocalStore.prefs(this).edit()
            .putBoolean(LocalStore.KEY_RUNNING, ServiceState.running)
            .putBoolean(LocalStore.KEY_PAUSED, ServiceState.paused)
            .putLong(LocalStore.KEY_STARTED_AT, ServiceState.startedAt)
            .apply()
    }

    private fun persistRunning(running: Boolean) {
        ServiceState.running = running
        LocalStore.prefs(this).edit().putBoolean(LocalStore.KEY_RUNNING, running).apply()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "常驻服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "砚砚保持后台运行用的常驻通知"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        // specialUse 保证服务不受 dataSync 那种 6 小时时限。
        // M3a：监听开关打开 + 有麦克风权限 + 界面在前台时，追加 microphone 类型 ——
        // 只有带 microphone 类型的前台服务，退到后台才不会被系统静音（Android 14+ 的
        // 「后台应用不可用麦克风」规则），通知上的「正在监听」才不是假话。
        // 界面不在前台时绝不能申请 microphone 类型：系统会直接 SecurityException，
        // 连前台服务都起不来（实测 targetSdk 36 / Android 16）。
        val wantMic = ListenController.enabled &&
                ListenController.hasMicPermission(this) &&
                AppVisibility.hasVisibleActivity
        val specialUse = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (wantMic) {
                specialUse or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                specialUse
            }
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Throwable) {
            // 麦克风类型不满足条件就退回 specialUse（显式传类型，别用会读 manifest 的 2 参版本）
            Log.e(TAG, "startForeground(type=$type) 失败，退回 specialUse", e)
            try {
                val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    specialUse
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fallback)
            } catch (t: Throwable) {
                Log.e(TAG, "startForeground(specialUse) 也失败", t)
            }
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val resumeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = LocalStore.notifTitle(this)
        // M3a：监听状态优先于默认文案；开关关着时仍然用用户配置的文案
        val listenText = ListenController.notificationStateText()
        val text = when {
            listenText != null -> listenText
            ServiceState.paused -> "已暂停（服务仍在运行，点「继续」恢复）"
            else -> LocalStore.notifText(this)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, "暂停", pauseIntent)
            .addAction(0, "继续", resumeIntent)
            .apply {
                // M3b：录音中多一个「停止录音」入口（唤醒后自动开录时界面可能在后台）
                if (ListenController.recording) {
                    val stopRecordIntent = PendingIntent.getService(
                        this@KeepAliveService,
                        3,
                        Intent(this@KeepAliveService, KeepAliveService::class.java)
                            .setAction(ACTION_STOP_RECORDING),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    addAction(0, "停止录音", stopRecordIntent)
                }
            }
            .build()
    }
}
