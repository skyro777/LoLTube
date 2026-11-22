package com.loltube.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
 *  - ABR that can actually DOWN-switch quickly (the previous parameters made
 *    down-switching effectively impossible once the 30s buffer was full, which
 *    locked playback to 4K on slow lines and rebuffered forever);
 *  - a 400 MB on-disk segment cache + [TurboPrefetcher] parallel read-ahead;
 *  - large time buffers with a matching byte budget (the default ~131 MB byte
 *    cap silently truncated the 90s 4K buffer);
 *  - hardware-decoder-only selection for video;
 *  - OkHttp transport (HTTP/2 multiplexing, connection pooling, keep-alive).
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
    data class Built(val player: ExoPlayer, val prefetcher: TurboPrefetcher)

    fun build(
        context: Context,
        settings: AppSettings,
        streamHeaders: Map<String, String>,
        prefetchEnabled: () -> Boolean = { true },
        bufferAheadMs: () -> Long = { 0L },
        positionMs: () -> Long = { 0L },
    ): Built {
        val profile = settings.bufferProfile

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minMs,
                profile.maxMs,
                profile.playbackMs,
                profile.rebufferMs,
            )
            // 90s of 4K@16Mbps is ~180MB; the default byte budget (~131MB) would
            // silently cap the time buffer well below the configured value.
            .setTargetBufferBytes(if (profile.maxMs >= 90_000) 230 shl 20 else 90 shl 20)
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

        val okClient = MappleStreamInterceptor.playbackClient(streamHeaders)
        val httpFactory = OkHttpDataSource.Factory(okClient)
            .setDefaultRequestProperties(streamHeaders)

        // Every byte the player reads (manifests + segments) flows through the
        // 400MB disk cache; TurboPrefetcher fills it ahead of the playhead.
        val cache = PlaybackCache.get(context)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory)

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

        val prefetcher = TurboPrefetcher(
            cache = cache,
            cacheDataSourceFactory = cacheFactory,
            enabled = prefetchEnabled,
            bufferAheadMs = bufferAheadMs,
            positionMs = positionMs,
        )
        player.addAnalyticsListener(prefetcher)

        applyQualityMode(player, settings.quality)
        return Built(player, prefetcher)
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
