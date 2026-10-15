package nl.rass.player.player.subtitle.online

/** Credentials and switches for the online providers (R6.7). Values are user-provided; never log them. */
data class OnlineSubtitleConfig(
    val subDlEnabled: Boolean = false,
    val subDlApiKey: String = "",
    val openSubtitlesEnabled: Boolean = false,
    val openSubtitlesApiKey: String = "",
    val openSubtitlesUsername: String = "",
    val openSubtitlesPassword: String = "",
    /** Login token cached by the app; may be empty. */
    val openSubtitlesToken: String = "",
    val preferEnglish: Boolean = false,
) {
    // Keep secrets out of accidental toString() logging.
    override fun toString() = "OnlineSubtitleConfig(subDl=$subDlEnabled, openSubtitles=$openSubtitlesEnabled, preferEnglish=$preferEnglish)"
}

/** Implemented by the settings work; [InMemoryOnlineSubtitleSettings] is the stand-in. */
interface OnlineSubtitleSettings {
    suspend fun current(): OnlineSubtitleConfig
    suspend fun saveOpenSubtitlesToken(token: String?)
}

class InMemoryOnlineSubtitleSettings(
    @Volatile var config: OnlineSubtitleConfig = OnlineSubtitleConfig(),
) : OnlineSubtitleSettings {
    override suspend fun current(): OnlineSubtitleConfig = config
    override suspend fun saveOpenSubtitlesToken(token: String?) {
        config = config.copy(openSubtitlesToken = token.orEmpty())
    }
}
