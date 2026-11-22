package com.loltube.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.loltube.app.data.Catalog
import com.loltube.app.data.CatalogEntry
import com.loltube.app.data.Cinemeta
import com.loltube.app.data.ImdbSuggest
import com.loltube.app.data.ProgressStore
import com.loltube.app.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    data class HomeUiState(
        val tab: Int = 0,
        val initialLoading: Boolean = true,
        val continueWatching: List<ProgressStore.WatchProgress> = emptyList(),
        val topMovies: List<CatalogEntry> = emptyList(),
        val topSeries: List<CatalogEntry> = emptyList(),
        val genre: String? = null,
        val gridItems: List<CatalogEntry> = emptyList(),
        val gridLoading: Boolean = false,
        val gridDone: Boolean = false,
        val searchQuery: String = "",
        val searchResults: List<CatalogEntry> = emptyList(),
        val searching: Boolean = false,
        val searchError: String? = null,
    )

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var gridSkip = 0
    private var searchJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(initialLoading = true)
            val cont = ProgressStore.continueWatching(getApplication())
            val movies = Cinemeta.catalog(Catalog.MOVIE)
            val series = Cinemeta.catalog(Catalog.SERIES)
            _state.value = _state.value.copy(
                initialLoading = false,
                continueWatching = cont,
                topMovies = movies,
                topSeries = series,
            )
        }
    }

    fun setTab(tab: Int) {
        if (tab == _state.value.tab) return
        _state.value = _state.value.copy(tab = tab)
        if (tab != 0 && _state.value.gridItems.isEmpty()) {
            loadGrid(reset = true)
        }
    }

    fun gridType(): String = if (_state.value.tab == 2) Catalog.SERIES else Catalog.MOVIE

    fun setGenre(genre: String?) {
        _state.value = _state.value.copy(genre = genre)
        loadGrid(reset = true)
    }

    fun loadMoreGrid() {
        val s = _state.value
        if (s.gridLoading || s.gridDone) return
        loadGrid(reset = false)
    }

    private fun loadGrid(reset: Boolean) {
        if (reset) gridSkip = 0
        viewModelScope.launch {
            _state.value = _state.value.copy(gridLoading = true, gridDone = if (reset) false else _state.value.gridDone)
            val items = Cinemeta.catalog(gridType(), genre = _state.value.genre, skip = gridSkip)
            gridSkip += maxOf(items.size, 50)
            val merged = if (reset) items else _state.value.gridItems + items
            val dedup = merged.distinctBy { it.imdbId }
            _state.value = _state.value.copy(
                gridLoading = false,
                gridItems = dedup,
                gridDone = items.isEmpty() || gridSkip > 400,
            )
            AppLog.d("Home", "grid ${gridType()}/${_state.value.genre ?: "-"} -> ${dedup.size} items, done=${items.isEmpty()}")
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query, searchError = null)
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _state.value = _state.value.copy(searchResults = emptyList(), searching = false)
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            _state.value = _state.value.copy(searching = true)
            val q = query.trim()

            // Primary: Cinemeta search — the same backend that powers the home feeds,
            // so it works on every network the app already works on.
            val results = Cinemeta.search(q)

            // Fallback: IMDb suggestion endpoint (different CDN, sometimes blocked).
            val final = results.ifEmpty {
                ImdbSuggest.search(q).also {
                    if (it.isEmpty()) {
                        AppLog.e("Search", "no results from Cinemeta or IMDb for '$q'")
                    }
                }
            }

            // Ignore stale responses if the user kept typing.
            if (_state.value.searchQuery.trim() == q) {
                _state.value = _state.value.copy(
                    searching = false,
                    searchResults = final,
                )
            }
        }
    }
}
