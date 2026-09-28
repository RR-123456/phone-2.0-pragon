package com.pragon.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Saved PC address + the token the PC gave us when we paired. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pragon", Context.MODE_PRIVATE)
    fun host(c: Context): String = sp(c).getString("host", "") ?: ""
    fun port(c: Context): Int = sp(c).getInt("port", 8000)
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun save(c: Context, host: String, port: Int, token: String) =
        sp(c).edit().putString("host", host).putInt("port", port).putString("token", token).apply()
    fun saveToken(c: Context, token: String) = sp(c).edit().putString("token", token).apply()
    fun clear(c: Context) = sp(c).edit().clear().apply()
}

/** Tiny bridge so the service can show its status in the activity. */
object Bridge {
    @Volatile var status: String = "Not connected"
    @Volatile var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun set(s: String) {
        status = s
        main.post { listener?.invoke(s) }
    }
}
