package com.yanyan.assistant

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 本地存储：M1 放运行状态和几项可配置文案；M2 追加通道配置。
 *
 * M2 约定（见 M2 文档「接口契约 · 与 M1 的接缝」）：
 * - 服务端地址、deviceId、「有没有配对过」存在这里；
 * - token 单独用 EncryptedSharedPreferences（见 SecureStore），但读写入口仍挂在 LocalStore 上。
 */
object LocalStore {
    const val PREFS_NAME = "yanyan_prefs"

    const val KEY_RUNNING = "service_running"
    const val KEY_PAUSED = "service_paused"
    const val KEY_STARTED_AT = "service_started_at"
    const val KEY_AUTOSTART = "boot_autostart"
    const val KEY_NOTIF_TITLE = "notif_title"
    const val KEY_NOTIF_TEXT = "notif_text"

    /** M2：服务端地址（ws://host:port，手填，记住上次） */
    const val KEY_SERVER_URL = "channel_server_url"

    /** M2：本机 deviceId（UUID，配对与 hello 都用它） */
    const val KEY_DEVICE_ID = "channel_device_id"

    /** M2：是否配对过（token 本体在 SecureStore） */
    const val KEY_PAIRED = "channel_paired"

    /** M3a：监听总开关（默认关；关着时不创建 AudioRecord） */
    const val KEY_LISTEN_ENABLED = "listen_enabled"

    /** M3b：命中唤醒词后是否播一句「我在 / 嗯？ / 在呢」（默认开；关了完全静音） */
    const val KEY_WAKE_PROMPT = "wake_prompt_enabled"

    /** M5c：悬浮窗开关（默认关；关着时不加任何 WindowManager 浮层） */
    const val KEY_OVERLAY_ENABLED = "overlay_enabled"

    /** M5c：浮层位置（像素，左上角；没存过就是没设置） */
    const val KEY_OVERLAY_X = "overlay_x"
    const val KEY_OVERLAY_Y = "overlay_y"
    const val KEY_OVERLAY_POS_SET = "overlay_pos_set"

    /** M5b：主页快捷栏槽位（JSON 数组，长度固定 QuickSlots.COUNT；[QuickSlots] 负责序列化）。 */
    const val KEY_QUICK_SLOTS = "quick_slots"

    /** M6：快捷栏收起状态（主界面按钮与设置页共用同一个值）。 */
    const val KEY_QUICK_COLLAPSED = "quick_bar_collapsed"

    /** M6：场景 → 应用映射（JSON 对象：{"navigate":"pkg", ...}，空串 / 缺键 = 交回系统默认）。 */
    const val KEY_SCENE_MAP = "scene_app_map"
    const val SCENE_NAVIGATE = "navigate"
    const val SCENE_BROWSER = "browser"
    const val SCENE_MUSIC = "music"
    const val SCENE_SMS = "sms"

    /** M6：深浅色（0 浅色 / 1 深色 / 2 跟随系统，默认浅色；手动切换立即生效）。 */
    const val KEY_THEME_MODE = "ui_theme_mode"
    const val THEME_LIGHT = 0
    const val THEME_DARK = 1
    const val THEME_SYSTEM = 2

    /** M6a：语音回复总开关（默认开）。关掉后电脑端连 TTS 请求都不发。 */
    const val KEY_SPEAK_ENABLED = "speak_enabled"

    /** M6a：音色（stella 女声 / tide 男声，默认 stella）。 */
    const val KEY_SPEAK_VOICE = "speak_voice"

    /** M6a：TTS 音频缓存保留天数（7 / 30 / 0=不自动删，默认 7）。 */
    const val KEY_TTS_KEEP_DAYS = "tts_keep_days"

    /** M6a 音色常量：与电脑端 tts.js 的 VOICES 一致。 */
    const val VOICE_STELLA = "stella"
    const val VOICE_TIDE = "tide"

