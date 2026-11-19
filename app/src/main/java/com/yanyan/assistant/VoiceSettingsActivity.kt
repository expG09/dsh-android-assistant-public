package com.yanyan.assistant

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/**
 * M6a：语音回复（TTS）设置页。
 *
 * 按 decision.md「设置分类进二级菜单 / 主界面只留状态与按钮」：主界面只有一个入口按钮，
 * 开关、音色、试听、缓存保留期与清理都在这一页。
 *
 * 开关与音色的改动会立刻通过通道告诉电脑（`speakConfig`）：关掉后电脑端一次 TTS 请求都不发。
 */
class VoiceSettingsActivity : BaseActivity(), TtsListener, ChannelListener {

    private lateinit var swSpeak: Switch
    private lateinit var tvSpeakHint: TextView
    private lateinit var rgVoice: RadioGroup
    private lateinit var rbStella: RadioButton
    private lateinit var rbTide: RadioButton
    private lateinit var btnAudition: Button
    private lateinit var tvAuditionState: TextView
    private lateinit var tvCacheUsage: TextView
    private lateinit var rgKeep: RadioGroup
    private lateinit var rbKeep7: RadioButton
    private lateinit var rbKeep30: RadioButton
    private lateinit var rbKeepNever: RadioButton

    private val main = Handler(Looper.getMainLooper())

    private var suppressVoiceCallback = false
    private var suppressKeepCallback = false

    /** 已请求试听，但电脑还没把音频发过来。 */
    private var auditionPending = false

    /** 试听的那段正在播。 */
    private var auditionInFlight = false

    private val auditionTimeout = Runnable {
        if (!auditionPending) return@Runnable
        auditionPending = false
        tvAuditionState.setText(R.string.tts_audition_failed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_settings)
        setupTopBar(getString(R.string.title_voice_settings), showBack = true)

        swSpeak = findViewById(R.id.sw_speak)
        tvSpeakHint = findViewById(R.id.tv_speak_hint)
        rgVoice = findViewById(R.id.rg_voice)
        rbStella = findViewById(R.id.rb_voice_stella)
        rbTide = findViewById(R.id.rb_voice_tide)
        btnAudition = findViewById(R.id.btn_audition)
        tvAuditionState = findViewById(R.id.tv_audition_state)
        tvCacheUsage = findViewById(R.id.tv_cache_usage)
        rgKeep = findViewById(R.id.rg_keep)
        rbKeep7 = findViewById(R.id.rb_keep_7)
        rbKeep30 = findViewById(R.id.rb_keep_30)
        rbKeepNever = findViewById(R.id.rb_keep_never)

        swSpeak.isChecked = LocalStore.speakEnabled(this)
        swSpeak.setOnCheckedChangeListener { _, checked ->
            LocalStore.setSpeakEnabled(this, checked)
            refreshSpeakHint()
            // 立刻告诉电脑（关掉时它也把在途的那段合成停掉）
            val sent = KeepAliveService.channel?.sendSpeakConfigFromPrefs() ?: false
            if (!checked) {
                TtsPlayer.stopPlayback("switch_off")
                toast("已关闭语音回复：之后只有文字，电脑端不会再发语音请求")
            } else {
                toast(if (sent) "已开启语音回复" else "已开启（通道没连上，连上后自动生效）")
            }
            Log.i(TAG, "语音回复开关=$checked，speakConfig ${if (sent) "已发送" else "未发送"}")
        }

        val voice = LocalStore.speakVoice(this)
        suppressVoiceCallback = true
        if (voice == LocalStore.VOICE_TIDE) rbTide.isChecked = true else rbStella.isChecked = true
        suppressVoiceCallback = false
        rgVoice.setOnCheckedChangeListener { _, checkedId ->
            if (suppressVoiceCallback) return@setOnCheckedChangeListener
            val picked = if (checkedId == R.id.rb_voice_tide) LocalStore.VOICE_TIDE else LocalStore.VOICE_STELLA
            LocalStore.setSpeakVoice(this, picked)
            KeepAliveService.channel?.sendSpeakConfigFromPrefs()
            Log.i(TAG, "音色改为 $picked")
            tvAuditionState.setText(R.string.hint_voice_audition)
        }

        btnAudition.setOnClickListener { audition() }

        rgKeep.setOnCheckedChangeListener { _, checkedId ->
            if (suppressKeepCallback) return@setOnCheckedChangeListener
            val days = when (checkedId) {
                R.id.rb_keep_30 -> 30
                R.id.rb_keep_never -> 0
                else -> 7
            }
            LocalStore.setTtsKeepDays(this, days)
            TtsStore.prune(this)
            Log.i(TAG, "语音缓存保留期改为 ${if (days == 0) "不自动删" else "$days 天"}")
            refreshCacheUsage()
        }

        val keep = LocalStore.ttsKeepDays(this)
        suppressKeepCallback = true
        when (keep) {
            30 -> rbKeep30.isChecked = true
            0 -> rbKeepNever.isChecked = true
            else -> rbKeep7.isChecked = true
        }
        suppressKeepCallback = false

        findViewById<Button>(R.id.btn_clear_tts).setOnClickListener {
            TtsPlayer.stopPlayback("clear_cache")
            val n = TtsStore.clearAll(this)
            TtsPlayer.notifyCacheCleared()
            toast(if (n > 0) getString(R.string.tts_cleared, n) else getString(R.string.tts_cache_empty))
            refreshCacheUsage()
        }

        refreshSpeakHint()
        refreshCacheUsage()
    }

