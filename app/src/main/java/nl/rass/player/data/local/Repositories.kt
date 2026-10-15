package nl.rass.player.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogEpisode
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.TitleType
import nl.rass.player.domain.WatchProgress
import nl.rass.player.domain.WatchRules
import nl.rass.player.player.subtitle.SubtitleOffset
import nl.rass.player.player.subtitle.SubtitleOffsetKey
import nl.rass.player.player.subtitle.SubtitleOffsetStore
import nl.rass.player.search.PersianTextNormalizer

/** Watch progress (R4), Continue Watching (R2.4), mark watched/unwatched and remove from the row (R11.5). */
class WatchProgressRepository(
    private val dao: WatchProgressDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Unfinished items, newest first, one per title. */
    val continueWatching: Flow<List<WatchProgress>> =
        dao.observeRecent(CANDIDATES).map { rows -> WatchRules.continueWatching(rows.map { it.toDomain() }) }

    /** Progress of one title by episode key ("" for a movie). */
    fun observeTitle(catalogKey: String): Flow<Map<String, WatchProgress>> =
        dao.observeTitle(catalogKey).map { rows -> rows.associate { it.episodeKey to it.toDomain() } }

    suspend fun get(catalogKey: String, episodeKey: String = ""): WatchProgress? = dao.get(catalogKey, episodeKey)?.toDomain()

    /** R4.1: called about every 10 s and on pause/stop/exit. */
    suspend fun save(catalogKey: String, episodeKey: String, positionMs: Long, durationMs: Long, creditsStartSec: Double? = null) {
        val completed = WatchRules.isCompleted(positionMs, durationMs, creditsStartSec)
        dao.upsert(WatchProgressEntity(catalogKey, episodeKey, positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), now(), completed))
    }

    /** R4.4: episode the detail page's Play starts, and where. */
    suspend fun nextEpisode(title: CatalogTitle): Pair<CatalogEpisode, Long?>? =
        WatchRules.nextEpisode(title, dao.forTitle(title.key).associate { it.episodeKey to it.toDomain() })

    /** R11.5. For a movie pass `listOf("")`; for a series the episode keys (all, or one season). */
    suspend fun markWatched(catalogKey: String, episodeKeys: List<String>) {
        val t = now()
        dao.upsertAll(episodeKeys.map { ek ->
            val old = dao.get(catalogKey, ek)
            WatchProgressEntity(catalogKey, ek, old?.durationMs ?: 0, old?.durationMs ?: 0, t, completed = true)
        })
    }

    suspend fun markUnwatched(catalogKey: String, episodeKeys: List<String>) {
        episodeKeys.forEach { dao.delete(catalogKey, it) }
    }

    suspend fun markTitleWatched(title: CatalogTitle) =
        markWatched(title.key, if (title.type == TitleType.MOVIE) listOf("") else title.episodes.map { it.key })

    suspend fun markTitleUnwatched(title: CatalogTitle) = dao.deleteTitle(title.key)

    /** R11.5: hides the title from Continue Watching until it is played again. Progress is kept. */
    suspend fun removeFromContinueWatching(catalogKey: String) = dao.hideTitle(catalogKey)

    suspend fun clearAll() = dao.clear()

    private companion object { const val CANDIDATES = 300 }
}

/** My List (R3), recently played (R2.4), "new episode" badges (R11.3). */
class LibraryRepository(
    private val myList: MyListDao,
    private val history: PlayHistoryDao,
    private val seen: SeenEpisodeDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val myListEntries: Flow<List<MyListEntity>> = myList.observeAll()

    fun isInMyList(catalogKey: String): Flow<Boolean> = myList.observeContains(catalogKey)

    /** Adding a series also marks its current episodes as seen, so only later episodes get a badge. */
    suspend fun addToMyList(title: CatalogTitle) {
        myList.upsert(MyListEntity(title.key, title.type.wire, now()))
        markEpisodesSeen(title)
    }

    suspend fun removeFromMyList(catalogKey: String) = myList.delete(catalogKey)

    suspend fun toggleMyList(title: CatalogTitle, currentlyIn: Boolean) =
        if (currentlyIn) removeFromMyList(title.key) else addToMyList(title)

    suspend fun recordPlay(catalogKey: String, type: TitleType, episodeKey: String = "") =
        history.upsert(PlayHistoryEntity(catalogKey, episodeKey, type.wire, now()))

    fun recentlyPlayed(limit: Int = 30): Flow<List<PlayHistoryEntity>> = history.observeRecent(limit)

    suspend fun removeFromHistory(catalogKey: String) = history.delete(catalogKey)

    /** Clears play history only (R9.2). My List and progress are untouched. */
    suspend fun clearHistory() = history.clear()

    /** Call when the user opens a series' detail page: its listed episodes are no longer "new". */
    suspend fun markEpisodesSeen(title: CatalogTitle) {
        if (title.type != TitleType.SERIES) return
        val t = now()
        seen.insertAll(title.episodes.map { SeenEpisodeEntity(title.key, it.key, t) })
    }

    /** "New episode" counts for series in My List (R11.3), keyed by catalog key; zero counts omitted. */
    fun newEpisodeCounts(catalog: Flow<Catalog>): Flow<Map<String, Int>> =
        combine(catalog, myList.observeAll(), seen.observeAll()) { c, list, seenRows ->
            val seenByTitle = seenRows.groupBy({ it.catalogKey }, { it.episodeKey }).mapValues { it.value.toSet() }
            list.filter { it.type == TitleType.SERIES.wire }.mapNotNull { e ->
                val t = c.title(e.catalogKey) ?: return@mapNotNull null
                val n = WatchRules.newEpisodeCount(t, seenByTitle[e.catalogKey].orEmpty())
                if (n > 0) e.catalogKey to n else null
            }.toMap()
        }.distinctUntilChanged()
}

/** Recent searches (R7.3), deduplicated by normalized form. */
class RecentSearchRepository(
    private val dao: RecentSearchDao,
    private val normalizer: PersianTextNormalizer = PersianTextNormalizer.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val recent: Flow<List<String>> = dao.observeRecent(MAX).map { rows -> rows.map { it.query } }

    suspend fun add(query: String) {
        val q = query.trim()
        val key = normalizer.compact(q)
        if (key.isEmpty()) return
        dao.upsert(RecentSearchEntity(key, q, now()))
        dao.trim(MAX)
    }

    suspend fun remove(query: String) = dao.delete(normalizer.compact(query))
    suspend fun clear() = dao.clear()

    companion object { const val MAX = 10 }
}

/** Room-backed [SubtitleOffsetStore] (replaces the in-memory one of player/subtitle). */
class RoomSubtitleOffsetStore(
    private val dao: SubtitleOffsetDao,
    private val now: () -> Long = System::currentTimeMillis,
) : SubtitleOffsetStore {
    override suspend fun get(key: SubtitleOffsetKey): Long = dao.get(key.storageKey)?.offsetMs ?: 0L

    override suspend fun set(key: SubtitleOffsetKey, offsetMs: Long) {
        val v = SubtitleOffset.normalize(offsetMs)
        if (v == 0L) dao.delete(key.storageKey) else dao.upsert(SubtitleOffsetEntity(key.storageKey, v, now()))
    }

    override fun observe(key: SubtitleOffsetKey): Flow<Long> = dao.observe(key.storageKey).map { it ?: 0L }.distinctUntilChanged()
}
