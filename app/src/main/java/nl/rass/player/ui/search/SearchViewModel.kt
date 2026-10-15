package nl.rass.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.data.local.RecentSearchRepository
import nl.rass.player.domain.TitleType
import nl.rass.player.search.PersianTextNormalizer
import nl.rass.player.data.source.LanEntryServer
import nl.rass.player.data.source.LanMode
import nl.rass.player.ui.search.keyboard.KbKey
import nl.rass.player.ui.search.keyboard.KeyboardPredictor
import nl.rass.player.ui.settings.LanUi
import nl.rass.player.ui.search.keyboard.KeyboardEdit
import nl.rass.player.ui.search.keyboard.KeyboardLanguage

enum class TypeFilter { All, Movie, Series }

data class SearchSource(val id: String, val name: String)

data class SearchItem(
    val key: String,
    val title: String,
    val posterUrl: String?,
    val type: TitleType,
    /** Names of the sources with streams for this title. */
    val sourceNames: List<String>,
)

data class SearchUiState(
    val query: String = "",
    val language: KeyboardLanguage = KeyboardLanguage.FA,
    val sourceFilter: String? = null,
    val typeFilter: TypeFilter = TypeFilter.All,
    val sources: List<SearchSource> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val results: List<SearchItem> = emptyList(),
    val recents: List<String> = emptyList(),
    val catalogLoading: Boolean = false,
    /** Words for the keyboard predictor (catalog titles + genres), most frequent first. */
    val vocab: List<String> = emptyList(),
) {
    val hasQuery: Boolean get() = PersianTextNormalizer.Default.compact(query).isNotEmpty()
}

private data class Input(val query: String, val source: String?, val type: TypeFilter, val tick: Int)
private data class Output(val suggestions: List<String>, val results: List<SearchItem>)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val recentRepo: RecentSearchRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val language = MutableStateFlow(KeyboardLanguage.FA)
    private val sourceFilter = MutableStateFlow<String?>(null)
    private val typeFilter = MutableStateFlow(TypeFilter.All)

    private val output: StateFlow<Output> = combine(
        query, sourceFilter, typeFilter, catalog.state,
    ) { q, s, t, st -> Input(q, s, t, st.catalog.titles.size) }
        .debounce(DEBOUNCE_MS)
        .mapLatest { run(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Output(emptyList(), emptyList()))

    private val vocab: StateFlow<List<String>> = catalog.state
        .map { it.catalog }
        .distinctUntilChangedBy { it.titles.size }
        .mapLatest { c ->
            KeyboardPredictor.vocabulary(
                c.titles.flatMap { listOfNotNull(it.meta.titleFa, it.meta.titleEn) } +
                    c.genres.flatMap { listOfNotNull(it.nameFa, it.nameEn) },
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<SearchUiState> = combine(
        query, language, sourceFilter, typeFilter,
    ) { q, l, s, t -> SearchUiState(query = q, language = l, sourceFilter = s, typeFilter = t) }
        .combine(output) { base, out -> base.copy(suggestions = out.suggestions, results = out.results) }
        .combine(catalog.state) { base, cs ->
            base.copy(
                sources = cs.sources.map { SearchSource(it.sourceId, it.name) },
                catalogLoading = cs.isInitialLoading,
            )
        }
        .combine(recentRepo.recent) { base, r -> base.copy(recents = r) }
        .combine(vocab) { base, v -> base.copy(vocab = v) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    private fun run(i: Input): Output {
        if (PersianTextNormalizer.Default.compact(i.query).isEmpty()) return Output(emptyList(), emptyList())
        val suggestions = catalog.suggest(i.query, i.source, SUGGESTION_LIMIT)
        val hits = catalog.search(i.query, i.source, RESULT_LIMIT)
        val items = hits.asSequence()
            .map { it.title }
            .filter {
                when (i.type) {
                    TypeFilter.All -> true
                    TypeFilter.Movie -> it.type == TitleType.MOVIE
                    TypeFilter.Series -> it.type == TitleType.SERIES
                }
            }
            .map { t ->
                SearchItem(
                    key = t.key,
                    title = t.meta.displayTitle,
                    posterUrl = t.meta.poster?.urlFor(POSTER_PX),
                    type = t.type,
                    sourceNames = t.sources.filter { it.hasStreams }.map { it.sourceName },
                )
            }
            .toList()
        return Output(suggestions, items)
    }

    fun onKey(key: KbKey) {
        when (key) {
            KbKey.Language -> language.update { it.toggled() }
            KbKey.Mic -> Unit
            else -> query.update { KeyboardEdit.apply(it, key) }
        }
    }

    /** Text from the system IME / remote keyboard. */
    fun setQuery(text: String) { query.value = text }

    /** Voice result or a suggestion/recent chosen by the user: fill the field and remember it. */
    fun submit(text: String) {
        query.value = text
        remember(text)
    }

    fun rememberCurrent() = remember(query.value)

    private fun remember(text: String) {
        viewModelScope.launch { recentRepo.add(text) }
    }

    // ---- R1.7: search text typed on the phone (one-time code, LAN only) ----
    private val _lan = MutableStateFlow<LanUi>(LanUi.Off)
    val lan: StateFlow<LanUi> = _lan.asStateFlow()
    private var server: LanEntryServer? = null

    fun startLan() {
        stopLan()
        val s = LanEntryServer(LanMode.TEXT) { _, text ->
            submit(text)
            _lan.value = LanUi.Off
            null
        }
        val url = runCatching { s.startAndGetUrl() }.getOrNull()
        if (url == null) { _lan.value = LanUi.NoNetwork; return }
        server = s
        _lan.value = LanUi.Waiting(url)
    }

    fun stopLan() {
        server?.let { s -> viewModelScope.launch(Dispatchers.IO) { s.stop() } }
        server = null
        _lan.value = LanUi.Off
    }

    override fun onCleared() { server?.stop(); server = null }

    fun clearRecents() { viewModelScope.launch { recentRepo.clear() } }
    fun setSource(id: String?) { sourceFilter.value = id }
    fun setType(t: TypeFilter) { typeFilter.value = t }

    companion object {
        const val DEBOUNCE_MS = 120L
        const val SUGGESTION_LIMIT = 8
        const val RESULT_LIMIT = 60
        private const val POSTER_PX = 360
    }
}
