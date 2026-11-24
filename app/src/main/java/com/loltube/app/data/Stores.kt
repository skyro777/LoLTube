package com.loltube.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    AUTO("Auto — adaptive, up to 4K"),
    MAX("4K lock — always top rendition (2160p)"),
    CAP_1080("Cap at 1080p (smoothest HD)"),
    CAP_720("Cap at 720p (slow lines)");

    /** Legacy stored value migration: the old "FORCE_MAX" locked ABR off and
     *  caused permanent rebuffering - map it to the adaptive MAX mode. */
    companion object {
        fun migrate(raw: String?): String? = when (raw) {
            "FORCE_MAX" -> MAX.name
            else -> raw
        }
    }
}

enum class AspectMode(val label: String) {
    AUTO("Auto — no black bars (stretch to fit device)"),
    FIT("Fit — letterbox with black bars"),
    FILL("Stretch — fill screen, ignore ratio"),
    ZOOM("Zoom — fill screen, crop edges"),
}

/** Subtitle text color choices — same palette the sites' players offer. */
enum class SubColor(val label: String) {
    WHITE("White"), YELLOW("Yellow"), CYAN("Cyan"), SILVER("Silver");
}

/** Subtitle text size, % of the player-appropriate base. */
enum class SubSize(val label: String, val pct: Int) {
    SMALL("Small", 80), NORMAL("Normal", 100), LARGE("Large", 125), XL("Extra large", 150);
}

enum class BufferProfile(val label: String, val minMs: Int, val maxMs: Int, val playbackMs: Int, val rebufferMs: Int, val backMs: Int) {
    // Buffer profiles never change WHAT you see (4K stays 4K in all of them —
    // with the 4K lock the visual quality is identical); they only change how
    // much is buffered ahead in RAM/disk to keep it stutter-free.
    SMOOTH("Smooth 4K (30–90s buffer)", 30_000, 90_000, 5_000, 10_000, 30_000),
    DEEP("Deep 4K (45–120s buffer)", 45_000, 120_000, 5_000, 12_000, 30_000),
    BALANCED("Balanced (20–60s buffer)", 20_000, 60_000, 3_000, 5_000, 10_000),
    LOW_MEM("Low memory (10–30s buffer)", 10_000, 30_000, 2_000, 3_000, 5_000),
}

data class AppSettings(
    // Vidcore first — its CDN is measurably faster than vidfast's right now.
    val providerOrder: List<ProviderId> = listOf(ProviderId.VIDCORE, ProviderId.VIDFAST, ProviderId.MAPPLE),
    val quality: QualityMode = QualityMode.AUTO,
    val aspect: AspectMode = AspectMode.AUTO,
    val bufferProfile: BufferProfile = BufferProfile.SMOOTH,
    val autoNextEpisode: Boolean = true,
    val preferredSubtitleLang: String = "en",
    val subtitlesEnabled: Boolean = true,
    val lastBandwidthBps: Int = 0,
    /** Parallel segment read-ahead into the disk cache (the "no buffering" engine). */
    val turboPrefetch: Boolean = true,
    // ---- subtitle appearance (rendered by the app's own overlay) ----
    /** Subtitle text size. Base is noticeably smaller than the old built-in renderer. */
    val subtitleSize: SubSize = SubSize.NORMAL,
    val subtitleColor: SubColor = SubColor.WHITE,
    /** Subtitle background opacity %, 0 = no background plate. */
    val subtitleBgPct: Int = 55,
    /** Per-source subtitle sync delay (ms; >0 = subtitles appear later).
     *  Each source's video copy is trimmed differently, so the offset that
     *  fixes one source is wrong for another — that's why it's per-provider. */
    val subDelayByProvider: Map<ProviderId, Int> = emptyMap(),
)

object SettingsStore {
    private val KEY_PROVIDER_ORDER = stringPreferencesKey("provider_order")
    private val KEY_QUALITY = stringPreferencesKey("quality")
    private val KEY_ASPECT = stringPreferencesKey("aspect")
    private val KEY_BUFFER = stringPreferencesKey("buffer_profile")
    private val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next_episode")
    private val KEY_SUB_LANG = stringPreferencesKey("preferred_subtitle_lang")
    private val KEY_SUBS_ENABLED = booleanPreferencesKey("subtitles_enabled")
    private val KEY_LAST_BITRATE = intPreferencesKey("last_bandwidth_bps")
    private val KEY_TURBO = booleanPreferencesKey("turbo_prefetch")
    private val KEY_SUB_SIZE = stringPreferencesKey("subtitle_size")
    private val KEY_SUB_COLOR = stringPreferencesKey("subtitle_color")
    private val KEY_SUB_BG = intPreferencesKey("subtitle_bg_pct")
    private val KEY_SUB_DELAYS = stringPreferencesKey("sub_delay_by_provider")

