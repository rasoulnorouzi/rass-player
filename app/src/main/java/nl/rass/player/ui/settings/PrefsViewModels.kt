package nl.rass.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.rass.player.data.settings.AppSettings
import nl.rass.player.data.settings.DataStoreOnlineSubtitleSettings
import nl.rass.player.data.settings.SettingsRepository
import nl.rass.player.data.settings.SubtitleStyleCodec
import nl.rass.player.player.subtitle.SubtitleStyle
import nl.rass.player.player.subtitle.online.OnlineSubtitleConfig

/** Playback (R9.1) and subtitle default (R6.1/R6.2) pages. */
@HiltViewModel
class PrefsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(change: (AppSettings) -> AppSettings) { viewModelScope.launch { repo.update(change) } }

    fun updateStyle(change: (SubtitleStyle) -> SubtitleStyle) = update { s ->
        s.copy(subtitleStyleJson = SubtitleStyleCodec.encode(change(SubtitleStyleCodec.decode(s.subtitleStyleJson))))
    }
}

@HiltViewModel
class OnlineSubtitlesViewModel @Inject constructor(private val store: DataStoreOnlineSubtitleSettings) : ViewModel() {
    val config: StateFlow<OnlineSubtitleConfig?> = store.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(change: (OnlineSubtitleConfig) -> OnlineSubtitleConfig) {
        // A changed login invalidates the cached OpenSubtitles token.
        viewModelScope.launch { store.update { old -> change(old).let { n -> if (n.loginChanged(old)) n.copy(openSubtitlesToken = "") else n } } }
    }

    private fun OnlineSubtitleConfig.loginChanged(o: OnlineSubtitleConfig) =
        openSubtitlesUsername != o.openSubtitlesUsername || openSubtitlesPassword != o.openSubtitlesPassword ||
            openSubtitlesApiKey != o.openSubtitlesApiKey
}
