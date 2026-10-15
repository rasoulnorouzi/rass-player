package nl.rass.player.ui.mylist

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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.data.local.LibraryRepository
import nl.rass.player.data.local.MyListEntity
import nl.rass.player.data.local.WatchProgressRepository
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.TitleType
import nl.rass.player.ui.browse.TitleActions
import nl.rass.player.ui.browse.TitleCardUi
import nl.rass.player.ui.format.fa

@Immutable
data class MyListUiState(
    val tab: TitleType = TitleType.MOVIE,
    val cards: List<TitleCardUi> = emptyList(),
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
)

/** My List (R3): Movies and Series tabs, newest added first, "new episode" badges (R11.3). */
@HiltViewModel
class MyListViewModel @Inject constructor(
    catalogRepo: CatalogRepository,
    progressRepo: WatchProgressRepository,
    libraryRepo: LibraryRepository,
    private val actions: TitleActions,
) : ViewModel() {

    private val tab = MutableStateFlow(TitleType.MOVIE)

    var savedIndex = 0
    var savedOffset = 0
    var lastFocusedKey: String? = null

    private class Inputs(
        val catalog: Catalog,
        val entries: List<MyListEntity>,
        val progress: Map<String, Float>,
        val newEpisodes: Map<String, Int>,
    )

    private val inputs = combine(
        catalogRepo.catalog,
        libraryRepo.myListEntries,
        progressRepo.continueWatching.map { rows -> rows.associate { it.catalogKey to it.fraction } },
        libraryRepo.newEpisodeCounts(catalogRepo.catalog),
    ) { c, e, p, n -> Inputs(c, e, p, n) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<MyListUiState> = combine(tab, inputs) { t, i -> t to i }
        .mapLatest { (t, i) ->
            val movies = MyListLogic.titles(i.entries, i.catalog, TitleType.MOVIE)
            val series = MyListLogic.titles(i.entries, i.catalog, TitleType.SERIES)
            MyListUiState(
                tab = t,
                cards = (if (t == TitleType.MOVIE) movies else series).map { toCard(it, i) },
                movieCount = movies.size,
                seriesCount = series.size,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyListUiState())

    private fun toCard(t: CatalogTitle, i: Inputs) = TitleCardUi(
        key = t.key,
        title = t.meta.displayTitle,
        year = t.meta.year?.fa(),
        poster = t.meta.poster,
        progress = i.progress[t.key],
        newEpisode = (i.newEpisodes[t.key] ?: 0) > 0,
        sourceName = null,
        inMyList = true,
        type = t.type,
    )

    fun selectTab(t: TitleType) {
        if (tab.value != t) { lastFocusedKey = null; savedIndex = 0; savedOffset = 0 }
        tab.value = t
    }

    fun toggleMyList(key: String) { viewModelScope.launch { actions.toggleMyList(key) } }
    fun markWatched(key: String) { viewModelScope.launch { actions.markWatched(key) } }
    fun markUnwatched(key: String) { viewModelScope.launch { actions.markUnwatched(key) } }
}
