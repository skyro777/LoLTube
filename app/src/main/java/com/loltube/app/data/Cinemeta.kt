package com.loltube.app.data

import com.loltube.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Cinemeta (Stremio's public metadata API) - keyless catalog & details.
 * Endpoints verified live: /catalog/{type}/top[/genre=X&skip=N][.json], /meta/{type}/{imdb}.json
 * Every meta carries `moviedb_id` (TMDB id) which the Mapple provider needs.
 */
object Cinemeta {
    private const val BASE = "https://v3-cinemeta.strem.io"

    @Serializable
    private data class CMeta(
        val id: String = "",
        val imdb_id: String? = null,
        val type: String? = null,
        val name: String = "",
        val poster: String? = null,
        val background: String? = null,
        val logo: String? = null,
        val releaseInfo: String? = null,
        val imdbRating: String? = null,
        val genres: List<String> = emptyList(),
        val genre: List<String> = emptyList(),
        val description: String? = null,
        val moviedb_id: Int? = null,
        val runtime: String? = null,
        val cast: List<String> = emptyList(),
        val director: List<String> = emptyList(),
        val country: String? = null,
        val awards: String? = null,
        val videos: List<CVideo> = emptyList(),
    )

    @Serializable
    private data class CVideo(
        val id: String = "",
        val name: String = "",
        val season: Int = 0,
        val number: Int = 0,
        val episode: Int = 0,
        val firstAired: String? = null,
        val released: String? = null,
        val thumbnail: String? = null,
        val overview: String? = null,
        val description: String? = null,
        val rating: String? = null,
        val runtime: Int? = null,
    )

    @Serializable
    private data class CatalogResponse(val metas: List<CMeta> = emptyList())

    @Serializable
    private data class MetaResponse(val meta: CMeta? = null)

    private fun CMeta.toEntry(fallbackType: String) = CatalogEntry(
        imdbId = if (id.startsWith("tt")) id else (imdb_id ?: id),
        type = when (type) { "movie", "series" -> type else -> fallbackType },
        name = name,
        poster = poster ?: Catalog.poster(id),
        background = background,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = if (genres.isNotEmpty()) genres else genre,
        description = description,
        tmdbId = moviedb_id,
    )

    private fun CMeta.toDetails(fallbackType: String): MediaDetails {
        val eps = videos.map { v ->
            EpisodeInfo(
                id = v.id,
                season = v.season,
                number = if (v.number > 0) v.number else v.episode,
                name = v.name,
                overview = v.overview ?: v.description,
                thumbnail = v.thumbnail,
                firstAired = v.firstAired ?: v.released,
                rating = v.rating,
                runtimeMin = v.runtime,
            )
        }
        return MediaDetails(
            imdbId = if (id.startsWith("tt")) id else (imdb_id ?: id),
            tmdbId = moviedb_id,
            type = when (type) { "movie", "series" -> type else -> fallbackType },
            name = name,
            poster = poster ?: Catalog.poster(id),
            background = background,
            logo = logo,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            runtime = runtime,
            genres = if (genres.isNotEmpty()) genres else genre,
            description = description,
            cast = cast,
            director = director,
            country = country,
            awards = awards,
            episodes = eps,
            seasons = eps.map { it.season }.distinct().sorted(),
        )
    }

    /** Browse a catalog page. type = "movie"|"series". */
    suspend fun catalog(type: String, genre: String? = null, skip: Int = 0): List<CatalogEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                val filters = buildList {
                    genre?.let { add("genre=$it") }
                    if (skip > 0) add("skip=$skip")
                }
                val url = if (filters.isEmpty()) "$BASE/catalog/$type/top.json"
                else "$BASE/catalog/$type/top/${filters.joinToString("&")}.json"
                val body = Net.get(url)
                Net.json.decodeFromString(CatalogResponse.serializer(), body).metas
                    .filter { it.id.startsWith("tt") }
                    .map { it.toEntry(type) }
            }.onFailure { AppLog.e("Cinemeta", "catalog failed: ${it.message}") }
                .getOrDefault(emptyList())
        }

    /** Full metadata (details + episode list for series). */
    suspend fun meta(imdbId: String, type: String): MediaDetails? = withContext(Dispatchers.IO) {
        runCatching {
            val body = Net.get("$BASE/meta/$type/$imdbId.json")
            Net.json.decodeFromString(MetaResponse.serializer(), body).meta?.toDetails(type)
        }.onFailure { AppLog.e("Cinemeta", "meta failed for $imdbId: ${it.message}") }
            .getOrNull()
    }

    /** Sometimes the type in our DB is wrong; try both. */
    suspend fun metaAnyType(imdbId: String, type: String): MediaDetails? {
        meta(imdbId, type)?.let { return it }
        return meta(imdbId, if (type == Catalog.MOVIE) Catalog.SERIES else Catalog.MOVIE)
    }

    /** Probe the TMDB id for an imdb id (used by the Mapple provider). */
    suspend fun tmdbId(imdbId: String, type: String): Int? = meta(imdbId, type)?.tmdbId
}
