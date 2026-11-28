package com.loltube.app.data

import com.loltube.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Wyzie subtitles - token-free subtitle listing exposed by the vidfast player.
 * Verified live: GET https://vidfast.vc/wyzie?id={imdb}&season=&episode=
 */
object Wyzie {
    @Serializable
    data class Sub(
        val display: String = "",
        val language: String? = null,
        val url: String = "",
        val encoding: String? = null,
        val isHearingImpaired: Boolean? = null,
    )

    private val ORIGINS = listOf("https://vidfast.vc", "https://vidcore.io")

    suspend fun fetch(imdbId: String, season: Int? = null, episode: Int? = null): List<Sub> =
        withContext(Dispatchers.IO) {
            for (origin in ORIGINS) {
                // referer first (matches the browser), then without (the endpoint is
                // documented to be hot-link agnostic - belt and braces)
                val result = runCatching {
                    val url = buildString {
                        append(origin).append("/wyzie?id=").append(imdbId)
                        season?.let { append("&season=").append(it) }
                        episode?.let { append("&episode=").append(it) }
                    }
                    val body = Net.get(url, headers = mapOf("Referer" to "$origin/"))
                    Net.json.decodeFromString(ListSerializer(Sub.serializer()), body)
                        .filter { it.url.isNotBlank() }
                }.onFailure {
                    AppLog.d("Wyzie", "$origin with referer failed: ${it.message}")
                }.getOrNull()
                    ?: runCatching {
                        val url = buildString {
                            append(origin).append("/wyzie?id=").append(imdbId)
                            season?.let { append("&season=").append(it) }
                            episode?.let { append("&episode=").append(it) }
                        }
                        val body = Net.get(url)
                        Net.json.decodeFromString(ListSerializer(Sub.serializer()), body)
                            .filter { it.url.isNotBlank() }
                    }.onFailure {
                        AppLog.d("Wyzie", "$origin no-referer failed: ${it.message}")
                    }.getOrNull()
                if (!result.isNullOrEmpty()) return@withContext result
            }
            emptyList()
        }

    /** The SITES' OWN selection algorithm (verified from the vidfast/vidcore
     *  bundles): sort the listing UTF-8-encoding-first, non-HI-before-HI, then
     *  arm the FIRST track of the stored language, English as the fallback.
     *  Shared by the harvest-time immediate download, the in-page pick and the
     *  pre-attach path so every layer arms the exact track the site would. */
    fun siteOrdered(pool: List<Sub>, prefLang: String): List<Sub> {
        val pref = prefLang.lowercase()
        val sorted = pool.sortedWith(
            compareBy<Sub>(
                { (it.encoding ?: "").uppercase() != "UTF-8" }, // UTF-8 first
                { it.isHearingImpaired == true }, // non-HI first
            )
        )
        val langMatches = sorted.filter { s ->
            s.language?.lowercase() == pref || s.language?.lowercase()?.startsWith(pref) == true ||
                s.display.lowercase().startsWith(pref)
        }
        val englishFallback = sorted.filter { s ->
            s.language?.lowercase() == "en" || s.display.contains("english", true)
        }
        return (langMatches + englishFallback).distinctBy { it.url }
    }

    /** The first track the site's own player would arm for this language. */
    fun pickSiteTrack(pool: List<Sub>, prefLang: String): Sub? =
        siteOrdered(pool, prefLang).firstOrNull()

    /**
     * FRESH-LISTING subtitle: re-fetches the listing and immediately downloads
     * the site's own pick. Why this exists (verified live): the /wyzie token
     * URLs are short-lived and rotate server-side — the token captured during
     * the harvest can be DEAD seconds later at attach time (the exact token
     * from a user's failure log returned 404 on re-test while a FRESH
     * listing's token for the same track returned 200). A fresh listing + an
     * immediate download is always inside the validity window.
     * Returns (sub, body, mime) or null.
     */
    suspend fun freshBestSub(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        prefLang: String = "en",
    ): Triple<Sub, String, String>? = withContext(Dispatchers.IO) {
        val listing = fetch(imdbId, season, episode)
        if (listing.isEmpty()) return@withContext null
        val pick = pickSiteTrack(listing, prefLang) ?: return@withContext null
        val body = runCatching {
            Net.get(pick.url, headers = mapOf("Accept" to "*/*"))
        }.getOrNull() ?: return@withContext null
        if (!looksLikeSubtitle(body)) return@withContext null
        val mime = when {
            looksLikeAss(body) -> "application/x-ssa"
            body.trimStart().startsWith("WEBVTT", ignoreCase = true) -> "text/vtt"
            else -> "application/x-subrip"
        }
        AppLog.d("Wyzie", "fresh-listing subtitle "${pick.display}" downloaded (${body.length} chars, $mime)")
        Triple(pick, body, mime)
    }

