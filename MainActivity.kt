package com.inaib.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * InaiB: a Chromium-based browser (it renders with the system Android WebView, which is Chrome's engine).
 *
 * Low-RAM design: only ONE WebView is alive at any time. Background tabs are kept as a small
 * saved-state Bundle (history + scroll position) and a WebView is rebuilt when you switch to them.
 * The WebView is also released when the app is in the background and the system is short on memory.
 */
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private class Tab(var url: String, var title: String = "", var state: Bundle? = null)

    private companion object {
        const val MAX_TABS = 10
        const val REQ_FILE = 1
        const val REQ_STORAGE = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var adBlocker: AdBlocker
    private lateinit var root: View
    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var btnReload: ImageButton
    private lateinit var tabCount: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val tabs = ArrayList<Tab>()
    private var current = 0
    private var webView: WebView? = null
    private var loading = false
    private var stopped = false

    @Volatile
    private var pageHost = ""

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingDownload: (() -> Unit)? = null

    private val defaultUa: String by lazy { WebSettings.getDefaultUserAgent(this) }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        adBlocker = AdBlocker(this)

        root = findViewById(R.id.root)
        container = findViewById(R.id.web_container)
        urlBar = findViewById(R.id.url_bar)
        progress = findViewById(R.id.progress)
        btnReload = findViewById(R.id.btn_reload)
        tabCount = findViewById(R.id.btn_tabs)

        // Android 15 draws apps edge-to-edge; keep our bars clear of the system bars and keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        urlBar.setOnEditorActionListener { v, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go(v.text.toString())
                hideKeyboard()
                v.clearFocus()
                true
            } else {
                false
            }
        }
        urlBar.setOnFocusChangeListener { _, focused ->
            if (focused) urlBar.post { urlBar.selectAll() }
        }
        btnReload.setOnClickListener {
            if (loading) webView?.stopLoading() else webView?.reload()
        }
        tabCount.setOnClickListener { showTabs() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMenu() }

        // Restore the tab list (URLs only; pages load lazily).
        for (u in prefs.savedTabs.take(MAX_TABS)) tabs.add(Tab(u))
        if (tabs.isEmpty()) tabs.add(Tab(Search.START_URL))
        current = prefs.savedCurrent.coerceIn(0, tabs.size - 1)

        val linked = intent?.dataString
        if (linked != null) {
            if (tabs.size >= MAX_TABS) tabs.removeAt(tabs.size - 1)
            tabs.add(Tab(linked))
            current = tabs.size - 1
        }
        updateTabCount()
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        if (webView == null) attach(tabs[current])
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { openNewTab(it) }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        prefs.saveTabs(tabs.map { it.url }, current)
        CookieManager.getInstance().flush()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            // In the background and the system wants memory back: drop the WebView entirely.
            stopped && level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> destroyWebView(true)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> webView?.clearCache(false)
        }
    }

    override fun onDestroy() {
        destroyWebView(false)
        super.onDestroy()
    }

    override fun onBackPressed() {
        when {
            customView != null -> hideCustomView()
            webView?.canGoBack() == true -> webView?.goBack()
            tabs.size > 1 -> closeCurrentTab()
            else -> super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun attach(tab: Tab) {
        val wv = createWebView()
        webView = wv
        container.addView(
            wv,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val st = tab.state
        tab.state = null
        if (st == null || wv.restoreState(st) == null) load(tab.url)
        showUrl(tab.url)
        updateTabCount()
    }

    private fun destroyWebView(save: Boolean) {
        val wv = webView ?: return
        if (save && current in tabs.indices) {
            val b = Bundle()
            wv.saveState(b)
            tabs[current].state = b
        }
        wv.stopLoading()
        container.removeView(wv)
        wv.removeAllViews()
        wv.destroy()
        webView = null
        loading = false
    }

    private fun switchTo(i: Int) {
        if (i == current && webView != null) return
        destroyWebView(true)
        current = i
        attach(tabs[i])
    }

    private fun openNewTab(url: String) {
        if (tabs.size >= MAX_TABS) {
            toast("Tab limit reached ($MAX_TABS). Loading here instead.")
            load(url)
            return
        }
        destroyWebView(true)
        tabs.add(Tab(url))
        current = tabs.size - 1
        attach(tabs[current])
    }

    private fun openBackgroundTab(url: String) {
        if (tabs.size >= MAX_TABS) {
            toast("Tab limit reached ($MAX_TABS)")
            return
        }
        tabs.add(Tab(url))
        updateTabCount()
        toast("Opened in a background tab")
    }

    private fun closeCurrentTab() {
        destroyWebView(false)
        if (tabs.size == 1) {
            tabs[0] = Tab(Search.START_URL)
            current = 0
        } else {
            tabs.removeAt(current)
            current = current.coerceAtMost(tabs.size - 1)
        }
        attach(tabs[current])
    }

    private fun updateTabCount() {
        tabCount.text = tabs.size.toString()
    }

    private fun tabLabel(t: Tab): String = when {
        t.url.startsWith("file:///android_asset") -> "New tab"
        t.title.isNotBlank() -> t.title
        else -> t.url.removePrefix("https://")
    }

    private fun showTabs() {
        val titles = tabs.mapIndexed { i, t -> (if (i == current) "● " else "") + tabLabel(t) }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size}/$MAX_TABS)")
            .setItems(titles) { _, i -> switchTo(i) }
            .setPositiveButton("New tab") { _, _ -> openNewTab(Search.START_URL) }
            .setNegativeButton("Close this tab") { _, _ -> closeCurrentTab() }
            .show()
    }

    // ---------------------------------------------------------------- loading

    private fun go(input: String) {
        load(Search.resolve(input, prefs.engine, prefs.lang))
    }

    private fun load(url: String) {
        val wv = webView ?: return
        tabs[current].url = url
        if (url.startsWith("http")) {
            val headers = HashMap<String, String>()
            headers["Accept-Language"] = Search.acceptLanguage(prefs.lang)
            headers["DNT"] = "1"
            headers["Sec-GPC"] = "1"
            wv.loadUrl(url, headers)
        } else {
            wv.loadUrl(url)
        }
    }

    private fun showUrl(url: String) {
        urlBar.setText(if (url.startsWith("file:///android_asset")) "" else url.removePrefix("https://"))
    }

    private fun hostOf(url: String?): String {
        if (url == null) return ""
        return try {
            Uri.parse(url).host?.lowercase(Locale.ROOT) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- WebView

    private fun userAgent(): String {
        if (prefs.desktop) {
            val ver = Regex("Chrome/[\\d.]+").find(defaultUa)?.value ?: "Chrome/120.0.0.0"
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $ver Safari/537.36"
        }
        // Make the UA look like Chrome rather than an embedded WebView so more sites serve the normal page.
        return defaultUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false // file:///android_asset/ still works
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.mediaPlaybackRequiresUserGesture = true // no autoplay: saves data and CPU
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false // popups only after a real tap
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.blockNetworkImage = prefs.dataSaver
        s.userAgentString = userAgent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.safeBrowsingEnabled = true

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, false) // blocks cross-site tracking cookies

        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.webViewClient = client
        wv.webChromeClient = chrome
        wv.setDownloadListener { url, ua, contentDisposition, mime, _ ->
            startDownload(url, ua, contentDisposition, mime)
        }
        wv.setOnLongClickListener { onLongPress(wv) }
        return wv
    }

    private val client = object : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (prefs.adBlock && !request.isForMainFrame && adBlocker.shouldBlock(request.url, pageHost)) {
                adBlocker.blockedCount.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            when (u.scheme) {
                "http", "https", "file", "about", "data", "blob", "javascript" -> return false
            }
            // tel:, mailto:, upi:, whatsapp:, intent: ... only when the user actually tapped something.
            if (request.hasGesture()) openExternal(u.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== webView) return
            loading = true
            pageHost = hostOf(url)
            btnReload.setImageResource(R.drawable.ic_close)
            progress.progress = 5
            progress.visibility = View.VISIBLE
            if (!urlBar.hasFocus() && url != null) showUrl(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (view !== webView || url == null || url.startsWith("data:") || url == "about:blank") return
            tabs.getOrNull(current)?.url = url
            pageHost = hostOf(url)
            if (!urlBar.hasFocus()) showUrl(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== webView) return
            loading = false
            btnReload.setImageResource(R.drawable.ic_reload)
            progress.visibility = View.GONE
            if (prefs.adBlock && url != null && url.startsWith("http") && adBlocker.cosmeticJs.isNotEmpty()) {
                view.evaluateJavascript(adBlocker.cosmeticJs, null)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                showError(view, request.url.toString(), error.description?.toString())
            }
        }

        @Deprecated("Used on Android 5.x/6.0 only")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) showError(view, failingUrl, description)
        }
    }

    private fun showError(view: WebView, failingUrl: String?, description: String?) {
        val u = failingUrl ?: return
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>body{font-family:sans-serif;padding:32px;color:#333}a{color:#0b57d0}" +
            "@media(prefers-color-scheme:dark){body{background:#202124;color:#e8eaed}a{color:#8ab4f8}}</style>" +
            "</head><body><h2>Can't open this page</h2><p>${TextUtils.htmlEncode(description ?: "")}</p>" +
            "<p><a href=\"${TextUtils.htmlEncode(u)}\">Try again</a></p></body></html>"
        view.loadDataWithBaseURL(u, html, "text/html", "utf-8", null)
    }

    private val chrome = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view !== webView) return
            progress.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (view !== webView) return
            tabs.getOrNull(current)?.title = title ?: ""
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            if (!isUserGesture) return false // popup blocked
            // Catch the popup's URL with a throw-away WebView and open it as a normal tab.
            val temp = WebView(this@MainActivity)
            temp.webViewClient = object : WebViewClient() {
                private var done = false

                private fun grab(url: String?) {
                    if (done || url.isNullOrEmpty() || url == "about:blank") return
                    done = true
                    handler.post {
                        temp.stopLoading()
                        temp.destroy()
                        openNewTab(url)
                    }
                }

                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    grab(r.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    grab(url)
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: WebChromeClient.FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                startActivityForResult(fileChooserParams.createIntent(), REQ_FILE)
                true
            } catch (e: Exception) {
                fileCallback = null
                false
            }
        }

        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            root.visibility = View.GONE
            (window.decorView as ViewGroup).addView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        // Camera, microphone and location are never granted silently.
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, false, false)
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        root.visibility = View.VISIBLE
        customCallback?.onCustomViewHidden()
        customView = null
        customCallback = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ---------------------------------------------------------------- downloads, links, share

    private fun startDownload(url: String, ua: String?, contentDisposition: String?, mime: String?) {
        if (!url.startsWith("http")) {
            toast("This download type isn't supported yet")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = { startDownload(url, ua, contentDisposition, mime) }
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mime.isNullOrEmpty()) req.setMimeType(mime)
            if (!ua.isNullOrEmpty()) req.addRequestHeader("User-Agent", ua)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Download failed")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val run = pendingDownload
            pendingDownload = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                run?.invoke()
            } else {
                toast("Storage permission is needed to save downloads")
            }
        }
    }

    private fun openExternal(url: String) {
        try {
            val i = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("No app found to open this link")
        }
    }

    private fun onLongPress(wv: WebView): Boolean {
        val r = wv.hitTestResult
        val extra = r.extra ?: return false
        return when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                showLinkMenu(extra, false)
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                showLinkMenu(extra, true)
                true
            }
            else -> false
        }
    }

    private fun showLinkMenu(url: String, isImage: Boolean) {
        val items = arrayOf(
            "Open in new tab",
            if (isImage) "Copy image address" else "Copy link address",
            "Share"
        )
        AlertDialog.Builder(this)
            .setTitle(url.take(80))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openBackgroundTab(url)
                    1 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("url", url))
                        toast("Copied")
                    }
                    2 -> share(url)
                }
            }
            .show()
    }

    private fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, "Share"))
    }

    // ---------------------------------------------------------------- menu

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    private fun showMenu() {
        val items = arrayOf(
            "New tab",
            "Forward",
            "Share page",
            "Ad blocker: ${onOff(prefs.adBlock)}  (${adBlocker.blockedCount.get()} blocked)",
            "Data saver, no images: ${onOff(prefs.dataSaver)}",
            "Desktop site: ${onOff(prefs.desktop)}",
            "Search engine: ${Search.engines[prefs.engine].name}",
            "Search language: ${Search.langs[prefs.lang].label}",
            "Clear browsing data",
            "Make InaiB your default browser"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                val wv = webView
                when (which) {
                    0 -> openNewTab(Search.START_URL)
                    1 -> if (wv != null && wv.canGoForward()) wv.goForward()
                    2 -> share(tabs[current].url)
                    3 -> {
                        prefs.adBlock = !prefs.adBlock
                        wv?.reload()
                    }
                    4 -> {
                        prefs.dataSaver = !prefs.dataSaver
                        wv?.settings?.blockNetworkImage = prefs.dataSaver
                    }
                    5 -> {
                        prefs.desktop = !prefs.desktop
                        if (wv != null) {
                            wv.settings.userAgentString = userAgent()
                            wv.reload()
                        }
                    }
                    6 -> chooseEngine()
                    7 -> chooseLanguage()
                    8 -> confirmClear()
                    9 -> try {
                        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    } catch (e: Exception) {
                        toast("Open Settings > Apps > Default apps > Browser app")
                    }
                }
            }
            .show()
    }

    private fun chooseEngine() {
        val names = Search.engines.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(names, prefs.engine) { d, which ->
                prefs.engine = which
                d.dismiss()
            }
            .show()
    }

    private fun chooseLanguage() {
        val names = Search.langs.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Search language")
            .setSingleChoiceItems(names, prefs.lang) { d, which ->
                prefs.lang = which
                d.dismiss()
            }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear browsing data?")
            .setMessage("Removes cookies, cache, history and saved site data.")
            .setPositiveButton("Clear") { _, _ ->
                webView?.let {
                    it.clearCache(true)
                    it.clearHistory()
                    it.clearFormData()
                }
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.state = null }
                toast("Browsing data cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
