package com.loltube.app.providers

import com.loltube.app.data.Catalog
import com.loltube.app.data.Net

enum class ProviderId(val label: String) {
    VIDFAST("Vidfast"),
    VIDCORE("Vidcore"),
    MAPPLE("Mapple"),
}

/** What we want to play. */
data class PlaybackRequest(
    val imdbId: String,
    val type: String, // movie | series
    val tmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String = "",
    val posterUrl: String? = null,
    val startAtMs: Long = 0,
)

data class StreamCandidate(val url: String, val kind: String, val capturedAtMs: Long)

data class ResolvedStream(
    val url: String,
    val kind: String, // "m3u8-master" | "m3u8" | "mpd" | "mp4"
    val provider: ProviderId,
    val headers: Map<String, String>,
    val allCandidates: List<StreamCandidate> = emptyList(),
    /** Subtitles the provider page itself listed (captured during the harvest). */
    val subs: List<com.loltube.app.data.Wyzie.Sub> = emptyList(),
    /** Subtitle files the page actually DOWNLOADED for its auto-selected track —
     *  stream-coupled (the site pairs them with the exact copy it serves). */
    val siteSubs: List<SiteSubtitle> = emptyList(),
)

object Providers {
    fun embedUrl(id: ProviderId, req: PlaybackRequest): String? = when (id) {
        ProviderId.VIDFAST ->
            if (req.type == Catalog.MOVIE) "https://vidfast.vc/movie/${req.imdbId}"
            else "https://vidfast.vc/tv/${req.imdbId}/${req.season ?: 1}/${req.episode ?: 1}"

        ProviderId.VIDCORE ->
            if (req.type == Catalog.MOVIE) "https://vidcore.io/movie/${req.imdbId}"
            else "https://vidcore.io/tv/${req.imdbId}/${req.season ?: 1}/${req.episode ?: 1}"

        ProviderId.MAPPLE -> {
            val tmdb = req.tmdbId ?: return null
            if (req.type == Catalog.MOVIE) "https://mapple.fun/watch/movie/$tmdb?autoPlay=true"
            else "https://mapple.fun/watch/tv/$tmdb-${req.season ?: 1}-${req.episode ?: 1}?autoPlay=true"
        }
    }

    fun origin(id: ProviderId): String = when (id) {
        ProviderId.VIDFAST -> "https://vidfast.vc"
        ProviderId.VIDCORE -> "https://vidcore.io"
        ProviderId.MAPPLE -> "https://mapple.fun"
    }

    /** Headers ExoPlayer should send when fetching the harvested stream. */
    fun playbackHeaders(id: ProviderId): Map<String, String> = mapOf(
        "User-Agent" to Net.UA,
        "Referer" to "${origin(id)}/",
        "Origin" to origin(id),
    )
}
