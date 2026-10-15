package nl.rass.player.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import nl.rass.player.domain.filter.ContentFilterConfig
import kotlinx.serialization.json.Json

/** Updates are published as GitHub Releases on the user's public repo (R8.2). The user can change it. */
const val DEFAULT_UPDATE_URL = "https://github.com/rasoulnorouzi/rass-player/releases/latest/download/update.json"

enum class ResumeBehavior { ASK, ALWAYS_RESUME, ALWAYS_START_OVER }

enum class SubtitleLanguagePref { FA, EN, OFF }

/**
 * App preferences (R9.1, R6.1, R8.2, R9.2). Stored as one JSON string in a Preferences DataStore; unknown
 * keys are ignored and missing keys get defaults, so adding fields needs no migration.
 */
@Serializable
data class AppSettings(
    /** "auto" or e.g. "1080p" (feed-spec §7.2 `Q`). */
    val defaultQuality: String = "auto",
    val autoplayNext: Boolean = true,
    val resumeBehavior: ResumeBehavior = ResumeBehavior.ASK,
    // Subtitle defaults (R6.1/R6.2). The style itself is owned by player/subtitle (SubtitleStyle); it is kept
    // here as an opaque JSON string until that module defines its persisted form.
    val subtitleLanguage: SubtitleLanguagePref = SubtitleLanguagePref.FA,
    val subtitlesAutoOn: Boolean = true,
    val subtitleStyleJson: String? = null,
    /** R8.2: URL of the user's update.json; null = not configured. */
    val updateUrl: String? = DEFAULT_UPDATE_URL,
    val autoCheckUpdates: Boolean = false,
    /** R9.2 cache size caps. */
    val imageCacheMb: Int = 150,
    val httpCacheMb: Int = 100,
    /** Optional auto-clean cap for all caches together; 0 = off. */
    val totalCacheCapMb: Int = 0,
    /** R13 content filter. */
    val contentFilter: ContentFilterConfig = ContentFilterConfig(),
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings = settings.first()
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
    suspend fun replace(settings: AppSettings) { update { settings } }
}

class DataStoreSettingsRepository(store: DataStore<Preferences>) : SettingsRepository {
    private val key = stringPreferencesKey("app_settings_v1")

    // Served from memory after the first read (see DataStoreCell: DataStore's flow could miss early writes).
    private val cell = nl.rass.player.data.DataStoreCell(
        store,
        read = { decode(it[key]) },
        write = { p, v -> p[key] = SettingsJson.encodeToString(AppSettings.serializer(), v) },
    )

    override val settings: Flow<AppSettings> = cell.data.distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = cell.update(transform)

    private fun decode(s: String?): AppSettings =
        s?.let { runCatching { SettingsJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
}

class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        state.update(transform); return state.value
    }
}

internal val SettingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
