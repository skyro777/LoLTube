package com.loltube.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
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
 *  - an up-to-800 MB on-disk segment cache + [TurboPrefetcher] 4-worker
 *    parallel read-ahead (the real "no buffering" engine — see [PlaybackCache]);
 *  - large time buffers with a memory-aware byte budget (the default ~131 MB
 *    byte cap silently truncated the 4K buffer; asking for more than the device
 *    has RAM for does the opposite);
 *  - a crash-proof data pipeline: every byte flows through [SafeDataSource],
 *    which converts any runtime-exception race (cache/OkHttp/prefetch
 *    collisions — the source of ERROR_CODE_FAILED_RUNTIME_CHECK) into a
 *    retriable network error instead of a fatal one;
 *  - hardware-decoder-only selection for video (audio decoders are usually
 *    software, filtering them out kills the audio renderer);
 *  - OkHttp transport with keep-alive pooling and 16 requests per host so the
 *    player + 4 prefetch workers can fetch segments concurrently.
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
    data class Built(val player: ExoPlayer, val prefetcher: TurboPrefetcher?)

    /**
     * @param safeMode rebuild without disk cache and prefetch — used by the
     * self-healing recovery ladder after repeated failures. Identical to the
     * v0.2 pipeline that always played, so even a total cache failure can only
     * degrade throughput, never playback itself.
     */
    fun build(
        context: Context,
        settings: AppSettings,
        streamHeaders: Map<String, String>,
        prefetchEnabled: () -> Boolean = { true },
        bufferAheadMs: () -> Long = { 0L },
        positionMs: () -> Long = { 0L },
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

        // Hardware decoders only — but ONLY for video: audio decoders are almost always
        // software ("OMX.google.*") and filtering them out leaves no audio renderer at
        // all, which produced silent playback on most devices.
        val hwCodecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            if (mimeType.startsWith("video/")) {
                val hw = infos.filter { it.hardwareAccelerated }
                if (hw.isEmpty()) infos else hw // fall back rather than lose video entirely
            } else {
                infos
            }
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setMediaCodecSelector(hwCodecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val okClient = MappleStreamInterceptor.playbackClient(streamHeaders, parallel = !safeMode)
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okClient)
            .setDefaultRequestProperties(streamHeaders)

        val mediaSourceFactory: DefaultMediaSourceFactory = if (safeMode) {
            // fail-safe: network only, nothing between the player and the CDN
            DefaultMediaSourceFactory(httpFactory)
        } else {
            // Every byte the player reads (manifests + segments) flows through the
            // disk cache AND the crash-proof wrapper. TurboPrefetcher fills the
            // cache ahead of the playhead.
            val cache = PlaybackCache.get(context)
            val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(
                    androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                )
            DefaultMediaSourceFactory(SafeDataSource.Factory(cacheFactory))
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

        val prefetcher: TurboPrefetcher? = if (safeMode) {
            null
        } else {
            val cache = PlaybackCache.get(context)
            TurboPrefetcher(
                cache = cache,
                cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpFactory)
                    .setFlags(
                        androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                    ),
                enabled = prefetchEnabled,
                bufferAheadMs = bufferAheadMs,
                positionMs = positionMs,
            ).also { player.addAnalyticsListener(it) }
        }

        applyQualityMode(player, settings.quality)
        return Built(player, prefetcher)
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
     * Applies a quality mode. All modes stay adaptive (the old "force highest
     * bitrate" flag disabled ABR entirely and guaranteed rebuffering); the
     * difference is only how high the selection is allowed to climb. Capped
     * modes also rewrite the master playlist via [CapPolicy].
     */
    fun applyQualityMode(player: ExoPlayer, mode: QualityMode) {
        val params = player.trackSelectionParameters.buildUpon()
        when (mode) {
            QualityMode.AUTO -> params.setMaxVideoSize(3840, 2160)
            QualityMode.MAX -> params.setMaxVideoSize(3840, 2160)
            QualityMode.CAP_1080 -> params.setMaxVideoSize(1920, 1088)
            QualityMode.CAP_720 -> params.setMaxVideoSize(1280, 736)
        }
        player.trackSelectionParameters = params.build()
        CapPolicy.maxVideoHeight = when (mode) {
            QualityMode.AUTO, QualityMode.MAX -> 2160
            QualityMode.CAP_1080 -> 1088
            QualityMode.CAP_720 -> 736
        }
        AppLog.d("Player", "quality mode -> $mode (manifest cap ${CapPolicy.maxVideoHeight}p)")
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
