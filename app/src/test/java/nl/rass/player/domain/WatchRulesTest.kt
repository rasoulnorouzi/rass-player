package nl.rass.player.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchRulesTest {
    private val key = "tmdb:series:1"

    private fun ep(season: Int, n: Int) = CatalogEpisode(
        key = CatalogKeys.episodeKey(key, season, n), seasonNumber = season, number = n,
        titleFa = null, titleEn = null, descriptionFa = null, descriptionEn = null, still = null,
        runtimeMin = null, airDate = null, addedAt = null, streams = emptyList(), subtitles = emptyList(), sources = emptyList(),
    )

    private val series = CatalogTitle(
        key = key, type = TitleType.SERIES, meta = TitleMetadata(titleEn = "S"), sources = emptyList(),
        seasons = listOf(
            CatalogSeason(0, null, listOf(ep(0, 1))),
            CatalogSeason(2, null, listOf(ep(2, 1))),
            CatalogSeason(1, null, listOf(ep(1, 2), ep(1, 1))),
        ),
    )

    private fun p(s: Int, e: Int, pos: Long, at: Long, done: Boolean = false) =
        CatalogKeys.episodeKey(key, s, e) to WatchProgress(key, CatalogKeys.episodeKey(key, s, e), pos, 1_000_000, at, done)

    @Test
    fun `play order puts specials last`() {
        assertEquals(listOf("s1e1", "s1e2", "s2e1", "s0e1"), WatchRules.playOrder(series).map { it.key.substringAfter('#') })
    }

    @Test
    fun `next unwatched episode`() {
        // nothing watched → first regular episode
        assertEquals("$key#s1e1" to null, WatchRules.nextEpisode(series, emptyMap())!!.let { it.first.key to it.second })
        // last watched unfinished → resume it
        val r1 = WatchRules.nextEpisode(series, mapOf(p(1, 1, 300_000, 10, done = true), p(1, 2, 120_000, 20)))!!
        assertEquals("$key#s1e2", r1.first.key); assertEquals(120_000L, r1.second)
        // last watched finished → the next one (crosses seasons)
        val r2 = WatchRules.nextEpisode(series, mapOf(p(1, 1, 1, 10, true), p(1, 2, 1, 20, true)))!!
        assertEquals("$key#s2e1", r2.first.key); assertNull(r2.second)
        // finished the last one but skipped an earlier one → that one
        val r3 = WatchRules.nextEpisode(series, mapOf(p(1, 1, 1, 10, true), p(2, 1, 1, 30, true), p(0, 1, 1, 40, true)))!!
        assertEquals("$key#s1e2", r3.first.key)
        // all finished → start over at the first
        val all = WatchRules.playOrder(series).associate { e -> e.key to WatchProgress(key, e.key, 1, 1, 1, true) }
        assertEquals("$key#s1e1", WatchRules.nextEpisode(series, all)!!.first.key)
    }

    @Test
    fun `completion at 90 percent or credits`() {
        assertTrue(WatchRules.isCompleted(900, 1000))
        assertFalse(WatchRules.isCompleted(899, 1000))
        assertTrue(WatchRules.isCompleted(600_000, 1_000_000, creditsStartSec = 600.0))
        assertFalse(WatchRules.isCompleted(0, 0))
    }

    @Test
    fun `continue watching - unfinished, not hidden, newest first, one per title`() {
        val rows = listOf(
            WatchProgress("a", "a#s1e1", 50_000, 100_000, 10, false),
            WatchProgress("a", "a#s1e2", 20_000, 100_000, 30, false),
            WatchProgress("b", "", 95_000, 100_000, 40, true),
            WatchProgress("c", "", 10_000, 100_000, 20, false, hiddenFromContinue = true),
            WatchProgress("d", "", 30_000, 100_000, 5, false),
        )
        assertEquals(listOf("a" to "a#s1e2", "d" to ""), WatchRules.continueWatching(rows).map { it.catalogKey to it.episodeKey })
    }

    @Test
    fun `catalog keys`() {
        assertEquals("tmdb:movie:5", CatalogKeys.titleKey(TitleType.MOVIE, 5, "tt1", "s", "x"))
        assertEquals("imdb:tt0000001", CatalogKeys.titleKey(TitleType.SERIES, null, "TT0000001", "s", "x"))
        assertEquals("src:s:x", CatalogKeys.titleKey(TitleType.SERIES, null, null, "s", "x"))
        assertEquals(2 to 13, CatalogKeys.episodeNumbers("imdb:tt1#s2e13"))
        assertEquals("imdb:tt1", CatalogKeys.titleKeyOf("imdb:tt1#s2e13"))
    }
}
