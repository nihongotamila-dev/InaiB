package com.inaib.browser

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brave-style blocking in three layers:
 *  1. Network: requests to listed ad/tracker hosts (and their subdomains) are answered with an empty body.
 *  2. Network: a few well-known ad URL patterns, only for third-party requests.
 *  3. Cosmetic: CSS that hides leftover ad containers.
 *
 * Low-RAM design: the host list is stored as a sorted LongArray of 64-bit hashes
 * (8 bytes per domain, so even 100k domains is under 1 MB) and loaded on a background thread.
 */
class AdBlocker(context: Context) {

    val blockedCount = AtomicInteger(0)

    @Volatile
    private var hashes = LongArray(0)

    /** JavaScript that injects the cosmetic CSS into the current page. Empty if there is no CSS. */
    val cosmeticJs: String

    init {
        val app = context.applicationContext
        val css = try {
            app.assets.open("cosmetic.css").bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            ""
        }
        cosmeticJs = if (css.isEmpty()) "" else
            "(function(){try{if(document.getElementById('__inaib'))return;" +
                "var s=document.createElement('style');s.id='__inaib';" +
                "s.textContent=" + JSONObject.quote(css) + ";" +
                "(document.head||document.documentElement).appendChild(s);}catch(e){}})();"

        Thread { loadList(app) }.apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun loadList(ctx: Context) {
        try {
            var arr = LongArray(8192)
            var n = 0
            ctx.assets.open("blocklist_hosts.txt").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    var line = raw.trim()
                    if (line.isEmpty() || line[0] == '#') continue
                    val cut = line.indexOf('#')
                    if (cut > 0) line = line.substring(0, cut).trim()
                    // Accepts "domain" and hosts-file style "0.0.0.0 domain"
                    val host = line.split(' ', '\t').last { it.isNotEmpty() }.lowercase(Locale.ROOT)
                    if (host.indexOf('.') < 0 || host == "0.0.0.0" || host == "127.0.0.1") continue
                    if (n == arr.size) arr = arr.copyOf(n * 2)
                    arr[n++] = fnv(host, 0)
                }
            }
            val out = arr.copyOf(n)
            Arrays.sort(out)
            hashes = out
        } catch (e: Exception) {
            // No list available: the blocker simply stays idle.
        }
    }

    /** True if this sub-resource request should be blocked. Never called for the main page itself. */
    fun shouldBlock(uri: Uri, pageHost: String): Boolean {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host == pageHost) return false
        if (isBlockedHost(host)) return true
        if (pageHost.isNotEmpty() && site(host) != site(pageHost)) {
            val s = uri.toString()
            for (p in PATHS) if (s.contains(p)) return true
        }
        return false
    }

    private fun isBlockedHost(host: String): Boolean {
        val arr = hashes
        if (arr.isEmpty()) return false
        var start = 0
        while (true) {
            if (Arrays.binarySearch(arr, fnv(host, start)) >= 0) return true
            val dot = host.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
            if (host.indexOf('.', start) < 0) return false // only the TLD is left
        }
    }

    /** Rough registrable domain: a.b.example.co.in -> example.co.in */
    private fun site(host: String): String {
        val p = host.split('.')
        if (p.size <= 2) return host
        val sld = p[p.size - 2]
        val n = if (p.last().length == 2 && sld in SLDS) 3 else 2
        return p.takeLast(n).joinToString(".")
    }

    private fun fnv(s: String, from: Int): Long {
        var h = -3750763034362895579L // FNV-1a 64-bit offset basis
        for (i in from until s.length) {
            h = h xor s[i].code.toLong()
            h *= 1099511628211L
        }
        return h
    }

    private companion object {
        val SLDS = setOf("co", "com", "org", "net", "gov", "ac", "edu", "nic", "res", "in")
        val PATHS = arrayOf(
            "/pagead/", "/adsbygoogle.js", "/adserver/", "/prebid", "/doubleclick/", "/ad_banner"
        )
    }
}