    /** The old shipped default order — silently migrated to the new Vidcore-first order. */
    private val LEGACY_DEFAULT_ORDER = listOf(ProviderId.VIDFAST, ProviderId.VIDCORE, ProviderId.MAPPLE)

    fun flow(context: Context): Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            providerOrder = parseProviders(p[KEY_PROVIDER_ORDER]),
            quality = enumOr(QualityMode.migrate(p[KEY_QUALITY]), QualityMode.AUTO),
            aspect = enumOr(p[KEY_ASPECT], AspectMode.AUTO),
            bufferProfile = enumOr(p[KEY_BUFFER], BufferProfile.SMOOTH),
            autoNextEpisode = p[KEY_AUTO_NEXT] ?: true,
            preferredSubtitleLang = p[KEY_SUB_LANG] ?: "en",
            subtitlesEnabled = p[KEY_SUBS_ENABLED] ?: true,
            lastBandwidthBps = p[KEY_LAST_BITRATE] ?: 0,
            turboPrefetch = p[KEY_TURBO] ?: true,
            subtitleSize = enumOr(p[KEY_SUB_SIZE], SubSize.NORMAL),
            subtitleColor = enumOr(p[KEY_SUB_COLOR], SubColor.WHITE),
            subtitleBgPct = (p[KEY_SUB_BG] ?: 55).coerceIn(0, 100),
            subDelayByProvider = parseDelays(p[KEY_SUB_DELAYS]),
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

    /** Persist the bandwidth the ABR measured, so the next session warm-starts. */
    suspend fun setLastBandwidth(context: Context, bps: Int) {
        if (bps <= 0) return
        context.settingsStore.edit { it[KEY_LAST_BITRATE] = bps }
    }

    suspend fun setTurboPrefetch(context: Context, v: Boolean) {
        context.settingsStore.edit { it[KEY_TURBO] = v }
    }

    suspend fun setSubtitleSize(context: Context, v: SubSize) {
        context.settingsStore.edit { it[KEY_SUB_SIZE] = v.name }
    }

    suspend fun setSubtitleColor(context: Context, v: SubColor) {
        context.settingsStore.edit { it[KEY_SUB_COLOR] = v.name }
    }

    suspend fun setSubtitleBgPct(context: Context, v: Int) {
        context.settingsStore.edit { it[KEY_SUB_BG] = v.coerceIn(0, 100) }
    }

    /** Persist the sync delay for ONE provider (VIDCORE=2500,MAPPLE=-1000,…). */
    suspend fun setSubDelayFor(context: Context, provider: ProviderId, delayMs: Int) {
        context.settingsStore.edit { p ->
            val cur = parseDelays(p[KEY_SUB_DELAYS]).toMutableMap()
            cur[provider] = delayMs
            p[KEY_SUB_DELAYS] = cur.entries.joinToString(",") { "${it.key.name}=${it.value}" }
        }
    }

    private fun parseDelays(raw: String?): Map<ProviderId, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(',').mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val id = ProviderId.entries.firstOrNull { it.name == entry.take(idx).trim() } ?: return@mapNotNull null
            val ms = entry.substring(idx + 1).trim().toIntOrNull() ?: return@mapNotNull null
            id to ms
        }.toMap()
    }

    private fun parseProviders(raw: String?): List<ProviderId> {
        if (raw.isNullOrBlank()) return AppSettings().providerOrder
        val parsed = raw.split(",").mapNotNull { s -> ProviderId.entries.firstOrNull { it.name == s.trim() } }
        if (parsed == LEGACY_DEFAULT_ORDER) return AppSettings().providerOrder
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

    /** All progress entries of one show (episodes keyed "s:e"). */
    suspend fun allForShow(context: Context, imdbId: String): Map<String, WatchProgress> =
        all(context)
            .filter { it.imdbId == imdbId && it.season != null && it.episode != null }
            .associateBy { "${it.season}:${it.episode}" }

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
