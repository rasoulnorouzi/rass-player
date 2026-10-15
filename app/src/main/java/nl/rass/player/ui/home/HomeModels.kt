package nl.rass.player.ui.home

import nl.rass.player.data.catalog.CatalogState
import nl.rass.player.data.local.MyListEntity
import nl.rass.player.data.local.PlayHistoryEntity
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.Image
import nl.rass.player.domain.TitleType
import nl.rass.player.domain.WatchProgress
import nl.rass.player.ui.navigation.MediaType
import nl.rass.player.ui.navigation.Route

enum class CardStyle { Poster, Landscape }

enum class RowKind { ContinueWatching, RecentlyPlayed, RecentlyAdded, NewEpisodes, MyList, Top10, GenreMovies, GenreSeries }

/** What the card's context menu (Menu / long-press OK) offers besides "details". */
enum class CardMenu { ContinueWatching, History, MyList, Default }

data class HomeCard(
    /** Unique within its row (stable LazyRow key). */
    val id: String,
    val catalogKey: String,
    val type: TitleType,
    val title: String,
    val image: Image?,
    /** Shown behind the rows when this card is focused. */
    val backdrop: Image?,
    val onClick: Route,
    val progress: Float? = null,
    /** (season, episode) for an episode card. */
    val episode: Pair<Int, Int>? = null,
    val episodeKey: String = "",
    val newEpisodes: Int = 0,
    val rank: Int? = null,
    val menu: CardMenu = CardMenu.Default,
)

data class HomeRow(
    val key: String,
    val kind: RowKind,
    val style: CardStyle,
    val cards: List<HomeCard>,
    /** Genre name for genre rows. */
    val genreName: String? = null,
    /** Trailing "see all" tile. */
    val seeAll: Route? = null,
)

data class HeroItem(
    val catalogKey: String,
    val type: TitleType,
    val title: String,
    val backdrop: Image?,
    val logo: Image?,
    val rating: Double?,
    val year: Int?,
    val genres: List<String>,
    val description: String?,
    val seasonCount: Int,
)

sealed interface HomePhase {
    data object Loading : HomePhase
    /** [noSources] = no enabled source at all (point to Settings → Sources). */
    data class Empty(val noSources: Boolean) : HomePhase
    /** Every source failed and nothing is cached. */
    data object AllFailed : HomePhase
    data object Content : HomePhase
}

data class HomeUiState(
    val phase: HomePhase = HomePhase.Loading,
    val hero: List<HeroItem> = emptyList(),
    val rows: List<HomeRow> = emptyList(),
    /** Names of sources whose last refresh failed (partial-error banner). */
    val failedSources: List<String> = emptyList(),
    val myListKeys: Set<String> = emptySet(),
)

/** Builds the Home dashboard (R2.4) from the merged catalog and local data. Pure; runs off the main thread. */
object HomeBuilder {
    const val ROW_LIMIT = 20
    const val GENRE_ROWS_PER_TYPE = 8
    const val HERO_COUNT = 6

    fun build(
        state: CatalogState,
        enabledSourceCount: Int,
        continueWatching: List<WatchProgress>,
        history: List<PlayHistoryEntity>,
        myList: List<MyListEntity>,
        newEpisodes: Map<String, Int>,
    ): HomeUiState {
        val c = state.catalog
        val myKeys = myList.mapTo(HashSet()) { it.catalogKey }
        val failed = state.failedSources.map { it.name }
        if (c.titles.isEmpty()) {
            val phase = when {
                enabledSourceCount == 0 -> HomePhase.Empty(noSources = true)
                // Not built yet, or the first load is still running.
                state.sources.isEmpty() || state.isLoading -> HomePhase.Loading
                state.sources.all { it.error != null } -> HomePhase.AllFailed
                else -> HomePhase.Empty(noSources = false)
            }
            return HomeUiState(phase = phase, failedSources = failed, myListKeys = myKeys)
        }
        val rows = buildList {
            add(row("continue", RowKind.ContinueWatching, CardStyle.Landscape, continueCards(c, continueWatching)))
            add(row("played", RowKind.RecentlyPlayed, CardStyle.Poster, history.mapNotNull { h ->
                c.title(h.catalogKey)?.let { posterCard(it, menu = CardMenu.History) }
            }))
            add(row("added", RowKind.RecentlyAdded, CardStyle.Poster, c.recentlyAdded.map { posterCard(it) }))
            add(row("new-episodes", RowKind.NewEpisodes, CardStyle.Landscape, myList.mapNotNull { e ->
                val n = newEpisodes[e.catalogKey] ?: return@mapNotNull null
                c.title(e.catalogKey)?.let { landscapeCard(it).copy(newEpisodes = n, menu = CardMenu.MyList) }
            }))
            add(row("my-list", RowKind.MyList, CardStyle.Poster, myList.mapNotNull { e ->
                c.title(e.catalogKey)?.let { posterCard(it, menu = CardMenu.MyList).copy(newEpisodes = newEpisodes[it.key] ?: 0) }
            }))
            add(row("top10", RowKind.Top10, CardStyle.Landscape, c.top10.take(10).mapIndexed { i, t ->
                landscapeCard(t).copy(rank = i + 1)
            }))
            addAll(genreRows(c, TitleType.MOVIE))
            addAll(genreRows(c, TitleType.SERIES))
        }.filter { it.cards.isNotEmpty() }
        return HomeUiState(
            phase = HomePhase.Content,
            hero = hero(c),
            rows = rows,
            failedSources = failed,
            myListKeys = myKeys,
        )
    }

