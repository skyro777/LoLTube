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
    )

    suspend fun fetch(imdbId: String, season: Int? = null, episode: Int? = null): List<Sub> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = buildString {
                    append("https://vidfast.vc/wyzie?id=").append(imdbId)
                    season?.let { append("&season=").append(it) }
                    episode?.let { append("&episode=").append(it) }
                }
                val body = Net.get(url, headers = mapOf("Referer" to "https://vidfast.vc/"))
                Net.json.decodeFromString(ListSerializer(Sub.serializer()), body)
                    .filter { it.url.isNotBlank() }
            }.onFailure { AppLog.e("Wyzie", "subtitles failed: ${it.message}") }
                .getOrDefault(emptyList())
        }

    /** Download a subtitle and persist to a cache file; returns (file, mime). */
    suspend fun download(sub: Sub): Pair<java.io.File, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val body = Net.get(sub.url, headers = mapOf("Referer" to "https://vidfast.vc/"))
            val mime = if (body.trimStart().startsWith("WEBVTT", ignoreCase = true)) "text/vtt"
            else "application/x-subrip"
            val ext = if (mime == "text/vtt") "vtt" else "srt"
            val safe = (sub.language ?: "sub").replace(Regex("[^a-zA-Z0-9]"), "")
            val f = java.io.File(LoLTubeDir.subsDir(), "${System.currentTimeMillis()}_$safe.$ext")
            f.writeText(body)
            f to mime
        }.onFailure { AppLog.e("Wyzie", "download subtitle failed: ${it.message}") }
            .getOrNull()
    }
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
