package nl.rass.player.data.catalog

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.rass.player.data.FakeCipher
import nl.rass.player.data.feed.DocumentFetcher
import nl.rass.player.data.feed.FeedLoader
import nl.rass.player.data.feed.feedJson
import nl.rass.player.data.feed.movieJson
import nl.rass.player.data.feed.assemble
import nl.rass.player.data.source.FeedDiscovery
import nl.rass.player.data.source.InMemorySourceStore
import nl.rass.player.data.source.SourceRepository
import nl.rass.player.domain.filter.ContentFilterConfig
import nl.rass.player.ui.home.HomeBuilder
import okhttp3.OkHttpClient

class ContentFilterCatalogTest {

    @Test
    fun `parser reads rating and warnings, ignores unknown values`() {
        val doc = assemble(feedJson("F", listOf(
            movieJson("a", "A", "https://x.example/a.mp4", ""","content_rating":"tv-ma","content_warnings":["sexual","bogus","gore","sexual"]"""),
            movieJson("b", "B", "https://x.example/b.mp4", ""","content_rating":"XX","content_warnings":"no" """),
            movieJson("c", "C", "https://x.example/c.mp4"),
        )))
        val m = doc.catalog.titles.associateBy { it.id }
        assertEquals("TV-MA", m.getValue("a").meta.contentRating)
        assertEquals(listOf("sexual", "gore"), m.getValue("a").meta.contentWarnings)
        assertEquals(null, m.getValue("b").meta.contentRating)
        assertTrue(m.getValue("b").meta.contentWarnings.isEmpty())
        assertTrue(m.getValue("c").meta.contentWarnings.isEmpty())
        assertTrue(doc.report.warnings >= 3)
    }

    @Test
    fun `sensitive titles stay off Home, hide-everywhere removes them from search and suggestions, instantly`() = runBlocking<Unit> {
        val assets = mapOf(
            "test-feeds/blender.json" to feedJson("Blender", listOf(
                movieJson("ok", "Sintel Adventure", "https://cdn.example/1.mp4", ""","content_rating":"PG","added_at":"2026-10-01""""),
                movieJson("bad", "Sintel After Dark", "https://cdn.example/2.mp4", ""","content_warnings":["nudity"],"added_at":"2026-10-02""""),
                movieJson("kw", "Sintel Erotic Nights", "https://cdn.example/3.mp4", ""","added_at":"2026-10-03""""),
                movieJson("sussex", "Sintel in Sussex", "https://cdn.example/4.mp4", ""","added_at":"2026-10-04""""),
            )),
        )
        val fetcher = DocumentFetcher(OkHttpClient()) { p -> assets[p]?.byteInputStream() }
        val cache = FeedCache(Files.createTempDirectory("cf").toFile())
        val loader = FeedLoader(fetcher, cache)
        val sources = SourceRepository(InMemorySourceStore(), FakeCipher(), FeedDiscovery(fetcher), loader, cache)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = MutableStateFlow(ContentFilterConfig())
        try {
            val repo = CatalogRepository(sources, loader, null, scope, contentFilter = config)
            val state = withTimeout(10_000) { repo.state.first { !it.isLoading && it.catalog.titles.size == 4 } }
            val sensitive = state.catalog.titles.filter { it.sensitive }.map { it.meta.titleEn }.toSet()
            assertEquals(setOf("Sintel After Dark", "Sintel Erotic Nights"), sensitive)

            // Home promo rows skip them ...
            val home = HomeBuilder.build(state, 1, emptyList(), emptyList(), emptyList(), emptyMap())
            val homeTitles = home.rows.flatMap { it.cards }.map { it.title }.toSet()
            assertFalse(homeTitles.any { it.contains("After Dark") || it.contains("Erotic") })
            assertTrue(homeTitles.contains("Sintel Adventure"))
            // ... but search and suggestions still find them.
            assertEquals(4, repo.search("sintel").size)
            assertTrue(repo.suggest("sintel").any { it.contains("Erotic") })

            // "show on Home" lets them back onto Home.
            config.value = ContentFilterConfig(showOnHome = true)
            val shown = withTimeout(10_000) { repo.state.first { it.catalog.homeShowsSensitive } }
            val home2 = HomeBuilder.build(shown, 1, emptyList(), emptyList(), emptyList(), emptyMap())
            assertTrue(home2.rows.flatMap { it.cards }.any { it.title.contains("Erotic") })

            // "hide everywhere" removes them from search + suggestions + catalog, at once, and remembers the keys.
            config.value = ContentFilterConfig(hideEverywhere = true)
            val hidden = withTimeout(10_000) { repo.state.first { it.catalog.hiddenKeys.isNotEmpty() } }
            assertEquals(2, hidden.catalog.titles.size)
            assertEquals(2, repo.search("sintel").size)
            assertTrue(repo.suggest("sintel").none { it.contains("Erotic") || it.contains("Dark") })
            assertTrue(repo.suggest("sintel").any { it.contains("Sussex") })
            val badKey = state.catalog.titles.first { it.meta.titleEn == "Sintel After Dark" }.key
            assertTrue(hidden.catalog.isHidden(badKey)); assertEquals(null, hidden.catalog.title(badKey))

            // Filter off: everything is back, nothing hidden.
            config.value = ContentFilterConfig(enabled = false)
            val off = withTimeout(10_000) { repo.state.first { it.catalog.titles.size == 4 && it.catalog.hiddenKeys.isEmpty() } }
            assertTrue(off.catalog.titles.none { it.sensitive })
        } finally {
            scope.cancel()
        }
    }
}
