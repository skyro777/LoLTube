package com.loltube.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.loltube.app.util.AppLog
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The anti-buffering pipeline.
 *
 *  1. [PlaybackCache]     - up to an 800 MB LRU disk cache holding HLS segments.
 *  2. [TurboPrefetcher]   - watches what the player loads and downloads ahead of
 *                           the playhead with FOUR parallel workers, up to 4
 *                           minutes of media, straight into the cache. ExoPlayer
 *                           itself pulls segments one-by-one over a single
 *                           connection; the player's RAM buffer is capped at
 *                           30-120s by profile, but the DISK flywheel keeps
 *                           filling beyond it. Every segment the player needs is
 *                           then a local disk read (instant) instead of a network
 *                           round-trip — that is what actually removes the
 *                           rebuffering, especially for 4K (16-40 Mbps).
 *  3. [CapPolicy] + [ManifestCapInterceptor] - when the user caps quality, the
 *                           master playlist itself is rewritten (variants above
 *                           the cap are removed) so no oversized variant can
 *                           ever be selected.
 */
@UnstableApi
object PlaybackCache {
    private const val TAG = "PlaybackCache"
    private const val MIN_BYTES = 250L * 1024 * 1024
    private const val MAX_BYTES = 800L * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val dir = File(app.cacheDir, "media_cache")
                val budget = diskBudget(dir)
                AppLog.d(TAG, "opening segment cache at ${dir.absolutePath} (budget ${budget / (1024 * 1024)} MB)")
                SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(budget),
                    StandaloneDatabaseProvider(app),
                ).also { instance = it }
            }
        }
    }

    /** As much as useful, but never more than ~35% of free disk (min 250 MB, max 800 MB). */
    private fun diskBudget(dir: File): Long = runCatching {
        dir.mkdirs()
        val free = android.os.StatFs(dir.absolutePath).availableBytes
        val byFree = (free * 0.35f).toLong()
        maxOf(MIN_BYTES, minOf(MAX_BYTES, byFree))
    }.getOrDefault(400L * 1024 * 1024)
}

/** Current resolution cap; also read by [ManifestCapInterceptor]. */
object CapPolicy {
    @Volatile
    var maxVideoHeight: Int = 2160
}

/**
 * OkHttp interceptor that rewrites HLS *master* playlists on the fly: every
 * `#EXT-X-STREAM-INF` variant taller than the current cap is dropped together
 * with its URI line. Media playlists pass through untouched. Only active when
 * a cap below 2160 is set (AUTO / MAX modes stream the manifest unmodified).
 */
class ManifestCapInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val cap = CapPolicy.maxVideoHeight
        if (cap >= 2160 || !response.isSuccessful) return response

        val path = request.url.encodedPath
        val contentTypeHeader = response.header("Content-Type") ?: ""
        val looksLikeM3u8 = path.contains(".m3u8", ignoreCase = true) ||
            contentTypeHeader.contains("mpegurl", ignoreCase = true)
        if (!looksLikeM3u8) return response

        val body = response.body ?: return response
        val playlist = runCatching { body.string() }.getOrNull() ?: return response
        val rebuilt: ByteArray = if (playlist.startsWith("#EXTM3U") && playlist.contains("#EXT-X-STREAM-INF")) {
            filterMaster(playlist, cap)?.toByteArray(Charsets.UTF_8) ?: playlist.toByteArray(Charsets.UTF_8)
        } else {
            playlist.toByteArray(Charsets.UTF_8)
        }

        return response.newBuilder()
            .body(rebuilt.toResponseBody(M3U8_MEDIA_TYPE))
            .header("Content-Type", "application/vnd.apple.mpegurl")
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .build()
    }

    /** Returns null when filtering would remove every variant (keep the original). */
    private fun filterMaster(playlist: String, cap: Int): String? {
        val out = StringBuilder()
        var dropNextUri = false
        var droppedCount = 0
        var keptCount = 0

        playlist.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val h = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)?.groupValues?.get(2)?.toIntOrNull()
                    if (h != null && h > cap) {
                        dropNextUri = true
                        droppedCount++
                        return@forEach
                    }
                    keptCount++
                    out.appendLine(raw)
                }

                dropNextUri && line.isNotEmpty() && !line.startsWith("#") -> {
                    dropNextUri = false // swallow the variant's URI line
                }

                line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> {
                    val h = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)?.groupValues?.get(2)?.toIntOrNull()
                    if (h != null && h > cap) {
                        droppedCount++
                        return@forEach
                    }
                    out.appendLine(raw)
                }

                else -> out.appendLine(raw)
            }
        }
        if (keptCount == 0) return null
        AppLog.d("CapFilter", "master playlist: dropped $droppedCount variant(s) above ${cap}p")
        return out.toString()
    }

    private companion object {
        private val M3U8_MEDIA_TYPE = "application/vnd.apple.mpegurl".toMediaType()
    }
}

