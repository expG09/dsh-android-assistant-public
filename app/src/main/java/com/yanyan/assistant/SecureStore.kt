package com.yanyan.assistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * M2：deviceToken 专用存储 —— EncryptedSharedPreferences（Keystore 后端）。
 *
 * 约束：
 * - 只存 token 一个值，不存其它东西；
 * - token 原文不进日志、不打印、不 toString；
 * - 读写失败时只记异常类型，不记值。
 */
object SecureStore {
    private const val TAG = "SecureStore"
    private const val FILE_NAME = "yanyan_secure"
    private const val KEY_TOKEN = "device_token"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun store(ctx: Context): SharedPreferences? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return try {
                val app = ctx.applicationContext
                val masterKey = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val sp = EncryptedSharedPreferences.create(
                    app,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                cached = sp
                sp
            } catch (t: Throwable) {
                // 只记异常类型：这里最容易把密钥材料带进日志
                Log.e(TAG, "加密存储不可用：${t.javaClass.simpleName}")
                null
            }
        }
    }

    fun getToken(ctx: Context): String? = try {
        store(ctx)?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        Log.e(TAG, "读取 token 失败：${t.javaClass.simpleName}")
        null
    }

    fun setToken(ctx: Context, token: String) {
        try {
            store(ctx)?.edit()?.putString(KEY_TOKEN, token)?.apply()
        } catch (t: Throwable) {
            Log.e(TAG, "写入 token 失败：${t.javaClass.simpleName}")
        }
    }

    fun clearToken(ctx: Context) {
        try {
            store(ctx)?.edit()?.remove(KEY_TOKEN)?.apply()
        } catch (t: Throwable) {
            Log.e(TAG, "清除 token 失败：${t.javaClass.simpleName}")
        }
    }
}
