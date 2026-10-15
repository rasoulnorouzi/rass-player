package nl.rass.player.ui.navigation

/**
 * Navigation contract shared by all screens.
 *
 * Every top-level screen is a composable with the shape
 * `fun XxxScreen(onNavigate: (Route) -> Unit, onBack: () -> Unit)` and gets its ViewModel via `hiltViewModel()`.
 * The app shell (ui/AppShell.kt) owns the back stack and maps each [Route] to its screen.
 *
 * `catalogKey` is the stable cross-feed key from the data layer (tmdb:/imdb:/norm:title|year).
 */
sealed interface Route {
    data object Home : Route
    data object Movies : Route
    data object Series : Route
    data object Search : Route
    data object MyList : Route

    /** Movies or series filtered to one genre; [genreId] null = all. */
    data class Category(val type: MediaType, val genreId: String?) : Route

    data class Detail(val catalogKey: String) : Route

    /** For series, null season/episode means "continue at the next unwatched episode". */
    data class Player(
        val catalogKey: String,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val startFromBeginning: Boolean = false,
    ) : Route

    data class Settings(val section: SettingsSection = SettingsSection.Root) : Route
}

enum class MediaType { Movie, Series }

enum class SettingsSection {
    Root, Sources, Playback, Subtitles, OnlineSubtitles, PrivateConnection, Updates, Storage, Backup, About,
}
