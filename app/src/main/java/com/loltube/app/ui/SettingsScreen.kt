package com.loltube.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loltube.app.data.AppSettings
import com.loltube.app.data.AspectMode
import com.loltube.app.data.BufferProfile
import com.loltube.app.data.QualityMode
import com.loltube.app.data.SettingsStore
import com.loltube.app.providers.ProviderId
import com.loltube.app.ui.components.SectionHeader
import kotlinx.coroutines.launch

private val SUB_LANGUAGES = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "hi" to "Hindi", "pt" to "Portuguese", "ar" to "Arabic", "ru" to "Russian",
    "it" to "Italian", "tr" to "Turkish", "id" to "Indonesian", "ja" to "Japanese",
    "ko" to "Korean", "zh" to "Chinese", "off" to "Off (no auto subtitle)",
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow(context).collectAsState(initial = AppSettings())

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }

        // ---- provider order ----
        item {
            SectionHeader("Source priority")
            Text(
                "When set to Auto, sources are tried in this order. Mapple needs a TMDB id (fetched automatically).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(settings.providerOrder.size) { idx ->
            val id = settings.providerOrder[idx]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("${idx + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(id.label, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
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

        // ---- playback ----
        item {
            SectionHeader("Default quality")
            QualityMode.entries.forEach { mode ->
                OptionRow(mode.label, settings.quality == mode) {
                    scope.launch { SettingsStore.setQuality(context, mode) }
                }
            }
        }
        item {
            SectionHeader("Aspect ratio")
            Text(
                "Auto stretches the video to fill your screen (no black bars) whenever the video ratio differs from the device ratio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            AspectMode.entries.forEach { mode ->
                OptionRow(mode.label, settings.aspect == mode) {
                    scope.launch { SettingsStore.setAspect(context, mode) }
                }
            }
        }
        item {
            SectionHeader("Buffer profile (4K smoothness)")
            Text(
                "Bigger buffers absorb network dips at the cost of memory. Use Smooth on devices with 2GB+ RAM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            BufferProfile.entries.forEach { profile ->
                OptionRow(profile.label, settings.bufferProfile == profile) {
                    scope.launch { SettingsStore.setBufferProfile(context, profile) }
                }
            }
        }

        // ---- episodes / subtitles ----
        item {
            SectionHeader("Episodes")
            ToggleRow(
                "Auto-play next episode",
                settings.autoNextEpisode,
            ) { v -> scope.launch { SettingsStore.setAutoNext(context, v) } }
        }
        item {
            SectionHeader("Subtitles")
            ToggleRow(
                "Auto-enable preferred subtitle",
                settings.subtitlesEnabled,
            ) { v -> scope.launch { SettingsStore.setSubsEnabled(context, v) } }
            Text(
                "Preferred language",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
            )
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

        // ---- diagnostics ----
        item {
            SectionHeader("Diagnostics")
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = onOpenLogs) { Text("View logs") }
                Spacer(Modifier.width(10.dp))
            }
            Text(
                "LoLTube resolves streams by running each site's player in a background WebView and " +
                    "capturing the video manifest, then plays it in the native engine (Media3/ExoPlayer) " +
                    "with tuned 4K buffers and aspect control.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        if (selected) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "active",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val logs = com.loltube.app.util.AppLog.lines
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text("Logs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { com.loltube.app.util.AppLog.clear() },
                modifier = Modifier.padding(end = 12.dp),
            ) { Text("Clear") }
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logs.size) { i ->
                Text(
                    logs[i],
                    fontSize = 11.sp,
                    color = Color(0xFF9CCC9C),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
    }
}