    fun hero(c: Catalog): List<HeroItem> {
        val featured = c.featured.ifEmpty {
            // Fallback: the best-rated of the recent titles, preferring ones with a backdrop.
            val recent = c.recentlyAdded.take(40)
            (recent.filter { it.meta.backdrop != null }.ifEmpty { recent })
                .sortedByDescending { it.meta.rating ?: 0.0 }
        }
        return featured.take(HERO_COUNT).map { t ->
            HeroItem(
                catalogKey = t.key, type = t.type, title = t.meta.displayTitle,
                backdrop = t.meta.backdrop ?: t.meta.poster, logo = t.meta.logo,
                rating = t.meta.rating, year = t.meta.year,
                genres = genreNames(c, t.meta.genreIds), description = t.meta.description,
                seasonCount = t.seasons.size,
            )
        }
    }

    private fun row(key: String, kind: RowKind, style: CardStyle, cards: List<HomeCard>) =
        HomeRow(key, kind, style, cards.distinctBy { it.id }.take(ROW_LIMIT))

    private fun continueCards(c: Catalog, rows: List<WatchProgress>): List<HomeCard> = rows.mapNotNull { p ->
        val t = c.title(p.catalogKey) ?: return@mapNotNull null
        if (t.type == TitleType.SERIES) {
            val ep = t.episode(p.episodeKey) ?: return@mapNotNull null
            HomeCard(
                id = t.key, catalogKey = t.key, type = t.type, title = t.meta.displayTitle,
                image = ep.still ?: t.meta.backdrop ?: t.meta.poster, backdrop = t.meta.backdrop,
                onClick = Route.Player(t.key, ep.seasonNumber, ep.number),
                progress = p.fraction, episode = ep.seasonNumber to ep.number, episodeKey = ep.key,
                menu = CardMenu.ContinueWatching,
            )
        } else {
            HomeCard(
                id = t.key, catalogKey = t.key, type = t.type, title = t.meta.displayTitle,
                image = t.meta.backdrop ?: t.meta.poster, backdrop = t.meta.backdrop,
                onClick = Route.Player(t.key), progress = p.fraction, menu = CardMenu.ContinueWatching,
            )
        }
    }

    private fun posterCard(t: CatalogTitle, menu: CardMenu = CardMenu.Default) = HomeCard(
        id = t.key, catalogKey = t.key, type = t.type, title = t.meta.displayTitle,
        image = t.meta.poster ?: t.meta.backdrop, backdrop = t.meta.backdrop, onClick = Route.Detail(t.key), menu = menu,
    )

    private fun landscapeCard(t: CatalogTitle) = HomeCard(
        id = t.key, catalogKey = t.key, type = t.type, title = t.meta.displayTitle,
        image = t.meta.backdrop ?: t.meta.poster, backdrop = t.meta.backdrop, onClick = Route.Detail(t.key),
    )

    private fun genreRows(c: Catalog, type: TitleType): List<HomeRow> {
        val ofType = c.titles.filter { it.type == type }
        if (ofType.isEmpty()) return emptyList()
        val byGenre = HashMap<String, MutableList<CatalogTitle>>()
        ofType.forEach { t -> t.meta.genreIds.forEach { g -> byGenre.getOrPut(g.lowercase()) { ArrayList() }.add(t) } }
        val mediaType = if (type == TitleType.MOVIE) MediaType.Movie else MediaType.Series
        val kind = if (type == TitleType.MOVIE) RowKind.GenreMovies else RowKind.GenreSeries
        return c.genres
            .mapNotNull { g -> byGenre[g.id.lowercase()]?.let { g to it } }
            .sortedByDescending { it.second.size }
            .take(GENRE_ROWS_PER_TYPE)
            .map { (g, titles) ->
                val sorted = titles.sortedWith(compareByDescending<CatalogTitle> { it.meta.rating ?: 0.0 }.thenByDescending { it.addedAt ?: 0L })
                HomeRow(
                    key = "genre-${type.wire}-${g.id.lowercase()}", kind = kind, style = CardStyle.Poster,
                    cards = sorted.take(ROW_LIMIT).map { posterCard(it) }, genreName = g.displayName,
                    seeAll = if (titles.size > ROW_LIMIT) Route.Category(mediaType, g.id) else null,
                )
            }
    }

}
