package nl.rass.player.data.catalog

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.rass.player.data.feed.FeedError
import nl.rass.player.data.feed.FeedLoader
import nl.rass.player.data.feed.FeedReport
import nl.rass.player.data.feed.SourceSpec
import nl.rass.player.data.local.FirstSeenDao
import nl.rass.player.data.source.Source
import nl.rass.player.data.source.SourceRepository
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.SourceCatalog
import nl.rass.player.search.MatchType
import nl.rass.player.search.SearchDocument
import nl.rass.player.search.SearchIndex

/** Per-source status for Settings → Sources and partial-error banners. */
data class SourceStatus(
    val sourceId: String,
    val name: String,
    val isLoading: Boolean,
    /** Last refresh error; the previous good catalog (if any) is still shown. */
    val error: FeedError?,
    val report: FeedReport?,
    val lastSuccessAt: Long?,
    val hasCatalog: Boolean,
)

data class CatalogState(
    val catalog: Catalog = Catalog.Empty,
    /** Enabled sources, Settings order. */
    val sources: List<SourceStatus> = emptyList(),
) {
    val isLoading: Boolean get() = sources.any { it.isLoading }
    /** First load, nothing to show yet. */
    val isInitialLoading: Boolean get() = catalog.titles.isEmpty() && isLoading
    val failedSources: List<SourceStatus> get() = sources.filter { it.error != null }
    val hasPartialErrors: Boolean get() = failedSources.isNotEmpty() && catalog.titles.isNotEmpty()
}

data class CatalogSearchHit(val title: CatalogTitle, val matchType: MatchType, val matchedTitle: String)

