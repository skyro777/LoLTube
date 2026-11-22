package com.loltube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.loltube.app.data.Catalog
import com.loltube.app.data.CatalogEntry
import com.loltube.app.ui.components.EmptyHint
import com.loltube.app.ui.components.LoadingHint
import com.loltube.app.ui.components.PosterCard
import com.loltube.app.util.Format

@Composable
fun HomeScreen(
    onOpenDetails: (imdbId: String, type: String) -> Unit,
    onPlay: (imdbId: String, type: String, season: Int?, episode: Int?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // header: title + search + settings
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp),
        ) {
            Text(
                "LoLTube",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search movies & series…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        TabRow(selectedTabIndex = state.tab) {
            listOf("Home", "Movies", "Series").forEachIndexed { i, label ->
                Tab(
                    selected = state.tab == i,
                    onClick = { vm.setTab(i) },
                    text = { Text(label, fontWeight = if (state.tab == i) FontWeight.Bold else FontWeight.Normal) },
                )
            }
        }

        when {
            state.searchQuery.trim().length >= 2 -> SearchResults(
                state = state,
                onOpen = onOpenDetails,
            )

            state.tab == 0 -> HomeTab(state = state, vm = vm, onOpen = onOpenDetails, onPlay = onPlay)

            else -> GenreGridTab(
                state = state,
                vm = vm,
                onOpen = onOpenDetails,
            )
        }
    }
}

@Composable
private fun HomeTab(
    state: HomeViewModel.HomeUiState,
    vm: HomeViewModel,
    onOpen: (String, String) -> Unit,
    onPlay: (String, String, Int?, Int?) -> Unit,
) {
    if (state.initialLoading) {
        LoadingHint("Loading catalog…")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        if (state.continueWatching.isNotEmpty()) {
            item {
                ContinueRow(state = state, onResume = onPlay, onOpen = onOpen)
            }
        }
        item {
            PosterRow(
                title = "Top Movies",
                items = state.topMovies,
                onOpen = onOpen,
            )
        }
        item {
            PosterRow(
                title = "Top Series",
                items = state.topSeries,
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun ContinueRow(
    state: HomeViewModel.HomeUiState,
    onResume: (String, String, Int?, Int?) -> Unit,
    onOpen: (String, String) -> Unit,
) {
    Column {
        Text(
            "Continue Watching",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.continueWatching, key = { "${it.imdbId}-${it.season}-${it.episode}" }) { wp ->
                Column(
                    modifier = Modifier
                        .width(190.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onResume(wp.imdbId, wp.type, wp.season, wp.episode) },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(104.dp)
                    ) {
                        AsyncImage(
                            model = wp.poster ?: Catalog.backdrop(wp.imdbId),
                            contentDescription = wp.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0x55000000))
                        )
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Resume",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(34.dp)
                                .background(Color(0x88000000), CircleShape)
                                .padding(4.dp),
                        )
                        val pct = if (wp.durationMs > 0) wp.positionMs.toFloat() / wp.durationMs else 0f
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(pct.coerceIn(0.02f, 1f))
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text(
                        buildString {
                            append(wp.title)
                            wp.season?.let { s -> append("  S$s"); wp.episode?.let { e -> append("E$e") } }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        "${Format.time(wp.positionMs)} / ${Format.time(wp.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterRow(
    title: String,
    items: List<CatalogEntry>,
    onOpen: (String, String) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (items.isEmpty()) {
            Text(
                "Nothing loaded - pull the tab again later",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.imdbId }) { entry ->
                PosterCard(entry) { onOpen(entry.imdbId, entry.type) }
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: HomeViewModel.HomeUiState,
    onOpen: (String, String) -> Unit,
) {
    if (state.searching) {
        LoadingHint("Searching…")
        return
    }
    if (state.searchResults.isEmpty()) {
        EmptyHint("No results for \"${state.searchQuery}\"")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(126.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.searchResults, key = { it.imdbId }) { entry ->
            PosterCard(entry) { onOpen(entry.imdbId, entry.type) }
        }
    }
}

@Composable
private fun GenreGridTab(
    state: HomeViewModel.HomeUiState,
    vm: HomeViewModel,
    onOpen: (String, String) -> Unit,
) {
    val gridState = rememberLazyGridState()

    // infinite scroll trigger
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 12
        }
    }
    LaunchedEffect(shouldLoadMore, state.gridItems.size, state.gridDone, state.gridLoading) {
        if (shouldLoadMore && !state.gridLoading && !state.gridDone) vm.loadMoreGrid()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GenreChip("All", state.genre == null) { vm.setGenre(null) }
            Catalog.GENRES.forEach { g ->
                GenreChip(g, state.genre == g) { vm.setGenre(g) }
            }
        }
        if (state.gridItems.isEmpty() && state.gridLoading) {
            LoadingHint("Loading…")
        } else if (state.gridItems.isEmpty()) {
            EmptyHint("Nothing here. Try another genre.")
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(126.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.gridItems, key = { it.imdbId }) { entry ->
                    PosterCard(entry) { onOpen(entry.imdbId, entry.type) }
                }
                if (state.gridLoading) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        LoadingHint("Loading more…")
                    }
                }
            }
        }
    }
}

@Composable
fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}
