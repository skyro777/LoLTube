package com.loltube.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.loltube.app.data.Catalog
import com.loltube.app.data.Cinemeta
import com.loltube.app.data.MediaDetails
import com.loltube.app.data.ProgressStore
import com.loltube.app.providers.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailsViewModel(app: Application) : AndroidViewModel(app) {

    data class DetailsUiState(
        val loading: Boolean = true,
        val error: String? = null,
        val details: MediaDetails? = null,
        val selectedSeason: Int = 1,
        val provider: ProviderId? = null, // null = auto
        val movieProgress: ProgressStore.WatchProgress? = null,
        val watchedEpisodes: Set<String> = emptySet(),
        val episodeProgress: Map<String, ProgressStore.WatchProgress> = emptyMap(),
    )

    private val _state = MutableStateFlow(DetailsUiState())
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    fun load(imdbId: String, type: String) {
        if (_state.value.details?.imdbId == imdbId) return
        _state.value = DetailsUiState(loading = true)
        viewModelScope.launch {
            val details = Cinemeta.metaAnyType(imdbId, type)
            if (details == null) {
                _state.value = _state.value.copy(loading = false, error = "Couldn't load details for $imdbId")
                return@launch
            }
            val app = getApplication<Application>()
            val progress = ProgressStore.get(app, imdbId, null, null)
            val watched = ProgressStore.watchedEpisodes(app, imdbId)
            _state.value = DetailsUiState(
                loading = false,
                details = details,
                selectedSeason = details.seasons.firstOrNull { it >= 1 } ?: 1,
                movieProgress = progress,
                watchedEpisodes = watched,
            )
        }
    }

    fun setSeason(season: Int) {
        _state.value = _state.value.copy(selectedSeason = season)
    }

    fun setProvider(provider: ProviderId?) {
        _state.value = _state.value.copy(provider = provider)
    }
}
