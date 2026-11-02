package com.multibiz.hub.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.multibiz.hub.App
import com.multibiz.hub.core.AccountStore
import com.multibiz.hub.core.Notifier
import com.multibiz.hub.core.StatusBus
import org.json.JSONObject

/**
 * Owns the single WebView of THIS account process. The WebView lives independently of
 * any Activity (inside the process, kept alive by SessionService), so the session keeps
 * receiving messages while the screen is closed. When the user opens the account, the
 * same live WebView is attached to the Activity - no reload, instant open.
 */
@SuppressLint("StaticFieldLeak", "SetJavaScriptEnabled")
object SessionManager {
    private const val TAG = "MultiBiz.Session"
    const val WA_URL = "https://web.whatsapp.com/"
    private const val ORIGIN = "https://web.whatsapp.com"
    private const val BRIDGE = "MultiBizBridge"
    private const val INITIAL_RETRY = 2_000L
    private const val MAX_RETRY = 60_000L
    private const val HEARTBEAT_TIMEOUT = 10 * 60_000L
    private const val LOADING_TIMEOUT = 3 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var webView: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var host: AccountActivity? = null
    private var injectScript = ""
    private var docStartSupported = false

    private var loadFailed = false
    private var retryDelay = INITIAL_RETRY
    private var unread = 0
    private var state = StatusBus.STATE_LOADING
    private var everReady = false
    private var readySince = 0L
    private var lastNotifyAt = 0L
    private var lastJsMessageAt = 0L
    private var loadStartedAt = 0L
    private val crashTimes = ArrayDeque<Long>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val slot get() = App.slot
    private val isAlive get() = webView != null

    // ------------------------------------------------------------------ lifecycle

    fun ensure(ctx: Context): WebView? {
        app = ctx.applicationContext
        if (slot == 0 || AccountStore.account(app, slot) == null) return null
        webView?.let { return it }
        val wv = create()
        webView = wv
        registerNetwork()
        scheduleWatchdog()
        return wv
    }

    fun attach(activity: AccountActivity) {
        val wv = ensure(activity) ?: return
        host = activity
        attachTo(activity, wv)
        Notifier.cancelAll(app, slot)
        activity.onSessionStatus(state, unread, loadFailed)
    }

