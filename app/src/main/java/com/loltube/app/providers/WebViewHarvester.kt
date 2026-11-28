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
import kotlinx.coroutines.launch
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

/** One of Mapple's 13 internal sources, probed DIRECTLY through the page's own
 *  session (the injected JS resolves every source's master playlist — POST
 *  /api/encrypt per source -> GET the encrypted URL -> fetch the master ->
 *  parse RESOLUTION) while the page's own rotation runs. This is the "control
 *  over mapple's sources" engine: the app sees every source's real ladder in
 *  seconds and plays the best one instead of whatever the rotation lands on.
 *  height -1 = offline/failed, 0 = mp4 (no ladder to parse). */
data class MappleSourceProbe(
    val source: String,
    val height: Int,
    val url: String,
)

private fun parseListingTracks(body: String): List<Wyzie.Sub> {
    // Tolerant extractor: the mapple listing arrives inside an RSC stream
    // (`1[{"lang":...}]`-ish rows) and the /wyzie one as a plain JSON array.
    // Scan for the first array of objects that carry the track shape.
    val out = mutableListOf<Wyzie.Sub>()
    val open = body.indexOf('[')
    if (open < 0) return out
    var i = open
    val n = body.length
    while (i < n) {
        val start = body.indexOf('{', i)
        if (start < 0) break
        val end = body.indexOf('}', start)
        if (end < 0) break
        val obj = body.substring(start, end + 1)
        fun str(key: String): String? {
            val k = body.indexOf("\"$key\"", start)
            if (k < 0 || k > end) return null
            val colon = body.indexOf(':', k)
            if (colon < 0 || colon > end) return null
            var j = colon + 1
            while (j < n && body[j].isWhitespace()) j++
            if (j >= n || body[j] != '"') return null
            val sb = StringBuilder()
            j++
            while (j < n && body[j] != '"') {
                if (body[j] == '\\' && j + 1 < n) j++
                sb.append(body[j]); j++
            }
            return sb.toString()
        }
        val url = str("url")
        if (url == null || !url.startsWith("http")) {
            i = end + 1
            continue
        }
        val lang = str("lang") ?: str("language")
        val label = str("label") ?: str("display") ?: lang
        val hi = Regex("\"isHearingImpaired\"\\s*:\\s*true", RegexOption.IGNORE_CASE)
            .containsMatchIn(obj)
        out.add(
            Wyzie.Sub(
                display = label ?: "track",
                language = lang,
                url = url,
                encoding = str("encoding"),
                isHearingImpaired = hi,
            )
        )
        i = end + 1
    }
    return out.distinctBy { it.url }.filter { it.url.isNotBlank() }
}

/** Result of one harvest run: playable stream candidates + subtitle listing +
 *  the site-synced subtitle bodies captured off the page's own downloads +
 *  mapple's per-source quality table from the direct probe. */
