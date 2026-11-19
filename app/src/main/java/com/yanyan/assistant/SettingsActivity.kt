package com.yanyan.assistant

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import java.util.Locale

/**
 * M6 设置主页面：**只列分类**，点进去才是具体项（decision.md 2026-11-16「设置结构」）。
 *
 * 八个分类：语音 / 录音与上传 / 通道与配对 / 应用与快捷栏 / 悬浮窗 / 权限状态 / 日志与诊断 / 外观。
 * 每类的副标题是实时状态（监听开没开、通道连没连、当前深浅色…），不用点进去也能看出个大概。
 */
class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page)
        setupTopBar("设置", showBack = true)
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val content = findViewById<LinearLayout>(R.id.ll_content)
        content.removeAllViews()

        bodyText(
            content,
            "主界面只显示状态和几个大按钮；所有配置都在下面这八类里。",
            13f,
            R.attr.yyTextSecondary
        )

        category(
            content, SettingsSectionActivity.SECTION_VOICE, "语音",
            voiceSubtitle(), R.drawable.ic_mic
        )
        category(
            content, SettingsSectionActivity.SECTION_RECORDING, "录音与上传",
            recordingSubtitle(), R.drawable.ic_play
        )
        category(
            content, SettingsSectionActivity.SECTION_CHANNEL, "通道与配对",
            channelSubtitle(), R.drawable.ic_send
        )
        category(
            content, SettingsSectionActivity.SECTION_APPS, "应用与快捷栏",
            appsSubtitle(), R.drawable.ic_chat
        )
        category(
            content, SettingsSectionActivity.SECTION_OVERLAY, "悬浮窗",
            overlaySubtitle(), R.drawable.ic_settings
        )
        category(
            content, SettingsSectionActivity.SECTION_PERMISSIONS, "权限状态",
            permissionSubtitle(), R.drawable.ic_keyboard
        )
        category(
            content, SettingsSectionActivity.SECTION_LOGS, "日志与诊断",
            logSubtitle(), R.drawable.ic_retry
        )
        category(
            content, SettingsSectionActivity.SECTION_APPEARANCE, "外观",
            "当前：${ThemeManager.modeLabel(this)}", R.drawable.ic_sun
        )

        bodyText(
            content,
            "DSH砚砚助手 ${versionName()}（${versionCode()}） · 改完立即生效，返回主界面仍是新设置",
            11f,
            R.attr.yyTextSecondary
        ).apply {
            val lp = layoutParams as LinearLayout.LayoutParams
            lp.topMargin = dp(18)
            layoutParams = lp
        }
    }

    // ------------------------------------------------------------ 分类行

    private fun category(
        parent: LinearLayout,
        section: Int,
        title: String,
        subtitle: String,
        iconRes: Int
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(c(R.attr.yySurface), 16)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(SettingsSectionActivity.intent(this@SettingsActivity, section))
            }
        }
        row.addView(
            ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(c(R.attr.yyAccent))
            }, LinearLayout.LayoutParams(dp(24), dp(24))
        )
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(
            TextView(this).apply {
                text = title
                textSize = 16f
                setTextColor(c(R.attr.yyText))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        texts.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(c(R.attr.yyTextSecondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        )
        row.addView(texts, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply {
            marginStart = dp(14)
            marginEnd = dp(8)
        })
        row.addView(
            TextView(this).apply {
                text = "›"
                textSize = 20f
                setTextColor(c(R.attr.yyTextSecondary))
            }
        )

        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
    }

    // ------------------------------------------------------------ 副标题（实时状态）

    private fun voiceSubtitle(): String {
        val listen = if (ListenController.enabled) "监听中" else "监听已关"
        val speak = if (LocalStore.speakEnabled(this)) "播报开" else "播报关"
        return "$listen · 唤醒词${if (ListenController.enabled) "已加载" else "未加载"} · $speak"
    }

    private fun recordingSubtitle(): String {
        val (count, bytes) = TtsStore.usage(this)
        val keep = when (LocalStore.ttsKeepDays(this)) {
            30 -> "30 天"
            0 -> "不自动删"
            else -> "7 天"
        }
        return "语音缓存 $count 段 · ${formatSize(bytes)} · 保留 $keep"
    }

    private fun channelSubtitle(): String {
        val phase = when (ChannelHub.phase) {
            ChannelPhase.CONNECTED -> "已连上"
            ChannelPhase.CONNECTING -> "连接中"
            ChannelPhase.RECONNECTING -> "重连中"
            ChannelPhase.NOT_PAIRED -> "未配对"
            ChannelPhase.STOPPED -> "通道未运行"
        }
        val url = LocalStore.serverUrl(this) ?: "地址未填"
        return "$phase · $url"
    }

    private fun appsSubtitle(): String {
        val slots = QuickSlots.load(this)
        val filled = slots.count { it.pkg != null }
        val collapsed = if (LocalStore.quickBarCollapsed(this)) "已收起" else "展开"
        return "快捷栏 $filled/${QuickSlots.COUNT} 格 · $collapsed · 场景映射可改"
    }

    private fun overlaySubtitle(): String {
        val on = LocalStore.overlayEnabled(this)
        val granted = android.provider.Settings.canDrawOverlays(this)
        return "开关${if (on) "开" else "关"} · 权限${if (granted) "已给" else "没给"}"
    }

    private fun permissionSubtitle(): String {
        val mic = if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) "麦克风已给" else "麦克风没给"
        val notif = if (androidx.core.app.NotificationManagerCompat.from(this)
                .areNotificationsEnabled()
        ) "通知开" else "通知关"
        return "$mic · $notif"
    }

    private fun logSubtitle(): String = "v${versionName()} · 最近命令：${
        if (CommandHub.lastAt > 0L) CommandHub.lastLine else "还没有"
    }"

    // ------------------------------------------------------------ 小工具

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    private fun versionCode(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (t: Throwable) {
        0L
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
