package nl.rass.player.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.data.catalog.CatalogState
import nl.rass.player.data.local.LibraryRepository
import nl.rass.player.data.local.SeenEpisodeDao
import nl.rass.player.data.local.WatchProgressRepository
import nl.rass.player.domain.CatalogEpisode
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.MediaType
import nl.rass.player.domain.TitleType
import nl.rass.player.domain.Trailer
import nl.rass.player.domain.WatchProgress
import nl.rass.player.domain.WatchRules
import nl.rass.player.ui.home.genreNames

/** The episode the top Play button starts (R4.4) and where (null = from the start). */
data class NextUp(val episode: CatalogEpisode, val resumeMs: Long?)

data class DetailUiState(
    val loading: Boolean = true,
    val title: CatalogTitle? = null,
    val genres: List<String> = emptyList(),
    val inMyList: Boolean = false,
    /** By episode key; "" = the movie. */
    val progress: Map<String, WatchProgress> = emptyMap(),
    val movieResumeMs: Long? = null,
    val next: NextUp? = null,
    val watched: Boolean = false,
    /** Episodes listed since the user last opened this series (R11.3). */
    val newEpisodeKeys: Set<String> = emptySet(),
    /** Every source that has a stream for this title (badges). */
    val sourceNames: List<String> = emptyList(),
    val trailerPlayable: Boolean = false,
) {
    val notFound: Boolean get() = !loading && title == null
}

@HiltViewModel(assistedFactory = DetailViewModel.Factory::class)
class DetailViewModel @AssistedInject constructor(
    @Assisted private val catalogKey: String,
    private val catalog: CatalogRepository,
    private val progress: WatchProgressRepository,
    private val library: LibraryRepository,
    private val seenDao: SeenEpisodeDao,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(catalogKey: String): DetailViewModel
    }

    private val newKeys = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<DetailUiState> = combine(
        catalog.state,
        progress.observeTitle(catalogKey),
        library.isInMyList(catalogKey),
        newKeys,
    ) { cs, prog, inList, newK -> build(cs, prog, inList, newK) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        // Opening a series marks its listed episodes as seen; remember which were new for the badges first.
        viewModelScope.launch {
            val t = catalog.state.map { it.catalog.title(catalogKey) }.filterNotNull().first()
            if (t.type != TitleType.SERIES) return@launch
            val before = seenDao.forTitle(catalogKey).toSet()
            newKeys.value = if (before.isEmpty()) emptySet() else t.episodes.mapTo(HashSet()) { it.key }.apply { removeAll(before) }
            library.markEpisodesSeen(t)
        }
    }

    fun toggleMyList() {
        val s = state.value
        val t = s.title ?: return
        viewModelScope.launch { library.toggleMyList(t, s.inMyList) }
    }

    fun toggleWatched() {
        val s = state.value
        val t = s.title ?: return
        viewModelScope.launch { if (s.watched) progress.markTitleUnwatched(t) else progress.markTitleWatched(t) }
    }

    fun setEpisodeWatched(episode: CatalogEpisode, watched: Boolean) {
        viewModelScope.launch {
            if (watched) progress.markWatched(catalogKey, listOf(episode.key))
            else progress.markUnwatched(catalogKey, listOf(episode.key))
        }
    }

    private fun build(cs: CatalogState, prog: Map<String, WatchProgress>, inList: Boolean, newK: Set<String>): DetailUiState {
        val t = cs.catalog.title(catalogKey)
            ?: return DetailUiState(loading = cs.sources.isEmpty() || cs.isLoading)
        val isMovie = t.type == TitleType.MOVIE
        val movieP = prog[""]
        val watched = if (isMovie) movieP?.completed == true
        else t.episodes.isNotEmpty() && t.episodes.all { prog[it.key]?.completed == true }
        return DetailUiState(
            loading = false,
            title = t,
            genres = genreNames(cs.catalog, t.meta.genreIds, max = 4),
            inMyList = inList,
            progress = prog,
            movieResumeMs = if (isMovie) movieP?.takeIf { !it.completed && it.positionMs > 0 }?.positionMs else null,
            next = if (isMovie) null else WatchRules.nextEpisode(t, prog)?.let { (e, pos) -> NextUp(e, pos) },
            watched = watched,
            newEpisodeKeys = newK,
            sourceNames = t.sources.filter { it.hasStreams }.map { it.sourceName }.distinct(),
            trailerPlayable = isPlayableTrailer(t.meta.trailer),
        )
    }

    companion object {
        /** R11.7: only HLS/MP4 over http(s) play in the app (no DASH module, no web pages). */
        fun isPlayableTrailer(t: Trailer?): Boolean =
            t != null && (t.type == MediaType.HLS || t.type == MediaType.MP4) &&
                (t.url.startsWith("https://", ignoreCase = true) || t.url.startsWith("http://", ignoreCase = true))
    }
}
