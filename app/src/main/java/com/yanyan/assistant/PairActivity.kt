package com.yanyan.assistant

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M2 配对页：填电脑端地址 + 6 位配对码 → POST /pair 换 token → 通道自动连上。
 *
 * 连接成功后显示「已连上」和服务端时间；断线时显示「重连中…」（不静默）。
 * 页面只是配置入口，真正的连接由 KeepAliveService 持有的 ChannelClient 维持。
 */
class PairActivity : BaseActivity(), ChannelListener {

    private lateinit var etServerUrl: EditText
    private lateinit var etPairCode: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvServerTime: TextView
    private lateinit var tvDevice: TextView

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)
        setupTopBar(getString(R.string.title_pair), showBack = true)

        etServerUrl = findViewById(R.id.et_server_url)
        etPairCode = findViewById(R.id.et_pair_code)
        btnConnect = findViewById(R.id.btn_pair_connect)
        tvStatus = findViewById(R.id.tv_pair_status)
        tvServerTime = findViewById(R.id.tv_pair_server_time)
        tvDevice = findViewById(R.id.tv_pair_device)

        // 「默认填上次用过的地址」；第一次没有就用 hint 提示格式，不瞎填一个错的 IP
        etServerUrl.setText(LocalStore.serverUrl(this) ?: "")

        btnConnect.setOnClickListener { doPair() }
    }

    override fun onResume() {
        super.onResume()
        ChannelHub.addListener(this)
        if (!ServiceState.running) {
            try {
                KeepAliveService.start(this)
            } catch (t: Throwable) {
                toast("启动常驻服务失败：${t.javaClass.simpleName}")
            }
        }
        render()
    }

    override fun onPause() {
        ChannelHub.removeListener(this)
        super.onPause()
    }

    // ------------------------------------------------------------ 配对

    private fun doPair() {
        val rawUrl = etServerUrl.text.toString().trim()
        val url = normalizeUrl(rawUrl)
        if (url == null) {
            toast("地址要写成 ws://ip:8788")
            return
        }
        val code = etPairCode.text.toString().trim()
        if (!Regex("^\\d{6}$").matches(code)) {
            toast("配对码是 6 位数字（电脑端启动时打印的那个）")
            return
        }

        LocalStore.setServerUrl(this, url)
        btnConnect.isEnabled = false
        tvStatus.text = "配对中…"
        tvServerTime.text = "正在向 $url 换 token"

        // 先把服务拉起来，配对成功后通道立刻连（服务还没起来时 requestChannelReconnect 会补一次）
        try {
            KeepAliveService.start(this)
        } catch (t: Throwable) {
            toast("启动常驻服务失败：${t.javaClass.simpleName}")
        }

        val deviceId = LocalStore.deviceId(this)
        PairClient.pair(url, code, deviceId) { outcome ->
            runOnUiThread {
                btnConnect.isEnabled = true
                if (outcome.ok && outcome.token != null) {
                    LocalStore.setToken(this, outcome.token)
                    LocalStore.setPaired(this, true)
                    etPairCode.setText("")
                    tvStatus.text = "配对成功，正在建立连接…"
                    // 配对成功必须换到新 token 的链路：force=true（先正常关闭旧连接）
                    KeepAliveService.requestChannelReconnect(this, force = true)
                } else {
                    tvStatus.text = "配对失败"
                    tvServerTime.text = outcome.message
                    toast("配对失败：${outcome.message}")
                }
                render()
            }
        }
    }

    private fun normalizeUrl(raw: String): String? {
        if (raw.isEmpty()) return null
        val withScheme = when {
            raw.startsWith("ws://") || raw.startsWith("wss://") -> raw
            raw.startsWith("http://") -> "ws://" + raw.removePrefix("http://")
            raw.startsWith("https://") -> "wss://" + raw.removePrefix("https://")
            else -> "ws://$raw"
        }
        val parsed = try {
            java.net.URI(withScheme)
        } catch (e: Exception) {
            return null
        }
        if (parsed.host.isNullOrBlank()) return null
        return withScheme.trimEnd('/')
    }

    // ------------------------------------------------------------ 状态显示

    private fun render() {
        val phase = ChannelHub.phase
        val detail = ChannelHub.detail
        tvStatus.text = when (phase) {
            ChannelPhase.CONNECTED -> "已连上"
            ChannelPhase.CONNECTING -> "连接中…"
            ChannelPhase.RECONNECTING -> "重连中…"
            ChannelPhase.NOT_PAIRED -> "未配对"
            ChannelPhase.STOPPED -> "通道未运行"
        }
        tvServerTime.text = if (phase == ChannelPhase.CONNECTED && ChannelHub.serverTime > 0L) {
            "服务端时间：${timeFormat.format(Date(ChannelHub.serverTime))}"
        } else {
            detail
        }
        val paired = LocalStore.paired(this)
        tvDevice.text = "本机 deviceId：${LocalStore.deviceId(this)}\n" +
                "当前地址：${LocalStore.serverUrl(this) ?: "（未填写）"}\n" +
                "配对状态：${if (paired) "已配对（token 存在加密存储里）" else "未配对"}"
    }

    override fun onPhaseChanged(phase: ChannelPhase, detail: String) {
        render()
    }

    override fun onReady(serverTime: Long) {
        render()
    }

}
