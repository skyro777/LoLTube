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
import com.loltube.app.data.Net
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

    /** Manual stretch multipliers on top of the aspect mode (100% = off).
     *  Vertical fixes movies with black bars baked into the frame (auto can
     *  not detect those); horizontal fixes odd screens/phones. */
    private val _stretchYPct = MutableStateFlow(100)
    val stretchYPct: StateFlow<Int> = _stretchYPct.asStateFlow()

    private val _stretchXPct = MutableStateFlow(100)
    val stretchXPct: StateFlow<Int> = _stretchXPct.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _subs = MutableStateFlow<List<Wyzie.Sub>>(emptyList())
    val subs: StateFlow<List<Wyzie.Sub>> = _subs.asStateFlow()

    /** Subtitle files the site's own page downloaded while we harvested —
     *  stream-coupled (matched to the exact video copy being served, which is
     *  why their timeline is right by construction). */
    private val _siteSubs = MutableStateFlow<List<com.loltube.app.providers.SiteSubtitle>>(emptyList())
    val siteSubs: StateFlow<List<com.loltube.app.providers.SiteSubtitle>> = _siteSubs.asStateFlow()

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

    /** Live cue text for the app-rendered subtitle overlay (null = nothing on screen). */
    private val _cueText = MutableStateFlow<String?>(null)
    val cueText: StateFlow<String?> = _cueText.asStateFlow()

    /** Active per-source subtitle sync delay (ms). >0 = subtitles appear later. */
    private val _subDelayMs = MutableStateFlow(0)
    val subDelayMs: StateFlow<Int> = _subDelayMs.asStateFlow()

    /** Subtitle appearance, rendered by the overlay (size / color / background). */
    data class SubStyle(val sizePct: Int, val color: com.loltube.app.data.SubColor, val bgPct: Int)

    private val _subStyle = MutableStateFlow(
        SubStyle(com.loltube.app.data.SubSize.NORMAL.pct, com.loltube.app.data.SubColor.WHITE, 55)
    )
    val subStyle: StateFlow<SubStyle> = _subStyle.asStateFlow()

    // ---- internals ---------------------------------------------------------
    private var settings: AppSettings = AppSettings()
    private var currentStream: ResolvedStream? = null
    private val resolver = StreamResolver(context)
    private val rebufferTimes = mutableListOf<Long>()
    private var autoReResolved = false

    // ---- site-style subtitle engine (see Subtitles.kt) ----
    /** Parsed cues of the downloaded sidecar, rendered by our own overlay. */
    @Volatile private var sidecarCues: List<Subtitles.Cue> = emptyList()
    /** The listing entry the current sidecar came from. */
    @Volatile private var sidecarSub: com.loltube.app.data.Wyzie.Sub? = null
    /** Sticky language/display the user (or auto-pick) chose — survives source
     *  switches and episode jumps, mirroring the sites' own "remember my
     *  subtitle language" behavior. */
    @Volatile private var desiredSubLang: String? = null
    /** Explicit "Off" from the menu — stops auto re-attach until re-enabled. */
    @Volatile private var userTurnedSubsOff = false

    private var providerSubs: List<com.loltube.app.data.Wyzie.Sub> = emptyList()

    // stall watchdog state
    @Volatile private var bufferingSinceMs = 0L
    @Volatile private var stallStage = 0
    @Volatile private var hadFirstReady = false
    @Volatile private var switchingSource = false
    /** Since the player last reported STATE_IDLE while we were in Phase.Ready —
     *  a wedged loader that stops emitting events entirely. */
    @Volatile private var idleSinceMs = 0L
    /** Wedged-loader rebuilds since the last READY — bounded so a dead stream
     *  can't rebuild forever. */
    @Volatile private var idleRebuilds = 0

    // self-healing recovery ladder (see onPlayerError):
    // 0 = clean, 1 = full rebuild (fresh codecs, same quality), 2 = rebuild
    // capped 1080 (survives a 4K decoder that dies at runtime), 3 = fail-safe
    // rebuild at 720, 4 = provider switches, then terminal. Resets after 20s
    // of clean playback.
    @Volatile private var recoveryStage = 0
    @Volatile private var cleanSinceMs = 0L

    /** Providers that hard-failed during THIS playback session. */
    private val failedProviders = mutableSetOf<ProviderId>()

    /**
     * Codec-class error detection: RUNTIME_CHECK (1004 — raw
     * IllegalStateException/IllegalArgumentException on the playback thread) is
     * almost always a vendor decoder dying on the selected rendition, together
     * with the decoder-error family. These NEVER recover via prepare() — the
     * codec instance is poisoned; they need a full player rebuild.
     */
    private fun isCodecClassError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        -> true
        else -> false
    }

    /**
     * Ceiling for quality selection that survives THIS app session. Set when
     * the device's codecs hard-fail on high renditions (lying vendor decoders):
     * 4K stays enabled on every capable device and every new session; a box
     * that crashed at 4K gets instant stable playback instead of a crash loop.
     */
    @Volatile private var sessionDecoderCap: Int? = null

    /** Human words for the terminal error, with the real cause visible. */
    private fun friendlyError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "This TV's decoder couldn't handle that stream — try another source"
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        -> "Decoder failed mid-playback — retry to continue"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        -> "Network problem — check the connection and retry"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Source timed out — try another source"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> "Source refused the request (link expired) — retry"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        -> "Source no longer serves that file — try another source"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> "Source sent a broken playlist — try another source"
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        -> "Stream slipped behind live — retry"
        else -> "Playback error: ${error.errorCodeName}"
    }

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
                    idleSinceMs = 0
                    idleRebuilds = 0
                    if (cleanSinceMs == 0L) cleanSinceMs = android.os.SystemClock.elapsedRealtime()
                }

                Player.STATE_ENDED -> onEnded()

                Player.STATE_IDLE -> {
                    // an IDLE player with no error callback is a wedged loader —
                    // track it so the loop can hard-rebuild (the automated
                    // equivalent of the force-close + reopen the user had to do)
                    if (idleSinceMs == 0L) idleSinceMs = android.os.SystemClock.elapsedRealtime()
                    AppLog.d("Player", "state -> IDLE (unprepared / wedged loader)")
                }

                Player.STATE_BUFFERING -> {
                    idleSinceMs = 0
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (bufferingSinceMs == 0L) bufferingSinceMs = now
                    if (_isPlaying.value) {
                        synchronized(rebufferTimes) {
                            rebufferTimes.add(now)
                            rebufferTimes.removeAll { now - it > 60_000 }
                            _stats.value = _stats.value.copy(rebuffers = rebufferTimes.size)
                            // 4 rebuffers in a minute while on an uncapped mode ->
                            // the line clearly can't sustain it, step down.
                            // SESSION-ONLY (never persisted): the line's real speed
                            // deserves a fresh test every session — the persisted
                            // auto-cap is what made "reinstall = permanent 1080p",
                            // since a single cold-start stall wrote CAP_1080 forever.
                            if (rebufferTimes.size >= 4 &&
                                (_qualityMode.value == QualityMode.AUTO || _qualityMode.value == QualityMode.MAX)
                            ) {
                                rebufferTimes.clear()
                                setQuality(QualityMode.CAP_1080, persist = false)
                                _message.value = "Network can't sustain this quality — capped to 1080p for this session"
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
            logPlaybackError(error)
            val played = _positionMs.value
            val stream = currentStream
            val codecClass = isCodecClassError(error)

            // URL/sideload-class failures are provider-INDEPENDENT (the same
            // sidecars and the same URL pipeline follow us to every source) —
            // switching providers can never fix them. The classic case was the
            // pre-attached file:// subtitle fed to OkHttpDataSource ->
            // HttpDataSourceException("Malformed URL", RUNTIME_CHECK); the scheme
            // routing in PlayerEngine fixes the root cause, and this guard makes
            // ANY future sideload poisoning self-heal instead of cascading into
            // "switching source" loops: drop the sidecar, rebuild, continue.
            val errorText = generateSequence<Throwable>(error as Throwable) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            if (errorText.contains("Malformed URL", ignoreCase = true) && sidecarSub != null) {
                _message.value = "Subtitle sidecar failed to load — restarting without it…"
                AppLog.e("Recovery", "Malformed-URL error with a sidecar attached — dropping subtitle and rebuilding")
                sidecarSub = null
                sidecarCues = emptyList()
                _cueText.value = null
                _activeSubLabel.value = null
                recoveryStage = 2
                rebuildAtPosition(capHeight = null, safeMode = false)
                return
            }

            // Long sessions: the CDN token (the ?s=... in the m3u8 URL) expires
            // after ~1h — re-resolve the stream at the current position.
            if (!autoReResolved && stream != null && played > 5 * 60_000) {
                autoReResolved = true
                _message.value = "Stream link expired — reconnecting…"
                val pos = _positionMs.value
                startResolve(currentRequest.copy(startAtMs = pos), reusePlayer = true)
                return
            }

            // Self-healing ladder. Every step re-arms; 20s of clean playback
            // resets to stage 0. Codec-class errors skip the soft step — a
            // poisoned codec instance never recovers from prepare(), so the
            // player is rebuilt with FRESH codec instances immediately.
            when {
                !codecClass && recoveryStage == 0 -> {
                    recoveryStage = 1
                    _message.value = "Playback hiccup — reconnecting…"
                    AppLog.d("Recovery", "stage 1: re-preparing at position")
                    reprepareAtPosition()
                }

                codecClass && sessionDecoderCap == null && recoveryStage <= 1 -> {
                    // first codec crash at full quality: fresh player, same quality
                    recoveryStage = 2
                    _message.value = "Decoder hiccup — rebuilding player…"
                    AppLog.d("Recovery", "codec error ${error.errorCodeName}: full rebuild, fresh codecs")
                    rebuildAtPosition(capHeight = null, safeMode = false)
                }

                recoveryStage <= 2 -> {
                    // 4K step-down: the box's decoder can't hold the top rendition
                    recoveryStage = 3
                    sessionDecoderCap = 1080
                    _message.value =
                        if (codecClass) "4K decoder crashed on this TV — continuing at 1080p"
                        else "Rebuilding player (quality stepped down)…"
                    AppLog.d("Recovery", "step-down rebuild, cap 1080p")
                    rebuildAtPosition(capHeight = 1080, safeMode = false)
                }

                recoveryStage <= 3 -> {
                    // fail-safe pipeline: no cache, plain network
                    recoveryStage = 4
                    sessionDecoderCap = 720
                    _message.value = "Rebuilding player (fail-safe mode)…"
                    AppLog.d("Recovery", "safe-mode rebuild, cap 720p")
                    rebuildAtPosition(capHeight = 720, safeMode = true)
                }

                else -> {
                    recoveryStage = 5
                    stream?.let { failedProviders.add(it.provider) }
                    val remaining = settings.providerOrder.filter { it !in failedProviders }
                    if (remaining.isNotEmpty() && !switchingSource) {
                        _message.value = "This source keeps failing — trying ${remaining.first().label}…"
                        autoSwitchProvider(remaining.first())
                    } else {
                        _message.value = friendlyError(error)
                    }
                }
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // runs on the player's application thread; guard anyway — an
            // exception here must never kill playback
            try {
                onTracksChangedSafe(tracks)
            } catch (t: Throwable) {
                AppLog.e("Player", "onTracksChanged guard", t)
            }
        }

        private fun onTracksChangedSafe(tracks: androidx.media3.common.Tracks) {
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

    /** Auto-pick a preferred subtitle track once the manifest exposes them.
     *
     *  Only a fallback: real embedded manifest tracks (rare on these CDNs)
     *  or the case where no sidecar could be downloaded. When our own overlay
     *  is already showing a downloaded sidecar, embedded selection is left to
     *  the user. (The previous version *nulled the active sidecar here* — that
     *  is what silently turned subtitles off after every source change.) */
    private fun maybeAutoSelectEmbedded() {
        if (embeddedAutoTried) return
        if (sidecarCues.isNotEmpty()) {
            embeddedAutoTried = true // sidecar overlay is active — nothing to do
            return
        }
        val embedded = _embeddedTextTracks.value
        if (embedded.isEmpty()) return
        if (!settings.subtitlesEnabled) return
        if (userTurnedSubsOff) return
        val pref = settings.preferredSubtitleLang.lowercase()
        val pick = embedded.firstOrNull { it.language?.lowercase()?.startsWith(pref) == true }
            ?: embedded.firstOrNull {
                it.language?.lowercase() == "en" || it.label.contains("english", true)
            } ?: return
        embeddedAutoTried = true
        if (!pick.isSelected) selectEmbeddedSub(pick.groupIndex, pick.trackIndex)
        AppLog.d("Subs", "auto-selected the stream's embedded subtitle: ${pick.label}")
    }

    init {
        viewModelScope.launch {
            settings = SettingsStore.read(context)
            _qualityMode.value = settings.quality
            _aspectMode.value = settings.aspect
            _stretchYPct.value = settings.stretchYPct
            _stretchXPct.value = settings.stretchXPct
            _subStyle.value = SubStyle(
                sizePct = settings.subtitleSize.pct,
                color = settings.subtitleColor,
                bgPct = settings.subtitleBgPct,
            )

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

            // Subtitles are SITE-NATIVE first: the listing the provider's own page
            // loaded (harvested via the /wyzie proxy in WebViewHarvester — the
            // exact tracks the site's own player shows), plus any tracks embedded
            // in the stream manifest itself. No separate subtitle API is called
            // up-front anymore; Wyzie.fetch stays only as the post-resolve
            // fallback inside startResolve for titles whose page yielded nothing.
            startResolve(currentRequest, reusePlayer = false)
        }
    }

    /**
     * Download the preferred subtitle BEFORE the first prepare so it ships with
     * the initial MediaItem — subtitles appear immediately and nothing has to
     * be restarted later. Bounded to 4s: a slow subtitle host must never delay
     * the video. Priority order:
     *  1. the SITE-SYNCED file the page downloaded itself (captured body — the
     *     exact bytes the site pairs with the stream copy it served; the timeline
     *     is correct by construction),
     *  2. the site's own listing ([providerSubs], captured from the page's
     *     /wyzie call or player action) — picked with the SITE'S OWN selection
     *     algorithm, so the app downloads the exact track the site's player
     *     would have armed (UTF-8 first, non-HI before HI, first track of the
     *     preferred language — verified from the sites' bundles),
     *  3. the direct API fallback ([_subs]) — a generic index matched to the
     *     IMDb id only (timing not guaranteed).
     */
    private suspend fun preAttachSubtitle() {
        if (!settings.subtitlesEnabled) return
        if (userTurnedSubsOff) return
        if (sidecarCues.isNotEmpty()) return

        val synced = _siteSubs.value
        if (synced.isNotEmpty()) {
            val coupled = synced.filter { it.coupled }.ifEmpty { synced }
            if (armSiteSubtitle(coupled.first())) return
        }

        val siteNative = providerSubs.isNotEmpty()
        val pool = providerSubs.ifEmpty { _subs.value }
        if (pool.isEmpty()) return // nothing to pick from yet; a later listing can still attach

        val pref = (desiredSubLang ?: settings.preferredSubtitleLang).lowercase()
        // THE SITE'S OWN SELECTION ALGORITHM (vidfast/vidcore, from their
        // bundles): sort the listing UTF-8-encoding-first, non-HI-before-HI,
        // then arm the FIRST track of the stored language. The old code took
        // the first language match in RAW order — with 34 tracks for a movie
        // that lands on a *different file* than the site's pick (different
        // release/fps), which was the "subtitles at arbitrary timelines on
        // every content" bug. mapple sorts nothing and picks the first
        // en/english track — a stable sort over an encoding-less listing
        // preserves exactly that order.
        val sorted = pool.sortedWith(
            compareBy<Wyzie.Sub>(
                { (it.encoding ?: "").uppercase() != "UTF-8" }, // UTF-8 first
                { it.isHearingImpaired == true }, // non-HI first
            )
        )
        val pick = sorted.firstOrNull { s -> s.language?.lowercase() == pref }
            ?: sorted.firstOrNull { s -> s.language?.lowercase()?.startsWith(pref) == true }
            ?: sorted.firstOrNull { s -> s.display.lowercase().startsWith(pref) }
            ?: sorted.firstOrNull { s ->
                s.language?.lowercase() == "en" || s.display.contains("english", true)
            }
            ?: return
        attachSubtitleCues(pick, siteNative = siteNative)
    }

    /**
     * Arm a subtitle the SITE ITSELF downloaded for its auto-selected track.
     * The body was captured verbatim during the harvest, so there is nothing
     * to re-download — and because the site pairs this file with the exact
     * stream copy it is serving, the cue timeline matches the video by
     * construction (the /wyzie listing can differ by cut/release and drift
     * anywhere from seconds to minutes — that was the "subtitles at any
     * timeline" bug). The sync offset resets to 0 for these; manual nudges
     * still work on top.
     */
    private fun armSiteSubtitle(sub: com.loltube.app.providers.SiteSubtitle): Boolean {
        val cues = runCatching { Subtitles.parse(sub.body) }.getOrDefault(emptyList())
        if (cues.isEmpty()) {
            AppLog.d("Subs", "site-synced body parsed 0 cues — skipping")
            return false
        }
        sidecarSub = null
        sidecarCues = cues
        desiredSubLang = settings.preferredSubtitleLang
        userTurnedSubsOff = false
        _activeSubLabel.value = if (sub.coupled) "Site-synced" else "Site fallback"
        // site-synced = perfect timeline for THIS copy — never inherit the
        // per-provider offset that was calibrated for a different subtitle file
        _subDelayMs.value = 0
        disableAllTextTracks(player.value)
        AppLog.d(
            "Subs",
            "SITE-SYNCED subtitle armed (captured body, ${cues.size} cues, sync=0) <- ${sub.url.take(90)}",
        )
        return true
    }

    /** Menu pick of a site-synced capture (instant — body already in memory). */
    fun selectSiteSubtitle(sub: com.loltube.app.providers.SiteSubtitle) {
        if (!armSiteSubtitle(sub)) {
            _message.value = "Couldn't parse the site-synced subtitle"
        }
    }

    /** Download, parse and arm a subtitle for the overlay. [siteNative] marks
     *  tracks that came from the SITE'S OWN listing (page /wyzie call or player
     *  action): those are paired with the exact stream copy the site serves —
     *  the timeline is correct by construction, so the per-provider sync
     *  offset (calibrated for *generic* index tracks) is deliberately NOT
     *  applied — manual nudges still work on top at any time. */
    private suspend fun attachSubtitleCues(sub: Wyzie.Sub, siteNative: Boolean = false): Boolean {
        val dl = kotlinx.coroutines.withTimeoutOrNull(6_000) { Wyzie.download(sub) }
        if (dl == null) {
            AppLog.d("Subs", "download failed/timed out (${sub.display}) — menu selection stays available")
            return false
        }
        val cues = runCatching { Subtitles.parse(dl.first.readText()) }.getOrDefault(emptyList())
        if (cues.isEmpty()) {
            AppLog.d("Subs", "parsed 0 cues from \"${sub.display}\" — not arming")
            return false
        }
        sidecarSub = sub
        sidecarCues = cues
        desiredSubLang = sub.language ?: sub.display
        userTurnedSubsOff = false
        _activeSubLabel.value = sub.display
        if (siteNative) {
            // site-paired track: exact timeline for THIS stream copy — sync 0
            // (a legacy per-provider offset was calibrated for a different
            // subtitle file and would re-break the timeline)
            _subDelayMs.value = 0
            AppLog.d(
                "Subs",
                "SITE-PAIRED subtitle \"${sub.display}\" armed (site's own track pick): ${cues.size} cues, sync=0ms",
            )
        } else {
            currentStream?.let {
                _subDelayMs.value = settings.subDelayByProvider[it.provider] ?: 0
            }
            AppLog.d(
                "Subs",
                "overlay subtitle \"${sub.display}\" armed: ${cues.size} cues, sync=${_subDelayMs.value}ms for ${currentStream?.provider?.label ?: "?"}",
            )
        }
        return true
    }

    /** Full cause-chain + stack logging so the next log.txt shows root causes. */
    private fun logPlaybackError(error: PlaybackException) {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(4)
            .joinToString(" <- ") { c -> "${c.javaClass.simpleName}(${c.message?.take(90)})" }
        AppLog.e("Player", "playback error: ${error.errorCodeName} :: $chain", error)
    }

    /** Combine the provider-harvested listing with the directly-fetched one. */
    private fun mergeSubs(extra: List<Wyzie.Sub>) {
        val merged = (providerSubs + extra)
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .sortedByDescending { it.language?.lowercase() == settings.preferredSubtitleLang.lowercase() }
        _subs.value = merged
    }

    /**
     * Reads the stream's own master playlist and pre-selects the highest
     * rendition this TV can honestly decode. Two outcomes:
     *  - the box's codec list supports the top variant (2160p): full quality,
     *    4K stays the goal;
     *  - it doesn't: quality ceiling is pre-lowered to the best decodable
     *    height BEFORE the first prepare, so playback never even attempts the
     *    rendition that would crash (the crash-then-step-down ladder in
     *    onPlayerError still covers lying vendor codecs behind this).
     */
    private suspend fun probeLadder(stream: ResolvedStream) {
        if (stream.kind != "m3u8-master" && stream.kind != "m3u8") return
        val text = kotlinx.coroutines.withTimeoutOrNull(6_000) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { Net.get(stream.url, headers = stream.headers) }.getOrNull()
            }
        } ?: return
        val ladder = PlayerEngine.parseLadder(text)
        if (ladder.isEmpty()) return
        val top = ladder.first()
        AppLog.d(
            "Ladder",
            "site ladder: " + ladder.take(6).joinToString { PlayerEngine.describeVariant(it) },
        )
        if (top.height < 2000) return // no 4K variant to worry about

        val decodable = PlayerEngine.maxDecodableHeight(ladder)
        if (decodable == null || decodable < top.height) {
            val cap = decodable?.coerceIn(360, 1080) ?: 720
            sessionDecoderCap = minOf(cap, sessionDecoderCap ?: cap)
            AppLog.d(
                "Ladder",
                "TV decoders top out at ${decodable ?: "unknown"}p — starting capped at ${sessionDecoderCap}p (ladder top ${top.height}p)",
            )
            _message.value = "4K available — this TV's decoders support up to ${decodable ?: "?"}p"
        } else {
            AppLog.d("Ladder", "4K ${top.height}p decodable (${top.codecs ?: "?"}) — full quality enabled")
        }
    }

    private fun startResolve(
        req: PlaybackRequest,
        reusePlayer: Boolean,
        forceOrder: List<ProviderId>? = null,
        excludeHost: String? = null,
    ) {
        viewModelScope.launch {
            _phase.value = Phase.Resolving((forceOrder?.first() ?: providerChoice)?.label ?: "Auto", "Starting…")
            val order = forceOrder ?: providerChoice?.let { listOf(it) } ?: settings.providerOrder

            val (stream, attempts) = resolver.resolve(req, order, settings.preferredSubtitleLang, excludeHost) { id, status ->
                _phase.value = Phase.Resolving(id.label, status)
            }

            if (stream == null) {
                if (reusePlayer) {
                    // an auto-switch that failed to resolve must not permanently
                    // disable future switches (the old code left
                    // switchingSource latched forever after one failed switch);
                    // remember which providers hard-failed so the next attempt
                    // rotates to an untried one instead of looping
                    switchingSource = false
                    attempts.forEach { failedProviders.add(it.provider) }
                    stallStage = 2 // re-arm the watchdog so it can try the next source
                    if (bufferingSinceMs == 0L) bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
                }
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
            _siteSubs.value = stream.siteSubs
            if (providerSubs.isNotEmpty()) mergeSubs(emptyList())

            // Site-native subtitle listing came back empty (mapple, or a page
            // whose /wyzie call failed): fall back to the shared subtitle index
            // so the menu still offers tracks — and try to auto-attach the
            // preferred language from it too (subtitles must not require a
            // manual menu visit after every source change).
            if (providerSubs.isEmpty()) {
                viewModelScope.launch {
                    val wyzieSubs = Wyzie.fetch(req.imdbId, req.season, req.episode)
                    if (wyzieSubs.isNotEmpty()) {
                        mergeSubs(wyzieSubs)
                        AppLog.d("Subs", "site listing empty — subtitle-API fallback: ${wyzieSubs.size} track(s)")
                        if (sidecarCues.isEmpty() && !userTurnedSubsOff) {
                            kotlinx.coroutines.withTimeoutOrNull(4_000) { preAttachSubtitle() }
                        }
                    }
                }
            }

            // fresh stream re-arms the recovery ladder (but NOT the session
            // decoder cap — a box that crashed at 4K must not crash again)
            failedProviders.clear()
            recoveryStage = 0

            // One quick look at the site's own ladder before building the
            // player: what the top variant is and whether this TV's codecs
            // honestly support it. Prevents the guaranteed 4K crash on boxes
            // whose codec list itself already says no.
            probeLadder(stream)

            // Re-arm the subtitle auto-attach for the NEW stream: the sticky
            // language (or the previously parsed sidecar of the same title)
            // carries over — subtitles stay ON across source switches. The
            // SITE-SYNCED capture of the new stream wins over the carried-over
            // sidecar (it matches the new copy exactly).
            embeddedAutoTried = false
            if (stream.siteSubs.isNotEmpty()) {
                sidecarSub = null
                sidecarCues = emptyList()
            }
            _subDelayMs.value = settings.subDelayByProvider[stream.provider] ?: 0
            if (sidecarCues.isEmpty()) {
                kotlinx.coroutines.withTimeoutOrNull(4_000) { preAttachSubtitle() }
            }

            val existing = if (reusePlayer) player.value else null
            val p = existing
                ?: PlayerEngine.build(
                    context = context,
                    settings = settings,
                    streamHeaders = stream.headers,
                ).let { built ->
                    built.player.addListener(playerListener)
                    built.player.addAnalyticsListener(analyticsListener)
                    _player.value = built.player
                    startLoops()
                    built.player
                }

            PlayerEngine.applyQualityMode(p, _qualityMode.value, sessionDecoderCap)
            autoReResolved = false
            bufferingSinceMs = 0
            stallStage = 0
            switchingSource = false
            idleSinceMs = 0

            // a watchdog switch that landed on the SAME CDN backend the old
            // source was stalling on is worth knowing in the log
            if (reusePlayer) {
                val oldHost = excludeHost
                val newHost = android.net.Uri.parse(stream.url).host
                if (oldHost != null && oldHost == newHost) {
                    AppLog.d("Watchdog", "switched to ${stream.provider.label} but backend host is unchanged ($newHost)")
                }
            }

            val mediaItem = PlayerEngine.buildMediaItem(stream.url, stream.kind, null)
            p.setMediaItem(mediaItem, if (req.startAtMs > 0) req.startAtMs else 0)
            p.prepare()
            p.playWhenReady = true
            _phase.value = Phase.Ready(stream.provider, stream.kind, stream.allCandidates.size)
        }
    }

    // ---- self-healing recovery -----------------------------------------------

    /** Recovery stage 1: soft reset — clears the error state and resumes. */
    private fun reprepareAtPosition() {
        val p = player.value ?: return
        try {
            val pos = _positionMs.value
            p.seekTo(pos)
            p.prepare()
            p.playWhenReady = true
        } catch (t: Throwable) {
            AppLog.e("Recovery", "re-prepare failed", t)
        }
    }

    /**
     * Recovery: FULL rebuild — release everything (a poisoned codec instance
     * never recovers via prepare()), build a fresh player and continue at the
     * same position.
     *
     * @param capHeight decoder-safety ceiling for the rebuild (null = keep
     * whatever quality is currently allowed)
     * @param safeMode fail-safe pipeline: no disk cache, direct network — for
     * repeated failures
     */
    private fun rebuildAtPosition(capHeight: Int?, safeMode: Boolean) {
        val stream = currentStream ?: return
        val pos = _positionMs.value
        player.value?.let {
            runCatching {
                it.removeListener(playerListener)
                it.removeAnalyticsListener(analyticsListener)
                it.release()
            }
        }
        _player.value = null

        val built = PlayerEngine.build(
            context = context,
            settings = settings,
            streamHeaders = stream.headers,
            safeMode = safeMode,
        )
        built.player.addListener(playerListener)
        built.player.addAnalyticsListener(analyticsListener)
        _player.value = built.player
        PlayerEngine.applyQualityMode(built.player, _qualityMode.value, sessionDecoderCap ?: capHeight)

        val mediaItem = PlayerEngine.buildMediaItem(stream.url, stream.kind, null)
        built.player.setMediaItem(mediaItem, pos)
        built.player.prepare()
        built.player.playWhenReady = true
    }

    // ---- stall watchdog -------------------------------------------------------
    //
    // If playback keeps buffering for a long stretch the ladder is:
    //   1) re-prepare the stream (recovers dead loaders),
    //   2) re-prepare again,
    //   3) silently switch to the next provider at the same position.
    // Each step re-arms the timer; any recovery (STATE_READY) resets it.
    //
    // IMPORTANT: in 4K-lock mode the watchdog NEVER silently lowers the
    // visual quality — the user's whole point is "play 4K, don't change what I
    // see". The old stage-0 auto-cap to 1080p is exactly the invisible quality
    // drop that made "no matter what I set, it looks the same". Only the AUTO
    // (adaptive) mode may still trade quality for smoothness — that's its job.

    private fun checkStallWatchdog() {
        val since = bufferingSinceMs
        if (since == 0L) return
        val now = android.os.SystemClock.elapsedRealtime()
        val stalledFor = now - since
        val locked = _qualityMode.value == QualityMode.MAX
        val stage0Timeout = when {
            locked -> 25_000L // locked 4K: hold quality, be patient (flywheel catches up)
            hadFirstReady -> 12_000L
            else -> 20_000L
        }

        when {
            stallStage == 0 && stalledFor > stage0Timeout -> {
                bufferingSinceMs = now
                stallStage = 1
                if (!locked && _qualityMode.value == QualityMode.AUTO) {
                    // SESSION-ONLY cap: a cold-start stall must not write
                    // CAP_1080 into DataStore — that persistence is what made
                    // "first open after reinstall never loads, then 1080p
                    // forever" (fresh install = AUTO = top variant on a cold
                    // line; the stall wrote the cap; every later session
                    // started capped). The session cap still rescues THIS
                    // playback, and the next session re-tests the line.
                    setQuality(QualityMode.CAP_1080, persist = false)
                    _message.value = "Slow line detected — capped to 1080p, restarting the stream…"
                    reprepareStream("Slow line — capped to 1080p, restarting stream")
                } else {
                    reprepareStream(
                        if (locked) "Holding 4K — reconnecting stream…"
                        else "Reconnecting stream…"
                    )
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

    /** REAL reconnect: re-set the same MediaItem at the current position and
     *  prepare from scratch. A bare `prepare()` is a no-op on a player that is
     *  already in the preparing/buffering state — it does NOT cancel a hung
     *  loader request, which is why the old "reconnect" never actually
     *  reloaded anything (the first-open stall just sat there). Re-setting the
     *  media item tears the loading pipeline down and restarts it. */
    private fun reprepareStream(msg: String) {
        val p = player.value ?: return
        val item = p.currentMediaItem ?: return
        val pos = _positionMs.value.coerceAtLeast(0)
        AppLog.d("Watchdog", msg)
        _message.value = msg
        try {
            p.setMediaItem(item, pos)
            p.prepare()
            p.playWhenReady = true
        } catch (t: Throwable) {
            AppLog.d("Watchdog", "re-prepare failed: ${t.message}")
        }
    }

    private fun autoSwitchProvider(next: ProviderId? = null) {
        if (switchingSource) return
        val stream = currentStream ?: return
        val order = settings.providerOrder.filter { it !in failedProviders }
        if (order.size < 2) {
            AppLog.d("Watchdog", "no other provider left to try (${failedProviders.size} failed) — holding")
            return
        }
        val target = next ?: run {
            val idx = order.indexOf(stream.provider)
            if (idx < 0) order.first() else order[(idx + 1) % order.size]
        }
        switchingSource = true
        AppLog.d("Watchdog", "stalling on ${stream.provider.label} — switching to ${target.label}")
        _message.value = "This source is stalling — switching to ${target.label}…"
        val pos = _positionMs.value
        // Providers often share one CDN backend (vidcore + vidfast both serve
        // from moon.goldenkite.top) — switching between them changes nothing.
        // Resolve the REST of the provider list in one pass and prefer a stream
        // from a DIFFERENT backend host when one verifies.
        val excludeHost = android.net.Uri.parse(stream.url).host
        val curIdx = order.indexOf(stream.provider)
        val restOrder = if (curIdx >= 0) {
            order.drop(curIdx + 1) + order.take(curIdx)
        } else {
            order
        }
        startResolve(
            currentRequest.copy(startAtMs = pos),
            reusePlayer = true,
            forceOrder = restOrder.ifEmpty { listOf(target) },
            excludeHost = excludeHost,
        )
    }

    // ---- progress ----------------------------------------------------------
    private fun startLoops() {
        viewModelScope.launch {
            while (true) {
                delay(400)
                val pl = player.value
                if (pl == null) {
                    // player is being rebuilt (recovery) — wait, don't die
                    delay(600)
                    continue
                }
                _positionMs.value = pl.currentPosition.coerceAtLeast(0)
                if (pl.duration > 0) _durationMs.value = pl.duration
                _bufferedMs.value = pl.bufferedPosition.coerceAtLeast(0)
                checkStallWatchdog()

                // Wedged-loader guard: a player that sits in STATE_IDLE for >10s
                // in Phase.Ready is a loader that will never emit another event
                // (observed: watchdog went fully silent after a source switch —
                // no BUFFERING, no error, nothing). The automated equivalent of
                // the force-close + reopen that fixed it manually: a FULL player
                // rebuild on the same stream at the same position.
                if (idleSinceMs > 0 && !switchingSource && _phase.value is Phase.Ready) {
                    val idleFor = android.os.SystemClock.elapsedRealtime() - idleSinceMs
                    if (idleFor > 10_000) {
                        idleSinceMs = 0
                        if (idleRebuilds < 2) {
                            idleRebuilds++
                            AppLog.d("Watchdog", "player idle ${idleFor / 1000}s with no events — full rebuild ($idleRebuilds/2)")
                            _message.value = "Reconnecting the stream…"
                            rebuildAtPosition(capHeight = null, safeMode = false)
                        } else {
                            AppLog.d("Watchdog", "player idle ${idleFor / 1000}s — rebuild budget spent, letting the stall watchdog/source-switch handle it")
                        }
                    }
                }

                // 20s of clean playback re-arms the recovery ladder from stage 0
                if (cleanSinceMs > 0 && recoveryStage > 0 && !switchingSource) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - cleanSinceMs > 20_000) {
                        AppLog.d("Recovery", "playback stable ${recoveryStage}→0")
                        recoveryStage = 0
                    }
                }
                if (pl.playbackState != Player.STATE_READY || pl.playWhenReady.not()) {
                    cleanSinceMs = 0L
                }
            }
        }
        // Fast subtitle tick: the overlay must resolve cues at ~±0.1s
        // precision, tighter than the 400ms position loop. Cheap: binary
        // search over already-parsed cues, no allocation.
        viewModelScope.launch {
            while (true) {
                delay(100)
                val pl = player.value ?: continue
                val cues = sidecarCues
                _cueText.value = if (cues.isEmpty()) null
                else Subtitles.activeCueAt(cues, pl.currentPosition.coerceAtLeast(0), _subDelayMs.value)?.text
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

    fun setQuality(mode: QualityMode, persist: Boolean = true) {
        _qualityMode.value = mode
        val p = player.value ?: return
        PlayerEngine.applyQualityMode(p, mode, sessionDecoderCap)
        // Lock mode pins the top rendition via trackSelectionParameters (the
        // switch is INSTANT — the very next segment comes from the new
        // rendition, no reload); other modes return to adaptive selection.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        // Only the USER's explicit choice is persisted. Watchdog auto-caps are
        // session-only (persist=false): an automatic degradation must never
        // outlive the session — it otherwise silently downgraded 4K forever
        // after one bad night, and made reinstall reproduce the whole failure.
        if (persist) {
            viewModelScope.launch { SettingsStore.setQuality(context, mode) }
        }
    }

    fun setAspect(mode: AspectMode) {
        _aspectMode.value = mode
        viewModelScope.launch { SettingsStore.setAspect(context, mode) }
    }

    /**
     * Manual stretch on top of the aspect mode, in % (100 = off).
     * [vertical] = top-bottom stretch (removes bars baked INTO the video —
     * auto-fill can't detect those); horizontal = left-right stretch (odd
     * screens/phones). Persisted, applied instantly (pure view transform).
     */
    fun nudgeStretch(vertical: Boolean, deltaPct: Int) {
        if (vertical) {
            _stretchYPct.value = (_stretchYPct.value + deltaPct).coerceIn(100, 160)
            viewModelScope.launch { SettingsStore.setStretchY(context, _stretchYPct.value) }
        } else {
            _stretchXPct.value = (_stretchXPct.value + deltaPct).coerceIn(100, 160)
            viewModelScope.launch { SettingsStore.setStretchX(context, _stretchXPct.value) }
        }
        AppLog.d("Stretch", (if (vertical) "vertical" else "horizontal") + " -> ${if (vertical) _stretchYPct.value else _stretchXPct.value}%")
    }

    fun resetStretch() {
        _stretchYPct.value = 100
        _stretchXPct.value = 100
        viewModelScope.launch {
            SettingsStore.setStretchY(context, 100)
            SettingsStore.setStretchX(context, 100)
        }
        AppLog.d("Stretch", "manual stretch reset")
    }

    /**
     * Pick a downloaded sidecar subtitle from the menu. Instant: the overlay
     * simply switches its cue list — the video stream is never restarted
     * (the old implementation re-prepared the media item for this).
     */
    fun selectSubtitle(sub: Wyzie.Sub?) {
        if (sub == null) {
            sidecarSub = null
            sidecarCues = emptyList()
            _cueText.value = null
            _activeSubLabel.value = null
            userTurnedSubsOff = true // explicit Off — stay off across source switches
            disableAllTextTracks(player.value)
            AppLog.d("Subs", "subtitles off (user)")
            return
        }
        viewModelScope.launch {
            // a menu track that comes from the site's own listing is site-paired
            // (correct timeline by construction → sync 0); generic index
            // tracks keep the persisted per-provider offset
            val siteNative = providerSubs.any { it.url == sub.url }
            if (!attachSubtitleCues(sub, siteNative = siteNative)) {
                _message.value = "Couldn't download subtitle (${sub.display})"
            }
        }
    }

    /** Switch to a subtitle track embedded in the stream's own manifest. */
    fun selectEmbeddedSub(groupIndex: Int, trackIndex: Int) {
        val p = player.value ?: return
        val tracks = p.currentTracks
        if (groupIndex >= tracks.groups.size) return
        val group = tracks.groups[groupIndex]
        if (trackIndex >= group.length) return
        sidecarSub = null // overlay off — the PlayerView renders embedded tracks
        sidecarCues = emptyList()
        _cueText.value = null
        userTurnedSubsOff = false
        val fmt = group.getTrackFormat(trackIndex)
        _activeSubLabel.value = fmt.label ?: fmt.language ?: "Embedded"
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    /**
     * Nudge the per-source subtitle sync delay (ms). Positive = subtitles
     * appear LATER (the fix when they run ahead of the audio); negative =
     * earlier. Applied instantly — a pure display-side shift, exactly like
     * the sites' own timing adjusters (mapple's timingAdjustment, vidfast's
     * subtitle offset) — and persisted PER PROVIDER, because each source's
     * video copy is trimmed differently (up to a minute of drift is possible).
     */
    fun nudgeSubDelay(deltaMs: Int) {
        setSubDelay(_subDelayMs.value + deltaMs)
    }

    /** Direct set (the ±60s slider) — same instant + per-provider persistence. */
    fun setSubDelay(ms: Int) {
        val next = ms.coerceIn(-60_000, 60_000)
        _subDelayMs.value = next
        _cueText.value = Subtitles.activeCueAt(sidecarCues, _positionMs.value, next)?.text
        val provider = currentStream?.provider ?: return
        viewModelScope.launch { SettingsStore.setSubDelayFor(context, provider, next) }
        AppLog.d("Subs", "sync delay for ${provider.label} -> ${next}ms")
    }

    fun resetSubDelay() {
        _subDelayMs.value = 0
        _cueText.value = Subtitles.activeCueAt(sidecarCues, _positionMs.value, 0)?.text
        val provider = currentStream?.provider ?: return
        viewModelScope.launch { SettingsStore.setSubDelayFor(context, provider, 0) }
        AppLog.d("Subs", "sync delay for ${provider.label} -> reset")
    }

    /** Re-read settings (subtitle appearance / stretch / sync may have changed). */
    fun refreshSubStyle() {
        viewModelScope.launch {
            settings = SettingsStore.read(context)
            _subStyle.value = SubStyle(
                sizePct = settings.subtitleSize.pct,
                color = settings.subtitleColor,
                bgPct = settings.subtitleBgPct,
            )
            _stretchYPct.value = settings.stretchYPct
            _stretchXPct.value = settings.stretchXPct
            currentStream?.let {
                _subDelayMs.value = settings.subDelayByProvider[it.provider] ?: 0
            }
        }
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
        recoveryStage = 0
        cleanSinceMs = 0
        failedProviders.clear()
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
        recoveryStage = 0
        cleanSinceMs = 0
        synchronized(rebufferTimes) { rebufferTimes.clear() }
        bufferingSinceMs = 0
        stallStage = 0
        switchingSource = false
        _stats.value = Stats()
        _pendingNext.value = null
        _subs.value = emptyList()
        providerSubs = emptyList()
        _activeSubLabel.value = null
        // new episode = fresh subtitle file (its timing is episode-specific);
        // the sticky LANGUAGE stays so the same language auto-attaches.
        sidecarSub = null
        sidecarCues = emptyList()
        _cueText.value = null
        userTurnedSubsOff = false
        _embeddedTextTracks.value = emptyList()
        _message.value = "Loading S$season E$episode…"
        currentRequest = req
        // No direct subtitle-API call here: the new episode's listing comes from
        // its own harvest (or the fallback inside startResolve when empty).
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