    /** M5c：吸附在哪一边（"left" / "right"；没吸边就是 "free"）。存边比只存像素稳：方块大小变了也还贴边。 */
    const val KEY_OVERLAY_EDGE = "overlay_edge"
    const val EDGE_LEFT = "left"
    const val EDGE_RIGHT = "right"
    const val EDGE_FREE = "free"

    const val DEFAULT_NOTIF_TITLE = "砚砚值守中"
    const val DEFAULT_NOTIF_TEXT = "常驻服务正在运行，点开可查看状态"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun notifTitle(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_NOTIF_TITLE, null)
        return if (v.isNullOrBlank()) DEFAULT_NOTIF_TITLE else v
    }

    fun notifText(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_NOTIF_TEXT, null)
        return if (v.isNullOrBlank()) DEFAULT_NOTIF_TEXT else v
    }

    fun autoStartEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTOSTART, true)

    fun setAutoStart(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    // ------------------------------------------------------------ M2 通道配置

    fun serverUrl(ctx: Context): String? =
        prefs(ctx).getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() }

    fun setServerUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    /** deviceId：第一次用时生成并落盘，之后固定（配对与重连都认它）。 */
    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        val existing = p.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "android-" + UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    // ------------------------------------------------------------ M3a 监听开关

    fun listenEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LISTEN_ENABLED, false)

    fun setListenEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LISTEN_ENABLED, enabled).apply()
    }

    /** M3b：唤醒语音提示开关，默认开（expG09：命中时要「应我一声」）。 */
    fun wakePromptEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_WAKE_PROMPT, true)

    fun setWakePromptEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WAKE_PROMPT, enabled).apply()
    }

    fun paired(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PAIRED, false)

    // ------------------------------------------------------------ M6a 语音回复（TTS）

    /** 语音回复总开关，默认开（expG09 定的默认值）。 */
    fun speakEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SPEAK_ENABLED, true)

    fun setSpeakEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SPEAK_ENABLED, enabled).apply()
    }

    /** 音色：只认 stella / tide，其余一律按默认 stella 处理。 */
    fun speakVoice(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_SPEAK_VOICE, VOICE_STELLA)
        return if (v == VOICE_TIDE) VOICE_TIDE else VOICE_STELLA
    }

    fun setSpeakVoice(ctx: Context, voice: String) {
        val v = if (voice == VOICE_TIDE) VOICE_TIDE else VOICE_STELLA
        prefs(ctx).edit().putString(KEY_SPEAK_VOICE, v).apply()
    }

    /** TTS 缓存保留天数：7 / 30 / 0（不自动删）。默认 7。 */
    fun ttsKeepDays(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_TTS_KEEP_DAYS, 7)
        return if (v == 30 || v == 0) v else 7
    }

    fun setTtsKeepDays(ctx: Context, days: Int) {
        val v = if (days == 30 || days == 0) days else 7
        prefs(ctx).edit().putInt(KEY_TTS_KEEP_DAYS, v).apply()
    }

    // ------------------------------------------------------------ M5c 悬浮窗

    /** 悬浮窗开关：默认关（decision.md 定的「默认关、要用户显式授权」）。 */
    fun overlayEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_OVERLAY_ENABLED, false)

    fun setOverlayEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    /** 浮层左上角位置（像素）；没设置过返回 null，由 OverlayController 用默认位置。 */
    fun overlayPosition(ctx: Context): Pair<Int, Int>? {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_OVERLAY_POS_SET, false)) return null
        return Pair(p.getInt(KEY_OVERLAY_X, 0), p.getInt(KEY_OVERLAY_Y, 0))
    }

    /** 吸附边：null 表示从没存过；"free" 表示停在中间。 */
    fun overlayEdge(ctx: Context): String? =
        prefs(ctx).getString(KEY_OVERLAY_EDGE, null)?.takeIf { it.isNotBlank() }

    fun setOverlayPosition(ctx: Context, x: Int, y: Int, edge: String = EDGE_FREE) {
        prefs(ctx).edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .putString(KEY_OVERLAY_EDGE, edge)
            .putBoolean(KEY_OVERLAY_POS_SET, true)
            .apply()
    }


    fun setPaired(ctx: Context, paired: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    // ------------------------------------------------------------ M5b 快捷栏

    /** 快捷栏槽位的原始 JSON；没配置过返回 null（[QuickSlots] 按全空处理）。 */
    fun quickSlotsRaw(ctx: Context): String? =
        prefs(ctx).getString(KEY_QUICK_SLOTS, null)

    fun setQuickSlotsRaw(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_QUICK_SLOTS, raw).apply()
    }

    // ------------------------------------------------------------ M6 快捷栏收起 / 场景映射 / 主题

    fun quickBarCollapsed(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_QUICK_COLLAPSED, false)

    fun setQuickBarCollapsed(ctx: Context, collapsed: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_QUICK_COLLAPSED, collapsed).apply()
    }

    /** 场景映射：只存「用户挑过的」；没挑过 / 清空 = 交回系统默认。 */
    fun sceneMap(ctx: Context): Map<String, String> {
        val raw = prefs(ctx).getString(KEY_SCENE_MAP, null) ?: return emptyMap()
        val out = HashMap<String, String>()
        try {
            val o = org.json.JSONObject(raw)
            o.keys().forEach { k ->
                val pkg = o.optString(k).trim()
                if (pkg.isNotEmpty()) out[k] = pkg
            }
        } catch (e: Exception) {
            // 坏数据当没配置，不崩界面
        }
        return out
    }

    fun setSceneApp(ctx: Context, scene: String, pkg: String?) {
        val map = HashMap(sceneMap(ctx))
        if (pkg.isNullOrBlank()) map.remove(scene) else map[scene] = pkg
        val o = org.json.JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString(KEY_SCENE_MAP, o.toString()).apply()
    }

    /** 主题模式：只认 0/1/2，其余按浅色。 */
    fun themeMode(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_THEME_MODE, THEME_LIGHT)
        return if (v == THEME_DARK || v == THEME_SYSTEM) v else THEME_LIGHT
    }

    fun setThemeMode(ctx: Context, mode: Int) {
        val v = if (mode == THEME_DARK || mode == THEME_SYSTEM) mode else THEME_LIGHT
        prefs(ctx).edit().putInt(KEY_THEME_MODE, v).apply()
    }

    /** 把浮层位置恢复到「没设置过」= 下次显示回到默认贴右、竖直 30% 处。 */
    fun clearOverlayPosition(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_OVERLAY_X)
            .remove(KEY_OVERLAY_Y)
            .remove(KEY_OVERLAY_EDGE)
            .remove(KEY_OVERLAY_POS_SET)
            .apply()
    }

    /** token 的读写入口在 LocalStore 上，实际落在 EncryptedSharedPreferences。 */
    fun token(ctx: Context): String? = SecureStore.getToken(ctx)

    fun setToken(ctx: Context, token: String?) {
        if (token.isNullOrBlank()) SecureStore.clearToken(ctx) else SecureStore.setToken(ctx, token)
    }

    /** 清掉配对痕迹（token + 配对标记），地址和 deviceId 保留。 */
    fun clearPairing(ctx: Context) {
        SecureStore.clearToken(ctx)
        setPaired(ctx, false)
    }
}

/**
 * 进程内运行状态镜像。服务和界面同进程，静态状态即事实来源；
 * 进程被系统杀掉时两者一起消失，不会出现界面显示"在跑"而其实没跑。
 */
object ServiceState {
    @Volatile var running: Boolean = false
    @Volatile var paused: Boolean = false
    @Volatile var startedAt: Long = 0L
}

/**
 * 界面可见性（由 MainActivity 的 onStart/onStop 维护）。
 *
 * M3a 起用它做两件事：
 * - Android 14+ 只允许「界面在前台」时打开麦克风 / 申请 microphone 类型的前台服务；
 * - 进程在后台被拉起（开机、覆盖安装）时不偷偷开麦，等界面回来再按开关恢复。
 */
object AppVisibility {
    @Volatile var hasVisibleActivity: Boolean = false
}
