package nl.rass.player.data.catalog

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.rass.player.data.FakeCipher
import nl.rass.player.data.feed.AssetReader
import nl.rass.player.data.feed.DocumentFetcher
import nl.rass.player.data.feed.FeedErrorKind
import nl.rass.player.data.feed.FeedLoader
import nl.rass.player.data.feed.feedJson
import nl.rass.player.data.feed.movieJson
import nl.rass.player.data.source.FeedDiscovery
import nl.rass.player.data.source.InMemorySourceStore
import nl.rass.player.data.source.PresetSources
import nl.rass.player.data.source.SourceRepository
import okhttp3.OkHttpClient

class CatalogRepositoryTest {

    @Test
    fun `presets load from assets, a missing asset fails without crashing, search spans sources`() = runBlocking<Unit> {
        val assets = mapOf(
            "test-feeds/blender.json" to feedJson("Blender", listOf(
                movieJson("bbb", "Big Buck Bunny", "https://cdn.example/bbb.mp4", ""","year":2008,"ids":{"tmdb":10378}"""),
                movieJson("sintel", "Sintel", "https://cdn.example/sintel.mp4"),
            )),
            "test-feeds/hls-test.json" to feedJson("HLS", listOf(
                movieJson("bbb-hls", "Big Buck Bunny (HLS)", "https://cdn.example/bbb.m3u8", ""","ids":{"tmdb":10378}"""),
            )),
        )
        val fetcher = DocumentFetcher(OkHttpClient()) { p -> assets[p]?.byteInputStream() }
        val cache = FeedCache(Files.createTempDirectory("crt").toFile())
        val loader = FeedLoader(fetcher, cache)
        val sources = SourceRepository(InMemorySourceStore(), FakeCipher(), FeedDiscovery(fetcher), loader, cache)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = CatalogRepository(sources, loader, null, scope)
            val state = withTimeout(10_000) {
                repo.state.first { s -> s.sources.size == 4 && !s.isLoading && s.catalog.titles.isNotEmpty() }
            }
            assertEquals(2, state.catalog.titles.size)
            val bbb = state.catalog.title("tmdb:movie:10378")!!
            val blenderId = PresetSources.ALL[0].id
            val hlsId = PresetSources.ALL[3].id
            assertEquals(listOf(blenderId, hlsId), bbb.sources.map { it.sourceId })
            assertEquals(2, state.failedSources.size)
            assertTrue(state.failedSources.all { it.error?.kind == FeedErrorKind.ASSET_MISSING })
            assertTrue(state.hasPartialErrors)

            assertEquals(listOf("tmdb:movie:10378"), repo.search("big buck").map { it.title.key })
            assertEquals(listOf("tmdb:movie:10378"), repo.search("bunny", sourceId = hlsId).map { it.title.key })
            assertTrue(repo.search("sintel", sourceId = hlsId).isEmpty())
            assertTrue(repo.suggest("sin").contains("Sintel"))

            // disabling a source removes it from the merge
            sources.setEnabled(blenderId, false)
            val after = withTimeout(10_000) { repo.state.first { it.sources.size == 3 } }
            assertEquals(listOf(hlsId), after.catalog.titles.single().sources.map { it.sourceId })
        } finally {
            scope.cancel()
        }
    }
}
