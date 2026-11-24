package com.loltube.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.loltube.app.data.AspectMode
import com.loltube.app.data.QualityMode
import com.loltube.app.providers.PlaybackRequest
import com.loltube.app.providers.ProviderId
import com.loltube.app.providers.Providers
import com.loltube.app.player.PlayerViewModel
import com.loltube.app.util.AppLog
import com.loltube.app.util.Format
import com.loltube.app.util.TvHelper
import kotlin.math.abs
import kotlin.math.roundToInt

/** Overlay shown while a touch gesture (brightness / volume / seek) is in progress. */
private sealed interface GestureIndicator {
    data class Brightness(val pct: Int) : GestureIndicator
    data class Volume(val pct: Int) : GestureIndicator
    data class Seek(val targetMs: Long, val deltaMs: Long) : GestureIndicator
}

/** Unwrap ContextThemeWrapper chains to the owning Activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@UnstableApi
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    request: PlaybackRequest,
    onBack: () -> Unit,
    onOpenWebView: (String) -> Unit,
) {
    val phase by vm.phase.collectAsState()
    val player by vm.player.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val buffering by vm.buffering.collectAsState()
    val positionMs by vm.positionMs.collectAsState()
    val durationMs by vm.durationMs.collectAsState()
    val videoSize by vm.videoSize.collectAsState()
    val qualityMode by vm.qualityMode.collectAsState()
    val aspectMode by vm.aspectMode.collectAsState()
    val message by vm.message.collectAsState()
    val subs by vm.subs.collectAsState()
    val embeddedTextTracks by vm.embeddedTextTracks.collectAsState()
    val activeSubLabel by vm.activeSubLabel.collectAsState()
    val audioLanguages by vm.audioLanguages.collectAsState()
    val activeAudio by vm.activeAudio.collectAsState()
    val stats by vm.stats.collectAsState()
    val pendingNext by vm.pendingNext.collectAsState()
    val details by vm.details.collectAsState()
    val activeReq by vm.activeRequest.collectAsState()
    val cueText by vm.cueText.collectAsState()
    val subDelayMs by vm.subDelayMs.collectAsState()
    val subStyle by vm.subStyle.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var statsVisible by remember { mutableStateOf(false) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var openMenu by remember { mutableStateOf<String?>(null) }
    var lastInteractionMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var gestureIndicator by remember { mutableStateOf<GestureIndicator?>(null) }
    var gestureEpoch by remember { mutableStateOf(0) }

    fun interact() {
        lastInteractionMs = System.currentTimeMillis()
        controlsVisible = true
    }

    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember { context.findActivity() }
    val isTv = remember { TvHelper.isTv(context) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val rootFocus = remember { FocusRequester() }

    // latest values the gesture handler needs without restarting it
    val controlsVisibleNow = rememberUpdatedState(controlsVisible)
    val positionMsNow = rememberUpdatedState(positionMs)
    val durationMsNow = rememberUpdatedState(durationMs)

    // ---------------- immersive fullscreen + auto landscape ----------------
    // Bars are re-hidden EVERY time the window regains focus or the activity
    // resumes: on API < R (Android 9/10) the immersive flags are cleared by the
    // system each time the window loses focus (pause, recents, dialogs), which
    // is why the status bar used to come back after minimizing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        var initialBrightness = Float.MIN_VALUE

        fun applyImmersive() {
            try {
                controller?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    view.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        )
                }
            } catch (t: Throwable) {
                AppLog.d("Immersive", "re-apply failed: ${t.message}")
            }
        }

        if (window != null) {
            // Draw edge-to-edge: the video surface then covers the ENTIRE window,
            // including the strip under the status bar (which we also hide).
            WindowCompat.setDecorFitsSystemWindows(window, false)
            initialBrightness = window.attributes.screenBrightness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        applyImmersive()

        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) applyImmersive()
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                applyImmersive()
                // subtitle appearance / sync may have been edited in Settings
                vm.refreshSubStyle()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        // Rotate into landscape on touch devices; TVs are always landscape already.
        val prevOrientation = activity?.requestedOrientation
        if (activity != null && !isTv) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    view.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                    window.statusBarColor = android.graphics.Color.BLACK
                    window.navigationBarColor = android.graphics.Color.BLACK
                }
                if (initialBrightness >= 0f) {
                    window.attributes = window.attributes.apply { screenBrightness = initialBrightness }
                }
            }
            if (activity != null && !isTv) {
                activity.requestedOrientation =
                    prevOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            view.keepScreenOn = false
        }
    }

    // ---------------- volume helpers ----------------
    fun volumePercent(): Int {
        val am = audioManager ?: return -1
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return -1
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    fun adjustVolume(steps: Int) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (cur + steps).coerceIn(0, max)
        if (next != cur) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        }
        gestureIndicator = GestureIndicator.Volume(volumePercent())
        gestureEpoch++
    }

    // ---------------- brightness helper ----------------
    fun setBrightness(f: Float) {
        val w = activity?.window ?: return
        val clamped = f.coerceIn(0.02f, 1f)
        w.attributes = w.attributes.apply { screenBrightness = clamped }
        gestureIndicator = GestureIndicator.Brightness((clamped * 100).roundToInt())
        gestureEpoch++
    }

    // keep screen on while playing
    LaunchedEffect(isPlaying, buffering) {
        view.keepScreenOn = isPlaying || buffering
    }

    // focus the root so TV remote keys reach us even when controls are hidden
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) rootFocus.requestFocus()
    }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    // save progress when leaving
    DisposableEffect(Unit) {
        onDispose { vm.saveProgress() }
    }

    // message auto-dismiss
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3500)
            vm.consumeMessage()
        }
    }

    // seek flash auto-clear
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            kotlinx.coroutines.delay(800)
            seekFlash = null
        }
    }

    // gesture indicator auto-clear: 1.5s after the last update (covers D-pad volume
    // on TV and any gesture that ends without a clean pointer-up)
    LaunchedEffect(gestureEpoch) {
        if (gestureEpoch > 0) {
            kotlinx.coroutines.delay(1500)
            gestureIndicator = null
        }
    }

    fun showControls() {
        interact()
    }

    fun handleSeek(delta: Long) {
        vm.seekDelta(delta)
        seekFlash = (if (delta > 0) "+${delta / 1000}s" else "${delta / 1000}s")
        interact()
    }

    androidx.activity.compose.BackHandler {
        vm.saveProgress()
        onBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        if (!controlsVisible) {
                            showControls()
                            true
                        } else false
                    }

                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!controlsVisible) {
                            handleSeek(-15_000)
                            true
                        } else false
                    }

                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!controlsVisible) {
                            handleSeek(15_000)
                            true
                        } else false
                    }

                    // D-pad up/down = volume while controls are hidden (TV-friendly)
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!controlsVisible && isTv) {
                            adjustVolume(+1)
                            interact()
                            true
                        } else false
                    }

                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!controlsVisible && isTv) {
                            adjustVolume(-1)
                            interact()
                            true
                        } else false
                    }

                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_SPACE -> {
                        vm.togglePlayPause()
                        true
                    }

                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        handleSeek(30_000)
                        true
                    }

                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        handleSeek(-30_000)
                        true
                    }

                    else -> false
                }
            }
            // ---------------- touch gestures ----------------
            // vertical drag, left half = brightness / right half = volume,
            // horizontal drag = seek. Only active while controls are hidden so the
            // slider and menus keep working normally.
            .pointerInput(Unit) {
                val density = view.resources.displayMetrics.density
                val slop = density * 22f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) return@awaitEachGesture
                    val startX = down.position.x
                    val startY = down.position.y
                    var lastY = down.position.y
                    var mode = 0 // 0 undecided, 1 brightness, 2 volume, 3 seek
                    var brightness = activity?.window?.attributes?.screenBrightness
                        ?.takeIf { it >= 0f } ?: 0.5f
                    var volumeAccum = 0f
                    var gestureBaseMs = 0L
                    var lastSeekApply = 0L
                    val maxVolume = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15)
                        .coerceAtLeast(1)

                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        if (change.isConsumed) continue
                        val dx = change.position.x - startX
                        val dyTotal = change.position.y - startY

                        if (mode == 0) {
                            if (controlsVisibleNow.value) continue
                            if (abs(dx) > slop || abs(dyTotal) > slop) {
                                mode = if (abs(dyTotal) >= abs(dx)) {
                                    if (startX < size.width * 0.5f) 1 else 2
                                } else {
                                    if (durationMsNow.value > 0) 3 else 0
                                }
                                if (mode == 3) gestureBaseMs = positionMsNow.value
                            }
                        }

                        if (mode != 0) {
                            val dY = change.position.y - lastY
                            lastY = change.position.y
                            when (mode) {
                                1 -> {
                                    brightness += -dY / (size.height * 0.8f)
                                    setBrightness(brightness)
                                }

                                2 -> {
                                    volumeAccum += -dY
                                    val pxPerStep = size.height / maxVolume.toFloat()
                                    while (abs(volumeAccum) >= pxPerStep && pxPerStep > 0f) {
                                        val step = if (volumeAccum > 0) 1 else -1
                                        adjustVolume(step)
                                        volumeAccum -= step * pxPerStep
                                    }
                                }

                                3 -> {
                                    val dur = durationMsNow.value
                                    if (dur > 0) {
                                        val msPerPx = dur / (size.width * 1.6f)
                                        val target = (gestureBaseMs + (dx * msPerPx).toLong())
                                            .coerceIn(0L, (dur - 500).coerceAtLeast(0L))
                                        val delta = target - gestureBaseMs
                                        gestureIndicator =
                                            GestureIndicator.Seek(target, delta)
                                        gestureEpoch++
                                        val now = System.currentTimeMillis()
                                        if (now - lastSeekApply > 350) {
                                            lastSeekApply = now
                                            vm.seekTo(target)
                                        }
                                    }
                                }
                            }
                            change.consume()
                        }
                    }
                    if (mode != 0) {
                        gestureIndicator = null
                    }
                }
            }
            // tap / double-tap (kept separate so gesture drags cancel taps cleanly)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        lastInteractionMs = System.currentTimeMillis()
                    },
                    onDoubleTap = { offset ->
                        val third = size.width / 3f
                        when {
                            offset.x < third -> vm.seekDelta(-10_000)
                            offset.x > third * 2 -> vm.seekDelta(10_000)
                            else -> vm.togglePlayPause()
                        }
                    },
                )
            },
    ) {
        // ---------------- video surface ----------------
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { pv ->
                    pv.player = player
                    // The base layout is always FIT (surface exactly matches the
                    // video aspect); the fill/zoom modes are enforced with an exact
                    // scale transform on the video surface view itself - that works
                    // on every device and codec (some codecs ignore the resize-mode
                    // surface scaling, which is why the old modes did nothing on
                    // some videos).
                    pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    applyAspectTransform(pv, aspectMode, videoSize, screenAspect)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ---------------- subtitle overlay (site-style, app-rendered) ---------
        // Parsed sidecar cues with OUR timing offset + styling — the same
        // architecture vidfast/vidcore/mapple use (custom overlay, video
        // untouched). Embedded manifest tracks (rare) keep using PlayerView.
        if (cueText != null) {
            SubtitleOverlay(
                text = cueText ?: "",
                style = subStyle,
                bottomPad = if (controlsVisible) 92.dp else 40.dp,
            )
        }

        // ---------------- resolving / failed overlays ----------------
        when (val ph = phase) {
            is PlayerViewModel.Phase.LoadingMeta -> CenterNote("Loading title info…")
            is PlayerViewModel.Phase.Resolving -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Resolving via ${ph.provider}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ph.status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "stream hunting runs the site's own player in the background",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            is PlayerViewModel.Phase.Failed -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(28.dp),
                ) {
                    Text(
                        "Couldn't get a stream",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        ph.errors,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { vm.retry(null) }) { Text("Retry") }
                        val fb = fallbackEmbed(request)
                        if (fb != null) {
                            Button(onClick = { onOpenWebView(fb) }) { Text("Open in WebView") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProviderId.entries.forEach { p ->
                            OutlinedButton(onClick = { vm.retry(p) }) { Text(p.label) }
                        }
                    }
                }
            }

            else -> Unit
        }

        // ---------------- center buffering spinner ----------------
        if (phase is PlayerViewModel.Phase.Ready && buffering) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp),
            )
        }

        // ---------------- gesture indicator ----------------
        gestureIndicator?.let { ind ->
            Surface(
                color = Color(0xE6141418),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
                ) {
                    when (ind) {
                        is GestureIndicator.Brightness -> {
                            Icon(
                                Icons.Filled.Brightness7,
                                contentDescription = "Brightness",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${ind.pct}%", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { ind.pct / 100f },
                                modifier = Modifier.width(140.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        is GestureIndicator.Volume -> {
                            Icon(
                                if (ind.pct <= 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                contentDescription = "Volume",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${ind.pct}%", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { ind.pct / 100f },
                                modifier = Modifier.width(140.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        is GestureIndicator.Seek -> {
                            Icon(
                                if (ind.deltaMs >= 0) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                                contentDescription = "Seek",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(Format.time(ind.targetMs), color = Color.White, fontWeight = FontWeight.Bold)
                            val dSec = ind.deltaMs / 1000
                            Text(
                                (if (dSec >= 0) "+$dSec" else "$dSec") + "s",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---------------- seek flash ----------------
        seekFlash?.let { flash ->
            Surface(
                color = Color(0xB3000000),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    flash,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                )
            }
        }

        // ---------------- floating message ----------------
        message?.let { msg ->
            Surface(
                color = Color(0xE6222222),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp),
            ) {
                Text(
                    msg,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    color = Color.White,
                )
            }
        }

        // ---------------- next episode overlay ----------------
        pendingNext?.let { next ->
            Surface(
                color = Color(0xF2101014),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(22.dp),
                ) {
                    Text("Up next", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "S${next.season} E${next.episode} · ${next.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("in ${next.countdown}s", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { vm.cancelNext() }) { Text("Cancel") }
                        Button(onClick = { vm.playNextNow() }) { Text("Play now") }
                    }
                }
            }
        }

        // ---------------- controls auto-hide ----------------
        LaunchedEffect(isPlaying, openMenu, controlsVisible, lastInteractionMs) {
            while (isPlaying && openMenu == null && controlsVisible) {
                kotlinx.coroutines.delay(4_500)
                if (System.currentTimeMillis() - lastInteractionMs >= 4_400) {
                    controlsVisible = false
                    break
                }
            }
        }

        // ---------------- controls ----------------
        AnimatedVisibility(
            visible = controlsVisible && phase is PlayerViewModel.Phase.Ready,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(240)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xB3000000),
                            0.12f to Color.Transparent,
                            0.82f to Color.Transparent,
                            1f to Color(0xCC000000),
                        )
                    ),
            ) {
                // top bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    IconButton(onClick = { vm.saveProgress(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            details?.name ?: activeReq.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        val epInfo = activeReq.season?.let { s ->
                            activeReq.episode?.let { e ->
                                "S$s E$e · " + (details?.episodes?.firstOrNull { it.season == s && it.number == e }?.name ?: "")
                            }
                        }
                        if (epInfo != null) {
                            Text(
                                epInfo,
                                color = Color(0xFFBBBBCC),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                    val readyPhase = phase as? PlayerViewModel.Phase.Ready
                    if (readyPhase != null) Badge(readyPhase.provider.label)
                    Spacer(Modifier.width(8.dp))
                    if (stats.videoHeight > 0) {
                        val res = if (stats.videoHeight >= 2000) "4K ${stats.videoHeight}p" else "${stats.videoHeight}p"
                        Badge(res, highlight = stats.videoHeight >= 2000)
                    }
                }

                Spacer(Modifier.weight(1f))

                // stats panel
                if (statsVisible) {
                    Surface(
                        color = Color(0xCC101014),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(horizontal = 20.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            val readyPhase = phase as? PlayerViewModel.Phase.Ready
                            StatLine("provider", readyPhase?.provider?.label ?: "-")
                            StatLine("stream", readyPhase?.streamKind ?: "-")
                            StatLine("quality", "${stats.videoHeight}p @ ${Format.bitrate(stats.videoBitrate)}")
                            if (stats.bandwidthBps > 0) {
                                StatLine("measured bandwidth", Format.bitrate(stats.bandwidthBps))
                            }
                            StatLine("dropped frames", "${stats.droppedFrames}")
                            StatLine("rebuffers (60s)", "${stats.rebuffers}")
                            StatLine("buffer ahead", Format.time((vm.bufferedMs.value - positionMs).coerceAtLeast(0)))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // seek bar
                var dragging by remember { mutableStateOf(false) }
                var dragPos by remember { mutableStateOf(0f) }
                var lastScrubApply by remember { mutableStateOf(0L) }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(Format.time(if (dragging) dragPos.toLong() else positionMs), color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(Format.time(durationMs), color = Color(0xFFBBBBCC), fontSize = 12.sp)
                    }
                    Slider(
                        value = (if (dragging) dragPos else positionMs.toFloat())
                            .coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                        onValueChange = {
                            dragging = true
                            dragPos = it
                            lastInteractionMs = System.currentTimeMillis()
                            // throttled immediate seek: works for touch drags AND D-pad scrubbing
                            val now = System.currentTimeMillis()
                            if (now - lastScrubApply > 400) {
                                lastScrubApply = now
                                vm.seekTo(it.toLong())
                            }
                        },
                        onValueChangeFinished = {
                            vm.seekTo(dragPos.toLong())
                            dragging = false
                        },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                    )
                }

                // button row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .padding(bottom = 10.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.seekDelta(-15_000) }) {
                        Icon(Icons.Filled.FastRewind, "Rewind 15s", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(
                        onClick = { vm.togglePlayPause() },
                        modifier = Modifier
                            .size(62.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    IconButton(onClick = { vm.seekDelta(15_000) }) {
                        Icon(Icons.Filled.FastForward, "Forward 15s", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.weight(1f))

                    // subtitles (embedded manifest tracks + downloaded sidecars)
                    Box {
                        MenuButton(
                            icon = { Icon(Icons.Filled.Subtitles, null, tint = Color.White) },
                            label = activeSubLabel?.take(7) ?: "Subs",
                            open = openMenu == "subs",
                            onClick = { openMenu = if (openMenu == "subs") null else "subs" },
                        )
                        DropdownMenu(expanded = openMenu == "subs", onDismissRequest = { openMenu = null }) {
                            DropdownMenuItem(text = { Text("Off") }, onClick = { vm.selectSubtitle(null); openMenu = null })
                            if (embeddedTextTracks.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("— From this stream —", style = MaterialTheme.typography.labelSmall) },
                                    onClick = {},
                                    enabled = false,
                                )
                                embeddedTextTracks.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.label + (if (t.isSelected) "  ✓" else "")) },
                                        onClick = { vm.selectEmbeddedSub(t.groupIndex, t.trackIndex); openMenu = null },
                                    )
                                }
                            }
                            if (subs.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("— Downloaded —", style = MaterialTheme.typography.labelSmall) },
                                    onClick = {},
                                    enabled = false,
                                )
                                subs.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.display + (s.language?.let { "  ($it)" } ?: "")) },
                                        onClick = { vm.selectSubtitle(s); openMenu = null },
                                    )
                                }
                            }
                        }
                    }

                    // subtitle SYNC (per-source, instant nudge — fixes the
                    // 2–6s ahead/behind drift each source's copy has)
                    if (activeSubLabel != null) {
                        Box {
                            MenuButton(
                                icon = { Icon(Icons.Filled.Timer, null, tint = Color.White) },
                                label = syncShort(subDelayMs),
                                open = openMenu == "subsync",
                                onClick = { openMenu = if (openMenu == "subsync") null else "subsync" },
                            )
                            DropdownMenu(expanded = openMenu == "subsync", onDismissRequest = { openMenu = null }) {
                                DropdownMenuItem(
                                    text = { Text("Subtitles early → make them later (+)\nSubtitles late → make them earlier (−)", style = MaterialTheme.typography.labelSmall) },
                                    onClick = {},
                                    enabled = false,
                                )
                                listOf(-1000, -500, -250).forEach { d ->
                                    DropdownMenuItem(
                                        text = { Text("− ${absSec(d)}s  (earlier)") },
                                        onClick = { vm.nudgeSubDelay(d); openMenu = null },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Reset — 0s") },
                                    onClick = { vm.resetSubDelay(); openMenu = null },
                                )
                                listOf(250, 500, 1000).forEach { d ->
                                    DropdownMenuItem(
                                        text = { Text("+ ${absSec(d)}s  (later)") },
                                        onClick = { vm.nudgeSubDelay(d); openMenu = null },
                                    )
                                }
                            }
                        }
                    }

                    // episodes (jump between episodes of the current show)
                    val seriesDetails = details
                    if (seriesDetails != null && seriesDetails.type == "series" && seriesDetails.episodes.isNotEmpty()) {
                        Box {
                            MenuButton(
                                icon = { Icon(Icons.Filled.PlaylistPlay, null, tint = Color.White) },
                                label = "Episodes",
                                open = openMenu == "episodes",
                                onClick = { openMenu = if (openMenu == "episodes") null else "episodes" },
                            )
                            DropdownMenu(expanded = openMenu == "episodes", onDismissRequest = { openMenu = null }) {
                                var seasonPick by remember(activeReq.season) {
                                    mutableStateOf(activeReq.season ?: seriesDetails.seasons.firstOrNull { it > 0 } ?: 1)
                                }
                                // season chips
                                Row(
                                    Modifier
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .horizontalScroll(rememberScrollState()),
                                ) {
                                    seriesDetails.seasons.forEach { s ->
                                        val sel = s == seasonPick
                                        Surface(
                                            color = if (sel) MaterialTheme.colorScheme.primary else Color(0x22000000),
                                            contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else Color.White,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .clickable { seasonPick = s },
                                        ) {
                                            Text(
                                                if (s == 0) "Specials" else "S$s",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            )
                                        }
                                    }
                                }
                                val eps = remember(seasonPick) {
                                    seriesDetails.episodes.filter { it.season == seasonPick }.sortedBy { it.number }
                                }
                                Box(
                                    Modifier
                                        .heightIn(max = 400.dp)
                                        .widthIn(min = 240.dp, max = 360.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    Column {
                                        eps.forEach { ep ->
                                            val current = ep.season == activeReq.season && ep.number == activeReq.episode
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "E${ep.number} · ${ep.name}" + (if (current) "   ✓" else ""),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                                    )
                                                },
                                                onClick = {
                                                    vm.switchEpisode(ep.season, ep.number)
                                                    openMenu = null
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // audio
                    if (audioLanguages.size > 1) {
                        Box {
                            MenuButton(
                                icon = { Icon(Icons.Filled.Audiotrack, null, tint = Color.White) },
                                label = activeAudio ?: "Audio",
                                open = openMenu == "audio",
                                onClick = { openMenu = if (openMenu == "audio") null else "audio" },
                            )
                            DropdownMenu(expanded = openMenu == "audio", onDismissRequest = { openMenu = null }) {
                                audioLanguages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang) },
                                        onClick = { vm.selectAudio(lang); openMenu = null },
                                    )
                                }
                            }
                        }
                    }

                    // aspect
                    Box {
                        MenuButton(
                            icon = { Icon(Icons.Filled.AspectRatio, null, tint = Color.White) },
                            label = aspectShort(aspectMode),
                            open = openMenu == "aspect",
                            onClick = { openMenu = if (openMenu == "aspect") null else "aspect" },
                        )
                        DropdownMenu(expanded = openMenu == "aspect", onDismissRequest = { openMenu = null }) {
                            AspectMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.label) },
                                    onClick = { vm.setAspect(m); openMenu = null },
                                )
                            }
                        }
                    }

                    // quality
                    Box {
                        MenuButton(
                            icon = { Icon(Icons.Filled.Bolt, null, tint = Color.White) },
                            label = qualityShort(qualityMode),
                            open = openMenu == "quality",
                            onClick = { openMenu = if (openMenu == "quality") null else "quality" },
                        )
                        DropdownMenu(expanded = openMenu == "quality", onDismissRequest = { openMenu = null }) {
                            QualityMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.label) },
                                    onClick = { vm.setQuality(m); openMenu = null },
                                )
                            }
                        }
                    }

                    // provider switch
                    Box {
                        MenuButton(
                            icon = { Icon(Icons.Filled.SwapHoriz, null, tint = Color.White) },
                            label = "Source",
                            open = openMenu == "provider",
                            onClick = { openMenu = if (openMenu == "provider") null else "provider" },
                        )
                        DropdownMenu(expanded = openMenu == "provider", onDismissRequest = { openMenu = null }) {
                            ProviderId.entries.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text("Switch to ${p.label}") },
                                    onClick = { vm.retry(p); openMenu = null },
                                )
                            }
                        }
                    }

                    // stats
                    IconButton(onClick = { statsVisible = !statsVisible }) {
                        Icon(
                            Icons.Filled.QueryStats,
                            if (statsVisible) "Hide stats" else "Show stats",
                            tint = if (statsVisible) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                }
            }
        }
    }
}

private fun aspectShort(m: AspectMode): String = when (m) {
    AspectMode.AUTO -> "Auto"
    AspectMode.FIT -> "Fit"
    AspectMode.FILL -> "Stretch"
    AspectMode.ZOOM -> "Zoom"
}

/** "Sync +2.5s" / "Sync 0s" — the label on the CC-sync button. */
private fun syncShort(delayMs: Int): String {
    val s = delayMs / 1000f
    val sign = if (delayMs > 0) "+" else if (delayMs < 0) "−" else ""
    return "Sync ${sign}${"%.1f".format(kotlin.math.abs(s))}s"
}

