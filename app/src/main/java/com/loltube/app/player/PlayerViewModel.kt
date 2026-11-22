package com.loltube.app.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
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

    /** A subtitle track that lives inside the HLS/DASH manifest itself. */
    data class EmbeddedTextTrack(
        val groupIndex: Int,
        val trackIndex: Int,
        val label: String,
        val language: String?,
        val isSelected: Boolean,
    )

    data class PendingNext(val season: Int, val episode: Int, val name: String, val countdown: Int)

    data class Stats(
        val droppedFrames: Int = 0,
        val rebuffers: Int = 0,
        val videoHeight: Int = 0,
        val videoBitrate: Int = 0,
        val bandwidthBps: Int = 0,
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

    private val _qualityMode = MutableStateFlow(QualityMode.AUTO)
    val qualityMode: StateFlow<QualityMode> = _qualityMode.asStateFlow()

    private val _aspectMode = MutableStateFlow(AspectMode.AUTO)
    val aspectMode: StateFlow<AspectMode> = _aspectMode.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _subs = MutableStateFlow<List<Wyzie.Sub>>(emptyList())
    val subs: StateFlow<List<Wyzie.Sub>> = _subs.asStateFlow()

    private val _embeddedTextTracks = MutableStateFlow<List<EmbeddedTextTrack>>(emptyList())
    val embeddedTextTracks: StateFlow<List<EmbeddedTextTrack>> = _embeddedTextTracks.asStateFlow()

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
    private val resolver = StreamResolver(context)
    private val rebufferTimes = mutableListOf<Long>()
    private var autoReResolved = false
    private var activeSubtitle: PlayerEngine.SubtitleSelection? = null
    private var providerSubs: List<Wyzie.Sub> = emptyList()
    private var prefetcher: TurboPrefetcher? = null

    // stall watchdog state
    @Volatile private var bufferingSinceMs = 0L
    @Volatile private var stallStage = 0
    @Volatile private var hadFirstReady = false
    @Volatile private var switchingSource = false

    private val _currentRequest = MutableStateFlow(request)
    val activeRequest: StateFlow<PlaybackRequest> get() = _currentRequest.asStateFlow()

    private var currentRequest: PlaybackRequest
        get() = _currentRequest.value
        set(value) { _currentRequest.value = value }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _buffering.value = playbackState == Player.STATE_BUFFERING
            when (playbackState) {
                Player.STATE_READY -> {
                    val p = player.value ?: return
                    if (p.duration > 0) _durationMs.value = p.duration
                    hadFirstReady = true
                    bufferingSinceMs = 0
                    stallStage = 0
                }

                Player.STATE_ENDED -> onEnded()

                Player.STATE_BUFFERING -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (bufferingSinceMs == 0L) bufferingSinceMs = now
                    if (_isPlaying.value) {
                        synchronized(rebufferTimes) {
                            rebufferTimes.add(now)
                            rebufferTimes.removeAll { now - it > 60_000 }
                            _stats.value = _stats.value.copy(rebuffers = rebufferTimes.size)
                            // 4 rebuffers in a minute while on an uncapped mode ->
                            // the line clearly can't sustain it, step down.
                            if (rebufferTimes.size >= 4 &&
                                (_qualityMode.value == QualityMode.AUTO || _qualityMode.value == QualityMode.MAX)
                            ) {
                                rebufferTimes.clear()
                                setQuality(QualityMode.CAP_1080)
                                _message.value = "Network can't sustain this quality — capped to 1080p"
                            }
                        }
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

            // Expose subtitle tracks embedded in the manifest (the sites' own subs).
            val embedded = mutableListOf<EmbeddedTextTrack>()
            for (gi in 0 until tracks.groups.size) {
                val g = tracks.groups[gi]
                if (g.type != C.TRACK_TYPE_TEXT) continue
                for (ti in 0 until g.length) {
                    val fmt = g.getTrackFormat(ti)
                    val label = buildString {
                        append(fmt.label ?: fmt.language ?: fmt.id ?: "Track ${gi}:${ti}")
                    }
                    embedded.add(
                        EmbeddedTextTrack(
                            groupIndex = gi,
                            trackIndex = ti,
                            label = label,
                            language = fmt.language,
                            isSelected = g.isTrackSelected(ti),
                        )
                    )
                }
            }
            _embeddedTextTracks.value = embedded
            maybeAutoSelectEmbedded()

            p.videoFormat?.let { f ->
                _stats.value = _stats.value.copy(videoHeight = f.height, videoBitrate = f.bitrate)
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

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            if (bitrateEstimate <= 0) return
            val bps = bitrateEstimate.toInt()
            _stats.value = _stats.value.copy(bandwidthBps = bps)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBandwidthSaveMs > 15_000) {
                lastBandwidthSaveMs = now
                viewModelScope.launch { SettingsStore.setLastBandwidth(context, bps) }
            }
        }
    }

    private var lastBandwidthSaveMs = 0L
    private var embeddedAutoTried = false

    /** Auto-pick a preferred embedded subtitle track once the manifest exposes them. */
    private fun maybeAutoSelectEmbedded() {
        if (embeddedAutoTried) return
        val embedded = _embeddedTextTracks.value
        if (embedded.isEmpty()) return
        if (!settings.subtitlesEnabled) return
        if (activeSubtitle != null || _activeSubLabel.value != null) return
        embeddedAutoTried = true
        val pref = settings.preferredSubtitleLang.lowercase()
        val pick = embedded.firstOrNull { it.language?.lowercase()?.startsWith(pref) == true }
            ?: embedded.firstOrNull {
                it.language?.lowercase() == "en" || it.label.contains("english", true)
            }
        if (pick != null) {
            if (pick.isSelected) _activeSubLabel.value = pick.label
            else selectEmbeddedSub(pick.groupIndex, pick.trackIndex)
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

            // resolve start position:
            //  >0 explicit resume, <0 explicit restart, 0 auto (use saved progress).
            //  A saved position that is >= 90% into the file - or leaves less than
            //  2 minutes - counts as "already watched": restart at 0 instead of
            //  resuming into the credits (which instantly ended the episode and
            //  rolled into the NEXT one, making it look like the wrong episode
            //  was playing).
            val effectiveStartMs = when {
                request.startAtMs > 0 -> request.startAtMs
                request.startAtMs < 0 -> 0L
                else -> {
                    val saved = ProgressStore.get(
                        context, request.imdbId, request.season, request.episode,
                    )
                    val finished = saved != null && saved.durationMs > 0 &&
                            saved.positionMs > saved.durationMs * 90 / 100
                    val nearEnd = saved != null && saved.durationMs > 0 &&
                            saved.durationMs - saved.positionMs < 120_000
                    if (finished || nearEnd) 0L
                    else saved?.positionMs?.takeIf { it > 30_000 && it < 95_000 * 60 } ?: 0L
                }
            }
            currentRequest = currentRequest.copy(startAtMs = effectiveStartMs)

            fetchSubtitles(currentRequest)
            startResolve(currentRequest, reusePlayer = false)
        }
    }

    private fun fetchSubtitles(req: PlaybackRequest) {
        viewModelScope.launch {
            val wyzieSubs = Wyzie.fetch(req.imdbId, req.season, req.episode)
            mergeSubs(wyzieSubs)
            // Embedded manifest tracks are auto-picked in onTracksChanged (they only
            // exist once the player has parsed the manifest).
            if (settings.subtitlesEnabled && activeSubtitle == null && _activeSubLabel.value == null) {
                val pref = settings.preferredSubtitleLang.lowercase()
                val match = _subs.value.firstOrNull { s ->
                    s.language?.lowercase() == pref || s.display.lowercase().startsWith(pref)
                } ?: _subs.value.firstOrNull { it.display.contains("english", true) }
                if (match != null) selectSubtitle(match)
            }
        }
    }

    /** Combine the provider-harvested listing with the directly-fetched one. */
    private fun mergeSubs(extra: List<Wyzie.Sub>) {
        val merged = (providerSubs + extra)
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .sortedByDescending { it.language?.lowercase() == settings.preferredSubtitleLang.lowercase() }
        _subs.value = merged
    }

    private fun startResolve(
        req: PlaybackRequest,
        reusePlayer: Boolean,
        forceOrder: List<ProviderId>? = null,
    ) {
        viewModelScope.launch {
            _phase.value = Phase.Resolving((forceOrder?.first() ?: providerChoice)?.label ?: "Auto", "Starting…")
            val order = forceOrder ?: providerChoice?.let { listOf(it) } ?: settings.providerOrder

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
            providerSubs = stream.subs
            if (providerSubs.isNotEmpty()) mergeSubs(emptyList())

            val existing = if (reusePlayer) player.value else null
            val p = existing
                ?: PlayerEngine.build(
                    context = context,
                    settings = settings,
                    streamHeaders = stream.headers,
                    prefetchEnabled = { settings.turboPrefetch },
                    bufferAheadMs = { (_bufferedMs.value - _positionMs.value).coerceAtLeast(0) },
                    positionMs = { _positionMs.value },
                ).let { built ->
                    built.player.addListener(playerListener)
                    built.player.addAnalyticsListener(analyticsListener)
                    _player.value = built.player
                    prefetcher = built.prefetcher
                    startLoops()
                    built.player
                }

            PlayerEngine.applyQualityMode(p, _qualityMode.value)

            autoReResolved = false
            bufferingSinceMs = 0
            stallStage = 0
            switchingSource = false

            val mediaItem = PlayerEngine.buildMediaItem(stream.url, stream.kind, null, activeSubtitle)
            p.setMediaItem(mediaItem, if (req.startAtMs > 0) req.startAtMs else 0)
            p.prepare()
            p.playWhenReady = true
            _phase.value = Phase.Ready(stream.provider, stream.kind, stream.allCandidates.size)
        }
    }

    // ---- stall watchdog -------------------------------------------------------
    //
    // If playback keeps buffering for a long stretch the ladder is:
    //   1) cap quality to 1080p (survives slow lines),
    //   2) re-prepare the stream (recovers dead loaders),
    //   3) silently switch to the next provider at the same position.
    // Each step re-arms the timer; any recovery (STATE_READY) resets it.

    private fun checkStallWatchdog() {
        val since = bufferingSinceMs
        if (since == 0L) return
        val now = android.os.SystemClock.elapsedRealtime()
        val stalledFor = now - since
        val stage0Timeout = if (hadFirstReady) 12_000L else 20_000L

        when {
            stallStage == 0 && stalledFor > stage0Timeout -> {
                bufferingSinceMs = now
                stallStage = 1
                if (_qualityMode.value == QualityMode.AUTO || _qualityMode.value == QualityMode.MAX) {
                    setQuality(QualityMode.CAP_1080)
                    _message.value = "Slow line detected — capped to 1080p for smooth playback"
                } else {
                    reprepareStream("Reconnecting stream…")
                }
            }

            stallStage == 1 && stalledFor > 12_000L -> {
                bufferingSinceMs = now
                stallStage = 2
                reprepareStream("Still buffering — reconnecting…")
            }

            stallStage == 2 && stalledFor > 15_000L -> {
                bufferingSinceMs = 0
                stallStage = 3
                autoSwitchProvider()
            }
        }
    }

    private fun reprepareStream(msg: String) {
        val p = player.value ?: return
        AppLog.d("Watchdog", msg)
        _message.value = msg
        try {
            p.prepare()
        } catch (t: Throwable) {
            AppLog.d("Watchdog", "prepare failed: ${t.message}")
        }
    }

    private fun autoSwitchProvider() {
        if (switchingSource) return
        val stream = currentStream ?: return
        val order = settings.providerOrder
        if (order.size < 2) return
        val idx = order.indexOf(stream.provider)
        val next = order[(idx + 1) % order.size]
        switchingSource = true
        AppLog.d("Watchdog", "stalling on ${stream.provider.label} — switching to ${next.label}")
        _message.value = "This source is stalling — switching to ${next.label}…"
        val pos = _positionMs.value
        startResolve(currentRequest.copy(startAtMs = pos), reusePlayer = true, forceOrder = listOf(next))
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
                checkStallWatchdog()
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
        // ABR stays fully adaptive in every mode; only the ceiling changes.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        viewModelScope.launch { SettingsStore.setQuality(context, mode) }
    }

    fun setAspect(mode: AspectMode) {
        _aspectMode.value = mode
        viewModelScope.launch { SettingsStore.setAspect(context, mode) }
    }

    /**
     * Attach a downloaded sidecar subtitle. Race-free: the download happens first,
     * and afterwards we re-read the player - if it now exists we re-attach the
     * media item with the subtitle (the old code stored it for later in a window
     * where the player could already be playing, so subtitles silently never
     * appeared).
     */
    fun selectSubtitle(sub: Wyzie.Sub?) {
        if (sub == null) {
            activeSubtitle = null
            _activeSubLabel.value = null
            disableAllTextTracks(player.value)
            return
        }
        viewModelScope.launch {
            val dl = Wyzie.download(sub) ?: run {
                _message.value = "Couldn't download subtitle (${sub.display})"
                return@launch
            }
            val sel = PlayerEngine.SubtitleSelection(
                file = dl.first, mime = dl.second,
                language = sub.language, label = sub.display,
            )
            activeSubtitle = sel
            _activeSubLabel.value = sub.display

            val p = player.value
            if (p != null) {
                val stream = currentStream ?: return@launch
                val pos = _positionMs.value
                val mediaItem = PlayerEngine.buildMediaItem(stream.url, stream.kind, null, sel)
                p.setMediaItem(mediaItem, pos)
                p.prepare()
                p.playWhenReady = true
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(sub.language ?: sub.display)
                    .build()
            }
            // player == null: startResolve picks activeSubtitle up when it builds
        }
    }

    /** Switch to a subtitle track embedded in the stream's own manifest. */
    fun selectEmbeddedSub(groupIndex: Int, trackIndex: Int) {
        val p = player.value ?: return
        val tracks = p.currentTracks
        if (groupIndex >= tracks.groups.size) return
        val group = tracks.groups[groupIndex]
        if (trackIndex >= group.length) return
        activeSubtitle = null // external sidecar off
        val fmt = group.getTrackFormat(trackIndex)
        _activeSubLabel.value = fmt.label ?: fmt.language ?: "Embedded"
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    private fun disableAllTextTracks(p: ExoPlayer?) {
        if (p == null) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
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
            // keep the watched position when switching sources manually
            val pos = _positionMs.value
            val startAt = if (pos > 30_000) pos else 0L
            startResolve(currentRequest.copy(startAtMs = startAt), reusePlayer = false,
                forceOrder = provider?.let { listOf(it) })
        }
    }

    /** Jump to another episode of the same show from inside the player. */
    fun switchEpisode(season: Int, episode: Int) {
        if (currentRequest.season == season && currentRequest.episode == episode) return
        val req = currentRequest.copy(season = season, episode = episode, startAtMs = 0)
        autoReResolved = false
        embeddedAutoTried = false
        synchronized(rebufferTimes) { rebufferTimes.clear() }
        bufferingSinceMs = 0
        stallStage = 0
        switchingSource = false
        _stats.value = Stats()
        _pendingNext.value = null
        _subs.value = emptyList()
        providerSubs = emptyList()
        _activeSubLabel.value = null
        activeSubtitle = null
        _embeddedTextTracks.value = emptyList()
        _message.value = "Loading S$season E$episode…"
        currentRequest = req
        fetchSubtitles(req)
        startResolve(req, reusePlayer = true)
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
        switchEpisode(next.season, next.episode)
        _message.value = "Loading next episode…"
    }

    private fun nextEpisode(d: MediaDetails, s: Int, e: Int): Triple<Int, Int, String>? {
        val inSeason = d.episodes.filter { it.season == s }.sortedBy { it.number }
        inSeason.firstOrNull { it.number == e + 1 }?.let { return Triple(s, e + 1, it.name) }
        val nextSeason = d.seasons.firstOrNull { it > s } ?: return null
        val first = d.episodes.filter { it.season == nextSeason }.minByOrNull { it.number } ?: return null
        return Triple(nextSeason, first.number, first.name)
    }

    private fun releasePlayer() {
        prefetcher?.stop()
        prefetcher = null
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
