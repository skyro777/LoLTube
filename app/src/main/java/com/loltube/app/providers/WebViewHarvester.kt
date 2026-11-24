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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.loltube.app.data.Net
import com.loltube.app.data.Wyzie
import com.loltube.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayInputStream
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
/** A subtitle file the site's own player ACTUALLY DOWNLOADED while we harvested.
 *  These are the files the site pairs with the exact video copy it serves
 *  (vidfast/vidcore decrypt the track list inside the page and fetch the
 *  auto-selected one; mapple auto-selects from its own action) - i.e. they are
 *  STREAM-COUPLED, unlike the /wyzie listing which is matched to the IMDb id
 *  only. `coupled` is false when the URL is a /wyzie fallback download. */
data class SiteSubtitle(
    val url: String,
    val body: String,
    val coupled: Boolean,
)

/** Result of one harvest run: playable stream candidates + subtitle listing +
 *  the site-synced subtitle bodies captured off the page's own downloads. */
data class HarvestResult(
    val candidates: List<StreamCandidate>,
    val subs: List<Wyzie.Sub>,
    val siteSubs: List<SiteSubtitle> = emptyList(),
)

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class WebViewHarvester(private val context: Context) {

    private val candidates = CopyOnWriteArrayList<StreamCandidate>()
    private val siteSubs = CopyOnWriteArrayList<SiteSubtitle>()
    @Volatile private var lastActivityMs: Long = 0
    @Volatile private var sawFragment = false
    @Volatile private var sawPlaying = false
    @Volatile private var webViewRef: WebView? = null
    @Volatile private var pageHost: String = ""
    @Volatile private var capturedSubs: List<Wyzie.Sub> = emptyList()

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

        /** The page downloaded something that parses as a subtitle file. This is
         *  the SITE-SYNCED subtitle (matched to the exact stream copy) — the
         *  body is kept verbatim so playback never has to re-download it. */
        @JavascriptInterface
        fun onSiteSubtitle(url: String, body: String) {
            if (body.length < 150 || body.length > 1_500_000) return
            if (!body.contains("-->")) return
            if (body.split("-->").size < 3) return // one stray arrow is not a subtitle
            if (siteSubs.any { it.url == url }) return
            val coupled = !url.contains("/wyzie")
            siteSubs.add(SiteSubtitle(url, body, coupled))
            lastActivityMs = now()
            AppLog.d(
                "Harvest",
                "captured subtitle body ${body.length}b ${if (coupled) "STREAM-COUPLED" else "generic"} <- ${url.take(90)}",
            )
        }
    }

    /**
     * Loads [url] and waits until a video manifest is very likely captured.
     * Returns the stream candidates, the subtitle listing the page itself
     * requested (via the /wyzie proxy), and — the important one — the subtitle
     * FILE the page actually downloaded for its auto-selected language track.
     * [prefSubLang] seeds the page's stored language so the site auto-selects
     * (and downloads) the track it would pair with THIS stream copy.
     */
    suspend fun harvest(url: String, prefSubLang: String = "en", timeoutMs: Long = 45_000): HarvestResult {
        candidates.clear()
        siteSubs.clear()
        sawFragment = false
        sawPlaying = false
        lastActivityMs = 0
        capturedSubs = emptyList()
        pageHost = android.net.Uri.parse(url).host ?: ""

        return withContext(Dispatchers.Main) {
            var wv: WebView? = null
            try {
                wv = createWebView(url, prefSubLang)
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
                // grace period: let variant playlists / fragments land, and give
                // the page's auto-selected subtitle download a bounded window —
                // the site fetches it right around manifest time, and that file
                // is the stream-coupled subtitle we want to carry into playback.
                val subDeadline = now() + 4_000
                repeat(8) {
                    ensureActive()
                    delay(250)
                }
                while (now() < subDeadline && siteSubs.isEmpty()) {
                    ensureActive()
                    delay(250)
                }
                HarvestResult(candidates.toList(), capturedSubs, siteSubs.toList())
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppLog.e("Harvest", "failed: ${t.message}")
                HarvestResult(candidates.toList(), capturedSubs, siteSubs.toList())
            } finally {
                destroyWebView()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String, prefSubLang: String): WebView = WebView(context).apply {
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

            /**
             * The page itself fetches its subtitle listing from `{origin}/wyzie`.
             * We proxy that request through our own HTTP stack so we can keep a
             * copy of the JSON - this captures the exact subtitles the provider's
             * own player would offer, with the page's session context.
             */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                val host = url.host ?: return null
                val path = url.encodedPath ?: ""
                if (host != pageHost || !path.startsWith("/wyzie")) return null
                return try {
                    val origin = "https://$pageHost"
                    val req = okhttp3.Request.Builder()
                        .url(url.toString())
                        .header("User-Agent", Net.UA)
                        .header("Referer", "$origin/")
                        .header("Accept", "application/json")
                        .build()
                    Net.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return null
                        val body = resp.body?.string() ?: return null
                        runCatching {
                            val subs = Net.json.decodeFromString(
                                ListSerializer(Wyzie.Sub.serializer()), body,
                            ).filter { it.url.isNotBlank() }
                            if (subs.isNotEmpty()) {
                                capturedSubs = subs
                                AppLog.d("Harvest", "captured ${subs.size} subtitle track(s) from page")
                            }
                        }
                        WebResourceResponse(
                            "application/json", "utf-8", ByteArrayInputStream(body.toByteArray()),
                        )
                    }
                } catch (t: Throwable) {
                    AppLog.d("Harvest", "wyzie proxy failed: ${t.message?.take(60)}")
                    null
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // fallback injection (in case document-start API is unavailable)
                view.evaluateJavascript(currentHookJs, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(currentHookJs, null)
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
                WebViewCompat.addDocumentStartJavaScript(this, hookJs(prefSubLang), setOf("*"))
            }
        } catch (t: Throwable) {
            AppLog.d("Harvest", "document-start injection unavailable: ${t.message}")
        }

        currentHookJs = hookJs(prefSubLang)
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

    @Volatile private var currentHookJs: String = ""

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

        /** Injected at document start; watches the page's media traffic AND
         *  captures the subtitle file the page itself downloads (the
         *  stream-coupled one), plus seeds the page's stored language so the
         *  site auto-selects the subtitle it would pair with this exact stream
         *  copy (vidfast/vidcore read localStorage "language" for auto-select). */
        private fun hookJs(prefLang: String): String = """
            (function(){
              if (window.__loltube_hooked) return;
              window.__loltube_hooked = true;
              try {
                if (window.localStorage) {
                  localStorage.setItem('language', '__LANG__');
                }
              } catch(e) {}
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
                  var u = '';
                  try {
                    u = (typeof input === 'string') ? input : (input && input.url) ? input.url : '';
                  } catch(e) {}
                  var k = classify(u); if (k) send(u, k);
                  var pr = ff.apply(this, arguments);
                  try {
                    if (u && /^https?:/i.test(u) && classify(u) === null && u.indexOf('/wyzie?') === -1) {
                      pr.then(function(resp){
                        try {
                          if (!resp || !resp.ok || !resp.clone) return;
                          var ct = '';
                          try { ct = (resp.headers && resp.headers.get('content-type')) || ''; } catch(e2) {}
                          var len = 0;
                          try { len = parseInt(resp.headers.get('content-length') || '0', 10) || 0; } catch(e2) {}
                          if (len > 3000000) return;
                          if (/^(video|audio|image|font)\//i.test(ct)) return;
                          var looksSub = /text\//i.test(ct) ||
                                         /octet-stream|subrip|x-subrip|force-download/i.test(ct) ||
                                         /\.(vtt|srt|ssa|ass)(\?|#|$)/i.test(u);
                          if (looksSub) {
                            resp.clone().text().then(function(t){
                              try {
                                if (t && t.length > 150 && t.length < 2000000 &&
                                    t.indexOf('-->') > 0 && t.split('-->').length >= 3) {
                                  __loltube.onSiteSubtitle(u, t);
                                }
                              } catch(e3) {}
                            }).catch(function(){});
                          }
                        } catch(e2) {}
                      }).catch(function(){});
                    }
                  } catch(e) {}
                  return pr;
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
        """.trimIndent().replace("__LANG__", prefLang.take(8).replace("'", ""))
    }
}
