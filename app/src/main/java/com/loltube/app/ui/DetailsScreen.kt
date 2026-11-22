package com.loltube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.loltube.app.data.EpisodeInfo
import com.loltube.app.data.MediaDetails
import com.loltube.app.providers.ProviderId
import com.loltube.app.ui.components.LoadingHint
import com.loltube.app.util.Format

@Composable
fun DetailsScreen(
    imdbId: String,
    type: String,
    onBack: () -> Unit,
    onPlay: (imdbId: String, type: String, season: Int?, episode: Int?, provider: ProviderId?, startAtMs: Long) -> Unit,
) {
    val vm: DetailsViewModel = viewModel()
    vm.load(imdbId, type)
    val state by vm.state.collectAsState()

    val details = state.details
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
            Text(state.error ?: "Not found", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { HeroSection(details, onBack) }
        item { InfoSection(details) }
        item {
            PlaySection(
                details = details,
                providerChoice = state.provider,
                movieResumeMs = state.movieProgress?.positionMs?.takeIf { it > 30_000 } ?: 0,
                onProvider = vm::setProvider,
                onPlay = onPlay,
            )
        }
        if (details.type == "series") {
            item { SeasonChips(details, state.selectedSeason, vm::setSeason) }
            val season = details.episodes.filter { it.season == state.selectedSeason }
            items(season, key = { it.id.ifEmpty { "${it.season}-${it.number}" } }) { ep ->
                EpisodeRow(
                    ep = ep,
                    watched = "${ep.season}:${ep.number}" in state.watchedEpisodes,
                    onPlay = {
                        onPlay(details.imdbId, details.type, ep.season, ep.number, state.provider, 0)
                    },
                )
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun HeroSection(details: MediaDetails, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(260.dp)) {
        AsyncImage(
            model = details.background ?: details.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.5f to Color(0x66000000),
                        1f to MaterialTheme.colorScheme.background,
                    )
                )
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(6.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            AsyncImage(
                model = details.poster,
                contentDescription = details.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(110.dp)
                    .height(165.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    details.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!details.imdbRating.isNullOrEmpty() && details.imdbRating != "0") {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(details.imdbRating, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(12.dp))
                    }
                    details.releaseInfo?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                    }
                    details.runtime?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            g,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(10.dp))
        }
        if (details.cast.isNotEmpty()) {
            Text(
                "Cast: " + details.cast.take(8).joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (details.director.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Director: " + details.director.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        details.awards?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaySection(
    details: MediaDetails,
    providerChoice: ProviderId?,
    movieResumeMs: Long,
    onProvider: (ProviderId?) -> Unit,
    onPlay: (String, String, Int?, Int?, ProviderId?, Long) -> Unit,
) {
    var providerMenu by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val startAt = if (details.type == "movie" && movieResumeMs > 0) movieResumeMs else 0
                    onPlay(details.imdbId, details.type, null, null, providerChoice, startAt)
                },
                colors = ButtonDefaults.buttonColors(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (details.type == "movie" && movieResumeMs > 0)
                        "Resume (${Format.time(movieResumeMs)})"
                    else if (details.type == "movie") "Play movie"
                    else "Choose episode below",
                    fontWeight = FontWeight.Bold,
                )
            }
            if (details.type == "movie" && movieResumeMs > 0) {
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { onPlay(details.imdbId, details.type, null, null, providerChoice, 0) },
                    modifier = Modifier.height(48.dp),
                ) { Text("Start over") }
            }
            Spacer(Modifier.width(10.dp))
            Box {
                OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.height(48.dp)) {
                    Text(providerChoice?.label ?: "Auto")
                }
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Auto (try all)") },
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
        }
        Text(
            "Auto streams 4K when available. Aspect & quality are adjustable during playback.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SeasonChips(details: MediaDetails, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(details.seasons) { s ->
            val label = if (s == 0) "Specials" else "Season $s"
            GenreChip(label, selected = s == selected) { onSelect(s) }
        }
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeInfo, watched: Boolean, onPlay: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(width = 128.dp, height = 72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            ep.thumbnail?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
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
            Row {
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
                        Text("★ $r", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
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
