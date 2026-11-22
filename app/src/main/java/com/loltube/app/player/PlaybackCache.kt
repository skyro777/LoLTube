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

/**
 * The anti-buffering pipeline.
 *
 *  1. [PlaybackCache]     - a 400 MB LRU disk cache holding HLS segments.
 *  2. [TurboPrefetcher]   - watches what the player loads and downloads the NEXT
 *                           segments in parallel (3 workers) straight into the
 *                           cache, so the player reads them from disk instantly.
 *                           ExoPlayer normally pulls segments one-by-one over a
 *                           single connection; this turns the first 60-90s of
 *                           the buffer into 3 parallel downloads.
 *  3. [CapPolicy] + [ManifestCapInterceptor] - when the user caps quality, the
 *                           master playlist itself is rewritten (variants above
 *                           the cap are removed) so no oversized variant can
 *                           ever be selected.
 */
@UnstableApi
object PlaybackCache {
    private const val TAG = "PlaybackCache"
    private const val MAX_BYTES = 400L * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return instance ?: synchronized(this) {
            instance ?: run {
                val dir = File(context.applicationContext.cacheDir, "media_cache")
                AppLog.d(TAG, "opening segment cache at ${dir.absolutePath}")
                SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(context.applicationContext),
                ).also { instance = it }
            }
        }
    }
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
 * Parallel segment read-ahead. Attached to the player as an AnalyticsListener:
 *  - when a *media playlist* finishes loading it is read back from the cache and
 *    parsed into a segment list;
 *  - whenever a segment finishes loading, the following ~10 segments (up to 90s
 *    of media) are downloaded in parallel by 3 workers directly into the
 *    SimpleCache - the player then hits the cache for them;
 *  - throttles itself off when the player already has > 50s buffered.
 */
@UnstableApi
class TurboPrefetcher(
    private val cache: SimpleCache,
    cacheDataSourceFactory: CacheDataSource.Factory,
    private val enabled: () -> Boolean,
    private val bufferAheadMs: () -> Long,
    private val positionMs: () -> Long,
) : AnalyticsListener {

    private val executor: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "loltube-prefetch").apply { isDaemon = true }
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

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        if (stopped) return
        when (mediaLoadData.dataType) {
            C.DATA_TYPE_MANIFEST -> onManifestLoaded(loadEventInfo.dataSpec.uri.toString())
            C.DATA_TYPE_MEDIA -> onSegmentLoaded(loadEventInfo.uri.toString())
        }
    }

    // ---- manifest handling ---------------------------------------------------

    private fun onManifestLoaded(url: String) {
        if (!url.contains(".m3u8", ignoreCase = true)) return // only HLS
        executor.execute {
            if (stopped) return@execute
            val text = readViaCache(url) ?: return@execute
            if (text.contains("#EXTINF")) parseMediaPlaylist(text, url)
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

    private fun scheduleFrom(startIndex: Int) {
        if (stopped || !enabled()) return
        if (bufferAheadMs() > 50_000L) return
        val segs = segments
        val durs = segDurationsMs
        var msAhead = 0.0
        var scheduled = 0
        var i = startIndex.coerceIn(0, segs.size)
        while (i < segs.size && scheduled < 10) {
            val u = segs[i]
            msAhead += durs.getOrElse(i) { 6000.0 }
            if (i > startIndex && msAhead > 90_000.0) break
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
        executor.shutdownNow()
    }

    private companion object {
        private const val TAG = "TurboPrefetch"
    }
}
