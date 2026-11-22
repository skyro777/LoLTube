package com.loltube.app.providers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.loltube.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loads a provider's embed page inside an offscreen WebView and watches the page's
 * own network layer (XHR / fetch / media element) until the real video manifest
 * (m3u8 / mpd / mp4) shows up. The page does all the crypto/token work itself,
 * so nothing provider-specific has to be replicated natively.
 *
 * The WebView is muted, popups are blocked, and cross-origin navigations
 * (popunder ad redirects) are cancelled.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class WebViewHarvester(private val context: Context) {

    private val candidates = CopyOnWriteArrayList<StreamCandidate>()
    @Volatile private var lastActivityMs: Long = 0
    @Volatile private var sawFragment = false
    @Volatile private var sawPlaying = false
    @Volatile private var webViewRef: WebView? = null
    @Volatile private var pageHost: String = ""

    private val bridge = object {
        @JavascriptInterface
        fun onCandidate(url: String, kind: String) {
            if (kind == "fragment") {
                sawFragment = true
                lastActivityMs = now()
                return
            }
            if (isJunkUrl(url)) return
            if (candidates.none { it.url == url }) {
                candidates.add(StreamCandidate(url, kind, now()))
                AppLog.d("Harvest", "captured [$kind] ${url.take(160)}")
            }
            lastActivityMs = now()
        }

        @JavascriptInterface
        fun onState(state: String) {
            if (state == "playing") {
                sawPlaying = true
                lastActivityMs = now()
                AppLog.d("Harvest", "page reports playback started")
            }
        }
    }

    /**
     * Loads [url] and waits until a video manifest is very likely captured.
     * Returns whatever candidates were seen (may be empty).
     */
    suspend fun harvest(url: String, timeoutMs: Long = 45_000): List<StreamCandidate> {
        candidates.clear()
        sawFragment = false
        sawPlaying = false
        lastActivityMs = 0
        pageHost = android.net.Uri.parse(url).host ?: ""

        return withContext(Dispatchers.Main) {
            var wv: WebView? = null
            try {
                wv = createWebView(url)
                webViewRef = wv
                val start = now()
                var lastCandidateCount = 0

                withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        ensureActive()
                        delay(250)
                        val t = now()
                        if (lastActivityMs == 0L && t - start > 20_000) break // page appears dead
                        val hasManifest = candidates.any { it.kind == "m3u8" || it.kind == "mpd" }
                        val quietMs = if (lastActivityMs == 0L) 0 else t - lastActivityMs
                        val grew = candidates.size > lastCandidateCount
                        if (grew) lastCandidateCount = candidates.size
                        val earlyExit = hasManifest && (sawFragment || sawPlaying || quietMs > 12_000)
                        if (earlyExit) break
                    }
                }
                // grace period: let variant playlists / fragments land
                repeat(8) {
                    ensureActive()
                    delay(250)
                }
                candidates.toList()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppLog.e("Harvest", "failed: ${t.message}")
                candidates.toList()
            } finally {
                destroyWebView()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String): WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        addJavascriptInterface(bridge, "__loltube")

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                // block cross-origin navigations (popunders / ad redirects)
                val blocked = host != pageHost
                if (blocked) AppLog.d("Harvest", "blocked navigation to $host")
                return blocked
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // fallback injection (in case document-start API is unavailable)
                view.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(HOOK_JS, null)
                // nudge playback so hls.js starts pulling manifests/fragments
                view.evaluateJavascript(
                    "document.querySelectorAll('video').forEach(function(v){v.muted=true;v.volume=0;var p=v.play();if(p&&p.catch)p.catch(function(){});});",
                    null
                )
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel() // never proceed with bad certs
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean = false // block window.open popunders

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                AppLog.d("Harvest", "js: ${m.message().take(200)}")
                return true
            }
        }

        // document-start hook injection (best effort)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, HOOK_JS, setOf("*"))
            }
        } catch (t: Throwable) {
            AppLog.d("Harvest", "document-start injection unavailable: ${t.message}")
        }

        loadUrl(url)
    }

    private fun destroyWebView() {
        val wv = webViewRef ?: return
        webViewRef = null
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.removeAllViews()
            wv.destroy()
        } catch (t: Throwable) {
            AppLog.d("Harvest", "destroy: ${t.message}")
        }
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()

    companion object {
        private val JUNK_HOST_SUFFIXES = listOf(
            "doubleclick.net", "googlesyndication.com", "google-analytics.com",
            "googleadservices.com", "adservice.google.com", "googletagmanager.com",
            "gstatic.com", "googleapis.com", "google.com", "youtube.com", "youtu.be",
            "cloudflareinsights.com", "facebook.com", "facebook.net", "hotjar.com",
            "histats.com", "scorecardresearch.com", "adsrvr.org", "taboola.com",
            "outbrain.com", "popads.net", "llvpn.com", "urbanfungose.com",
            "rapidadexchange.com", "exoclick.com", "exosrv.com", "juicyads.com",
            "trafficjunky.net", "propellerads.com", "mgid.com", "revcontent.com",
            "ythd.org", "aclib.net", "llkdb.com", "adtraction.com", "adroll.com",
            "criteo.com", "bidswitch.net", "sharethrough.com", "openx.net",
            "rubiconproject.com", "pubmatic.com", "smartadserver.com", "casalemedia.com",
        )

        fun isJunkUrl(url: String): Boolean {
            val u = url.lowercase()
            if (u.startsWith("blob:") || u.startsWith("data:")) return true
            if (u.contains("/wyzie")) return true // subtitles endpoint, not video
            if (u.contains("clearkey") || u.contains("/ads/") || u.contains("/ad/")) return true
            val host = try {
                android.net.Uri.parse(url).host ?: return true
            } catch (t: Throwable) {
                return true
            }
            return JUNK_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
        }

        /** Injected at document start; watches the page's media traffic. */
        private val HOOK_JS = """
            (function(){
              if (window.__loltube_hooked) return;
              window.__loltube_hooked = true;
              function send(url, kind){
                try { __loltube.onCandidate(url, kind); } catch(e) {}
              }
              function classify(u){
                if (!u) return null;
                u = String(u);
                if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return null;
                if (/\.m3u8(\?|#|$)/i.test(u)) return 'm3u8';
                if (/\.mpd(\?|#|$)/i.test(u)) return 'mpd';
                if (/(master|manifest|playlist)[^\/]*\.m3u8/i.test(u)) return 'm3u8';
                if (/format=m3u8/i.test(u)) return 'm3u8';
                if (/\.mp4(\?|#|$)/i.test(u)) {
                  if (/(segment|fragment|chunk|seg-|init-)/i.test(u)) return 'fragment';
                  return 'mp4';
                }
                if (/\.(m4s|ts|webm)(\?|#|$)/i.test(u)) return 'fragment';
                return null;
              }
              var xo = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(m, u){
                try { var k = classify(u); if (k) send(u, k); } catch(e){}
                return xo.apply(this, arguments);
              };
              if (window.fetch) {
                var ff = window.fetch;
                window.fetch = function(input, init){
                  try {
                    var u = (typeof input === 'string') ? input : (input && input.url) ? input.url : '';
                    var k = classify(u); if (k) send(u, k);
                  } catch(e){}
                  return ff.apply(this, arguments);
                };
              }
              try {
                var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (d && d.set) {
                  Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                    configurable: true, enumerable: d.enumerable,
                    get: function(){ return d.get.call(this); },
                    set: function(v){
                      try { var k = classify(v); if (k === 'm3u8' || k === 'mpd' || k === 'mp4') send(v, k); } catch(e){}
                      d.set.call(this, v);
                    }
                  });
                }
              } catch(e){}
              try {
                document.addEventListener('playing', function(){
                  try { __loltube.onState('playing'); } catch(e){}
                }, true);
              } catch(e){}
            })();
        """.trimIndent()
    }
}
