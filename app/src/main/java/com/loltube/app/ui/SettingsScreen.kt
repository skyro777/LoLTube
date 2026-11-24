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
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

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
                    CardSubHeader("Buffer profile (4K smoothness)")
                    CardDescription(
                        "Bigger buffers absorb network dips at the cost of memory. Use Smooth on devices with 2GB+ RAM."
                    )
                    BufferProfile.entries.forEach { profile ->
                        OptionRow(profile.label, settings.bufferProfile == profile) {
                            scope.launch { SettingsStore.setBufferProfile(context, profile) }
                        }
                    }
                    CardSubHeader("Turbo download-ahead")
                    CardDescription(
                        "Downloads the next ~90 seconds of the video in parallel (3 connections) into the on-device cache while you watch, so slow servers never stall playback. Recommended ON."
                    )
                    ToggleRow(
                        "Turbo prefetch (no-buffer engine)",
                        settings.turboPrefetch,
                    ) { v -> scope.launch { SettingsStore.setTurboPrefetch(context, v) } }
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