data class HarvestResult(
    val candidates: List<StreamCandidate>,
    val subs: List<Wyzie.Sub>,
    val siteSubs: List<SiteSubtitle> = emptyList(),
    val mappleSources: List<MappleSourceProbe> = emptyList(),
)

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class WebViewHarvester(private val context: Context) {

    private val candidates = CopyOnWriteArrayList<StreamCandidate>()
    private val siteSubs = CopyOnWriteArrayList<SiteSubtitle>()
    private val mappleSources = CopyOnWriteArrayList<MappleSourceProbe>()
    @Volatile private var lastActivityMs: Long = 0
    @Volatile private var sawFragment = false
    @Volatile private var sawPlaying = false
    @Volatile private var webViewRef: WebView? = null
    @Volatile private var pageHost: String = ""
    @Volatile private var capturedSubs: List<Wyzie.Sub> = emptyList()
    @Volatile private var mappleProbeDone = false
    @Volatile private var prefSubLang: String = "en"
    @Volatile private var subFetchJob: kotlinx.coroutines.Job? = null

    /** IO scope for the immediate subtitle download (launched from the WebView
     *  binder thread when a listing lands — must never block the page). */
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

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
         *  body is kept verbatim so playback never has to re-download it.
         *  ASS/SSA scripts count too: series subtitle tracks are frequently
         *  full SubStation Alpha files (no `-->` timeline at all) — rejecting
         *  those is how "subtitle not available" appeared on titles whose
         *  English track only exists as ASS. */
        @JavascriptInterface
        fun onSiteSubtitle(url: String, body: String) {
            if (body.length < 150 || body.length > 1_500_000) return
            val isTimeline = body.split("-->").size >= 3 // one stray arrow is not a subtitle
            val isAss = body.contains("[Script Info]", ignoreCase = true) ||
                Regex("^Dialogue:", RegexOption.IGNORE_CASE).containsMatchIn(body)
            if (!isTimeline && !isAss) return
            if (siteSubs.any { it.url == url }) return
            val coupled = !url.contains("/wyzie")
            siteSubs.add(SiteSubtitle(url, body, coupled))
            lastActivityMs = now()
            AppLog.d(
                "Harvest",
                "captured subtitle body ${body.length}b ${if (coupled) "STREAM-COUPLED" else "generic"} <- ${url.take(90)}",
            )
        }

        /** A response that carries a LISTING of subtitle tracks (mapple's
         *  fetchSubtitlesAction server action, or any JSON array with the
         *  {lang,label,url} track shape). This is the site's OWN menu —
         * picking from it with the site's own algorithm arms a subtitle
         * with the exact timeline the site shows in a browser. */
        @JavascriptInterface
        fun onSiteSubListing(url: String, body: String) {
            if (body.length < 20 || body.length > 1_500_000) return
            if (body.contains("-->")) return // that's a subtitle file, not a listing
            val tracks = parseListingTracks(body)
            if (tracks.isEmpty()) return
            if (tracks.size <= capturedSubs.size) return // keep the richer listing
            capturedSubs = tracks
            lastActivityMs = now()
            AppLog.d("Harvest", "captured ${tracks.size} subtitle track(s) from player listing <- ${url.take(80)}")
            onListingCaptured(tracks, "page listing")
        }

        /** DIRECT SOURCE CONTROL — one of mapple's 13 internal sources,
         *  resolved by the injected probe. height -1 = offline, 0 = mp4. */
        @JavascriptInterface
        fun onMappleSource(source: String, height: String, url: String) {
            val h = height.toIntOrNull() ?: -1
            if (mappleSources.any { it.source == source }) return
            mappleSources.add(MappleSourceProbe(source, h, url))
            if (h >= 0 && url.isNotBlank()) {
                lastActivityMs = now()
                AppLog.d(
                    "MappleProbe",
                    "source $source -> ${if (h > 0) "${h}p" else "mp4"}${if (h >= 2000) " (4K!)" else ""}",
                )
            } else {
                AppLog.d("MappleProbe", "source $source offline/failed")
            }
        }

        /** The probe walked all 13 sources (or as many as answered). */
        @JavascriptInterface
        fun onMappleProbeDone(count: String) {
            mappleProbeDone = true
            lastActivityMs = now()
            AppLog.d("MappleProbe", "probe complete — $count source(s) answered")
        }
    }

    /** A subtitle listing just landed (wyzie proxy or the page's own action).
     *  The token URLs it carries are minted per-listing and ROTATE — the
     *  exact token from a user's failure log was dead 75 s after capture
     *  while a fresh listing's token for the same track answered 200 — so the
     *  site's pick is downloaded RIGHT NOW, while the token is seconds old.
     *  The body lands in [siteSubs] and ships with the stream. */
    private fun onListingCaptured(tracks: List<Wyzie.Sub>, via: String) {
        if (tracks.isEmpty()) return
        val pick = Wyzie.pickSiteTrack(tracks, prefSubLang) ?: return
        if (subFetchJob?.isActive == true) return
        AppLog.d("Subs", "listing captured ($via) — immediately downloading \"${pick.display}\" (fresh token)")
        subFetchJob = ioScope.launch {
            val dl = runCatching { Wyzie.download(pick) }.getOrNull()
            val body = dl?.let { runCatching { it.first.readText() }.getOrNull() }
            if (body != null && siteSubs.none { it.url == pick.url }) {
                siteSubs.add(SiteSubtitle(pick.url, body, coupled = true))
                AppLog.d(
                    "Subs",
                    "IMMEDIATE subtitle downloaded: ${body.length}b \"${pick.display}\" (fresh-token, site's own pick)",
                )
            } else {
                AppLog.d("Subs", "immediate download failed for \"${pick.display}\" — the in-page fetch and fresh-refetch layers still cover it")
            }
        }
    }

    /** Ask the PAGE ITSELF to fetch the subtitle track (for listings the app
     *  proxied and re-served — the page's own JS never sees them). The
     *  download then runs with the page's origin, cookies and user-agent —
     *  identical to the site's own player fetching its auto-armed track. */
    private fun fetchTrackInPage(view: WebView?, pickUrl: String) {
        val v = view ?: return
        val js = """
            (function(){
              try {
                var u = "${jsEscape(pickUrl)}";
                fetch(u).then(function(r){ return r.ok ? r.text() : null; }).then(function(t){
                  if (t && t.length > 150 &&
                      (t.split('-->').length >= 3 || t.indexOf('[Script Info]') === 0 || /^Dialogue:/m.test(t))) {
                    try { __loltube.onSiteSubtitle(u, t); } catch(e) {}
                  }
                }).catch(function(){});
              } catch(e) {}
            })();
        """.trimIndent()
        v.post { runCatching { v.evaluateJavascript(js, null) } }
    }

    private fun jsEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    /**
     * Loads [url] and waits until a video manifest is very likely captured.
     * Returns the stream candidates, the subtitle listing the page itself
     * requested (via the /wyzie proxy), and — the important one — the subtitle
     * FILE the page actually downloaded for its auto-selected language track.
     * [prefSubLang] seeds the page's stored language so the site auto-selects
     * (and downloads) the track it would pair with THIS stream copy.
     *
     * Mapple wait profile: the DIRECT source probe (injected JS that piggybacks
     * the page's own session and resolves every source's master playlist) is
     * the primary signal — a probed 4K source or a complete live table
     * resolves the harvest in ~10–30 s without waiting for playback. The
     * page's own rotation still backs it up: mapple embeds rotate up to 13
     * sources (per-source timeouts 5–25 s; worst-case ≈ 2 minutes), so the
     * harvest window stays wide (150 s series / 75 s movies) and a page that
     * actually reaches PLAYING also qualifies.
     */
    suspend fun harvest(
        url: String,
        prefSubLang: String = "en",
        timeoutMs: Long = 45_000,
        deadPageBreakMs: Long = 20_000,
        manifestGraceMs: Long = 12_000,
        requirePlaying: Boolean = false,
    ): HarvestResult {
        candidates.clear()
        siteSubs.clear()
        mappleSources.clear()
        sawFragment = false
        sawPlaying = false
        lastActivityMs = 0
        capturedSubs = emptyList()
        mappleProbeDone = false
        this.prefSubLang = prefSubLang
        try { subFetchJob?.cancel() } catch (t: Throwable) {}
        subFetchJob = null
        pageHost = android.net.Uri.parse(url).host ?: ""

        return withContext(Dispatchers.Main) {
            var wv: WebView? = null
            try {
                wv = createWebView(url, prefSubLang, requirePlaying)
                webViewRef = wv
                val start = now()
                var lastCandidateCount = 0

                withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        ensureActive()
                        delay(250)
                        val t = now()
                        if (lastActivityMs == 0L && t - start > deadPageBreakMs) break // page appears dead
                        val hasManifest = candidates.any { it.kind == "m3u8" || it.kind == "mpd" }
                        val quietMs = if (lastActivityMs == 0L) 0 else t - lastActivityMs
                        val grew = candidates.size > lastCandidateCount
                        if (grew) lastCandidateCount = candidates.size
                        // mapple: the DIRECT source probe is decisive — a probed
                        // 4K source (or a complete probe table with a live
                        // source) resolves without waiting for the page's
                        // rotation to actually hit play; playing or
                        // manifest+grace still qualify (the rotation landed).
                        // vidfast/vidcore: any manifest + traffic is decisive.
                        val probe4k = mappleSources.any { it.height >= 2000 && it.url.isNotBlank() }
                        val probeDoneLive = mappleProbeDone && mappleSources.any { it.url.isNotBlank() }
                        val earlyExit = if (requirePlaying) {
                            probe4k || probeDoneLive || sawPlaying || (hasManifest && quietMs > manifestGraceMs)
                        } else {
                            hasManifest && (sawFragment || sawPlaying || quietMs > manifestGraceMs)
                        }
                        if (earlyExit) break
                    }
                }
                // grace period: let variant playlists / fragments land, and give
                // the page's auto-selected subtitle download a bounded window —
                // the site fetches it right around manifest time, and that file
                // is the stream-coupled subtitle we want to carry into playback.
                // A listing landing late extends the window a little: the site's
                // own track download follows the listing by a couple of seconds.
                val subDeadline = now() + (if (capturedSubs.isNotEmpty()) 8_000 else 4_000)
                repeat(8) {
                    ensureActive()
                    delay(250)
                }
                while (now() < subDeadline && siteSubs.isEmpty()) {
                    ensureActive()
                    delay(250)
                }
                // the immediate subtitle download (launched the moment the
                // listing landed) gets a short bounded join so its body ships
                // with the result — but it must never hold the harvest hostage
                try {
                    kotlinx.coroutines.withTimeoutOrNull(2_500) { subFetchJob?.join() }
                } catch (t: Throwable) { /* never block on the subtitle */ }
                HarvestResult(candidates.toList(), capturedSubs, siteSubs.toList(), mappleSources.toList())
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppLog.e("Harvest", "failed: ${t.message}")
                HarvestResult(candidates.toList(), capturedSubs, siteSubs.toList(), mappleSources.toList())
            } finally {
                destroyWebView()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String, prefSubLang: String, requirePlaying: Boolean): WebView = WebView(context).apply {
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
             * The page fetches its subtitle LISTING from `{origin}/wyzie?id=…`.
             * We proxy THAT request (and only that one) through our own HTTP
             * stack so we can keep a copy of the JSON — the exact subtitles the
             * provider's own player offers, with the page's session context.
             *
             * IMPORTANT: only the listing (`/wyzie` with an `id` query) is
             * proxied. The /wyzie/{TOKEN} subtitle FILE downloads must go
             * through the WebView itself — re-serving them here used to
             * overwrite their content-type with application/json, which made
             * the fetch-sniffer reject the page's own subtitle download (the
             * site-synced capture was silently broken — the root cause of the
             * wrong-timeline subtitle bug).
             */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                val host = url.host ?: return null
                val path = url.encodedPath ?: ""
                val isListing = host == pageHost && path == "/wyzie" && url.getQueryParameter("id") != null
                if (!isListing) return null
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
                        var parsed: List<Wyzie.Sub> = emptyList()
                        runCatching {
                            parsed = Net.json.decodeFromString(
                                ListSerializer(Wyzie.Sub.serializer()), body,
                            ).filter { it.url.isNotBlank() }
                            if (parsed.isNotEmpty()) {
                                capturedSubs = parsed
                                AppLog.d("Harvest", "captured ${parsed.size} subtitle track(s) from page")
                            }
                        }
                        WebResourceResponse(
                            "application/json", "utf-8", ByteArrayInputStream(body.toByteArray()),
                        ).also {
                            // The listing was just re-served to the page — the
                            // token URLs are at their FRESHEST right now. Two
                            // immediate layers kick in:
                            //  1. app-side download of the site's own pick
                            //     (fresh token, plain HTTP — verified live)
                            //  2. the PAGE downloads the same track for us with
                            //     its own origin/cookies/UA (the exact context
                            //     the site's player uses) and the bridge hands
                            //     the body straight to the app.
                            if (parsed.isNotEmpty()) {
                                onListingCaptured(parsed, "wyzie proxy")
                                Wyzie.pickSiteTrack(parsed, prefSubLang)?.let { pick ->
                                    fetchTrackInPage(view, pick.url)
                                }
                            }
                        }
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
                // (the periodic retry inside the hook keeps nudging until the
                // page's video is actually playing — that's what makes the
                // site download its auto-selected subtitle, which we capture)
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
                WebViewCompat.addDocumentStartJavaScript(this, hookJs(prefSubLang, requirePlaying), setOf("*"))
            }
        } catch (t: Throwable) {
            AppLog.d("Harvest", "document-start injection unavailable: ${t.message}")
        }

        currentHookJs = hookJs(prefSubLang, requirePlaying)
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
         *  copy (vidfast/vidcore read localStorage "language" for auto-select).
         *  Also sniffs subtitle LISTING responses (mapple's server action) so
         *  every provider's own track menu can be replayed app-side. */
        private fun hookJs(prefLang: String, longNudges: Boolean = false): String = """
            (function(){
              // seed the site's stored language on EVERY injection — the
              // document-start context can silently fail (storage not ready),
              // and the hook guard must not stop the retry.
              try {
                if (window.localStorage) {
                  localStorage.setItem('language', '__LANG__');
                }
              } catch(e) {}
              // MAPPLE ONLY — take control of its 13-source rotation: mapple
              // persists the user's source drag-order in localStorage
              // ("mapple_source_orderv3", a JSON array of source ids). Seeding
              // it puts the 4K-confirmed sources first (s25, mapple, s2, s28,
              // s19, s16, s10, then the rest), so its rotation engine tries
              // the quality sources before the 720p ones. Also clears
              // "mapple_retry_count" — the page skips its FIRST source when
              // that counter is >= 2 (a stale retry count would silently
              // demote whatever we put first).
              try {
                if (location.hostname.indexOf('mapple') >= 0 && window.localStorage) {
                  localStorage.setItem('mapple_source_orderv3', JSON.stringify(
                    ["s25","mapple","s2","s28","s19","s16","s10","s27","s4","s12","s6","s7","s8"]
                  ));
                  localStorage.removeItem('mapple_retry_count');
                }
              } catch(e) {}
              if (window.__loltube_hooked) return;
              window.__loltube_hooked = true;
              // ===== MAPPLE DIRECT SOURCE CONTROL (the 13 sources) =====
              // Mapple's own player rotates its sources "first online wins".
              // This probe takes DIRECT control instead: it piggybacks the
              // page's own session (captured from its /api/ui/session +
              // /api/media-session responses), then resolves EVERY source's
              // encrypted stream itself (POST /api/encrypt per source ->
              // GET the encrypted URL -> fetch the master -> parse RESOLUTION)
              // and reports the full quality table to the app, which then
              // plays the BEST source instead of whatever the rotation found.
              var __mp = { requestToken: null, streamToken: null, mediaId: null, mediaType: 'movie', tv_slug: '', started: false, ownTried: false, bootTries: 0 };
              try {
                var __seg = location.pathname.split('/').filter(Boolean);
                var __last = __seg.length ? __seg[__seg.length - 1] : '';
                var __mtv = __last.match(/^(\d+)-(\d+)-(\d+)$/);
                var __mmv = __last.match(/^(\d+)$/);
                if (__mtv) { __mp.mediaId = parseInt(__mtv[1], 10); __mp.mediaType = 'tv'; __mp.tv_slug = __mtv[2] + '-' + __mtv[3]; }
                else if (__mmv) { __mp.mediaId = parseInt(__mmv[1], 10); __mp.mediaType = 'movie'; __mp.tv_slug = ''; }
              } catch(e) {}
              function __mpReport(s, h, url) {
                try { __loltube.onMappleSource(s, String(h), url || ''); } catch(e) {}
              }
              function __mpStart() {
                if (__mp.started) return;
                if (!__mp.requestToken || !__mp.streamToken || __mp.mediaId == null) return;
                __mp.started = true;
                var SOURCES = ["s25","mapple","s2","s28","s19","s16","s10","s27","s4","s12","s6","s7","s8"];
                var CONC = 3;
                function probeOne(s) {
                  return fetch('/api/encrypt', {
                    method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ data: { mediaId: __mp.mediaId, mediaType: __mp.mediaType, tv_slug: __mp.tv_slug, source: s }, endpoint: 'stream-encrypted', requestToken: __mp.requestToken })
                  }).then(function(r){ return r.ok ? r.json() : null; }).then(function(enc){
                    if (!enc || !enc.url) { __mpReport(s, -1, ''); return null; }
                    var u2 = enc.url + (enc.url.indexOf('?') >= 0 ? '&' : '?') +
                             'requestToken=' + encodeURIComponent(__mp.requestToken) +
                             '&token=' + encodeURIComponent(__mp.streamToken);
                    return fetch(u2, { credentials: 'include' }).then(function(r){ return r.ok ? r.json() : null; });
                  }).then(function(sr){
                    if (!sr || !sr.success || !sr.data || !sr.data.stream_url) { __mpReport(s, -1, ''); return null; }
                    var su = sr.data.stream_url;
                    if ((sr.data.format || '').toLowerCase() === 'mp4' || /\.mp4($|\?)/i.test(su)) { __mpReport(s, 0, su); return null; }
                    return fetch(su).then(function(r){ return r.ok ? r.text() : null; }).then(function(txt){
                      var h = 0;
                      if (txt) { var re = /RESOLUTION=(\d+)x(\d+)/g, mm; while ((mm = re.exec(txt))) { h = Math.max(h, parseInt(mm[2], 10)); } }
                      __mpReport(s, txt ? h : -1, txt ? su : '');
                    });
                  }).catch(function(){ __mpReport(s, -1, ''); });
                }
                var idx = 0, active = 0, done = 0;
                function next() {
                  if (idx >= SOURCES.length && active === 0) {
                    try { __loltube.onMappleProbeDone(String(done)); } catch(e) {}
                    return;
                  }
                  while (idx < SOURCES.length && active < CONC) {
                    (function(s){
                      active++;
                      probeOne(s).then(function(){ active--; done++; next(); }, function(){ active--; done++; next(); });
                    })(SOURCES[idx++]);
                  }
                }
                next();
              }
              function __mpSolvePow(pow) {
                return new Promise(function(res){
                  var settled = false;
                  function fin(v){ if (!settled) { settled = true; res(v); } }
                  try {
                    var w = new Worker('/pow-worker.js');
                    var to = setTimeout(function(){ try { w.terminate(); } catch(e1) {} fin(null); }, 9000);
                    w.onmessage = function(e){ clearTimeout(to); try { w.terminate(); } catch(e2) {} var d = e && e.data; fin(d && d.found ? d.nonce : null); };
                    w.onerror = function(){ clearTimeout(to); try { w.terminate(); } catch(e3) {} fin(null); };
                    w.postMessage({ challenge: pow.challenge, difficulty: pow.difficulty });
                  } catch(e) { fin(null); }
                });
              }
              // bootstrap watcher: the page seeds __REQUEST_TOKEN_BOOTSTRAP__
              // itself; if its own media-session call wasn't visible, make our
              // own (with the PoW worker) after ~20 s.
              if (location.hostname.indexOf('mapple') >= 0) {
                var __mpTimer = setInterval(function(){
                  try {
                    if (__mp.started) { clearInterval(__mpTimer); return; }
                    if (!__mp.requestToken && window.__REQUEST_TOKEN_BOOTSTRAP__ && window.__REQUEST_TOKEN_BOOTSTRAP__.then) {
                      window.__REQUEST_TOKEN_BOOTSTRAP__.then(function(s){
                        if (s && s.token && !__mp.requestToken) { __mp.requestToken = s.token; __mpStart(); }
                      }).catch(function(){});
                    }
                    if (!__mp.streamToken && __mp.requestToken && __mp.mediaId != null && !__mp.ownTried && __mp.bootTries > 13) {
                      __mp.ownTried = true;
                      fetch('/api/media-session', {
                        method: 'POST', credentials: 'same-origin',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ mediaId: __mp.mediaId, mediaType: __mp.mediaType, tv_slug: __mp.tv_slug, requestToken: __mp.requestToken })
                      }).then(function(r){ return r.ok ? r.json() : null; }).then(function(st){
                        if (st && st.token) { __mp.streamToken = st.token; __mpStart(); return; }
                        if (st && st.requiresPow && st.pow) {
                          __mpSolvePow(st.pow).then(function(nonce){
                            if (nonce == null) return;
                            fetch('/api/media-session', {
                              method: 'POST', credentials: 'same-origin',
                              headers: { 'Content-Type': 'application/json' },
                              body: JSON.stringify({ mediaId: __mp.mediaId, mediaType: __mp.mediaType, tv_slug: __mp.tv_slug, requestToken: __mp.requestToken, pow: { challengeId: st.pow.challengeId, nonce: nonce } })
                            }).then(function(r){ return r.ok ? r.json() : null; }).then(function(st2){
                              if (st2 && st2.token) { __mp.streamToken = st2.token; __mpStart(); }
                            }).catch(function(){});
                          });
                        }
                      }).catch(function(){});
                    }
                    __mpStart();
                  } catch(e) {}
                  if (++__mp.bootTries > 120) clearInterval(__mpTimer);
                }, 1500);
              }
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
              // keep nudging the page's video to actually PLAY (muted): the
              // site downloads its auto-selected subtitle around playback —
              // that file is the stream-coupled subtitle we want to capture.
              // Long mode (mapple): the source rotation needs minutes, so the
              // nudge keeps pressing play the whole window.
              var nudges = 0;
              try {
                var nudgeTimer = setInterval(function(){
                  try {
                    document.querySelectorAll('video').forEach(function(v){
                      v.muted = true; v.volume = 0;
                      var p = v.play(); if (p && p.catch) p.catch(function(){});
                    });
                    if (++nudges >= __NUDGES__) clearInterval(nudgeTimer);
                  } catch(e) {}
                }, 1500);
              } catch(e) {}
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
                    // piggyback the page's own session tokens for the mapple
                    // source probe (request body of media-session also carries
                    // mediaId/mediaType/tv_slug — the ground truth params)
                    if (__mp && /^https?:/i.test(u) &&
                        (u.indexOf('/api/media-session') >= 0 || u.indexOf('/api/ui/session') >= 0)) {
                      if (u.indexOf('/api/media-session') >= 0) {
                        try {
                          var ib = (init && typeof init.body === 'string') ? init.body : '';
                          if (ib) { var pj = JSON.parse(ib); if (pj) { if (pj.mediaId != null) __mp.mediaId = pj.mediaId; if (pj.mediaType) __mp.mediaType = pj.mediaType; if (pj.tv_slug != null) __mp.tv_slug = pj.tv_slug; } }
                        } catch(e1) {}
                      }
                      pr.then(function(r){
                        try {
                          r.clone().text().then(function(t){
                            try { var sj = JSON.parse(t); if (sj && sj.token) { if (u.indexOf('/api/media-session') >= 0) __mp.streamToken = sj.token; else __mp.requestToken = sj.token; __mpStart(); } } catch(e2) {}
                          }).catch(function(){});
                        } catch(e3) {}
                      }).catch(function(){});
                    }
                  } catch(e0) {}
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
                                         /octet-stream|subrip|x-subrip|force-download|binary\/octet/i.test(ct) ||
                                         /\.(vtt|srt|ssa|ass)(\?|#|$)/i.test(u);
                          var looksListing = !/^(video|audio|image|font)\//i.test(ct) &&
                                             !looksSub;
                          if (looksSub || looksListing) {
                            resp.clone().text().then(function(t){
                              try {
                                if (!t || t.length < 20 || t.length > 2000000) return;
                                if (t.indexOf('-->') > 0 && t.split('-->').length >= 3) {
                                  __loltube.onSiteSubtitle(u, t);
                                  return;
                                }
                                // ASS/SSA subtitle (series tracks are often
                                // full SubStation Alpha scripts — no '-->')
                                if (t.indexOf('[Script Info]') === 0 || /^Dialogue:/m.test(t)) {
                                  __loltube.onSiteSubtitle(u, t);
                                  return;
                                }
                                // subtitle LISTING (mapple's server action / any
                                // track-array JSON) — the site's own menu
                                if (t.indexOf('"url"') > 0 &&
                                    (t.indexOf('"lang"') > 0 || t.indexOf('"language"') > 0 ||
                                     t.indexOf('"label"') > 0 || t.indexOf('"display"') > 0)) {
                                  __loltube.onSiteSubListing(u, t);
                                  // AND download the preferred track right here IN
                                  // the page — the page's own origin/cookies/UA
                                  // (the exact context the site's player uses)
                                  // and the freshest possible token. This is the
                                  // series-subtitle fix for listings the app
                                  // only SEES (mapple's server action): no
                                  // dependency on the page's own auto-arm
                                  // timing or a later app-side download.
                                  try {
                                    var arr = [];
                                    try { arr = JSON.parse(t); } catch(pe) {}
                                    if (!arr || !arr.length) { var po = t.indexOf('['); if (po >= 0) { try { arr = JSON.parse(t.slice(po)); } catch(pe2) {} } }
                                    if (arr && arr.length) {
                                      var prefL = ('__LANG__').toLowerCase();
                                      function isPrefTrack(x){
                                        var l = String(x.lang || x.language || '').toLowerCase();
                                        var d = String(x.label || x.display || '').toLowerCase();
                                        return l === prefL || l.indexOf(prefL) === 0 || d.indexOf(prefL) === 0 ||
                                               (prefL === 'en' && (l === 'english' || d.indexOf('english') === 0));
                                      }
                                      var pk = null;
                                      for (var ai = 0; ai < arr.length; ai++) { var a1 = arr[ai]; if (a1 && a1.url && isPrefTrack(a1)) { pk = a1; break; } }
                                      if (!pk) { for (var aj = 0; aj < arr.length; aj++) { var a2 = arr[aj]; if (a2 && a2.url) { var l2 = String(a2.lang || a2.language || '').toLowerCase(); var d2 = String(a2.label || a2.display || '').toLowerCase(); if (l2 === 'en' || d2.indexOf('english') === 0) { pk = a2; break; } } } }
                                      if (pk && pk.url) {
                                        fetch(pk.url).then(function(r){ return r.ok ? r.text() : null; }).then(function(st){
                                          if (st && st.length > 150 && (st.split('-->').length >= 3 || st.indexOf('[Script Info]') === 0 || /^Dialogue:/m.test(st))) {
                                            try { __loltube.onSiteSubtitle(pk.url, st); } catch(se) {}
                                          }
                                        }).catch(function(){});
                                      }
                                    }
                                  } catch(le) {}
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
        """.trimIndent()
            .replace("__LANG__", prefLang.take(8).replace("'", ""))
            .replace("__NUDGES__", if (longNudges) "90" else "25")
    }
}
