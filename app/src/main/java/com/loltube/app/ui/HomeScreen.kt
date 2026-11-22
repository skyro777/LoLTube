package com.loltube.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.loltube.app.data.Catalog
import com.loltube.app.data.CatalogEntry
import com.loltube.app.data.ProgressStore
import com.loltube.app.ui.components.EmptyHint
import com.loltube.app.ui.components.LoadingHint
import com.loltube.app.ui.components.PosterCard
import com.loltube.app.ui.components.SectionHeader
import com.loltube.app.util.Format
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onOpenDetails: (imdbId: String, type: String) -> Unit,
    onPlay: (imdbId: String, type: String, season: Int?, episode: Int?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val state by vm.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // header: wordmark + search + settings
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
        ) {
            Text(
                "LoLTube",
                style = MaterialTheme.typography.headlineSmall.copy(
                    brush = Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                        )
                    ),
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                ),
            )
            Spacer(Modifier.width(14.dp))
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = vm::setQuery,
                placeholder = {
                    Text(
                        "Search movies & series…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.weight(1f),
            )
            val settingsInteraction = remember { MutableInteractionSource() }
            val settingsFocused by settingsInteraction.collectIsFocusedAsState()
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = if (settingsFocused) {
                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                } else null,
                modifier = Modifier.padding(start = 6.dp),
            ) {
                IconButton(
                    onClick = onOpenSettings,
                    interactionSource = settingsInteraction,
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SegmentedTabs(selectedTab = state.tab) { i -> vm.setTab(i) }

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

/** Modern segmented pill control for Home / Movies / Series. */
@Composable
private fun SegmentedTabs(selectedTab: Int, onTab: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(4.dp),
    ) {
        listOf("Home", "Movies", "Series").forEachIndexed { i, label ->
            val selected = selectedTab == i
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = interaction, indication = null) { onTab(i) }
                    .border(
                        width = if (focused) 1.5.dp else 0.dp,
                        color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                    ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
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
        if (state.topMovies.isNotEmpty()) {
            item {
                HeroCarousel(items = state.topMovies.take(6), onOpen = onOpen)
            }
        }
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

/**
 * Cinematic hero carousel: large 16:9 backdrops with scrim, title, meta chips
 * and an open affordance. Auto-advances every 7s (paused while the user is
 * dragging; pure scrolling never steals D-pad focus).
 */
@Composable
private fun HeroCarousel(items: List<CatalogEntry>, onOpen: (String, String) -> Unit) {
    val listState = rememberLazyListState()

    LaunchedEffect(items.size) {
        if (items.size < 2) return@LaunchedEffect
        while (true) {
            delay(7000)
            if (!listState.isScrollInProgress) {
                val next = (listState.firstVisibleItemIndex + 1) % items.size
                listState.animateScrollToItem(next)
            }
        }
    }

    val current by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceAtMost(items.lastIndex) }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = maxWidth - 56.dp
        val heroWidth = if (cardWidth < 240.dp) 240.dp else cardWidth
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.imdbId }) { entry ->
                HeroCard(entry = entry, cardWidth = heroWidth) {
                    onOpen(entry.imdbId, entry.type)
                }
            }
        }
    }

    if (items.size > 1) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        ) {
            repeat(items.size) { i ->
                Box(
                    Modifier
                        .size(if (i == current) 7.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
            }
        }
    }
}

@Composable
private fun HeroCard(entry: CatalogEntry, cardWidth: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "heroScale")
    val glow by animateFloatAsState(if (focused) 26f else 0f, label = "heroGlow")
    val cardShape = RoundedCornerShape(16.dp)
    val glowColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .width(cardWidth)
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = glow
                shape = cardShape
                ambientColor = glowColor
                spotColor = glowColor
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
            model = entry.background ?: Catalog.backdrop(entry.imdbId),
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.35f to Color(0x14000000),
                        0.55f to Color.Transparent,
                        1f to Color(0xE6000000),
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!entry.imdbRating.isNullOrEmpty() && entry.imdbRating != "0") {
                    HeroChip(
                        text = entry.imdbRating,
                        icon = Icons.Filled.Star,
                        iconTint = MaterialTheme.colorScheme.primary,
                    )
                }
                entry.releaseInfo?.let { HeroChip(text = it.take(4)) }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "Open",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroChip(
    text: String,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        color = Color(0xA60A0A0E),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
private fun ContinueRow(
    state: HomeViewModel.HomeUiState,
    onResume: (String, String, Int?, Int?) -> Unit,
    onOpen: (String, String) -> Unit,
) {
    Column {
        SectionHeader("Continue Watching")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.continueWatching, key = { "${it.imdbId}-${it.season}-${it.episode}" }) { wp ->
                ContinueCard(wp = wp, onResume = onResume)
            }
        }
    }
}

@Composable
private fun ContinueCard(
    wp: ProgressStore.WatchProgress,
    onResume: (String, String, Int?, Int?) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "contScale")
    val glow by animateFloatAsState(if (focused) 20f else 0f, label = "contGlow")
    val cardShape = RoundedCornerShape(12.dp)
    val glowColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .width(186.dp)
            .scale(scale)
            .clip(cardShape)
            .clickable(interactionSource = interaction, indication = null) {
                onResume(wp.imdbId, wp.type, wp.season, wp.episode)
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    shadowElevation = glow
                    shape = cardShape
                    ambientColor = glowColor
                    spotColor = glowColor
                }
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (focused) 2.dp else 0.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = cardShape,
                ),
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
                    .background(
                        Brush.horizontalGradient(
                            0.4f to Color(0x33000000),
                            1f to Color(0x66000000),
                        )
                    )
            )
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Resume",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(38.dp)
                    .background(Color(0x66000000), CircleShape)
                    .padding(6.dp),
            )
            val pct = if (wp.durationMs > 0) wp.positionMs.toFloat() / wp.durationMs else 0f
            if (pct > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(pct.coerceIn(0.02f, 1f))
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                buildString {
                    append(wp.title)
                    wp.season?.let { s -> append("  S$s"); wp.episode?.let { e -> append("E$e") } }
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Format.time(wp.positionMs)} / ${Format.time(wp.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
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
        SectionHeader(title)
        if (items.isEmpty()) {
            Text(
                "Nothing loaded yet - check back in a moment",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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

/** Rounded pill chip used for genres (home) and seasons (details). */
@Composable
fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(20.dp),
        border = if (focused) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}
