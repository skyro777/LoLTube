package com.loltube.app.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.loltube.app.util.AppLog
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File

/**
 * The anti-buffering pipeline.
 *
 *  1. [PlaybackCache]     - up to an 800 MB LRU disk cache holding HLS segments.
 *                           Every segment the player reads goes through it, so
 *                           replays/seek-backs are local disk reads.
 *                           (The old "turbo prefetch" 4-worker parallel
 *                           read-ahead was REMOVED in v0.8.0: the extra
 *                           connections + concurrent cache writes stalled
 *                           playback on all three sources — the exact opposite
 *                           of what it was supposed to do. The single adaptive
 *                           pipeline with the Media3 RAM buffer is what
 *                           actually sustains 4K without buffering.)
 *  2. [CapPolicy] + [ManifestCapInterceptor] - when the user caps quality, the
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

    /**
     * Release the SimpleCache and DELETE its content — the storage janitor.
     * Called when the app closes (never while playing: the player keeps its
     * own reference for the whole session, so this only ever runs from the
     * settings "Clear caches now" button or app-exit paths).
     */
    fun wipe() {
        synchronized(this) {
            val c = instance
            instance = null
            runCatching { c?.release() }
        }
        runCatching {
            File(com.loltube.app.LoLTubeApp.instance.cacheDir, "media_cache").deleteRecursively()
            AppLog.d(TAG, "media cache wiped")
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
