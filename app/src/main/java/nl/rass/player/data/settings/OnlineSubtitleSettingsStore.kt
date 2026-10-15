package nl.rass.player.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.rass.player.data.source.SecretCipher
import nl.rass.player.di.SettingsDataStore
import nl.rass.player.player.subtitle.online.OnlineSubtitleConfig
import nl.rass.player.player.subtitle.online.OnlineSubtitleSettings

@Serializable
private data class OnlineConfigDto(
    val subDlEnabled: Boolean = false,
    val subDlApiKey: String = "",
    val openSubtitlesEnabled: Boolean = false,
    val openSubtitlesApiKey: String = "",
    val openSubtitlesUsername: String = "",
    val openSubtitlesPassword: String = "",
    val openSubtitlesToken: String = "",
    val preferEnglish: Boolean = false,
)

/**
 * Real [OnlineSubtitleSettings] (R6.7): the whole config (API keys, OpenSubtitles login) is one JSON blob,
 * encrypted with the Keystore-backed [SecretCipher] before it reaches DataStore. Never logged, never in backups.
 */
@Singleton
class DataStoreOnlineSubtitleSettings @Inject constructor(
    @SettingsDataStore private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : OnlineSubtitleSettings {
    private val key = stringPreferencesKey("online_subtitles_enc_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val config: Flow<OnlineSubtitleConfig> = store.data.map { decode(it[key]) }.distinctUntilChanged()

    override suspend fun current(): OnlineSubtitleConfig = config.first()

    override suspend fun saveOpenSubtitlesToken(token: String?) {
        update { it.copy(openSubtitlesToken = token.orEmpty()) }
    }

    suspend fun update(transform: (OnlineSubtitleConfig) -> OnlineSubtitleConfig) {
        store.edit { p ->
            val next = transform(decode(p[key]))
            p[key] = cipher.encrypt(json.encodeToString(OnlineConfigDto.serializer(), next.toDto()))
        }
    }

    private fun decode(enc: String?): OnlineSubtitleConfig {
        val plain = enc?.let(cipher::decrypt) ?: return OnlineSubtitleConfig()
        val d = runCatching { json.decodeFromString(OnlineConfigDto.serializer(), plain) }.getOrNull() ?: return OnlineSubtitleConfig()
        return OnlineSubtitleConfig(
            d.subDlEnabled, d.subDlApiKey, d.openSubtitlesEnabled, d.openSubtitlesApiKey,
            d.openSubtitlesUsername, d.openSubtitlesPassword, d.openSubtitlesToken, d.preferEnglish,
        )
    }

    private fun OnlineSubtitleConfig.toDto() = OnlineConfigDto(
        subDlEnabled, subDlApiKey, openSubtitlesEnabled, openSubtitlesApiKey,
        openSubtitlesUsername, openSubtitlesPassword, openSubtitlesToken, preferEnglish,
    )
}