    override fun onResume() {
        super.onResume()
        TtsPlayer.addListener(this)
        ChannelHub.addListener(this)
        refreshAuditionButton()
        refreshCacheUsage()
    }

    override fun onPause() {
        TtsPlayer.removeListener(this)
        ChannelHub.removeListener(this)
        main.removeCallbacks(auditionTimeout)
        super.onPause()
    }

    // ------------------------------------------------------------ 试听

    private fun audition() {
        val voice = LocalStore.speakVoice(this)
        val channel = KeepAliveService.channel
        if (channel == null || ChannelHub.phase != ChannelPhase.CONNECTED) {
            tvAuditionState.setText(R.string.tts_audition_not_connected)
            return
        }
        TtsPlayer.stopPlayback("audition_restart")
        auditionPending = true
        auditionInFlight = false
        tvAuditionState.setText(R.string.tts_audition_playing)
        val ok = channel.sendSpeakRequest(voice)
        if (!ok) {
            auditionPending = false
            tvAuditionState.setText(R.string.tts_audition_not_connected)
            return
        }
        main.removeCallbacks(auditionTimeout)
        main.postDelayed(auditionTimeout, 20000L)
        Log.i(TAG, "试听 voice=$voice（请求已发给电脑）")
    }

    // ------------------------------------------------------------ 界面刷新

    private fun refreshSpeakHint() {
        tvSpeakHint.setText(
            if (LocalStore.speakEnabled(this)) R.string.hint_speak_on else R.string.hint_speak_off
        )
    }

    private fun refreshAuditionButton() {
        btnAudition.isEnabled = ChannelHub.phase == ChannelPhase.CONNECTED
    }

    private fun refreshCacheUsage() {
        val (count, bytes) = TtsStore.usage(this)
        val keep = when (LocalStore.ttsKeepDays(this)) {
            30 -> getString(R.string.tts_keep_label_30)
            0 -> getString(R.string.tts_keep_label_never)
            else -> getString(R.string.tts_keep_label_7)
        }
        tvCacheUsage.text = getString(R.string.tts_usage, count, formatSize(bytes), keep)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    // ------------------------------------------------------------ 回调

    override fun onTtsChanged(replyId: String?) {
        runOnUiThread {
            if (auditionPending && TtsPlayer.streaming) {
                // 试听音频开始播了：撤掉超时兜底
                auditionPending = false
                auditionInFlight = true
                main.removeCallbacks(auditionTimeout)
            } else if (auditionInFlight && !TtsPlayer.streaming) {
                // 试听那段播完了（或被打断）
                auditionInFlight = false
                tvAuditionState.setText(R.string.tts_audition_done)
            }
            refreshCacheUsage()
        }
    }

    override fun onPhaseChanged(phase: ChannelPhase, detail: String) {
        runOnUiThread { refreshAuditionButton() }
    }


    companion object {
        private const val TAG = "VoiceSettings"
    }
}
