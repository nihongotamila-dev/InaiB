package com.inaib.browser

import android.net.Uri

class Engine(val name: String, val template: String)

/** code = language code used by the engine, label = name shown in the menu. */
class Lang(val label: String, val code: String)

/**
 * India-tuned search. Every engine is pinned to the Indian region (gl=in, cc=IN, kl=in-en)
 * and the results language can be switched between the major Indian languages.
 *
 * Placeholders: {q} = encoded query, {l} = language code (ta), {t} = language tag (ta-IN).
 */
object Search {
    const val START_URL = "file:///android_asset/start.html"

    val engines = listOf(
        Engine("Google (India)", "https://www.google.co.in/search?q={q}&gl=in&hl={l}"),
        Engine("Bing (India)", "https://www.bing.com/search?q={q}&cc=IN&setlang={t}"),
        Engine("DuckDuckGo (India)", "https://duckduckgo.com/?q={q}&kl=in-en"),
        Engine("Yahoo (India)", "https://in.search.yahoo.com/search?p={q}")
    )

    val langs = listOf(
        Lang("English", "en"),
        Lang("हिन्दी (Hindi)", "hi"),
        Lang("தமிழ் (Tamil)", "ta"),
        Lang("తెలుగు (Telugu)", "te"),
        Lang("മലയാളം (Malayalam)", "ml"),
        Lang("ಕನ್ನಡ (Kannada)", "kn"),
        Lang("বাংলা (Bengali)", "bn"),
        Lang("मराठी (Marathi)", "mr")
    )

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val HOST = Regex("^([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(:\\d+)?([/?#].*)?$")
    private val LOCAL = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$")

    /** Turns whatever the user typed into a URL: a full URL, a bare domain, or a search. */
    fun resolve(raw: String, engine: Int, lang: Int): String {
        val s = raw.trim()
        if (s.isEmpty()) return START_URL
        if (SCHEME.containsMatchIn(s)) return s
        if (!s.contains(' ')) {
            if (LOCAL.matches(s)) return "http://$s"
            if (HOST.matches(s)) return "https://$s"
        }
        val l = langs[lang]
        return engines[engine].template
            .replace("{q}", Uri.encode(s))
            .replace("{l}", l.code)
            .replace("{t}", l.code + "-IN")
    }

    /** Sent with our own page loads so sites prefer Indian-language content. */
    fun acceptLanguage(lang: Int): String {
        val c = langs[lang].code
        return if (c == "en") "en-IN,en;q=0.9,hi;q=0.6" else "$c-IN,$c;q=0.9,en-IN;q=0.8,en;q=0.7"
    }
}
