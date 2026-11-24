package com.loltube.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.loltube.app.data.AppSettings
import com.loltube.app.data.BufferProfile
import com.loltube.app.data.QualityMode
import com.loltube.app.providers.MappleStreamInterceptor
import com.loltube.app.util.AppLog

/**
 * Builds ExoPlayer instances tuned for stall-free playback:
 *  - ABR that can actually DOWN-switch quickly (locking ABR off once the buffer
 *    was full is what rebuffered forever on slow lines);
 *  - an up-to-800 MB on-disk segment cache every player byte flows through
 *    (seek-backs and replays become disk reads); the old 4-worker parallel
 *    prefetcher was removed in v0.8.0 — it stalled all three sources;
 *  - large time buffers with a memory-aware byte budget (the default ~131 MB
 *    byte cap silently truncated the 4K buffer; asking for more than the device
 *    has RAM for does the opposite);
 *  - a crash-proof data pipeline: every byte flows through [SafeDataSource],
 *    which converts any runtime-exception race (cache/OkHttp collisions — the
 *    source of ERROR_CODE_FAILED_RUNTIME_CHECK) into a retriable network error
 *    instead of a fatal one;
 *  - a crash-proof renderer layer ([SafeRenderers]): the codec-surface switch
 *    bug on vendor TV-box decoders (media3 #1641/#2529/#3011) and any codec
 *    query failure are survived instead of killing the stream with
 *    ERROR_CODE_FAILED_RUNTIME_CHECK; software decoders stay available as
 *    real fallback (the old hardware-only filter removed them);
 *  - OkHttp transport with keep-alive pooling.
 */
@UnstableApi
object PlayerEngine {

    /**
     * (minDurationForQualityIncreaseMs, maxDurationForQualityDecreaseMs,
     *  minDurationToRetainAfterDiscardMs, bandwidthFraction)
     *
     * maxDurationForQualityDecrease is the important one: a down-switch is only
     * allowed while buffered media < this. The old value of 10s made that
     * unreachable with a 30s+ buffer, so ABR never down-switched. 75s means the
     * player can drop to a sustainable quality as soon as throughput dips.
     */
    private const val ABR_MIN_INCREASE_MS = 10_000
    private const val ABR_MAX_DECREASE_MS = 75_000
    private const val ABR_RETAIN_MS = 25_000
    private const val ABR_BANDWIDTH_FRACTION = 0.55f

    /** A player plus the resources that must be released with it. */
    data class Built(val player: ExoPlayer)

    /**
     * @param safeMode rebuild without disk cache — used by the self-healing
     * recovery ladder after repeated failures. Identical to the v0.2 pipeline
     * that always played, so even a total cache failure can only degrade
     * throughput, never playback itself.
     */
    fun build(
        context: Context,
        settings: AppSettings,
        streamHeaders: Map<String, String>,
        safeMode: Boolean = false,
    ): Built {
        val profile = settings.bufferProfile

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minMs,
                profile.maxMs,
                profile.playbackMs,
                profile.rebufferMs,
            )
            .setTargetBufferBytes(targetBufferBytes(context, profile))
            .setBackBuffer(profile.backMs, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val adaptiveFactory = AdaptiveTrackSelection.Factory(
            ABR_MIN_INCREASE_MS,
            ABR_MAX_DECREASE_MS,
            ABR_RETAIN_MS,
            ABR_BANDWIDTH_FRACTION,
        )
        val trackSelector = DefaultTrackSelector(context, adaptiveFactory)

        // Stock pipeline with the safe video renderer patched in — see
        // [SafeRenderers]. Software decoders (c2.android.*) are kept as genuine
        // fallback: the old hardware-only filter is what left some boxes with
        // no usable decoder at all.
        val renderersFactory = SafeRenderers.factory(context)
            .setMediaCodecSelector(SAFE_CODEC_SELECTOR)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val okClient = MappleStreamInterceptor.playbackClient(streamHeaders, parallel = !safeMode)
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okClient)
            .setDefaultRequestProperties(streamHeaders)

