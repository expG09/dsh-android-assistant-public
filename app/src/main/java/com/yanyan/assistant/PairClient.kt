package com.yanyan.assistant

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * M2：配对用的 HTTP 客户端（POST /pair）。
 *
 * 只做一次请求：ws://host:port → http://host:port/pair，body {code, deviceId}。
 * token 只在成功回调里交给调用方落盘，本类不打印、不缓存。
 */
object PairClient {

    data class Outcome(val ok: Boolean, val token: String?, val message: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 把手机填的 ws 地址转成配对用的 http 地址（保留端口）。 */
    fun httpUrlFor(serverUrl: String): String? {
        val httpScheme = when {
            serverUrl.startsWith("ws://") -> "http://" + serverUrl.removePrefix("ws://")
            serverUrl.startsWith("wss://") -> "https://" + serverUrl.removePrefix("wss://")
            else -> return null
        }
        return httpScheme.trimEnd('/')
    }

    fun pair(serverUrl: String, code: String, deviceId: String, onDone: (Outcome) -> Unit) {
        val base = httpUrlFor(serverUrl)?.toHttpUrlOrNull()
        if (base == null) {
            onDone(Outcome(false, null, "地址格式不对，要写成 ws://ip:8788"))
            return
        }
        val body = JSONObject()
            .put("code", code)
            .put("deviceId", deviceId)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(base.newBuilder().encodedPath("/pair").build())
            .post(body)
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onDone(Outcome(false, null, "连不上电脑端（${e.javaClass.simpleName}）"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = when (resp.code) {
                            401 -> "配对码不对或已过期"
                            else -> "配对失败（HTTP ${resp.code}）"
                        }
                        onDone(Outcome(false, null, msg))
                        return
                    }
                    val token = try {
                        JSONObject(text).optString("deviceToken").takeIf { it.isNotBlank() }
                    } catch (e: Exception) {
                        null
                    }
                    if (token == null) {
                        onDone(Outcome(false, null, "电脑端没有返回 deviceToken"))
                    } else {
                        onDone(Outcome(true, token, "配对成功"))
                    }
                }
            }
        })
    }
}
