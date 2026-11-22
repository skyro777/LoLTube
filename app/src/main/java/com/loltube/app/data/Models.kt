package com.loltube.app.data

/** A catalog item as shown on browse/search screens. */
data class CatalogEntry(
    val imdbId: String,
    val type: String, // "movie" | "series"
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val tmdbId: Int? = null,
)

data class EpisodeInfo(
    val id: String,
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String? = null,
    val thumbnail: String? = null,
    val firstAired: String? = null,
    val rating: String? = null,
    val runtimeMin: Int? = null,
)

data class MediaDetails(
    val imdbId: String,
    val tmdbId: Int?,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val runtime: String?,
    val genres: List<String>,
    val description: String?,
    val cast: List<String>,
    val director: List<String>,
    val country: String?,
    val awards: String?,
    val episodes: List<EpisodeInfo> = emptyList(),
    val seasons: List<Int> = emptyList(),
)

object Catalog {
    const val MOVIE = "movie"
    const val SERIES = "series"

    val GENRES = listOf(
        "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama",
        "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance",
        "Sci-Fi", "Thriller", "War", "Western"
    )

    fun poster(imdbId: String): String = "https://images.metahub.space/poster/medium/$imdbId/img"
    fun backdrop(imdbId: String): String = "https://images.metahub.space/background/medium/$imdbId/img"
    fun logo(imdbId: String): String = "https://images.metahub.space/logo/medium/$imdbId/img"
}