/**
 * The merged catalog of all enabled sources (R1.4, R7.5, feed-spec §12, §15).
 *
 * - Renders from the on-disk cache first, then refreshes stale sources concurrently in the background.
 * - A failed refresh keeps the source's last good catalog and reports the error per source.
 * - Rebuilds the merged [Catalog] and the [SearchIndex] whenever a source's catalog or the source list
 *   (enable/disable/reorder) changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRepository(
    private val sourceRepository: SourceRepository,
    private val loader: FeedLoader,
    private val firstSeenDao: FirstSeenDao?,
    private val scope: CoroutineScope,
    private val merger: CatalogMerger = CatalogMerger(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
    autoStart: Boolean = true,
) {
    private data class Snapshot(
        val url: String,
        val authHash: Int,
        val catalog: SourceCatalog? = null,
        val report: FeedReport? = null,
        val error: FeedError? = null,
        val loading: Boolean = false,
        val fetchedAt: Long? = null,
        val maxAgeSec: Long? = null,
        val lastSuccessAt: Long? = null,
    )

    private class Built(val state: CatalogState, val index: SearchIndex)

    private val snapshots = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    private val built = MutableStateFlow(Built(CatalogState(), SearchIndex(emptyList())))
    private val jobs = HashMap<String, Job>()
    private var started = false

    val state: StateFlow<CatalogState> = built.map { it.state }.stateIn(scope, SharingStarted.Eagerly, CatalogState())

    val catalog: kotlinx.coroutines.flow.Flow<Catalog> = state.map { it.catalog }.distinctUntilChanged()

    init {
        if (autoStart) start()
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            sourceRepository.seedPresets()
            sourceRepository.enabledSources.distinctUntilChanged().collect { sync(it) }
        }
        scope.launch {
            combine(sourceRepository.enabledSources, snapshots) { s, snap -> s to snap }
                .mapLatest { (sources, snap) -> build(sources, snap) }
                .collect { built.value = it }
        }
        scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                refreshStale()
            }
        }
    }

    /** "Refresh now" (ignores intervals). Suspends until every enabled source finished. */
    suspend fun refreshAll() {
        val list = sourceRepository.enabledSources.first()
        list.map { refresh(it) }.joinAll()
    }

    suspend fun refreshSource(id: String) {
        sourceRepository.enabledSources.first().firstOrNull { it.id == id }?.let { refresh(it).join() }
    }

    /** Refreshes sources whose cached copy is older than their interval (or `max-age`, capped at 24 h). */
    suspend fun refreshStale() {
        val snap = snapshots.value
        sourceRepository.enabledSources.first().filter { isStale(it, snap[it.id]) }.forEach { refresh(it) }
    }

    fun search(query: String, sourceId: String? = null, limit: Int = 50): List<CatalogSearchHit> {
        val b = built.value
        return b.index.search(query, sourceId, limit).mapNotNull { r ->
            b.state.catalog.title(r.documentId)?.let { CatalogSearchHit(it, r.matchType, r.matchedTitle) }
        }
    }

    fun suggest(prefix: String, sourceId: String? = null, limit: Int = 8): List<String> =
        built.value.index.suggest(prefix, limit, sourceId)

    // ------------------------------------------------------------------

    private suspend fun sync(enabled: List<Source>) {
        val ids = enabled.map { it.id }.toSet()
        synchronized(jobs) { jobs.keys.filter { it !in ids }.forEach { jobs.remove(it)?.cancel() } }
        val current = snapshots.value
        val next = LinkedHashMap<String, Snapshot>()
        val toLoad = ArrayList<Source>()
        for (s in enabled) {
            val old = current[s.id]
            if (old != null && old.url == s.url && old.authHash == s.auth.hashCode()) next[s.id] = old
            else { next[s.id] = Snapshot(s.url, s.auth.hashCode(), loading = true); toLoad.add(s) }
        }
        snapshots.value = next
        for (s in toLoad) {
            val cached = withContext(io) { loader.loadCached(spec(s)) }
            update(s.id) {
                it.copy(
                    catalog = cached.feed?.catalog, report = cached.feed?.report,
                    fetchedAt = cached.fetchedAt, maxAgeSec = cached.maxAgeSec,
                    lastSuccessAt = cached.fetchedAt, loading = false,
                )
            }
            if (isStale(s, snapshots.value[s.id])) refresh(s)
        }
    }

    private fun refresh(s: Source): Job = synchronized(jobs) {
        jobs[s.id]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            update(s.id) { it.copy(loading = true) }
            val r = withContext(io) { loader.refresh(spec(s)) }
            update(s.id) { old ->
                val keep = r.error == null && r.notModified && old.catalog != null
                old.copy(
                    catalog = if (keep) old.catalog else r.feed?.catalog ?: old.catalog,
                    report = if (keep) old.report else r.feed?.report ?: old.report,
                    error = r.error,
                    loading = false,
                    fetchedAt = if (r.error == null) r.fetchedAt else old.fetchedAt,
                    maxAgeSec = if (r.error == null) r.maxAgeSec else old.maxAgeSec,
                    lastSuccessAt = if (r.error == null) r.fetchedAt else old.lastSuccessAt,
                )
            }
        }
        jobs[s.id] = job
        job
    }

    private fun update(id: String, f: (Snapshot) -> Snapshot) =
        snapshots.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    private fun isStale(s: Source, snap: Snapshot?): Boolean {
        if (snap == null || snap.catalog == null) return true
        if (s.isAsset) return false
        val fetched = snap.fetchedAt ?: return true
        if (s.refreshIntervalMin <= 0) return false
        val maxAge = (snap.maxAgeSec ?: 0).coerceAtMost(MAX_AGE_CAP_SEC)
        val wait = maxOf(s.refreshIntervalMin * 60L, maxAge) * 1000
        return now() - fetched >= wait
    }

    private fun spec(s: Source) = SourceSpec(s.id, s.name, s.url, s.auth)

    private suspend fun build(sources: List<Source>, snap: Map<String, Snapshot>): Built = withContext(compute) {
        val inputs = sources.mapNotNull { s -> snap[s.id]?.catalog?.let { MergeInput(s.id, s.name, it) } }
        val raw = merger.mergeRaw(inputs)
        val needFirstSeen = raw.titles.filter { it.addedAt == null }.map { it.key }
        val firstSeen = if (needFirstSeen.isEmpty() || firstSeenDao == null) emptyMap()
        else runCatching { withContext(io) { firstSeenDao.recordAndGet(needFirstSeen, now()) } }.getOrDefault(emptyMap())
        val catalog = merger.finish(raw, firstSeen)
        val statuses = sources.map { s ->
            val sn = snap[s.id]
            SourceStatus(s.id, s.name, sn?.loading ?: true, sn?.error, sn?.report, sn?.lastSuccessAt, sn?.catalog != null)
        }
        Built(CatalogState(catalog, statuses), buildIndex(catalog))
    }

    private fun buildIndex(c: Catalog): SearchIndex {
        val topRank = c.top10.withIndex().associate { (i, t) -> t.key to (10 - i) }
        return SearchIndex(c.titles.map { t ->
            SearchDocument(
                id = t.key,
                sourceIds = t.playableSourceIds,
                titles = t.searchTitles.ifEmpty { listOfNotNull(t.meta.titleFa, t.meta.titleEn, t.meta.originalTitle) },
                year = t.meta.year,
                popularity = (topRank[t.key] ?: 0) + t.playableSourceIds.size * 0.1,
            )
        })
    }

    companion object {
        const val CHECK_INTERVAL_MS = 15 * 60_000L
        const val MAX_AGE_CAP_SEC = 24 * 3600L
    }
}
