package com.loltube.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.loltube.app.data.EpisodeInfo
import com.loltube.app.data.MediaDetails
import com.loltube.app.data.ProgressStore
import com.loltube.app.providers.ProviderId
import com.loltube.app.ui.components.SectionHeader
import com.loltube.app.util.Format

@Composable
fun DetailsScreen(
    imdbId: String,
    type: String,
    onBack: () -> Unit,
    onPlay: (imdbId: String, type: String, season: Int?, episode: Int?, provider: ProviderId?, startAtMs: Long) -> Unit,
) {
    val vm: DetailsViewModel = viewModel()
    var loadedKey by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    if (loadedKey != "$imdbId:$type") {
        loadedKey = "$imdbId:$type"
        vm.load(imdbId, type)
    }
    val state by vm.state.collectAsState()

    val details = state.details
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
        }
        return
    }
    if (details == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                state.error ?: "Not found",
                color = MaterialTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        item { HeroSection(details, onBack) }
        item { InfoSection(details) }
        item {
            PlaySection(
                details = details,
                state = state,
                onProvider = vm::setProvider,
                onPlay = onPlay,
            )
        }
        if (details.type == "series") {
            item {
                Column {
                    SectionHeader("Episodes")
                    SeasonChips(details, state.selectedSeason, vm::setSeason)
                }
            }
            val season = details.episodes.filter { it.season == state.selectedSeason }
            items(season, key = { it.id.ifEmpty { "${it.season}-${it.number}" } }) { ep ->
                EpisodeRow(
                    ep = ep,
                    watched = "${ep.season}:${ep.number}" in state.watchedEpisodes,
                    progress = state.episodeProgress["${ep.season}:${ep.number}"],
                    onPlay = {
                        onPlay(details.imdbId, details.type, ep.season, ep.number, state.provider, 0)
                    },
                )
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** Cinematic hero: full-width backdrop with the poster straddling its bottom edge. */
@Composable
private fun HeroSection(details: MediaDetails, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(352.dp)) {
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            AsyncImage(
                model = details.background ?: details.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // top scrim for the back button, bottom scrim fading into the background
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color(0x99000000),
                            1f to Color.Transparent,
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background,
                        )
                    )
            )
            Surface(
                color = Color(0x66000000),
                shape = CircleShape,
                modifier = Modifier.padding(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            val posterShape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .width(110.dp)
                    .height(165.dp)
                    .graphicsLayer {
                        shadowElevation = 18f
                        shape = posterShape
                    }
                    .clip(posterShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, posterShape),
            ) {
                AsyncImage(
                    model = details.poster,
                    contentDescription = details.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (details.logo != null) {
                    AsyncImage(
                        model = details.logo,
                        contentDescription = details.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.width(200.dp),
                    )
                } else {
                    Text(
                        details.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!details.imdbRating.isNullOrEmpty() && details.imdbRating != "0") {
                        MetaChip(text = details.imdbRating, icon = Icons.Filled.Star)
                    }
                    details.releaseInfo?.let { MetaChip(text = it) }
                    details.runtime?.let { MetaChip(text = it) }
                }
            }
        }
    }
}

@Composable
private fun MetaChip(
    text: String,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        color = Color(0xB30A0A0E),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InfoSection(details: MediaDetails) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (details.genres.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(details.genres) { g ->
                    Surface(
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(
                            g,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (!details.description.isNullOrBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Text(
                details.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 40 else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            Text(
                if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { expanded = !expanded },
            )
            Spacer(Modifier.height(12.dp))
        }
        if (details.cast.isNotEmpty()) {
            LabeledInfo("Cast", details.cast.take(8).joinToString(", "), maxLines = 2)
        }
        if (details.director.isNotEmpty()) {
            LabeledInfo("Director", details.director.joinToString(", "), maxLines = 3)
        }
        details.awards?.let {
            LabeledInfo("Awards", it, maxLines = 2)
        }
    }
}

@Composable
private fun LabeledInfo(label: String, value: String, maxLines: Int = 2) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaySection(
    details: MediaDetails,
    state: DetailsViewModel.DetailsUiState,
    onProvider: (ProviderId?) -> Unit,
    onPlay: (String, String, Int?, Int?, ProviderId?, Long) -> Unit,
) {
    var providerMenu by remember { mutableStateOf(false) }
    val movieResumeMs = state.movieProgress?.positionMs?.takeIf { it > 30_000 } ?: 0
    val nextEp = state.nextEpisode

    val playLabel = when {
        details.type == "movie" && movieResumeMs > 0 -> "Resume (${Format.time(movieResumeMs)})"
        details.type == "movie" -> "Play movie"
        else -> "Play ${nextEp?.let { "S${it.season} E${it.number}" } ?: "episode"}"
    }

    // Same semantics as before: movies auto-resume when there is progress,
    // series jump to the next unwatched episode.
    val playAction: () -> Unit = {
        if (details.type == "movie") {
            // >0 = explicit resume, -1 = start over, 0 = auto (resume if any)
            val startAt = if (movieResumeMs > 0) movieResumeMs else 0L
            onPlay(details.imdbId, details.type, null, null, state.provider, startAt)
        } else if (nextEp != null) {
            onPlay(details.imdbId, details.type, nextEp.season, nextEp.number, state.provider, 0)
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // big filled gradient play button
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val glow by animateFloatAsState(if (focused) 24f else 0f, label = "playGlow")
        val buttonShape = RoundedCornerShape(16.dp)
        val glowColor = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .graphicsLayer {
                    shadowElevation = glow
                    shape = buttonShape
                }
                .clip(buttonShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                        )
                    )
                )
                .border(
                    width = if (focused) 2.dp else 0.dp,
                    color = if (focused) MaterialTheme.colorScheme.tertiary else Color.Transparent,
                    shape = buttonShape,
                )
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = playAction,
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    playLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { providerMenu = true },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        state.provider?.label ?: "Auto (Vidcore first)",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Auto (Vidcore first)") },
                        onClick = { onProvider(null); providerMenu = false },
                    )
                    ProviderId.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = { onProvider(p); providerMenu = false },
                        )
                    }
                }
            }
            if (details.type == "movie" && movieResumeMs > 0) {
                OutlinedButton(
                    onClick = { onPlay(details.imdbId, details.type, null, null, state.provider, -1L) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(46.dp),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Start over")
                }
            }
        }

        Text(
            "Vidcore-first auto source, up to 4K adaptive. Aspect, quality, subtitles switchable during playback.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun SeasonChips(details: MediaDetails, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        items(details.seasons) { s ->
            val label = if (s == 0) "Specials" else "Season $s"
            GenreChip(label, selected = s == selected) { onSelect(s) }
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: EpisodeInfo,
    watched: Boolean,
    progress: ProgressStore.WatchProgress?,
    onPlay: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusAlpha by animateFloatAsState(if (focused) 0.10f else 0f, label = "epFocus")
    val thumbShape = RoundedCornerShape(10.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = focusAlpha))
            .clickable(interactionSource = interaction, indication = null, onClick = onPlay)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .size(width = 144.dp, height = 81.dp)
                .clip(thumbShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ep.thumbnail?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to Color(0x96000000),
                        )
                    )
            )
            Surface(
                color = Color(0xCC0A0A0E),
                contentColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            ) {
                Text(
                    "E${ep.number}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play episode",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .background(Color(0x66000000), CircleShape)
                    .padding(3.dp),
            )
            val prog = progress
            if (prog != null && prog.durationMs > 0) {
                val frac = (prog.positionMs.toFloat() / prog.durationMs).coerceIn(0.02f, 1f)
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(frac)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "E${ep.number}  ${ep.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (watched) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Watched",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ep.firstAired?.take(10)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                }
                ep.runtimeMin?.let {
                    Text("${it}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                }
                ep.rating?.let { r ->
                    if (r != "0" && r != "0.0") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(r, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                val prog = progress
                if (prog != null && prog.positionMs > 30_000) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Resume ${Format.time(prog.positionMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!ep.overview.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    ep.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