    private fun attachTo(activity: AccountActivity, wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wrapper?.baseContext = activity
        activity.webContainer.addView(
            wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        wv.onResume()
    }

    fun detach(activity: AccountActivity) {
        if (host !== activity) return
        host = null
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (::app.isInitialized) wrapper?.baseContext = app
        // NOTE: we deliberately do NOT pause the WebView: it must keep running for notifications.
    }

    fun onHostResumed() {
        if (::app.isInitialized) Notifier.cancelAll(app, slot)
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        unregisterNetwork()
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
        wrapper = null
    }

    fun reload() {
        loadFailed = false
        retryDelay = INITIAL_RETRY
        loadStartedAt = SystemClock.uptimeMillis()
        webView?.loadUrl(WA_URL)
    }

    fun republishStatus() {
        if (::app.isInitialized && slot > 0) StatusBus.send(app, slot, unread, state)
    }

    fun clickNotification(jsId: String) {
        webView?.evaluateJavascript(
            "window.__mbNotifClick && window.__mbNotifClick(${JSONObject.quote(jsId)});", null
        )
    }

    fun applyViewport() {
        val w = layoutWidth()
        webView?.evaluateJavascript("window.__mbViewport && window.__mbViewport($w);", null)
    }

    fun layoutWidth(): Int =
        app.getSharedPreferences(com.multibiz.hub.core.Slots.prefsName(slot), Context.MODE_PRIVATE)
            .getInt("layoutWidth", 900)

    fun setLayoutWidth(w: Int) {
        app.getSharedPreferences(com.multibiz.hub.core.Slots.prefsName(slot), Context.MODE_PRIVATE)
            .edit().putInt("layoutWidth", w).apply()
        applyViewport()
    }

    // ------------------------------------------------------------------ creation

    private fun create(): WebView {
        if (injectScript.isEmpty()) {
            injectScript = app.assets.open("inject.js").bufferedReader().use { it.readText() }
        }
        val w = MutableContextWrapper(host ?: app)
        wrapper = w
        val wv = WebView(w)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = desktopUserAgent(WebSettings.getDefaultUserAgent(app))
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        // Secure bridge: only the web.whatsapp.com main frame can talk to the app.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, BRIDGE, setOf(ORIGIN)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) message.data?.let { handleMessage(it) }
            }
        } else {
            wv.addJavascriptInterface(LegacyBridge, BRIDGE)
        }
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, injectScript, setOf(ORIGIN))
        }

        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { url, ua, cd, mime, _ -> onDownload(url, ua, cd, mime) }

        loadStartedAt = SystemClock.uptimeMillis()
        wv.loadUrl(WA_URL)
        return wv
    }

    /** WhatsApp Web only works with desktop browsers - present the real Chrome version as desktop. */
    private fun desktopUserAgent(default: String): String {
        val major = Regex("Chrome/(\\d+)").find(default)?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"
    }

    private object LegacyBridge {
        @JavascriptInterface
        fun postMessage(data: String) {
            main.post {
                if (webView?.url?.startsWith(ORIGIN) == true) handleMessage(data)
            }
        }
    }

    // ------------------------------------------------------------------ JS -> app

    private fun handleMessage(raw: String) {
        val o = try { JSONObject(raw) } catch (_: Exception) { return }
        lastJsMessageAt = SystemClock.uptimeMillis()
        when (o.optString("t")) {
            "notify" -> onNotify(
                o.optString("title"), o.optString("body"), o.optString("tag"),
                o.optString("id"), o.optBoolean("silent")
            )
            "cancel" -> Notifier.cancelTag(app, slot, o.optString("tag"))
            "unread" -> onUnread(o.optInt("n"))
            "state" -> onState(o.optString("s"))
            "blob" -> {
                val acc = AccountStore.account(app, slot) ?: return
                Downloads.saveDataUrl(app, acc.name, o.optString("data"), o.optString("name"), o.optString("mime"))
            }
            "hb" -> {}
            "log" -> Log.d(TAG, "[js] ${o.optString("m")}")
        }
    }

    private fun isUserLooking(): Boolean {
        val h = host ?: return false
        val pm = app.getSystemService(PowerManager::class.java)
        return h.isVisibleToUser && pm.isInteractive
    }

    private fun onNotify(title: String, body: String, tag: String, id: String, silent: Boolean) {
        lastNotifyAt = SystemClock.elapsedRealtime()
        if (isUserLooking()) return
        val acc = AccountStore.account(app, slot) ?: return
        Notifier.message(app, acc, title.ifBlank { "New message" }, body.ifBlank { "New message" }, tag, id, silent)
    }

    private fun onUnread(n: Int) {
        val prev = unread
        unread = n
        StatusBus.send(app, slot, n, null)
        host?.onSessionStatus(state, unread, loadFailed)
        if (n == 0) {
            Notifier.cancelAll(app, slot)
            return
        }
        // Fallback: counter went up but the page didn't raise a notification (e.g. page throttled).
        val settled = state == StatusBus.STATE_READY &&
            SystemClock.elapsedRealtime() - readySince > 15_000
        if (n > prev && settled && !isUserLooking()) {
            main.postDelayed({
                val acc = AccountStore.account(app, slot) ?: return@postDelayed
                val quiet = SystemClock.elapsedRealtime() - lastNotifyAt > 8_000
                if (quiet && unread > prev && !isUserLooking()) Notifier.generic(app, acc, unread)
            }, 5_000)
        }
    }

    private fun onState(s: String) {
        if (s.isEmpty() || s == state) return
        state = s
        StatusBus.send(app, slot, null, s)
        host?.onSessionStatus(state, unread, loadFailed)
        val acc = AccountStore.account(app, slot) ?: return
        when (s) {
            StatusBus.STATE_READY -> {
                everReady = true
                readySince = SystemClock.elapsedRealtime()
                Notifier.cancelLoggedOut(app, slot)
            }
            StatusBus.STATE_QR -> if (everReady) {
                everReady = false
                Notifier.loggedOut(app, acc)
            }
        }
    }

    // ------------------------------------------------------------------ robustness

    private val retryRunnable = Runnable {
        if (loadFailed) {
            Log.i(TAG, "slot $slot retrying load")
            loadStartedAt = SystemClock.uptimeMillis()
            webView?.reload()
        }
    }

    private fun scheduleRetry() {
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!isAlive) return
            val now = SystemClock.uptimeMillis()
            val stuckLoading = state == StatusBus.STATE_LOADING && now - loadStartedAt > LOADING_TIMEOUT
            val jsDead = lastJsMessageAt > 0 && now - lastJsMessageAt > HEARTBEAT_TIMEOUT
            if (!loadFailed && (stuckLoading || jsDead)) {
                Log.w(TAG, "slot $slot watchdog reload (stuck=$stuckLoading jsDead=$jsDead)")
                lastJsMessageAt = 0
                reload()
            }
            main.postDelayed(this, 3 * 60_000L)
        }
    }

    private fun scheduleWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3 * 60_000L)
    }

    private fun registerNetwork() {
        if (networkCallback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (loadFailed) {
                        retryDelay = INITIAL_RETRY
                        main.removeCallbacks(retryRunnable)
                        main.postDelayed(retryRunnable, 1_000)
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) {}
    }

    private fun unregisterNetwork() {
        val cb = networkCallback ?: return
        try { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } catch (_: Exception) {}
        networkCallback = null
    }

    private fun recreateAfterCrash(old: WebView) {
        (old.parent as? ViewGroup)?.removeView(old)
        try { old.destroy() } catch (_: Exception) {}
        if (webView === old) { webView = null; wrapper = null }
        val now = SystemClock.elapsedRealtime()
        crashTimes.addLast(now)
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > 60_000) crashTimes.removeFirst()
        val delay = if (crashTimes.size > 3) 30_000L else 500L
        main.postDelayed({
            val wv = ensure(app) ?: return@postDelayed
            host?.let { attachTo(it, wv) }
        }, delay)
    }

    // ------------------------------------------------------------------ navigation

    private fun isInternal(uri: Uri): Boolean {
        when (uri.scheme) {
            "blob", "data", "about" -> return true
            "https" -> {}
            else -> return false
        }
        val h = uri.host ?: return false
        return h == "web.whatsapp.com" || h.endsWith(".whatsapp.net")
    }

    private fun openExternal(uri: Uri) {
        val ctx: Context = host ?: return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
        } catch (_: Exception) {}
    }

    private fun onDownload(url: String, ua: String?, cd: String?, mime: String?) {
        if (url.startsWith("blob:")) {
            webView?.evaluateJavascript(
                "window.__mbDownloadBlob && window.__mbDownloadBlob(${JSONObject.quote(url)}, '', ${JSONObject.quote(mime ?: "")});",
                null
            )
            return
        }
        val acc = AccountStore.account(app, slot) ?: return
        Downloads.enqueue(app, url, ua, cd, mime, acc.name)
    }

    private class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isInternal(uri)) return false
            openExternal(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loadStartedAt = SystemClock.uptimeMillis()
            if (!docStartSupported && url?.startsWith(ORIGIN) == true) view.evaluateJavascript(injectScript, null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url?.startsWith(ORIGIN) != true) return
            if (!docStartSupported) view.evaluateJavascript(injectScript, null)
            applyViewport()
            if (loadFailed) return
            retryDelay = INITIAL_RETRY
            host?.onSessionStatus(state, unread, false)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            Log.w(TAG, "slot $slot main frame error ${error.errorCode} ${error.description}")
            loadFailed = true
            StatusBus.send(app, slot, null, StatusBus.STATE_OFFLINE)
            state = StatusBus.STATE_OFFLINE
            host?.onSessionStatus(state, unread, true)
            scheduleRetry()
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (url?.startsWith(ORIGIN) == true) loadFailed = false
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "slot $slot renderer gone (crash=${detail.didCrash()}) - recovering")
            recreateAfterCrash(view)
            return true // never let a renderer crash kill the app
        }
    }

    private class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            host?.onProgress(newProgress)
        }

        override fun onShowFileChooser(
            webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams
        ): Boolean {
            val h = host ?: return false
            return h.openFileChooser(filePathCallback, fileChooserParams)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            main.post {
                val fromWa = request.origin?.host == "web.whatsapp.com"
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!fromWa || !wantsAudio) { request.deny(); return@post }
                val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) { request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)); return@post }
                val h = host
                if (h == null) { request.deny(); return@post }
                h.requestRuntimePermission(Manifest.permission.RECORD_AUDIO) { ok ->
                    if (ok) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
                }
            }
        }
    }
}
