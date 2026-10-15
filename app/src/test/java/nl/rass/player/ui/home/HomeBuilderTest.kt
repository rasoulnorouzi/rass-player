package nl.rass.player.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.rass.player.data.catalog.CatalogState
import nl.rass.player.data.catalog.SourceStatus
import nl.rass.player.data.feed.FeedError
import nl.rass.player.data.feed.FeedErrorKind
import nl.rass.player.data.local.MyListEntity
import nl.rass.player.data.local.PlayHistoryEntity
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogEpisode
import nl.rass.player.domain.CatalogKeys
import nl.rass.player.domain.CatalogSeason
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.Genre
import nl.rass.player.domain.TitleMetadata
import nl.rass.player.domain.TitleType
import nl.rass.player.domain.WatchProgress
import nl.rass.player.ui.decodeRoute
import nl.rass.player.ui.encode
import nl.rass.player.ui.navigation.MediaType
import nl.rass.player.ui.navigation.Route
import nl.rass.player.ui.navigation.SettingsSection

class HomeBuilderTest {
    private fun movie(n: Int, genres: List<String> = emptyList(), rating: Double? = null, added: Long = n.toLong()) = CatalogTitle(
        key = "tmdb:movie:$n", type = TitleType.MOVIE,
        meta = TitleMetadata(titleFa = "فیلم $n", genreIds = genres, rating = rating), sources = emptyList(), addedAt = added,
    )

    private val seriesKey = "tmdb:series:9"
    private fun ep(s: Int, e: Int) = CatalogEpisode(
        key = CatalogKeys.episodeKey(seriesKey, s, e), seasonNumber = s, number = e,
        titleFa = null, titleEn = null, descriptionFa = null, descriptionEn = null, still = null,
        runtimeMin = null, airDate = null, addedAt = null, streams = emptyList(), subtitles = emptyList(), sources = emptyList(),
    )
    private val series = CatalogTitle(
        key = seriesKey, type = TitleType.SERIES, meta = TitleMetadata(titleFa = "سریال", genreIds = listOf("drama")),
        sources = emptyList(), seasons = listOf(CatalogSeason(1, null, listOf(ep(1, 1), ep(1, 2)))),
    )

    private val m1 = movie(1, listOf("drama"), rating = 7.0)
    private val m2 = movie(2, listOf("drama", "comedy"), rating = 8.0)
    private val catalog = Catalog(
        titles = listOf(m1, m2, series),
        genres = listOf(Genre("drama", "درام", null), Genre("comedy", "کمدی", null)),
        top10 = listOf(m2, m1),
        recentlyAdded = listOf(m2, m1),
    )

    private fun status(id: String, error: Boolean = false, loading: Boolean = false) = SourceStatus(
        id, "منبع $id", loading, if (error) FeedError(FeedErrorKind.HTTP, "x") else null, null, null, !error,
    )

    @Test
    fun `rows in R2_4 order, empty rows hidden`() {
        val s = HomeBuilder.build(
            CatalogState(catalog, listOf(status("a"))), 1,
            continueWatching = listOf(WatchProgress(seriesKey, ep(1, 2).key, 10_000, 100_000, 5, false)),
            history = emptyList(),
            myList = listOf(MyListEntity(seriesKey, "series", 1)),
            newEpisodes = mapOf(seriesKey to 2),
        )
        assertEquals(HomePhase.Content, s.phase)
        assertEquals(
            listOf(RowKind.ContinueWatching, RowKind.RecentlyAdded, RowKind.NewEpisodes, RowKind.MyList, RowKind.Top10,
                RowKind.GenreMovies, RowKind.GenreMovies, RowKind.GenreSeries),
            s.rows.map { it.kind },
        )
        val cw = s.rows.first().cards.single()
        assertEquals(Route.Player(seriesKey, 1, 2, resume = true), cw.onClick)
        assertEquals(1 to 2, cw.episode)
        assertEquals(listOf(1, 2), s.rows.first { it.kind == RowKind.Top10 }.cards.map { it.rank })
        assertEquals(2, s.rows.first { it.kind == RowKind.NewEpisodes }.cards.single().newEpisodes)
        // Drama has 2 movies → first movie genre row, best rated first.
        val drama = s.rows.first { it.kind == RowKind.GenreMovies }
        assertEquals("درام", drama.genreName)
        assertEquals(listOf(m2.key, m1.key), drama.cards.map { it.catalogKey })
        assertTrue(s.failedSources.isEmpty())
    }

    @Test
    fun `hero falls back to best rated recent titles`() {
        val hero = HomeBuilder.hero(catalog)
        assertEquals(m2.key, hero.first().catalogKey)
    }

    @Test
    fun `phases - loading, empty, all failed, partial errors`() {
        val empty = Catalog()
        assertEquals(HomePhase.Empty(noSources = true), HomeBuilder.build(CatalogState(), 0, emptyList(), emptyList(), emptyList(), emptyMap()).phase)
        assertEquals(HomePhase.Loading, HomeBuilder.build(CatalogState(), 2, emptyList(), emptyList(), emptyList(), emptyMap()).phase)
        assertEquals(HomePhase.Loading, HomeBuilder.build(CatalogState(empty, listOf(status("a", loading = true))), 1, emptyList(), emptyList(), emptyList(), emptyMap()).phase)
        assertEquals(HomePhase.AllFailed, HomeBuilder.build(CatalogState(empty, listOf(status("a", error = true))), 1, emptyList(), emptyList(), emptyList(), emptyMap()).phase)
        assertEquals(HomePhase.Empty(noSources = false), HomeBuilder.build(CatalogState(empty, listOf(status("a"))), 1, emptyList(), emptyList(), emptyList(), emptyMap()).phase)
        val partial = HomeBuilder.build(CatalogState(catalog, listOf(status("a"), status("b", error = true))), 2, emptyList(), emptyList(), emptyList(), emptyMap())
        assertEquals(HomePhase.Content, partial.phase)
        assertEquals(listOf("منبع b"), partial.failedSources)
    }

    @Test
    fun `history row skips titles that left the catalog`() {
        val s = HomeBuilder.build(
            CatalogState(catalog, listOf(status("a"))), 1, emptyList(),
            history = listOf(PlayHistoryEntity("gone", "", "movie", 2), PlayHistoryEntity(m1.key, "", "movie", 1)),
            myList = emptyList(), newEpisodes = emptyMap(),
        )
        assertEquals(listOf(m1.key), s.rows.first { it.kind == RowKind.RecentlyPlayed }.cards.map { it.catalogKey })
        assertFalse(s.rows.any { it.kind == RowKind.MyList })
    }

    @Test
    fun `rating and route codec`() {
        assertEquals("۷٫۹", formatRatingFa(7.86))
        val routes = listOf(
            Route.Home, Route.Movies, Route.Series, Route.Search, Route.MyList,
            Route.Category(MediaType.Series, "drama"), Route.Category(MediaType.Movie, null),
            Route.Detail("src:abc:x|y"), Route.Player("tmdb:series:1", 2, 3, true), Route.Player("tmdb:movie:1", resume = true),
            Route.Settings(SettingsSection.Sources), Route.Trailer("imdb:tt1"),
        )
        routes.forEach { assertEquals(it, decodeRoute(it.encode())) }
    }
}
