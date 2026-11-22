package com.loltube.app.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.loltube.app.data.AppSettings
import com.loltube.app.data.AspectMode
import com.loltube.app.data.Catalog
import com.loltube.app.data.Cinemeta
import com.loltube.app.data.MediaDetails
import com.loltube.app.data.ProgressStore
import com.loltube.app.data.QualityMode
import com.loltube.app.data.SettingsStore
import com.loltube.app.data.Wyzie
import com.loltube.app.providers.PlaybackRequest
import com.loltube.app.providers.ProviderId
import com.loltube.app.providers.ResolvedStream
import com.loltube.app.providers.StreamResolver
import com.loltube.app.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@UnstableApi
class PlayerViewModel(
    app: Application,
    val request: PlaybackRequest,
    private val providerChoice: ProviderId?, // null = auto (settings order)
) : ViewModel() {

    private val context = app.applicationContext

    sealed interface Phase {
        data object LoadingMeta : Phase
        data class Resolving(val provider: String, val status: String) : Phase
        data class Ready(val provider: ProviderId, val streamKind: String, val candidates: Int) : Phase
        data class Failed(val errors: String) : Phase
    }

    data class PendingNext(val season: Int, val episode: Int, val name: String, val countdown: Int)

    data class Stats(
        val droppedFrames: Int = 0,
        val rebuffers: Int = 0,
        val videoHeight: Int = 0,
        val videoBitrate: Int = 0,
    )

    // ---- observable state -------------------------------------------------
    private val _phase = MutableStateFlow<Phase>(Phase.LoadingMeta)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _details = MutableStateFlow<MediaDetails?>(null)
    val details: StateFlow<MediaDetails?> = _details.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedMs = MutableStateFlow(0L)
    val bufferedMs: StateFlow<Long> = _bufferedMs.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()

    private val _qualityMode = MutableStateFlow(QualityMode.AUTO_4K)
    val qualityMode: StateFlow<QualityMode> = _qualityMode.asStateFlow()

    private val _aspectMode = MutableStateFlow(AspectMode.AUTO)
    val aspectMode: StateFlow<AspectMode> = _aspectMode.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _subs = MutableStateFlow<List<Wyzie.Sub>>(emptyList())
    val subs: StateFlow<List<Wyzie.Sub>> = _subs.asStateFlow()

    private val _activeSubLabel = MutableStateFlow<String?>(null)
    val activeSubLabel: StateFlow<String?> = _activeSubLabel.asStateFlow()

    private val _audioLanguages = MutableStateFlow<List<String>>(emptyList())
    val audioLanguages: StateFlow<List<String>> = _audioLanguages.asStateFlow()

    private val _activeAudio = MutableStateFlow<String?>(null)
    val activeAudio: StateFlow<String?> = _activeAudio.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val _pendingNext = MutableStateFlow<PendingNext?>(null)
    val pendingNext: StateFlow<PendingNext?> = _pendingNext.asStateFlow()

    // ---- internals ---------------------------------------------------------
    private var settings: AppSettings = AppSettings()
    private var currentStream: ResolvedStream? = null
    private var currentRequest: PlaybackRequest = request
    private val resolver = StreamResolver(context)
    private val rebufferTimes = mutableListOf<Long>()
    private var autoReResolved = false
    private var activeSubtitle: PlayerEngine.SubtitleSelection? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _buffering.value = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                val p = player.value ?: return
                if (p.duration > 0) _durationMs.value = p.duration
            }
            if (playbackState == Player.STATE_ENDED) onEnded()
            if (playbackState == Player.STATE_BUFFERING && _isPlaying.value) {
                val now = android.os.SystemClock.elapsedRealtime()
                synchronized(rebufferTimes) {
                    rebufferTimes.add(now)
                    rebufferTimes.removeAll { now - it > 60_000 }
                    _stats.value = _stats.value.copy(rebuffers = rebufferTimes.size)
                    if (rebufferTimes.size >= 3 && _qualityMode.value == QualityMode.FORCE_MAX) {
                        rebufferTimes.clear()
                        downgradeForStability()
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _videoSize.value = videoSize
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.e("Player", "playback error: ${error.errorCodeName}")
            val played = _positionMs.value
            val stream = currentStream
            if (!autoReResolved && stream != null && played > 5 * 60_000) {
                autoReResolved = true
                _message.value = "Stream link expired — reconnecting…"
                val pos = _positionMs.value
                startResolve(currentRequest.copy(startAtMs = pos), reusePlayer = true)
            } else {
                _message.value = "Playback error: ${error.errorCodeName}"
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val p = player.value ?: return
            val langs = mutableListOf<String>()
            for (gi in 0 until tracks.groups.size) {
                val g = tracks.groups[gi]
                if (g.type != C.TRACK_TYPE_AUDIO) continue
                for (ti in 0 until g.length) {
                    val lang = g.getTrackFormat(ti).language ?: continue
                    if (langs.none { it.equals(lang, ignoreCase = true) }) langs.add(lang)
                }
            }
            if (langs.isNotEmpty()) {
                _audioLanguages.value = langs
                val sel = p.trackSelectionParameters.preferredAudioLanguages.firstOrNull()
                _activeAudio.value = sel ?: langs.first()
            }
            p.videoFormat?.let { f ->
                _stats.value = _stats.value.copy(videoHeight = f.height, videoBitrate = f.bitrate)
            }
            if (_qualityMode.value == QualityMode.FORCE_MAX) {
                PlayerEngine.forceHighestVideo(p)
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            _stats.value = _stats.value.copy(droppedFrames = _stats.value.droppedFrames + droppedFrames)
        }
    }

    init {
        viewModelScope.launch {
            settings = SettingsStore.read(context)
            _qualityMode.value = settings.quality
            _aspectMode.value = settings.aspect

            val meta = Cinemeta.metaAnyType(request.imdbId, request.type)
            _details.value = meta
            currentRequest = currentRequest.copy(
                tmdbId = meta?.tmdbId ?: request.tmdbId,
                title = meta?.name ?: request.title,
                posterUrl = meta?.poster,
            )

            fetchSubtitles(currentRequest)
            startResolve(currentRequest, reusePlayer = false)
        }
    }

    private fun fetchSubtitles(req: PlaybackRequest) {
        viewModelScope.launch {
            val subs = Wyzie.fetch(req.imdbId, req.season, req.episode)
            _subs.value = subs
            if (settings.subtitlesEnabled && activeSubtitle == null) {
                val pref = settings.preferredSubtitleLang.lowercase()
                val match = subs.firstOrNull { s ->
                    s.language?.lowercase() == pref || s.display.lowercase().startsWith(pref)
                } ?: subs.firstOrNull { it.display.contains("english", true) }
                if (match != null) selectSubtitle(match)
            }
        }
    }

    private fun startResolve(req: PlaybackRequest, reusePlayer: Boolean) {
        viewModelScope.launch {
            _phase.value = Phase.Resolving(providerChoice?.label ?: "Auto", "Starting…")
            val order = providerChoice?.let { listOf(it) } ?: settings.providerOrder

            val (stream, attempts) = resolver.resolve(req, order) { id, status ->
                _phase.value = Phase.Resolving(id.label, status)
            }

            if (stream == null) {
                if (!reusePlayer) releasePlayer()
                _phase.value = Phase.Failed(
                    attempts.joinToString("\n") { "${it.provider.label}: ${it.error ?: "unknown error"}" }
                        .ifBlank { "No provider could resolve this title." }
                )
                return@launch
            }

            currentStream = stream
            currentRequest = req

            val p = (if (reusePlayer) player.value else null)
                ?: PlayerEngine.build(context, settings, stream.headers).also {
                    it.addListener(playerListener)
                    it.addAnalyticsListener(analyticsListener)
                    _player.value = it
                    startLoops()
                }

            PlayerEngine.applyQualityMode(p, _qualityMode.value)

            val mediaItem = PlayerEngine.buildMediaItem(stream.url, stream.kind, null, activeSubtitle)
            p.setMediaItem(mediaItem, if (req.startAtMs > 0) req.startAtMs else 0)
            p.prepare()
            p.playWhenReady = true
            _phase.value = Phase.Ready(stream.provider, stream.kind, stream.allCandidates.size)
        }
    }

    private fun downgradeForStability() {
        _qualityMode.value = QualityMode.AUTO_4K
        player.value?.let { PlayerEngine.applyQualityMode(it, QualityMode.AUTO_4K) }
        _message.value = "Repeated buffering detected — switched to Auto quality"
    }

    // ---- progress ----------------------------------------------------------
    private fun startLoops() {
        viewModelScope.launch {
            while (true) {
                delay(400)
                val pl = player.value ?: break
                _positionMs.value = pl.currentPosition.coerceAtLeast(0)
                if (pl.duration > 0) _durationMs.value = pl.duration
                _bufferedMs.value = pl.bufferedPosition.coerceAtLeast(0)
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (player.value?.isPlaying == true) saveProgress()
            }
        }
    }

    fun saveProgress() {
        val p = player.value ?: return
        val pos = p.currentPosition
        if (pos < 5_000) return
        val d = _details.value
        viewModelScope.launch {
            ProgressStore.save(
                context,
                ProgressStore.WatchProgress(
                    imdbId = currentRequest.imdbId,
                    type = currentRequest.type,
                    season = currentRequest.season,
                    episode = currentRequest.episode,
                    positionMs = pos,
                    durationMs = if (p.duration > 0) p.duration else 0,
                    title = d?.name ?: currentRequest.title,
                    episodeName = episodeName(),
                    poster = d?.poster,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun episodeName(): String? {
        val s = currentRequest.season ?: return null
        val e = currentRequest.episode ?: return null
        return _details.value?.episodes?.firstOrNull { it.season == s && it.number == e }?.name
    }

    // ---- user actions -------------------------------------------------------
    fun togglePlayPause() {
        val p = player.value ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(ms: Long) {
        player.value?.seekTo(ms.coerceIn(0, (_durationMs.value - 500).coerceAtLeast(0)))
    }

    fun seekDelta(deltaMs: Long) {
        seekTo(_positionMs.value + deltaMs)
    }

    fun setQuality(mode: QualityMode) {
        _qualityMode.value = mode
        val p = player.value ?: return
        PlayerEngine.applyQualityMode(p, mode)
        if (mode == QualityMode.FORCE_MAX) {
            PlayerEngine.forceHighestVideo(p)
        } else {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        }
        viewModelScope.launch { SettingsStore.setQuality(context, mode) }
    }

    fun setAspect(mode: AspectMode) {
        _aspectMode.value = mode
        viewModelScope.launch { SettingsStore.setAspect(context, mode) }
    }

    fun selectSubtitle(sub: Wyzie.Sub?) {
        val p = player.value
        if (sub == null) {
            activeSubtitle = null
            _activeSubLabel.value = null
            if (p != null) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            return
        }
        if (p == null) {
            // player not ready yet - remember for later
            viewModelScope.launch {
                val dl = Wyzie.download(sub) ?: return@launch
                activeSubtitle = PlayerEngine.SubtitleSelection(
                    file = dl.first, mime = dl.second,
                    language = sub.language, label = sub.display,
                )
                _activeSubLabel.value = sub.display
            }
            return
        }
        viewModelScope.launch {
            val dl = Wyzie.download(sub) ?: run {
                _message.value = "Couldn't download subtitle"
                return@launch
            }
            val sel = PlayerEngine.SubtitleSelection(
                file = dl.first, mime = dl.second,
                language = sub.language, label = sub.display,
            )
            activeSubtitle = sel
            _activeSubLabel.value = sub.display
            val stream = currentStream ?: return@launch
            val pos = _positionMs.value
            val mediaItem = PlayerEngine.buildMediaItem(stream.url, stream.kind, null, sel)
            p.setMediaItem(mediaItem, pos)
            p.prepare()
            p.playWhenReady = true
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        }
    }

    fun selectAudio(lang: String) {
        val p = player.value ?: return
        _activeAudio.value = lang
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(lang).build()
    }

    fun retry(provider: ProviderId? = null) {
        releasePlayer()
        autoReResolved = false
        viewModelScope.launch {
            settings = SettingsStore.read(context)
            val req = currentRequest.copy(startAtMs = 0)
            if (provider == null) {
                startResolve(req, reusePlayer = false)
            } else {
                _phase.value = Phase.Resolving(provider.label, "Starting…")
                val (stream, attempts) = resolver.resolve(req, listOf(provider)) { id, status ->
                    _phase.value = Phase.Resolving(id.label, status)
                }
                if (stream == null) {
                    _phase.value = Phase.Failed(attempts.joinToString("\n") { "${it.provider.label}: ${it.error}" })
                    return@launch
                }
                currentStream = stream
                currentRequest = req
                val p = PlayerEngine.build(context, settings, stream.headers).also {
                    it.addListener(playerListener)
                    it.addAnalyticsListener(analyticsListener)
                    _player.value = it
                    startLoops()
                }
                PlayerEngine.applyQualityMode(p, _qualityMode.value)
                val mediaItem = PlayerEngine.buildMediaItem(stream.url, stream.kind, null, activeSubtitle)
                p.setMediaItem(mediaItem, 0)
                p.prepare()
                p.playWhenReady = true
                _phase.value = Phase.Ready(stream.provider, stream.kind, stream.allCandidates.size)
            }
        }
    }

    // ---- next episode --------------------------------------------------------
    private fun onEnded() {
        val d = _details.value ?: return
        if (currentRequest.type != Catalog.SERIES) return
        if (!settings.autoNextEpisode) return
        val s = currentRequest.season ?: return
        val e = currentRequest.episode ?: return
        val next = nextEpisode(d, s, e) ?: return
        viewModelScope.launch {
            var count = 8
            _pendingNext.value = PendingNext(next.first, next.second, next.third, count)
            while (count > 0) {
                delay(1_000)
                val cur = _pendingNext.value ?: return@launch
                if (cur.season != next.first || cur.episode != next.second) return@launch
                count--
                _pendingNext.value = cur.copy(countdown = count)
            }
            _pendingNext.value = null
            playNextNow()
        }
    }

    fun cancelNext() {
        _pendingNext.value = null
    }

    fun playNextNow() {
        val next = _pendingNext.value ?: return
        _pendingNext.value = null
        val req = currentRequest.copy(season = next.season, episode = next.episode, startAtMs = 0)
        autoReResolved = false
        synchronized(rebufferTimes) { rebufferTimes.clear() }
        _stats.value = Stats()
        currentRequest = req
        _subs.value = emptyList()
        _activeSubLabel.value = null
        activeSubtitle = null
        _message.value = "Loading next episode…"
        fetchSubtitles(req)
        startResolve(req, reusePlayer = true)
    }

    private fun nextEpisode(d: MediaDetails, s: Int, e: Int): Triple<Int, Int, String>? {
        val inSeason = d.episodes.filter { it.season == s }.sortedBy { it.number }
        inSeason.firstOrNull { it.number == e + 1 }?.let { return Triple(s, e + 1, it.name) }
        val nextSeason = d.seasons.firstOrNull { it > s } ?: return null
        val first = d.episodes.filter { it.season == nextSeason }.minByOrNull { it.number } ?: return null
        return Triple(nextSeason, first.number, first.name)
    }

    private fun releasePlayer() {
        player.value?.let {
            it.removeListener(playerListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        _player.value = null
        _isPlaying.value = false
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        saveProgress()
        releasePlayer()
        super.onCleared()
    }

    companion object {
        fun factory(app: Application, request: PlaybackRequest, provider: ProviderId?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlayerViewModel(app, request, provider) as T
                }
            }
    }
}
