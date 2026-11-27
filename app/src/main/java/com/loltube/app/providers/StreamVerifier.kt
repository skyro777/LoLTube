package com.loltube.app.providers

import com.loltube.app.data.Net
import com.loltube.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Verifies harvested URLs natively (with provider headers) and classifies them.
 * A "master" m3u8 (multi-variant, quality selectable) beats a plain media playlist.
 */
object StreamVerifier {

    data class Verified(val url: String, val kind: String)

    /** Rank: master playlist > dash manifest > media playlist > mp4 */
    private fun rank(kind: String): Int = when (kind) {
        "m3u8-master" -> 0
        "mpd" -> 1
        "m3u8" -> 2
        "mp4" -> 3
        else -> 9
    }

    /**
     * Tries candidates (best-ranked first) until one responds with a real
     * media payload. Returns null if nothing works.
     *
     * Within the same rank the NEWEST capture is tried first: CDNs mint a new
     * signed URL per fetch (the old token is stale the moment the page rotated
     * sources), and mapple's source-rotation means the LAST captured manifest
     * is the one from the source that actually works — the first one is often
     * a dead source the page already rotated away from.
     */
    suspend fun pickBest(
        candidates: List<StreamCandidate>,
        headers: Map<String, String>,
        providerTag: String,
    ): Verified? = withContext(Dispatchers.IO) {
        val ordered = candidates
            .filter { it.kind == "m3u8" || it.kind == "mpd" || it.kind == "mp4" }
            .sortedWith(compareBy({ rank(adjustedKind(it)) }, { -it.capturedAtMs }))

        for (c in ordered) {
            ensureActive()
            val result = runCatching { verify(c.url, headers) }.getOrNull()
            when {
                result != null -> {
                    AppLog.d("Verify", "[$providerTag] OK ${result.kind}: ${c.url.take(140)}")
                    return@withContext Verified(result.url, result.kind)
                }
                else -> AppLog.d("Verify", "[$providerTag] rejected ${c.url.take(140)}")
            }
        }
        null
    }

    private fun adjustedKind(c: StreamCandidate): String = when (c.kind) {
        "m3u8" -> if (c.url.contains("master", ignoreCase = true) ||
            c.url.contains("manifest", ignoreCase = true)) "m3u8-master" else "m3u8"
        else -> c.kind
    }

    /** Fetch the first bytes and classify the payload. */
    private fun verify(url: String, headers: Map<String, String>): Verified? {
        val bytes = try {
            Net.getBytes(url, headers, limit = 8192)
        } catch (e: Exception) {
            // some servers reject Range on manifests; retry plain GET
            try {
                Net.getBytesPlain(url, headers, limit = 8192)
            } catch (e2: Exception) {
                null
            }
        }
        if (bytes == null || bytes.isEmpty()) return null

        // MP4: bytes 4..8 == "ftyp"
        if (bytes.size > 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        ) {
            // could still be a DASH init segment (.m4s); accept as mp4
            return Verified(url, "mp4")
        }

        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        return when {
            head.contains("#EXTM3U") ->
                if (head.contains("#EXT-X-STREAM-INF")) Verified(url, "m3u8-master")
                else Verified(url, "m3u8")

            head.contains("<MPD") -> Verified(url, "mpd")
            else -> null
        }
    }
}
