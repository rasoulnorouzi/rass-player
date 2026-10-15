package nl.rass.player.ui.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.data.catalog.CatalogState
import nl.rass.player.data.local.LibraryRepository
import nl.rass.player.data.local.WatchProgressRepository
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.TitleType
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.navigation.MediaType

enum class BrowsePhase { Loading, Error, Ready }

data class GenreOption(val id: String, val name: String)

@Immutable
data class BrowseUiState(
    val phase: BrowsePhase = BrowsePhase.Loading,
    val genres: List<GenreOption> = emptyList(),
    /** "" = all genres. */
    val selectedGenre: String = "",
    /** Name of the genre of a category route, for the page title. */
    val genreTitle: String? = null,
    val sort: BrowseSort = BrowseSort.Newest,
    val sources: List<SourceOption> = emptyList(),
    val selectedSource: String? = null,
    val cards: List<TitleCardUi> = emptyList(),
    val showSourceBadge: Boolean = false,
)

/**
 * Movies, Series and Category screens (R2.2, R11.4). One instance per (type, genre) route: the screen asks for it
 * with a key, so each keeps its own chips, scroll and focus. Sorting and filtering run on Dispatchers.Default.
 */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val catalogRepo: CatalogRepository,
    progressRepo: WatchProgressRepository,
    libraryRepo: LibraryRepository,
    private val actions: TitleActions,
) : ViewModel() {

    private val type = MutableStateFlow<TitleType?>(null)
    private val filter = MutableStateFlow(BrowseFilter())
    private var latest = BrowseUiState()

    // Plain fields: written by the UI without triggering recomposition.
    var savedIndex = 0
    var savedOffset = 0
    var lastFocusedKey: String? = null

    fun init(mediaType: MediaType, genreId: String?) {
        if (type.value != null) return
        filter.value = BrowseFilter(genreId = genreId?.lowercase())
        type.value = if (mediaType == MediaType.Movie) TitleType.MOVIE else TitleType.SERIES
    }

    private class Inputs(
        val catalog: CatalogState,
        val progress: Map<String, Float>,
        val newEpisodes: Map<String, Int>,
        val inList: Set<String>,
    )

    private val inputs = combine(
        catalogRepo.state,
        progressRepo.continueWatching.map { rows -> rows.associate { it.catalogKey to it.fraction } },
        libraryRepo.newEpisodeCounts(catalogRepo.catalog),
        libraryRepo.myListEntries.map { rows -> rows.mapTo(HashSet()) { it.catalogKey } },
    ) { c, p, n, l -> Inputs(c, p, n, l) }

    private val collate = BrowseLogic.persianComparator()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<BrowseUiState> = combine(type.filterNotNull(), filter, inputs) { t, f, i -> Triple(t, f, i) }
        .mapLatest { (t, f, i) -> build(t, f, i) }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    private fun build(t: TitleType, f: BrowseFilter, i: Inputs): BrowseUiState {
        val catalog = i.catalog.catalog
        if (i.catalog.isInitialLoading) return BrowseUiState(BrowsePhase.Loading, sort = f.sort).also { latest = it }
        if (catalog.titles.isEmpty() && i.catalog.failedSources.isNotEmpty()) {
            return BrowseUiState(BrowsePhase.Error, sort = f.sort).also { latest = it }
        }
        val ofType = BrowseLogic.ofType(catalog.titles, t)
        val genres = BrowseLogic.genresWithTitles(ofType, catalog.genres)
        val genreId = f.genreId?.takeIf { g -> genres.any { it.id.equals(g, true) } }
        val sources = BrowseLogic.sourceOptions(ofType)
        val sourceId = f.sourceId?.takeIf { s -> sources.any { it.id == s } }
        val showSource = sourceId == null && sources.size > 1
        val sorted = BrowseLogic.apply(ofType, BrowseFilter(genreId, f.sort, sourceId), collate)
        val cards = sorted.map { toCard(it, i, showSource) }
        return BrowseUiState(
            phase = BrowsePhase.Ready,
            genres = genres.map { GenreOption(it.id.lowercase(), it.displayName) },
            selectedGenre = genreId?.lowercase().orEmpty(),
            genreTitle = genres.firstOrNull { it.id.equals(f.genreId, true) }?.displayName,
            sort = f.sort,
            sources = if (sources.size > 1) sources else emptyList(),
            selectedSource = sourceId,
            cards = cards,
            showSourceBadge = showSource,
        ).also { latest = it }
    }

    private fun toCard(t: CatalogTitle, i: Inputs, showSource: Boolean) = TitleCardUi(
        key = t.key,
        title = t.meta.displayTitle,
        year = t.meta.year?.fa(),
        poster = t.meta.poster,
        progress = i.progress[t.key],
        newEpisode = (i.newEpisodes[t.key] ?: 0) > 0,
        sourceName = if (showSource) t.primary.sourceName else null,
        inMyList = t.key in i.inList,
        type = t.type,
    )

    fun selectGenre(id: String) { filter.value = filter.value.copy(genreId = id.ifEmpty { null }) }
    fun selectSort(sort: BrowseSort) { filter.value = filter.value.copy(sort = sort) }

    /** Cycles: all → each source → all. */
    fun cycleSource() {
        val ids = latest.sources.map { it.id }
        if (ids.isEmpty()) return
        val cur = latest.selectedSource
        val next = if (cur == null) ids.first() else ids.getOrNull(ids.indexOf(cur) + 1)
        filter.value = filter.value.copy(sourceId = next)
    }

    fun retry() { viewModelScope.launch { catalogRepo.refreshAll() } }
    fun toggleMyList(key: String) { viewModelScope.launch { actions.toggleMyList(key) } }
    fun markWatched(key: String) { viewModelScope.launch { actions.markWatched(key) } }
    fun markUnwatched(key: String) { viewModelScope.launch { actions.markUnwatched(key) } }
}
