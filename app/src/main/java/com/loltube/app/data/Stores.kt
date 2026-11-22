package com.loltube.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.loltube.app.providers.ProviderId
import com.loltube.app.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

private val Context.settingsStore by preferencesDataStore(name = "loltube_settings")
private val Context.progressStore by preferencesDataStore(name = "loltube_progress")

enum class QualityMode(val label: String) {
    AUTO_4K("Auto (adaptive, up to 4K)"),
    FORCE_MAX("Force max quality (4K lock)"),
    CAP_1080("Cap at 1080p"),
    CAP_720("Cap at 720p"),
}

enum class AspectMode(val label: String) {
    AUTO("Auto — no black bars (stretch to fit device)"),
    FIT("Fit — letterbox with black bars"),
    FILL("Stretch — fill screen, ignore ratio"),
    ZOOM("Zoom — fill screen, crop edges"),
}

enum class BufferProfile(val label: String, val minMs: Int, val maxMs: Int, val playbackMs: Int, val rebufferMs: Int, val backMs: Int) {
    SMOOTH("Smooth 4K (30–90s buffer)", 30_000, 90_000, 5_000, 8_000, 15_000),
    BALANCED("Balanced (20–60s buffer)", 20_000, 60_000, 3_000, 5_000, 10_000),
    LOW_MEM("Low memory (10–30s buffer)", 10_000, 30_000, 2_000, 3_000, 5_000),
}

data class AppSettings(
    val providerOrder: List<ProviderId> = listOf(ProviderId.VIDFAST, ProviderId.VIDCORE, ProviderId.MAPPLE),
    val quality: QualityMode = QualityMode.AUTO_4K,
    val aspect: AspectMode = AspectMode.AUTO,
    val bufferProfile: BufferProfile = BufferProfile.SMOOTH,
    val autoNextEpisode: Boolean = true,
    val preferredSubtitleLang: String = "en",
    val subtitlesEnabled: Boolean = true,
)

object SettingsStore {
    private val KEY_PROVIDER_ORDER = stringPreferencesKey("provider_order")
    private val KEY_QUALITY = stringPreferencesKey("quality")
    private val KEY_ASPECT = stringPreferencesKey("aspect")
    private val KEY_BUFFER = stringPreferencesKey("buffer_profile")
    private val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next_episode")
    private val KEY_SUB_LANG = stringPreferencesKey("preferred_subtitle_lang")
    private val KEY_SUBS_ENABLED = booleanPreferencesKey("subtitles_enabled")

    fun flow(context: Context): Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            providerOrder = parseProviders(p[KEY_PROVIDER_ORDER]),
            quality = enumOr(p[KEY_QUALITY], QualityMode.AUTO_4K),
            aspect = enumOr(p[KEY_ASPECT], AspectMode.AUTO),
            bufferProfile = enumOr(p[KEY_BUFFER], BufferProfile.SMOOTH),
            autoNextEpisode = p[KEY_AUTO_NEXT] ?: true,
            preferredSubtitleLang = p[KEY_SUB_LANG] ?: "en",
            subtitlesEnabled = p[KEY_SUBS_ENABLED] ?: true,
        )
    }

    suspend fun read(context: Context): AppSettings = flow(context).first()

    suspend fun setProviderOrder(context: Context, order: List<ProviderId>) {
        context.settingsStore.edit { it[KEY_PROVIDER_ORDER] = order.joinToString(",") { it.name } }
    }

    suspend fun setQuality(context: Context, q: QualityMode) {
        context.settingsStore.edit { it[KEY_QUALITY] = q.name }
    }

    suspend fun setAspect(context: Context, a: AspectMode) {
        context.settingsStore.edit { it[KEY_ASPECT] = a.name }
    }

    suspend fun setBufferProfile(context: Context, b: BufferProfile) {
        context.settingsStore.edit { it[KEY_BUFFER] = b.name }
    }

    suspend fun setAutoNext(context: Context, v: Boolean) {
        context.settingsStore.edit { it[KEY_AUTO_NEXT] = v }
    }

    suspend fun setSubLang(context: Context, lang: String) {
        context.settingsStore.edit { it[KEY_SUB_LANG] = lang }
    }

    suspend fun setSubsEnabled(context: Context, v: Boolean) {
        context.settingsStore.edit { it[KEY_SUBS_ENABLED] = v }
    }

    private fun parseProviders(raw: String?): List<ProviderId> {
        if (raw.isNullOrBlank()) return AppSettings().providerOrder
        val parsed = raw.split(",").mapNotNull { s -> ProviderId.entries.firstOrNull { it.name == s.trim() } }
        val missing = ProviderId.entries.filter { it !in parsed }
        return (parsed + missing).ifEmpty { AppSettings().providerOrder }
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, default: T): T =
        raw?.let { r -> enumValues<T>().firstOrNull { it.name == r } } ?: default
}

/** Watch progress persistence (continue watching + resume). */
object ProgressStore {
    @kotlinx.serialization.Serializable
    data class WatchProgress(
        val imdbId: String,
        val type: String,
        val season: Int? = null,
        val episode: Int? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val title: String = "",
        val episodeName: String? = null,
        val poster: String? = null,
        val updatedAt: Long = 0,
    )

    private val KEY_ITEMS = stringPreferencesKey("items")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WatchProgress.serializer())

    private suspend fun all(context: Context): List<WatchProgress> {
        val raw = context.progressStore.data.first()[KEY_ITEMS] ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse { AppLog.e("Progress", "corrupt store, resetting"); emptyList() }
    }

    private fun keyOf(w: WatchProgress) = "${w.imdbId}:${w.season ?: 0}:${w.episode ?: 0}"

    suspend fun save(context: Context, wp: WatchProgress) {
        val items = all(context).toMutableList()
        items.removeAll { keyOf(it) == keyOf(wp) }
        items.add(wp)
        val trimmed = items.sortedByDescending { it.updatedAt }.take(200)
        context.progressStore.edit { prefs -> prefs[KEY_ITEMS] = json.encodeToString(serializer, trimmed) }
    }

    suspend fun get(context: Context, imdbId: String, season: Int?, episode: Int?): WatchProgress? =
        all(context).firstOrNull { it.imdbId == imdbId && it.season == season && it.episode == episode }

    /** Continue-watching list: only entries with meaningful progress. */
    suspend fun continueWatching(context: Context): List<WatchProgress> =
        all(context)
            .filter { it.positionMs > 30_000 && (it.durationMs <= 0 || it.positionMs < it.durationMs * 95 / 100) }
            .sortedByDescending { it.updatedAt }
            .take(20)

    suspend fun watchedEpisodes(context: Context, imdbId: String): Set<String> =
        all(context)
            .filter { it.imdbId == imdbId && it.season != null && it.episode != null && it.durationMs > 0 && it.positionMs > it.durationMs * 90 / 100 }
            .map { "${it.season}:${it.episode}" }
            .toSet()

    suspend fun remove(context: Context, imdbId: String, season: Int?, episode: Int?) {
        val items = all(context).filterNot { it.imdbId == imdbId && it.season == season && it.episode == episode }
        context.progressStore.edit { prefs -> prefs[KEY_ITEMS] = json.encodeToString(serializer, items) }
    }
}
