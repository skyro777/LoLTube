package com.loltube.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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
 * Builds ExoPlayer instances tuned for buttery-smooth 4K:
 *  - large buffers (see BufferProfile) so network dips never surface as rebuffering
 *  - hysteresis-heavy ABR (slow up-switch, quick down-switch) to avoid quality oscillation
 *  - hardware-decoder-only selection (software 4K decode = guaranteed stutter)
 *  - OkHttp transport (HTTP/2 multiplexing, connection pooling, keep-alive)
 */
@UnstableApi
object PlayerEngine {

    /** (minDurationForQualityIncrease, maxDurationForQualityDecrease, retainDiscarded, bandwidthFraction) */
    private val ABR_4K = intArrayOf(25_000, 10_000, 25_000)
    private const val ABR_BANDWIDTH_FRACTION = 0.62f

    fun build(
        context: Context,
        settings: AppSettings,
        streamHeaders: Map<String, String>,
    ): ExoPlayer {
        val profile = settings.bufferProfile

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minMs,
                profile.maxMs,
                profile.playbackMs,
                profile.rebufferMs,
            )
            .setBackBuffer(profile.backMs, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val adaptiveFactory = AdaptiveTrackSelection.Factory(
            ABR_4K[0],
            ABR_4K[1],
            ABR_4K[2],
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
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // Warm-start the ABR with the bandwidth we measured last session so playback
        // ramps to a sane rendition immediately instead of crawling up from 480p.
        val bandwidthMeter = if (settings.lastBandwidthBps > 200_000) {
            DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate(settings.lastBandwidthBps)
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

        applyQualityMode(player, settings.quality)
        return player
    }

    /** Applies a quality mode to track selection parameters. */
    fun applyQualityMode(player: ExoPlayer, mode: QualityMode) {
        val params = player.trackSelectionParameters.buildUpon()
        when (mode) {
            QualityMode.AUTO_4K -> params.setMaxVideoSize(3840, 2160)
            QualityMode.FORCE_MAX -> params.setMaxVideoSize(3840, 2160).setForceHighestSupportedBitrate(true)
            QualityMode.CAP_1080 -> params.setMaxVideoSize(1920, 1080)
            QualityMode.CAP_720 -> params.setMaxVideoSize(1280, 720)
        }
        player.trackSelectionParameters = params.build()
        AppLog.d("Player", "quality mode -> $mode")
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

    /**
     * FORCE_MAX fallback: given current tracks, pick the highest-resolution video
     * format and hard-override to it (no ABR oscillation at all).
     */
    fun forceHighestVideo(player: ExoPlayer): Boolean {
        var bestGroup = -1
        var bestTrack = -1
        var bestScore = -1L
        val tracks = player.currentTracks
        for (gi in 0 until tracks.groups.size) {
            val group = tracks.groups[gi]
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val fmt = group.getTrackFormat(ti)
                val score = fmt.height.toLong() * 1_000_000L + fmt.bitrate
                if (score > bestScore) {
                    bestScore = score
                    bestGroup = gi
                    bestTrack = ti
                }
            }
        }
        if (bestGroup < 0) return false
        val mediaGroup = tracks.groups[bestGroup].mediaTrackGroup
        val override = TrackSelectionOverride(mediaGroup, bestTrack)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
        AppLog.d("Player", "forced highest video track (group $bestGroup track $bestTrack)")
        return true
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