        val mediaSourceFactory: DefaultMediaSourceFactory = if (safeMode) {
            // fail-safe: network only, nothing between the player and the CDN — but
            // still scheme-ROUTED (DefaultDataSource). Feeding file:// subtitle
            // sidecars straight into OkHttpDataSource was the actual
            // ERROR_CODE_FAILED_RUNTIME_CHECK killer: media3's OkHttp datasource
            // runs HttpUrl.parse(), which returns null ("Malformed URL") for
            // anything that is not http/https — including the file:// URIs of the
            // pre-attached subtitles — and throws that as a FATAL error.
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
        } else {
            // Every byte the player reads (manifests + segments) flows through the
            // disk cache AND the crash-proof wrapper. DefaultDataSource in front
            // routes by scheme: http(s) -> cache/OkHttp pipeline, file:// ->
            // FileDataSource (subtitle sidecars!), content:// -> ContentDataSource.
            // This is what finally makes subtitles load instead of killing the stream.
            val cache = PlaybackCache.get(context)
            val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(
                    androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                )
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(context, SafeDataSource.Factory(cacheFactory))
            )
        }

        // Warm-start the ABR with the bandwidth we measured last session (slightly
        // conservative so an optimistic measurement doesn't pick 4K immediately).
        val bandwidthMeter = if (settings.lastBandwidthBps > 200_000) {
            DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate((settings.lastBandwidthBps * 0.8f).toLong())
                .build()
        } else {
            DefaultBandwidthMeter.Builder(context).build()
        }

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()

        // media audio focus + pause when headphones unplugged
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )

        // auto-select the preferred subtitle language for embedded AND sidecar tracks
        if (settings.preferredSubtitleLang.isNotBlank() && settings.preferredSubtitleLang != "off") {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(settings.preferredSubtitleLang)
                .build()
        }

        applyQualityMode(player, settings.quality)
        return Built(player)
    }

    /**
     * Time buffers want a lot of bytes at 4K (90s @ 16-40 Mbps = 180-450 MB),
     * but asking for more RAM than the device class allows causes pressure and
     * evictions elsewhere. Budget: profile baseline, clamped to half the app's
     * memory class (never below 90 MB).
     */
    private fun targetBufferBytes(context: Context, profile: BufferProfile): Int {
        val baseline = if (profile.maxMs >= 90_000) 230 shl 20 else 90 shl 20
        val ramCap = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memoryClassMb = (am?.memoryClass ?: 128).coerceAtLeast(128)
            (memoryClassMb / 2.0 * 1024 * 1024).toInt()
        }.getOrDefault(96 shl 20)
        return baseline.coerceAtMost(ramCap.coerceAtLeast(90 shl 20))
    }

    /**
     * Applies a quality mode.
     *
     *  - LOCK (MAX): a REAL lock — [setForceHighestSupportedBitrate] pins the
     *    selection to the top rendition the codecs support, regardless of what
     *    the ABR bandwidth estimate happens to be. This is what brings 4K
     *    *instantly*: the top variant starts playing from the first segment,
     *    and switching the setting mid-playback re-selects the track on the
     *    spot (no reload). The old code only raised a *ceiling* and let ABR
     *    decide — on a 25–30 Mbps line the 16.2 Mbps 2160p variant never won,
     *    so "prefer 4K" silently played 1080p and looked soft/"crunched".
     *  - AUTO: fully adaptive up to 4K (ceiling only).
     *  - CAP_*: ceiling + master-playlist rewriting via [CapPolicy].
     *
     * [maxHeightOverride] is the decoder-safety ceiling computed by
     * [probeLadder] / the recovery ladder: 4K is attempted whenever the
     * device's codecs honestly report support; a box whose vendor decoder
     * chokes on 2160p gets playback instead of a crash.
     */
    fun applyQualityMode(player: ExoPlayer, mode: QualityMode, maxHeightOverride: Int? = null) {
        val ceiling = when (mode) {
            QualityMode.AUTO, QualityMode.MAX -> 2160
            QualityMode.CAP_1080 -> 1088
            QualityMode.CAP_720 -> 736
        }
        val effective = maxHeightOverride?.let { minOf(it, ceiling) } ?: ceiling
        val params = player.trackSelectionParameters.buildUpon()
        // The lock: force the highest supported rendition within the constraints
        // (must be set to false explicitly in the other modes — parameters persist).
        params.setForceHighestSupportedBitrate(mode == QualityMode.MAX)
        when (mode) {
            QualityMode.AUTO, QualityMode.MAX -> params.setMaxVideoSize(3840, effective)
            QualityMode.CAP_1080 -> params.setMaxVideoSize(1920, effective)
            QualityMode.CAP_720 -> params.setMaxVideoSize(1280, effective)
        }
        player.trackSelectionParameters = params.build()
        CapPolicy.maxVideoHeight = effective
        AppLog.d(
            "Player",
            if (mode == QualityMode.MAX)
                "4K LOCK engaged -> top rendition pinned (ceiling ${ceiling}p -> effective ${effective}p)"
            else
                "quality mode -> $mode (ceiling ${ceiling}p -> effective ${effective}p)"
        )
    }

    /** True if a 4K hardware decoder exists for HEVC/AVC at 3840x2160@30. */
    fun has4kDecoder(mime: String = MimeTypes.VIDEO_H265): Boolean = try {
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false).any { info ->
            info.hardwareAccelerated && run {
                val caps = info.capabilities?.videoCapabilities
                caps != null && caps.areSizeAndRateSupported(3840, 2160, 30.0)
            }
        }
    } catch (t: Throwable) {
        AppLog.e("Player", "codec probe failed: ${t.message}")
        false
    }

    // ---- variant ladder (parsed from the harvested master playlist) ---------

    /** One #EXT-X-STREAM-INF variant, as the site's CDN serves it. */
    data class Variant(
        val height: Int,
        val width: Int,
        val bandwidth: Long,
        val codecs: String?,
        val uri: String,
    )

    /** Parses `#EXT-X-STREAM-INF` variants out of a master playlist. */
    fun parseLadder(masterText: String): List<Variant> {
        val out = mutableListOf<Variant>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)?.groupValues
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
                val codecs = Regex("""CODECS="?([^",]+)"?""").find(line)?.groupValues?.get(1)
                var j = i + 1
                var uri: String? = null
                while (j < lines.size) {
                    val l2 = lines[j].trim()
                    if (l2.isNotEmpty() && !l2.startsWith("#")) {
                        uri = l2
                        break
                    }
                    j++
                }
                if (uri != null && res != null) {
                    out.add(
                        Variant(
                            height = res[2].toIntOrNull() ?: 0,
                            width = res[1].toIntOrNull() ?: 0,
                            bandwidth = bw ?: 0,
                            codecs = codecs,
                            uri = uri,
                        )
                    )
                }
                i = j
            } else {
                i++
            }
        }
        return out.sortedByDescending { it.height }
    }

    /**
     * Highest variant height the device can honestly decode:
     *  - 4K (>1088p) requires a HARDWARE decoder advertising the exact size —
     *    software 4K cannot keep up on TV-box CPUs;
     *  - <=1080p accepts hardware or software.
     * Lying vendor codecs (claim 4K, die at runtime) still fall through to the
     * recovery ladder's step-down; this probe only prevents the guaranteed
     * crashes the codec list itself already knows about.
     */
    fun maxDecodableHeight(ladder: List<Variant>): Int? {
        if (ladder.isEmpty()) return null
        var best = 0
        for (v in ladder) {
            if (v.height <= 0) continue
            val mime = mimeFor(v) ?: continue
            val decodable = try {
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false).any { info ->
                    val ok = if (v.height > 1088) info.hardwareAccelerated else true
                    ok && run {
                        val caps = info.capabilities?.videoCapabilities
                        caps != null && caps.isSizeSupported(v.width, v.height)
                    }
                }
            } catch (t: Throwable) {
                false
            }
            if (decodable && v.height > best) best = v.height
        }
        return if (best > 0) best else null
    }

    /** video/... MIME for a variant's CODECS= attribute (avc/hevc aware). */
    private fun mimeFor(v: Variant): String? {
        val c = v.codecs?.lowercase() ?: return null
        return when {
            c.contains("hvc") || c.contains("hevc") -> MimeTypes.VIDEO_H265
            c.contains("avc") || c.contains("h264") -> MimeTypes.VIDEO_H264
            c.contains("av01") || c.contains("av1") -> "video/av01"
            c.contains("vp9") || c.contains("vp09") -> MimeTypes.VIDEO_VP9
            else -> MimeTypes.VIDEO_H264 // CDN ladders are H.264/HEVC in practice
        }
    }

    /** Human label for logging: "2160p avc1.640033 @ 16.2 Mbps". */
    fun describeVariant(v: Variant): String {
        val mbps = if (v.bandwidth > 0) " @ %.1f Mbps".format(v.bandwidth / 1_000_000f) else ""
        return "${v.height}p ${v.codecs ?: "?codec"}$mbps"
    }

    fun buildMediaItem(
        url: String,
        kind: String,
        mimeType: String?,
        subtitle: SubtitleSelection? = null,
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        when (kind) {
            "m3u8-master", "m3u8" -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            "mpd" -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            "mp4" -> builder.setMimeType(MimeTypes.VIDEO_MP4)
        }
        if (mimeType != null) builder.setMimeType(mimeType)
        if (subtitle != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(subtitle.file))
                        .setMimeType(subtitle.mime)
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return builder.build()
    }

    data class SubtitleSelection(
        val file: java.io.File,
        val mime: String,
        val language: String?,
        val label: String,
    )
}