    /** Download a subtitle and persist to a cache file; returns (file, mime).
     *  Retries with several Referer strategies since the subtitle hosts' hot-link
     *  rules vary. Also validates the payload actually looks like a subtitle. */
    suspend fun download(sub: Sub): Pair<java.io.File, String>? = withContext(Dispatchers.IO) {
        val referers = listOfNotNull(
            sub.url.takeIf { it.startsWith("http") }?.let { "https://${android.net.Uri.parse(it).host}/" },
            // mapple's own track URLs are served under its origins
            "https://mappletv.uk/",
            "https://mapple.fun/",
            "https://vidfast.vc/",
            "https://vidcore.io/",
            "", // no referer at all
        ).distinct()

        var lastStatus = 0
        for (referer in referers) {
            val body = (try {
                Net.get(sub.url, headers = buildMap {
                    put("Accept", "*/*")
                    if (referer.isNotEmpty()) put("Referer", referer)
                })
            } catch (e: Net.HttpException) {
                lastStatus = e.code
                null
            } catch (e: Throwable) {
                null
            }) ?: continue
            if (!looksLikeSubtitle(body)) continue
            // ASS scripts are common for series (a full SubStation Alpha file,
            // no `-->` timeline) — they need their own mime so a sideloaded
            // MediaItem is parsed by the right text decoder.
            val isAss = looksLikeAss(body)
            val mime = when {
                isAss -> "application/x-ssa"
                body.trimStart().startsWith("WEBVTT", ignoreCase = true) -> "text/vtt"
                else -> "application/x-subrip"
            }
            val ext = when (mime) {
                "text/vtt" -> "vtt"
                "application/x-ssa" -> "ass"
                else -> "srt"
            }
            val safe = (sub.language ?: "sub").replace(Regex("[^a-zA-Z0-9]"), "")
            val f = java.io.File(LoLTubeDir.subsDir(), "${System.currentTimeMillis()}_$safe.$ext")
            if (!runCatching { f.writeText(body) }.isSuccess) continue
            AppLog.d("Wyzie", "subtitle ${sub.display} downloaded (${body.length} chars, $mime, referer=${referer.ifEmpty { "none" }})")
            return@withContext f to mime
        }
        // full URL + the HTTP status on purpose: the old truncated take(80) log
        // once masqueraded as a "-e0" suffix and sent the fix hunting in the
        // wrong direction; the status tells the next session whether the token
        // expired (404), Cloudflare challenged (403) or rate-limited (429)
        AppLog.e("Wyzie", "subtitle download failed (HTTP $lastStatus): ${sub.url}")
        null
    }

    private fun looksLikeSubtitle(body: String): Boolean {
        if (body.isBlank() || body.length < 12) return false
        val t = body.trimStart('﻿', '\uFEFF', ' ')
        return t.startsWith("WEBVTT", true) ||
            t.contains("-->") ||
            Regex("""^\s*\d+\s*$""", RegexOption.MULTILINE).containsMatchIn(t) ||
            // SubStation Alpha — series tracks are frequently full ASS
            // scripts (header + Dialogue: events, zero `-->` lines)
            looksLikeAss(t)
    }

    /** SubStation Alpha detection (ASS/SSA). */
    private fun looksLikeAss(body: String): Boolean =
        body.startsWith("[Script Info]", ignoreCase = true) ||
            Regex("^Dialogue:", RegexOption.IGNORE_CASE).containsMatchIn(body)
}

/** Cache dir helpers. */
object LoLTubeDir {
    private var dir: java.io.File? = null

    @Synchronized
    fun init(context: android.content.Context) {
        if (dir == null) {
            dir = java.io.File(context.cacheDir, "loltube").apply { mkdirs() }
        }
    }

    fun root(): java.io.File {
        val d = dir ?: java.io.File(
            com.loltube.app.LoLTubeApp.instance.cacheDir, "loltube"
        ).apply { mkdirs() }
        dir = d
        return d
    }

    fun subsDir(): java.io.File = java.io.File(root(), "subs").apply {
        mkdirs()
        // keep the cache bounded: drop oldest files beyond 24
        val files = listFiles()?.sortedBy { it.lastModified() } ?: return@apply
        if (files.size > 24) {
            files.take(files.size - 24).forEach { it.delete() }
        }
    }

    fun clearSubs() {
        subsDir().listFiles()?.forEach { it.delete() }
    }
}
