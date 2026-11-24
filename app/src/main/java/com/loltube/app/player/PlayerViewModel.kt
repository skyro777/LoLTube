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
    private var prefetcher: TurboPrefetcher? = null

    // stall watchdog state
    @Volatile private var bufferingSinceMs = 0L
    @Volatile private var stallStage = 0
    @Volatile private var hadFirstReady = false
    @Volatile private var switchingSource = false

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
                    if (cleanSinceMs == 0L) cleanSinceMs = android.os.SystemClock.elapsedRealtime()
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
                    // fail-safe pipeline: no cache, no prefetch, plain network
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
     * the video. The preferred source is the SITE's own listing
     * ([providerSubs], captured from the page's /wyzie call); the direct API
     * ([_subs], filled by the fallback) is only used when that was empty.
     */
    private suspend fun preAttachSubtitle() {
        if (!settings.subtitlesEnabled) return
        if (userTurnedSubsOff) return
        if (sidecarCues.isNotEmpty()) return
        val pool = providerSubs.ifEmpty { _subs.value }
        if (pool.isEmpty()) return // nothing to pick from yet; a later listing can still attach

        val pref = (desiredSubLang ?: settings.preferredSubtitleLang).lowercase()
        val pick = pool.firstOrNull { s -> s.language?.lowercase()?.startsWith(pref) == true }
            ?: pool.firstOrNull { s -> s.display.lowercase().startsWith(pref) }
            ?: pool.firstOrNull { s ->
                s.language?.lowercase() == "en" || s.display.contains("english", true)
            }
            ?: return
        attachSubtitleCues(pick)
    }

    /** Download, parse and arm a subtitle for the overlay. Returns success. */
    private suspend fun attachSubtitleCues(sub: Wyzie.Sub): Boolean {
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
        currentStream?.let {
            _subDelayMs.value = settings.subDelayByProvider[it.provider] ?: 0
        }
        AppLog.d(
            "Subs",
            "overlay subtitle \"${sub.display}\" armed: ${cues.size} cues, sync=${_subDelayMs.value}ms for ${currentStream?.provider?.label ?: "?"}",
        )
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
            // carries over — subtitles stay ON across source switches, only
            // the per-source sync delay changes.
            embeddedAutoTried = false
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

            PlayerEngine.applyQualityMode(p, _qualityMode.value, sessionDecoderCap)
            autoReResolved = false
            bufferingSinceMs = 0
            stallStage = 0
            switchingSource = false

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
     * @param safeMode fail-safe pipeline: no disk cache, no prefetch, direct
     * network — for repeated failures
     */
    private fun rebuildAtPosition(capHeight: Int?, safeMode: Boolean) {
        val stream = currentStream ?: return
        val pos = _positionMs.value
        prefetcher?.stop()
        prefetcher = null
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
            prefetchEnabled = { settings.turboPrefetch },
            bufferAheadMs = { (_bufferedMs.value - _positionMs.value).coerceAtLeast(0) },
            positionMs = { _positionMs.value },
            safeMode = safeMode,
        )
        built.player.addListener(playerListener)
        built.player.addAnalyticsListener(analyticsListener)
        _player.value = built.player
        prefetcher = built.prefetcher
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
                    setQuality(QualityMode.CAP_1080)
                    _message.value = "Slow line detected — capped to 1080p for smooth playback"
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

    private fun autoSwitchProvider(next: ProviderId? = null) {
        if (switchingSource) return
        val stream = currentStream ?: return
        val order = settings.providerOrder
        if (order.size < 2) return
        val target = next ?: run {
            val idx = order.indexOf(stream.provider)
            order[(idx + 1) % order.size]
        }
        switchingSource = true
        AppLog.d("Watchdog", "stalling on ${stream.provider.label} — switching to ${target.label}")
        _message.value = "This source is stalling — switching to ${target.label}…"
        val pos = _positionMs.value
        startResolve(currentRequest.copy(startAtMs = pos), reusePlayer = true, forceOrder = listOf(target))
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

    fun setQuality(mode: QualityMode) {
        _qualityMode.value = mode
        val p = player.value ?: return
        PlayerEngine.applyQualityMode(p, mode, sessionDecoderCap)
        // Lock mode pins the top rendition via trackSelectionParameters (the
        // switch is INSTANT — the very next segment comes from the new
        // rendition, no reload); other modes return to adaptive selection.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        viewModelScope.launch { SettingsStore.setQuality(context, mode) }
    }

    fun setAspect(mode: AspectMode) {
        _aspectMode.value = mode
        viewModelScope.launch { SettingsStore.setAspect(context, mode) }
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
            if (!attachSubtitleCues(sub)) {
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
     * video copy is trimmed differently (2–6s drift depending on source).
     */
    fun nudgeSubDelay(deltaMs: Int) {
        val next = (_subDelayMs.value + deltaMs).coerceIn(-30_000, 30_000)
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

    /** Re-read settings (subtitle appearance may have changed in Settings). */
    fun refreshSubStyle() {
        viewModelScope.launch {
            settings = SettingsStore.read(context)
            _subStyle.value = SubStyle(
                sizePct = settings.subtitleSize.pct,
                color = settings.subtitleColor,
                bgPct = settings.subtitleBgPct,
            )
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
