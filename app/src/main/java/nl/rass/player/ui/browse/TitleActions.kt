package nl.rass.player.ui.browse

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.first
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.data.local.LibraryRepository
import nl.rass.player.data.local.WatchProgressRepository
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.Image
import nl.rass.player.domain.TitleType
import javax.inject.Inject

/** What one grid card shows. Built off the main thread by the view models. */
@Immutable
data class TitleCardUi(
    val key: String,
    val title: String,
    /** Persian digits, or null. */
    val year: String?,
    val poster: Image?,
    val progress: Float?,
    val newEpisode: Boolean,
    /** Source badge text (R7.5); null when not shown. */
    val sourceName: String?,
    val inMyList: Boolean,
    val type: TitleType,
)

/** Context-menu actions shared by Browse and My List (R3.1, R11.5). */
class TitleActions @Inject constructor(
    private val catalog: CatalogRepository,
    private val progress: WatchProgressRepository,
    private val library: LibraryRepository,
) {
    private fun title(key: String): CatalogTitle? = catalog.state.value.catalog.title(key)

    suspend fun toggleMyList(key: String) {
        val inList = library.myListEntries.first().any { it.catalogKey == key }
        if (inList) library.removeFromMyList(key) else title(key)?.let { library.addToMyList(it) }
    }

    suspend fun markWatched(key: String) { title(key)?.let { progress.markTitleWatched(it) } }
    suspend fun markUnwatched(key: String) { title(key)?.let { progress.markTitleUnwatched(it) } }
}
