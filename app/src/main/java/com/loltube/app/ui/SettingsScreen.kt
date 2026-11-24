package com.loltube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loltube.app.data.AppSettings
import com.loltube.app.data.AspectMode
import com.loltube.app.data.BufferProfile
import com.loltube.app.data.QualityMode
import com.loltube.app.data.SettingsStore
import com.loltube.app.data.SubColor
import com.loltube.app.data.SubSize
import com.loltube.app.providers.ProviderId
import com.loltube.app.util.StorageJanitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val SUB_LANGUAGES = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "hi" to "Hindi", "pt" to "Portuguese", "ar" to "Arabic", "ru" to "Russian",
    "it" to "Italian", "tr" to "Turkish", "id" to "Indonesian", "ja" to "Japanese",
    "ko" to "Korean", "zh" to "Chinese", "off" to "Off (no auto subtitle)",
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow(context).collectAsState(initial = AppSettings())

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 16.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "Playback, subtitles, sources & diagnostics",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        ) {
            // ---- playback ----
            item {
                SettingsCard(title = "Playback", icon = Icons.Filled.Tune) {
                    CardSubHeader("Default quality")
                    QualityMode.entries.forEach { mode ->
                        OptionRow(mode.label, settings.quality == mode) {
                            scope.launch { SettingsStore.setQuality(context, mode) }
                        }
                    }
                    CardSubHeader("Aspect ratio")
                    CardDescription(
                        "Auto stretches the video to fill your screen (no black bars) whenever the video ratio differs from the device ratio."
                    )
                    AspectMode.entries.forEach { mode ->
                        OptionRow(mode.label, settings.aspect == mode) {
                            scope.launch { SettingsStore.setAspect(context, mode) }
                        }
                    }

                    // ---- manual stretch (works on top of any mode) ----
                    CardSubHeader("Manual stretch")
                    CardDescription(
                        "For movies that keep black bars even in Auto (the bars are " +
                            "burned into the picture — no player can auto-detect that) and " +
                            "screens that need sideways stretch. Applies on top of the mode above."
                    )
                    StretchSlider(
                        label = "Vertical stretch",
                        value = settings.stretchYPct,
                    ) { v -> scope.launch { SettingsStore.setStretchY(context, v) } }
                    StretchSlider(
                        label = "Horizontal stretch",
                        value = settings.stretchXPct,
                    ) { v -> scope.launch { SettingsStore.setStretchX(context, v) } }

                    CardSubHeader("Buffer profile (4K smoothness)")
                    CardDescription(
                        "Deep 4K is recommended — it spends more RAM so 4K plays without " +
                            "buffering (ideal on 2GB+ RAM devices). Balanced/Low memory for low-RAM devices."
                    )
                    BufferProfile.entries.forEach { profile ->
                        OptionRow(profile.label, settings.bufferProfile == profile) {
                            scope.launch { SettingsStore.setBufferProfile(context, profile) }
                        }
                    }
                }
            }

            // ---- episodes ----
            item {
                SettingsCard(title = "Episodes", icon = Icons.Filled.PlaylistPlay) {
                    ToggleRow(
                        "Auto-play next episode",
                        settings.autoNextEpisode,
                    ) { v -> scope.launch { SettingsStore.setAutoNext(context, v) } }
                }
            }

            // ---- subtitles ----
            item {
                SettingsCard(title = "Subtitles", icon = Icons.Filled.Subtitles) {
                    ToggleRow(
                        "Auto-enable preferred subtitle",
                        settings.subtitlesEnabled,
                    ) { v -> scope.launch { SettingsStore.setSubsEnabled(context, v) } }
                    CardSubHeader("Preferred language")
                    SUB_LANGUAGES.forEach { (code, label) ->
                        val active =
                            if (code == "off") !settings.subtitlesEnabled || settings.preferredSubtitleLang == "off"
                            else settings.subtitlesEnabled && settings.preferredSubtitleLang == code
                        OptionRow(label, active) {
                            if (code == "off") {
                                scope.launch { SettingsStore.setSubsEnabled(context, false) }
                            } else {
                                scope.launch {
                                    SettingsStore.setSubLang(context, code)
                                    SettingsStore.setSubsEnabled(context, true)
                                }
                            }
                        }
                    }

                    CardSubHeader("Text size")
                    CardDescription("Normal is already smaller than the old built-in renderer.")
                    SubSize.entries.forEach { s ->
                        OptionRow("${s.label} (${s.pct}%)", settings.subtitleSize == s) {
                            scope.launch { SettingsStore.setSubtitleSize(context, s) }
                        }
                    }

                    CardSubHeader("Text color")
                    SubColor.entries.forEach { c ->
                        val swatch = when (c) {
                            SubColor.WHITE -> Color(0xFFFFFFFF)
                            SubColor.YELLOW -> Color(0xFFFFE14D)
                            SubColor.CYAN -> Color(0xFF4DE5FF)
                            SubColor.SILVER -> Color(0xFFC9CDD4)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { scope.launch { SettingsStore.setSubtitleColor(context, c) } }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (settings.subtitleColor == c) 2.dp else 1.5.dp,
                                        color = if (settings.subtitleColor == c) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape,
                                    )
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                c.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (settings.subtitleColor == c) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    CardSubHeader("Background darkness")
                    CardDescription("Behind the subtitle text — 0 = transparent.")
                    val bgLevels = listOf(0, 25, 55, 80)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        bgLevels.forEach { pct ->
                            val sel = settings.subtitleBgPct == pct ||
                                (pct == 55 && settings.subtitleBgPct !in bgLevels)
                            Surface(
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    scope.launch { SettingsStore.setSubtitleBgPct(context, pct) }
                                },
                            ) {
                                Text(
                                    "$pct%",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ---- subtitle sync per source ----
            item {
                SettingsCard(title = "Subtitle sync", icon = Icons.Filled.Timer) {
                    CardDescription(
                        "Each source's video copy is trimmed differently, so one subtitle file can run " +
                            "far ahead or behind depending on the source. Drag the slider (±60 seconds, " +
                            "millisecond precision) or use the fine buttons — the offset sticks per source. " +
                            "(The fastest way: the Sync button while playing.)"
                    )
                    Spacer(Modifier.height(4.dp))
                    ProviderId.entries.forEach { p ->
                        val delay = settings.subDelayByProvider[p] ?: 0
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    p.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatDelay(delay),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (delay != 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            var local by remember(delay) { mutableFloatStateOf(delay / 1000f) }
                            Slider(
                                value = local,
                                onValueChange = { local = it },
                                onValueChangeFinished = {
                                    scope.launch {
                                        SettingsStore.setSubDelayFor(context, p, (local * 1000f).roundToInt())
                                    }
                                },
                                valueRange = -60f..60f,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(-5_000, -1_000, -100).forEach { d ->
                                    OutlinedButton(onClick = {
                                        scope.launch { SettingsStore.setSubDelayFor(context, p, delay + d) }
                                    }) { Text("−${absSec(d)}s") }
                                }
                                OutlinedButton(onClick = {
                                    scope.launch { SettingsStore.setSubDelayFor(context, p, 0) }
                                }) { Text("0") }
                                listOf(100, 1_000, 5_000).forEach { d ->
                                    OutlinedButton(onClick = {
                                        scope.launch { SettingsStore.setSubDelayFor(context, p, delay + d) }
                                    }) { Text("+${absSec(d)}s") }
                                }
                            }
                        }
                    }
                    CardDescription("− = subtitles earlier · + = subtitles later · range ±60s")
                }
            }

            // ---- provider order ----
            item {
                SettingsCard(title = "Sources", icon = Icons.Filled.Storage) {
                    CardDescription(
                        "When set to Auto, sources are tried in this order. Mapple needs a TMDB id (fetched automatically)."
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(settings.providerOrder.size) { idx ->
                val id = settings.providerOrder[idx]
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        Surface(
                            color = if (idx == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                "${idx + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (idx == 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(id.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            if (idx == 0) {
                                Text(
                                    "Tried first",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        IconButton(
                            enabled = idx > 0,
                            onClick = {
                                val list = settings.providerOrder.toMutableList()
                                val tmp = list[idx - 1]
                                list[idx - 1] = list[idx]
                                list[idx] = tmp
                                scope.launch { SettingsStore.setProviderOrder(context, list) }
                            },
                        ) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                        IconButton(
                            enabled = idx < settings.providerOrder.size - 1,
                            onClick = {
                                val list = settings.providerOrder.toMutableList()
                                val tmp = list[idx + 1]
                                list[idx + 1] = list[idx]
                                list[idx] = tmp
                                scope.launch { SettingsStore.setProviderOrder(context, list) }
                            },
                        ) { Icon(Icons.Filled.ArrowDownward, "Move down") }
                    }
                }
            }

            // ---- storage management ----
            item {
                SettingsCard(title = "Storage", icon = Icons.Filled.DeleteSweep) {
                    var cacheBytes by remember { mutableStateOf<Long?>(null) }
                    var clearing by remember { mutableStateOf(false) }
                    var freedNote by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(clearing) {
                        cacheBytes = withContext(Dispatchers.IO) { StorageJanitor.usedBytes(context) }
                    }
                    CardDescription(
                        "LoLTube uses the cache to run 4K smoothly while playing — nothing is " +
                            "erased mid-playback. When the app closes (or on demand below) the video cache, " +
                            "subtitle files, images and WebView data are erased, so storage never balloons " +
                            "and every session starts fresh (that also fixes sources that stopped loading)."
                    )
                    Spacer(Modifier.height(4.dp))
                    CardSubHeader("Currently on disk")
                    Text(
                        cacheBytes?.let { "${"%.0f".format(it / 1_000_000f)} MB of caches" } ?: "measuring…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        OutlinedButton(
                            enabled = cacheBytes != null && !clearing,
                            onClick = {
                                clearing = true
                                scope.launch {
                                    val before = cacheBytes ?: 0L
                                    withContext(Dispatchers.IO) { StorageJanitor.sweep(context) }
                                    cacheBytes = withContext(Dispatchers.IO) { StorageJanitor.usedBytes(context) }
                                    freedNote = "Cleared — freed ${"%.0f".format(before / 1_000_000f)} MB"
                                    clearing = false
                                }
                            },
                        ) {
                            Text(if (clearing) "Clearing…" else "Clear caches now")
                        }
                    }
                    freedNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    ToggleRow(
                        "Auto-clean when app closes",
                        settings.autoCleanOnExit,
                    ) { v -> scope.launch { SettingsStore.setAutoCleanOnExit(context, v) } }
                }
            }

            // ---- diagnostics ----
            item {
                SettingsCard(title = "Diagnostics", icon = Icons.Filled.BugReport) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        OutlinedButton(onClick = onOpenLogs) { Text("View logs") }
                    }
                    CardDescription(
                        "LoLTube resolves streams by running each site's player in a background WebView and " +
                            "capturing the video manifest, then plays it in the native engine (Media3/ExoPlayer) " +
                            "with tuned 4K buffers and aspect control.",
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** "+2.35s" / "−1.00s" / "0s" for the per-source sync display (ms precision). */
private fun formatDelay(ms: Int): String {
    val s = kotlin.math.abs(ms) / 1000f
    val sign = when {
        ms > 0 -> "+"
        ms < 0 -> "−"
        else -> ""
    }
    return "$sign${"%.2f".format(s)}s"
}

/** "5", "1", "0.1" — compact seconds for fine-adjust buttons. */
private fun absSec(ms: Int): String {
    val s = kotlin.math.abs(ms) / 1000f
    return if (s >= 1) "%.0f".format(s) else "%.1f".format(s)
}

/**
 * Manual-stretch slider row (100–160%), persisted on release. Works on top of
 * the selected aspect mode: 100% leaves that mode untouched.
 */
@Composable
private fun StretchSlider(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (value == 100) "100% (off)" else "$value%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (value != 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local.roundToInt()) },
            valueRange = 100f..160f,
        )
    }
}

/** Rounded card grouping related settings under a bold header with an amber icon. */
@Composable
private fun SettingsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(5.dp)
                            .size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.2.sp,
                )
            }
            content()
        }
    }
}

@Composable
private fun CardSubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CardDescription(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    width = if (selected) 2.dp else 1.5.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    // live snapshot: logs change only while actions happen, so a remember
    // keyed on a manual refresh keeps it stable while text is selected
    val logs = com.loltube.app.util.AppLog.lines
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copiedTick by remember { mutableStateOf(0) }

    fun shareLogs() {
        runCatching {
            val text = com.loltube.app.util.AppLog.lines.joinToString("\n")
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_TITLE, "LoLTube logs")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share logs"))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column {
                Text("Logs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (copiedTick > 0) "Copied ${logs.size} lines to clipboard" else "${logs.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copiedTick > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(com.loltube.app.util.AppLog.lines.joinToString("\n")))
                    copiedTick += 1
                },
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
            OutlinedButton(
                onClick = { shareLogs() },
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Icon(Icons.Filled.Share, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
            OutlinedButton(onClick = {
                com.loltube.app.util.AppLog.clear()
                copiedTick = 0
            }) { Text("Clear") }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .weight(1f),
        ) {
            // selectable text: individual lines can be highlighted + copied
            // with the system clipboard too (D-pad OK, then copy affordance)
            SelectionContainer {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(logs.size) { i ->
                        Text(
                            logs[i],
                            fontSize = 11.sp,
                            color = Color(0xFF9CCC9C),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
