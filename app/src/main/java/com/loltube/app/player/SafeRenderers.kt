package com.loltube.app.player

import android.content.Context
import android.os.Handler
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.loltube.app.util.AppLog
import java.util.ArrayList

/**
 * Crash-proof renderer construction.
 *
 * The fatal `ERROR_CODE_FAILED_RUNTIME_CHECK` the app hit on Android 9 TV boxes
 * comes from raw IllegalStateException / IllegalArgumentException escaping on
 * ExoPlayer's internal playback thread. In media3 1.4.1 those come from three
 * codec-layer places (verified against the 1.4.1 source + androidx/media
 * issues #1641, #2529, #3011, #2616 and legacy ExoPlayer #10598):
 *
 *  1. `MediaCodecVideoRenderer.setOutputSurfaceV23` -> `MediaCodec.setOutputSurface`
 *     throws IllegalArgumentException on several vendor TV-box codecs when the
 *     surface changes while a codec is alive. media3's own device-specific
 *     workaround (release + re-init) is not public in 1.4.1, but the method is
 *     protected — we override it.
 *  2. A custom MediaCodecSelector throwing anything runs on the playback thread
 *     and propagates raw. Ours never throws (and no longer filters codecs at
 *     all — software `c2.android.*` decoders are kept as genuine fallback, the
 *     hardware-only filter removed decoders the box actually needed).
 *  3. Vendor decoders dying mid-render with a non-MediaCodec top frame are
 *     re-thrown raw by design; that one cannot be patched at renderer level —
 *     the recovery ladder in [PlayerViewModel] handles it by rebuilding the
 *     player (fresh codec instances) and stepping the quality ceiling down.
 */
@UnstableApi
object SafeRenderers {

    /**
     * Renderer factory that builds the stock media3 pipeline with the one
     * behavioral patch that matters on TV boxes: [SafeMediaCodecVideoRenderer].
     * Returned concretely so callers can keep chaining the usual
     * [DefaultRenderersFactory] configuration methods.
     */
    fun factory(context: Context): CrashSafeRenderersFactory =
        CrashSafeRenderersFactory(context)
}

@UnstableApi
internal class CrashSafeRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            SafeMediaCodecVideoRenderer(
                context,
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            )
        )
    }

    private companion object {
        // DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY (50)
        private const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50
    }
}

/**
 * MediaCodecVideoRenderer that cannot be killed by a buggy vendor codec when
 * the output surface changes. If `MediaCodec.setOutputSurface` throws we keep
 * rendering to the previous surface (frozen video beats a dead playback; the
 * recovery ladder will rebuild the player anyway).
 */
@UnstableApi
internal class SafeMediaCodecVideoRenderer(
    context: Context,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {

    @RequiresApi(23)
    override fun setOutputSurfaceV23(codec: androidx.media3.exoplayer.mediacodec.MediaCodecAdapter, surface: Surface) {
        try {
            codec.setOutputSurface(surface)
        } catch (t: Throwable) {
            // media3 issues #1641 (FireStick), #2529 (SberBox), #3011: vendor
            // codecs throw IllegalArgumentException from native_setSurface.
            // Swallow: playback survives on the previous surface instead of the
            // whole stream dying with ERROR_CODE_FAILED_RUNTIME_CHECK.
            AppLog.e("Renderers", "setOutputSurface failed (${t.javaClass.simpleName}) — keeping previous surface", t)
        }
    }
}

/**
 * Codec selector that can never throw. It runs on ExoPlayer's playback thread
 * where any RuntimeException is a fatal RUNTIME_CHECK, so every path returns
 * the plain DEFAULT result (software decoders included — they are the only
 * thing that keeps playback alive when a vendor hardware decoder refuses).
 */
val SAFE_CODEC_SELECTOR = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
    try {
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
    } catch (t: Throwable) {
        AppLog.e("Renderers", "decoder query failed: ${t.message?.take(80)}")
        emptyList()
    }
}
