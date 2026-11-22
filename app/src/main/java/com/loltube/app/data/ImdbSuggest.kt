package com.loltube.app.data

import com.loltube.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/**
 * IMDb's own keyless suggestion API (powers the imdb.com search box).
 * Cinemeta's search catalog is broken (returns unrelated hits), so we use this instead.
 * URL shape verified live: https://v2.sg.media-imdb.com/suggestion/{first}/{query}.json
 */
object ImdbSuggest {
    private val ACCEPTED_TYPES = setOf("feature", "TV series", "TV mini-series", "TV movie", "TV special")

    @Serializable
    private data class SImage(val imageUrl: String? = null)

    @Serializable
    private data class SItem(
        val id: String = "",
        val l: String = "",
        val q: String? = null,
        val yr: String? = null,
        val y: Int? = null,
        val i: SImage? = null,
    )

    @Serializable
    private data class SResponse(val d: List<SItem> = emptyList())

    suspend fun search(query: String): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        runCatching {
            val first = q.first().lowercaseChar().let { c -> if (c.isLetterOrDigit()) c else 'x' }
            val enc = URLEncoder.encode(q, "UTF-8")
            val body = Net.get("https://v2.sg.media-imdb.com/suggestion/$first/$enc.json")
            Net.json.decodeFromString(SResponse.serializer(), body).d
                .asSequence()
                .filter { it.id.startsWith("tt") && it.q in ACCEPTED_TYPES }
                .map {
                    CatalogEntry(
                        imdbId = it.id,
                        type = if (it.q == "feature" || it.q == "TV movie") Catalog.MOVIE else Catalog.SERIES,
                        name = it.l,
                        poster = it.i?.imageUrl ?: Catalog.poster(it.id),
                        releaseInfo = it.yr ?: it.y?.toString(),
                    )
                }
                .take(30)
                .toList()
        }.onFailure { AppLog.e("ImdbSuggest", "search '$q' failed: ${it.message}") }
            .getOrDefault(emptyList())
    }
}