/**
 * Parallel segment read-ahead — the disk "flywheel".
 *
 * Attached to the player as an AnalyticsListener:
 *  - when a *media playlist* finishes loading it is read back from the cache and
 *    parsed into a segment list;
 *  - whenever a segment finishes loading (and on a 3s heartbeat), the NEXT
 *    segments — up to [READ_AHEAD_MS] of media ahead of the playhead — are
 *    downloaded in parallel by 4 workers directly into the SimpleCache, so the
 *    player hits the disk instead of the network for them;
 *  - a segment the player itself is currently fetching is never touched (the
 *    prefetch starts at index+1), so the player's own single connection keeps
 *    streaming undisturbed while the workers open 4 MORE connections to the
 *    same CDN (per-host limit was raised to 16 for exactly this).
 *
 * All callbacks are exception-proof: they run on the player's playback thread,
 * and any uncaught RuntimeException there is a fatal
 * ERROR_CODE_FAILED_RUNTIME_CHECK (the crash the user saw).
 */
@UnstableApi
class TurboPrefetcher(
    private val cache: SimpleCache,
    cacheDataSourceFactory: CacheDataSource.Factory,
    private val enabled: () -> Boolean,
    private val bufferAheadMs: () -> Long,
    private val positionMs: () -> Long,
) : AnalyticsListener {

    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "loltube-prefetch").apply { isDaemon = true }
    }
    private val heartbeat: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "loltube-prefetch-hb").apply { isDaemon = true }
        }
    private val cacheFactory: CacheDataSource.Factory = cacheDataSourceFactory
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val done: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var segments: List<String> = emptyList()

    @Volatile
    private var segDurationsMs: List<Double> = emptyList()

    @Volatile
    private var stopped = false

    init {
        heartbeat.scheduleAtFixedRate(
            {
                try {
                    if (!stopped && segments.isNotEmpty()) scheduleFrom(indexForPosition() + 1)
                } catch (_: Throwable) {
                }
            },
            3, 3, TimeUnit.SECONDS,
        )
    }

    // Playback-thread callbacks: NEVER let an exception escape.
    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        try {
            if (stopped) return
            when (mediaLoadData.dataType) {
                C.DATA_TYPE_MANIFEST -> onManifestLoaded(loadEventInfo.dataSpec.uri.toString())
                C.DATA_TYPE_MEDIA -> onSegmentLoaded(loadEventInfo.uri.toString())
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "onLoadCompleted guard", t)
        }
    }

    // ---- manifest handling ---------------------------------------------------

    private fun onManifestLoaded(url: String) {
        if (!url.contains(".m3u8", ignoreCase = true)) return // only HLS
        tryExecute {
            val text = readViaCache(url) ?: return@tryExecute
            if (text.contains("#EXTINF")) parseMediaPlaylist(text, url)
        }
    }

    private fun tryExecute(body: () -> Unit) {
        try {
            executor.execute {
                if (stopped) return@execute
                try {
                    body()
                } catch (t: Throwable) {
                    AppLog.d(TAG, "prefetch step failed: ${t.message?.take(60)}")
                }
            }
        } catch (_: Throwable) {
            // pool already shut down during a race with stop()
        }
    }

    private fun readViaCache(url: String): String? = runCatching {
        val ds = cacheFactory.createDataSource()
        try {
            ds.open(DataSpec(Uri.parse(url)))
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = ds.read(buf, 0, buf.size)
                if (r == C.RESULT_END_OF_INPUT) break
                if (r > 0) out.write(buf, 0, r)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            runCatching { ds.close() }
        }
    }.getOrNull()

    private fun parseMediaPlaylist(text: String, playlistUrl: String) {
        val urls = mutableListOf<String>()
        val durations = mutableListOf<Double>()
        val base = runCatching { URI(playlistUrl) }.getOrNull() ?: return

        var pendingDurMs = 6000.0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:")) {
                    pendingDurMs = (line.substringAfter(":").substringBefore(",")
                        .toDoubleOrNull() ?: 6.0) * 1000.0
                } else if (line.startsWith("#EXT-X-MAP:")) {
                    Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let { mapUri ->
                        resolve(base, mapUri)?.let {
                            urls.add(it)
                            durations.add(500.0)
                        }
                    }
                }
                return@forEach
            }
            resolve(base, line)?.let {
                urls.add(it)
                durations.add(pendingDurMs)
            }
            pendingDurMs = 6000.0
        }
        if (urls.isEmpty()) return

        segments = urls
        segDurationsMs = durations
        done.clear()
        inFlight.clear()

        // startup boost: warm the following segments while the player still
        // downloads its first one (start at 1 - segment 0 belongs to the player's
        // own loader; two concurrent cache writers on the same key would block).
        if (positionMs() < 20_000L) {
            AppLog.d(TAG, "playlist parsed: ${urls.size} segments, warming ahead of the playhead")
            scheduleFrom(1)
        } else {
            scheduleFrom(indexForPosition() + 1)
        }
    }

    private fun resolve(base: URI, ref: String): String? = runCatching {
        when {
            ref.startsWith("http://") || ref.startsWith("https://") -> ref
            else -> base.resolve(ref).toString()
        }
    }.getOrNull()

    // ---- segment handling ----------------------------------------------------

    private fun onSegmentLoaded(url: String) {
        val segs = segments
        if (segs.isEmpty()) return
        val idx = segs.indexOf(url)
        if (idx >= 0) scheduleFrom(idx + 1)
    }

    /** Media-time index of the current playhead in the parsed segment list. */
    private fun indexForPosition(): Int {
        val pos = positionMs()
        val durs = segDurationsMs
        if (durs.isEmpty()) return 0
        var t = 0.0
        for (i in durs.indices) {
            t += durs[i]
            if (t > pos) return i
        }
        return durs.size
    }

    private fun scheduleFrom(startIndex: Int) {
        if (stopped || !enabled()) return
        val segs = segments
        val durs = segDurationsMs
        var msAhead = 0.0
        var scheduled = 0
        var i = startIndex.coerceIn(0, segs.size)
        while (i < segs.size && scheduled < MAX_INFLIGHT) {
            val u = segs[i]
            msAhead += durs.getOrElse(i) { 6000.0 }
            if (i > startIndex && msAhead > READ_AHEAD_MS) break
            if (!done.contains(u) && !inFlight.contains(u)) {
                inFlight.add(u)
                scheduled++
                try {
                    executor.execute { doPrefetch(u) }
                } catch (t: Throwable) {
                    inFlight.remove(u)
                }
            }
            i++
        }
    }

    private fun doPrefetch(url: String) {
        var ds: DataSource? = null
        try {
            ds = cacheFactory.createDataSource()
            ds.open(DataSpec(Uri.parse(url)))
            val buf = ByteArray(64 * 1024)
            while (!stopped) {
                val r = ds.read(buf, 0, buf.size)
                if (r == C.RESULT_END_OF_INPUT) break
            }
        } catch (t: Throwable) {
            AppLog.d(TAG, "prefetch miss ${url.take(80)}: ${t.message?.take(50)}")
        } finally {
            runCatching { ds?.close() }
            inFlight.remove(url)
            done.add(url)
        }
    }

    fun stop() {
        stopped = true
        segments = emptyList()
        segDurationsMs = emptyList()
        try {
            executor.shutdownNow()
        } catch (_: Throwable) {
        }
        try {
            heartbeat.shutdownNow()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        private const val TAG = "TurboPrefetch"
        /** How much media to keep warm in the disk cache ahead of the playhead. */
        private const val READ_AHEAD_MS = 240_000.0 // 4 minutes
        /** Max simultaneously scheduled prefetch tasks (4 run in parallel). */
        private const val MAX_INFLIGHT = 12
    }
}
