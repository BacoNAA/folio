package com.baconaa.folio.records

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 设备上的阅读偏好。缺少设置时跟随系统，读取失败时返回 null 供页面重试。 */
internal class ReadingSettings(context: Context) {
    private val app = context.applicationContext
    private val preferences by lazy { app.getSharedPreferences("reading_settings", Context.MODE_PRIVATE) }
    private val key = "enlarge_body"

    suspend fun readEnlargedBody(): Boolean? = withContext(Dispatchers.IO) {
        try {
            synchronized(preferences) { preferences.getBoolean(key, false) }
        } catch (_: RuntimeException) {
            null
        }
    }

    suspend fun saveEnlargedBody(enlarged: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            synchronized(preferences) {
                val previous = preferences.getBoolean(key, false)
                val saved = preferences.edit().putBoolean(key, enlarged).commit()
                // commit 失败时 SharedPreferences 的进程内值也可能已经改变。
                if (!saved) preferences.edit().putBoolean(key, previous).apply()
                saved
            }
        } catch (_: RuntimeException) {
            false
        }
    }
}
