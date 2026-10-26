package com.inaib.browser

import android.content.Context

/** Tiny SharedPreferences wrapper. Values are cached in fields so the ad blocker can read them cheaply from WebView threads. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("inaib", Context.MODE_PRIVATE)

    @Volatile
    var adBlock: Boolean = sp.getBoolean("adblock", true)
        set(v) {
            field = v
            sp.edit().putBoolean("adblock", v).apply()
        }

    @Volatile
    var dataSaver: Boolean = sp.getBoolean("datasaver", false)
        set(v) {
            field = v
            sp.edit().putBoolean("datasaver", v).apply()
        }

    @Volatile
    var desktop: Boolean = sp.getBoolean("desktop", false)
        set(v) {
            field = v
            sp.edit().putBoolean("desktop", v).apply()
        }

    var engine: Int = sp.getInt("engine", 0).coerceIn(0, Search.engines.size - 1)
        set(v) {
            field = v
            sp.edit().putInt("engine", v).apply()
        }

    var lang: Int = sp.getInt("lang", 0).coerceIn(0, Search.langs.size - 1)
        set(v) {
            field = v
            sp.edit().putInt("lang", v).apply()
        }

    val savedTabs: List<String>
        get() = (sp.getString("tabs", "") ?: "").split('\n').filter { it.isNotEmpty() }

    val savedCurrent: Int
        get() = sp.getInt("current", 0)

    fun saveTabs(urls: List<String>, current: Int) {
        sp.edit().putString("tabs", urls.joinToString("\n")).putInt("current", current).apply()
    }
}