private fun absSec(ms: Int): String = "%.2f".format(kotlin.math.abs(ms / 1000f)).trimEnd('0').trimEnd('.').ifEmpty { "0" }

/**
 * The app-rendered subtitle overlay (vidfast/vidcore/mapple architecture:
 * the sites all render cues with their own overlay, leaving the video
 * untouched — that's what makes instant sync nudges and full styling
 * possible). Default size is noticeably smaller than the built-in
 * SubtitleView renderer (base ≈ 4.2% of screen height vs ~5.3%).
 */
@Composable
private fun SubtitleOverlay(
    text: String,
    style: PlayerViewModel.SubStyle,
    bottomPad: androidx.compose.ui.unit.Dp,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPad),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // ~4.2% of screen height at 100% size — the sites' visual weight class
        val fontSp = (maxHeight.value * 0.042f * (style.sizePct / 100f))
            .coerceIn(11f, 42f)
            .sp
        val textColor = when (style.color) {
            com.loltube.app.data.SubColor.WHITE -> Color.White
            com.loltube.app.data.SubColor.YELLOW -> Color(0xFFFFE14D)
            com.loltube.app.data.SubColor.CYAN -> Color(0xFF4DE5FF)
            com.loltube.app.data.SubColor.SILVER -> Color(0xFFC9CDD4)
        }
        val bgAlpha = (style.bgPct / 100f) * 0.78f
        Text(
            text = text,
            color = textColor,
            fontSize = fontSp,
            lineHeight = fontSp * 1.18f,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth(fraction = 0.78f)
                .then(
                    if (bgAlpha > 0.02f) {
                        Modifier.background(
                            Color.Black.copy(alpha = bgAlpha),
                            RoundedCornerShape(6.dp),
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

private fun qualityShort(m: QualityMode): String = when (m) {
    QualityMode.AUTO -> "Auto"
    QualityMode.MAX -> "4K"
    QualityMode.CAP_1080 -> "1080p"
    QualityMode.CAP_720 -> "720p"
}

private fun fallbackEmbed(request: PlaybackRequest): String? =
    ProviderId.entries.firstNotNullOfOrNull { id ->
        Providers.embedUrl(id, request)
    }

/**
 * Enforces the aspect mode by scaling the *video surface view* (not the whole
 * PlayerView, so subtitles stay undistorted). Runs on every recomposition
 * (position ticks every 400ms), so it also follows resolution changes.
 *
 *  - AUTO / FILL: non-uniform stretch so the video covers the whole screen
 *    (no black bars anywhere). AUTO leaves near-matching videos untouched.
 *  - ZOOM: uniform scale up + crop (covers, keeps proportions).
 *  - FIT: reset.
 */
private fun applyAspectTransform(
    pv: PlayerView,
    mode: AspectMode,
    videoSize: VideoSize?,
    screenAspect: Float,
) {
    if (screenAspect <= 0f) return
    val videoAspect = videoSize
        ?.takeIf { it.height > 0 && it.width > 0 }
        ?.let { (it.width * it.pixelWidthHeightRatio) / it.height }
        ?: (16f / 9f) // sensible default until the size is reported

    var scaleX = 1f
    var scaleY = 1f
    when (mode) {
        AspectMode.FIT -> Unit

        AspectMode.FILL, AspectMode.AUTO -> {
            val rel = videoAspect / screenAspect // >1: video wider than screen
            if (rel > 1f) {
                scaleY = rel
            } else if (rel < 1f) {
                scaleX = 1f / rel
            }
            if (mode == AspectMode.AUTO && abs(rel - 1f) < 0.02f) {
                scaleX = 1f; scaleY = 1f
            }
        }

        AspectMode.ZOOM -> {
            val s = maxOf(videoAspect / screenAspect, screenAspect / videoAspect)
            scaleX = s
            scaleY = s
        }
    }

    val target = runCatching { pv.videoSurfaceView }.getOrNull() ?: pv
    target.scaleX = scaleX
    target.scaleY = scaleY
}

@Composable
private fun Badge(text: String, highlight: Boolean = false) {
    Surface(
        color = if (highlight) MaterialTheme.colorScheme.primary else Color(0x88000000),
        contentColor = if (highlight) MaterialTheme.colorScheme.onPrimary else Color.White,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MenuButton(
    icon: @Composable () -> Unit,
    label: String,
    open: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (open) Color(0x40FFB300) else Color(0x33000000),
        contentColor = Color.White,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            icon()
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
