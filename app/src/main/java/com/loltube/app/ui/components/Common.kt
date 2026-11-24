package com.loltube.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.loltube.app.data.CatalogEntry

/**
 * Standard catalog poster card: 2:3 artwork, bottom scrim, rating + year chips,
 * 2-line title below. Focus (mouse hover, touch press or D-pad) scales the card
 * and adds an amber glow + 2dp amber border so it is clearly readable on TV.
 */
@Composable
fun PosterCard(
    entry: CatalogEntry,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "cardScale")
    val glow by animateFloatAsState(if (focused) 22f else 0f, label = "cardGlow")
    val cardShape = RoundedCornerShape(12.dp)
    val glowColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .width(120.dp)
            .scale(scale),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer {
                    shadowElevation = glow
                    shape = cardShape
                }
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (focused) 2.dp else 0.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = cardShape,
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            AsyncImage(
                model = entry.poster,
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color(0x96000000),
                        )
                    )
            )
            if (!entry.imdbRating.isNullOrEmpty() && entry.imdbRating != "0") {
                Surface(
                    color = Color(0xCC0A0A0E),
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            entry.imdbRating,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (entry.releaseInfo != null) {
                Surface(
                    color = Color(0x88000000),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        entry.releaseInfo.take(4),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (showTitle) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .padding(top = 6.dp, start = 2.dp, end = 2.dp)
                    .height(34.dp),
            )
        }
    }
}

/** Row of content under a bold section header (with a small amber accent bar). */
@Composable
fun MediaRow(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            if (subtitle != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
        }
        content()
    }
}

/** Bold section header with a slim amber accent bar on the leading edge. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.2.sp,
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun LoadingHint(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Modern immersive loading screen - shown after a content is clicked and
 * while the stream is being resolved. Cinematic, poster-aware and animated,
 * instead of a bare spinner: poster backdrop with a slow Ken-Burns zoom,
 * ambient amber orbs drifting behind a dark scrim, the LoLTube wordmark,
 * the title, the live status line, an indeterminate sweep progress bar and
 * (optionally) a step checklist with done/active/pending states.
 */
@Composable
fun ImmersiveLoading(
    posterUrl: String?,
    title: String?,
    status: String?,
    stepIndex: Int = -1,
    steps: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "immersive")
    // slow Ken-Burns zoom on the backdrop
    val zoom by infinite.animateFloat(
        initialValue = 1.14f,
        targetValue = 1.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 17_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "zoom",
    )
    // indeterminate sweep 0..1
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = LinearEasing),
        ),
        label = "sweep",
    )
    // breathing glow for the active step / logo halo
    val breath by infinite.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    // drifting ambient orbs
    val drift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11_000, easing = LinearEasing),
        ),
        label = "drift",
    )
    val amber = MaterialTheme.colorScheme.primary
    val amberSoft = Color(0xFFFFD54F)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF06060A)),
    ) {
        // poster backdrop (if known) - slow zoom, heavily dimmed
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        alpha = 0.30f
                    },
            )
        }
        // ambient orbs - soft amber glows drifting, "cinema lobby" feel
        Box(
            Modifier
                .offset(x = (30 + drift * 90).dp, y = (90 + drift * 60).dp)
                .size(340.dp)
                .alpha(0.10f + 0.05f * breath)
                .background(
                    Brush.radialGradient(
                        listOf(amber.copy(alpha = 0.9f), Color.Transparent),
                    ),
                    CircleShape,
                )
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-40 - drift * 70).dp, y = (-60 - drift * 40).dp)
                .size(240.dp)
                .alpha(0.08f + 0.04f * breath)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFFF7043).copy(alpha = 0.9f), Color.Transparent),
                    ),
                    CircleShape,
                )
        )
        // scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x99000000), Color(0xEE05050A)),
                    ),
                )
        )

        // center content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            // wordmark + play glyph
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .alpha(0.9f + 0.1f * breath)
                        .background(
                            Brush.radialGradient(
                                listOf(amber.copy(alpha = 0.35f), Color.Transparent),
                            ),
                        ),
                ) {
                    Surface(
                        color = amber,
                        shape = CircleShape,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFF1A1200),
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = amber, fontWeight = FontWeight.ExtraBold)) { append("LoL") }
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Light)) { append("Tube") }
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            if (!title.isNullOrBlank()) {
                Spacer(Modifier.height(22.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFEDEDF2),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(26.dp))
            // indeterminate sweep progress bar
            Box(
                Modifier
                    .width(300.dp)
                    .height(4.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))
                    .clip(RoundedCornerShape(2.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(110.dp)
                        .offset(x = (sweep * 190f).dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, amberSoft, amber),
                            ),
                            RoundedCornerShape(2.dp),
                        )
                )
            }

            if (!status.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    steps.forEachIndexed { idx, label ->
                        val state = when {
                            idx < stepIndex -> StepState.DONE
                            idx == stepIndex -> StepState.ACTIVE
                            else -> StepState.PENDING
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 5.dp),
                        ) {
                            when (state) {
                                StepState.DONE -> Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = amber,
                                    modifier = Modifier.size(18.dp),
                                )
                                StepState.ACTIVE -> Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(18.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .alpha(0.25f + 0.75f * breath)
                                            .background(amber, CircleShape)
                                    )
                                }
                                StepState.PENDING -> Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(Color(0x55FFFFFF), CircleShape)
                                )
                            }
                            Text(
                                label,
                                fontSize = 13.sp,
                                color = when (state) {
                                    StepState.DONE -> Color(0xFFD8D8E0)
                                    StepState.ACTIVE -> Color.White
                                    StepState.PENDING -> Color(0x88FFFFFF)
                                },
                                fontWeight = if (state == StepState.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class StepState { DONE, ACTIVE, PENDING }

/**
 * Modern mid-playback buffering indicator: a rotating gradient arc with a
 * soft halo instead of the stock spinner. It fades in gently (400 ms) so
 * quick seeks do not flash it.
 */
@Composable
fun ModernBuffering(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        visible = true
    }
    val infinite = rememberInfiniteTransition(label = "buffering")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
        ),
        label = "rot",
    )
    val breath by infinite.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val amber = MaterialTheme.colorScheme.primary

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(280)) + scaleIn(initialScale = 0.85f, animationSpec = tween(280)),
        exit = fadeOut(tween(120)),
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            // soft halo
            Box(
                Modifier
                    .size(120.dp)
                    .alpha(0.28f * breath)
                    .background(
                        Brush.radialGradient(listOf(amber.copy(alpha = 0.55f), Color.Transparent)),
                        CircleShape,
                    )
            )
            Canvas(Modifier.size(54.dp)) {
                rotate(degrees = rotation) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Color.Transparent, Color(0xFFFFD54F), amber, Color.Transparent),
                        ),
                        startAngle = -60f,
                        sweepAngle = 300f,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
