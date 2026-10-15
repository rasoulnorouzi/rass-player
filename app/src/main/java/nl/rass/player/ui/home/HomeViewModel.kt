package nl.rass.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.data.local.LibraryRepository
import nl.rass.player.data.local.WatchProgressRepository
import nl.rass.player.data.source.SourceRepository
import nl.rass.player.domain.TitleType

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val progress: WatchProgressRepository,
    private val library: LibraryRepository,
    sources: SourceRepository,
) : ViewModel() {

    private val local = combine(
        progress.continueWatching,
        library.recentlyPlayed(HomeBuilder.ROW_LIMIT),
        library.myListEntries,
        library.newEpisodeCounts(catalog.catalog),
    ) { cw, history, myList, newEps -> Local(cw, history, myList, newEps) }

    val state: StateFlow<HomeUiState> = combine(
        catalog.state,
        sources.enabledSources.map { it.size },
        local,
    ) { cs, enabled, l ->
        HomeBuilder.build(cs, enabled, l.cw, l.history, l.myList, l.newEps)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Retry failed sources (banner) or everything (all failed). */
    fun retry() {
        viewModelScope.launch {
            val failed = catalog.state.value.failedSources.map { it.sourceId }
            if (failed.isEmpty()) catalog.refreshAll() else failed.forEach { catalog.refreshSource(it) }
        }
    }

    fun removeFromContinueWatching(card: HomeCard) {
        viewModelScope.launch { progress.removeFromContinueWatching(card.catalogKey) }
    }

    fun markWatched(card: HomeCard) {
        viewModelScope.launch {
            val ep = if (card.type == TitleType.SERIES) card.episodeKey else ""
            progress.markWatched(card.catalogKey, listOf(ep))
        }
    }

    fun removeFromHistory(card: HomeCard) {
        viewModelScope.launch { library.removeFromHistory(card.catalogKey) }
    }

    fun toggleMyList(card: HomeCard) {
        viewModelScope.launch {
            val inList = card.catalogKey in state.value.myListKeys
            if (inList) library.removeFromMyList(card.catalogKey)
            else catalog.catalog.first().title(card.catalogKey)?.let { library.addToMyList(it) }
        }
    }

    private data class Local(
        val cw: List<nl.rass.player.domain.WatchProgress>,
        val history: List<nl.rass.player.data.local.PlayHistoryEntity>,
        val myList: List<nl.rass.player.data.local.MyListEntity>,
        val newEps: Map<String, Int>,
    )
}
