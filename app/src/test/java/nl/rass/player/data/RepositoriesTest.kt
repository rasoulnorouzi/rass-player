package nl.rass.player.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.rass.player.data.backup.BackupCodec
import nl.rass.player.data.backup.BackupException
import nl.rass.player.data.backup.BackupManager
import nl.rass.player.data.catalog.CacheMeta
import nl.rass.player.data.catalog.FeedCache
import nl.rass.player.data.feed.AssetReader
import nl.rass.player.data.feed.DocumentFetcher
import nl.rass.player.data.feed.FeedLoader
import nl.rass.player.data.local.LibraryRepository
import nl.rass.player.data.local.RecentSearchRepository
import nl.rass.player.data.local.WatchProgressRepository
import nl.rass.player.data.settings.AppSettings
import nl.rass.player.data.settings.InMemorySettingsRepository
import nl.rass.player.data.source.FeedDiscovery
import nl.rass.player.data.source.InMemorySourceStore
import nl.rass.player.data.source.PresetSources
import nl.rass.player.data.source.SourceAuth
import nl.rass.player.data.source.SourceRepository
import nl.rass.player.data.source.SourceValidationException
import nl.rass.player.data.source.TestConnectionResult
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogEpisode
import nl.rass.player.domain.CatalogKeys
import nl.rass.player.domain.CatalogSeason
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.TitleMetadata
import nl.rass.player.domain.TitleType
import okhttp3.OkHttpClient

class RepositoriesTest {
    private var clock = 1_000L
    private val now = { clock }
    private val cacheDir = Files.createTempDirectory("rc").toFile()
    private val cache = FeedCache(cacheDir)
    private val store = InMemorySourceStore()
    private val cipher = FakeCipher()
    private val fetcher = DocumentFetcher(OkHttpClient(), AssetReader { null })
    private val repo = SourceRepository(store, cipher, FeedDiscovery(fetcher), FeedLoader(fetcher, cache), cache, now)

    @Test
    fun `presets are seeded once and a disabled or removed preset stays that way`() = runBlocking<Unit> {
        repo.seedPresets()
        val presets = repo.current()
        assertEquals(PresetSources.ALL.map { it.url }, presets.map { it.url })
        assertTrue(presets.all { it.isPreset && it.enabled })
        repo.setEnabled(presets[0].id, false)
        repo.remove(presets[1].id)
        repo.seedPresets()
        val after = repo.current()
        assertEquals(3, after.size)
        assertFalse(after.first { it.id == presets[0].id }.enabled)
    }

    @Test
    fun `add, validate, reorder, edit url clears cache, secrets encrypted at rest`() = runBlocking<Unit> {
        val s1 = repo.add("Mine", "https://mysite.example/.well-known/rass-feed.json", SourceAuth(bearerToken = "tok"))
        val s2 = repo.add("", "http://nas.lan:8080/rass-feed.json")
        val s3 = repo.add("Third", "https://third.example/f.json", SourceAuth(headerName = "X-Feed-Key", headerValue = "v"))
        assertEquals("http://nas.lan:8080/rass-feed.json", s2.name)
        assertTrue(s2.isInsecure)
        assertFailsWith<SourceValidationException> { repo.add("dup", s1.url) }
        assertFailsWith<SourceValidationException> { repo.add("bad", "ftp://x.example/f.json") }
        assertFailsWith<SourceValidationException> { repo.add("asset", "asset:///test-feeds/x.json") }
        assertFailsWith<SourceValidationException> {
            repo.add("hdr", "https://h.example/f.json", SourceAuth(headerName = "Cookie", headerValue = "x"))
        }

        val record = store.data.first().sources.first { it.id == s1.id }
        assertEquals("enc:kot", record.tokenEnc)
        assertEquals("tok", repo.get(s1.id)!!.token)
        assertEquals("v", repo.get(s3.id)!!.customHeaderValue)
        assertFalse(s1.toString().contains("tok"))

        repo.move(s3.id, 0)
        assertEquals(listOf(s3.id, s1.id, s2.id), repo.current().map { it.id })
        repo.reorder(listOf(s2.id, s1.id))
        assertEquals(listOf(s2.id, s1.id, s3.id), repo.current().map { it.id })

        cache.write(s1.id, s1.url, "{}".toByteArray(), CacheMeta(s1.url, s1.url, fetchedAt = 1))
        repo.update(repo.get(s1.id)!!.copy(name = "Renamed"))
        assertTrue(cache.read(s1.id, s1.url) != null)
        repo.update(repo.get(s1.id)!!.copy(url = "https://mysite.example/rass-feed.json"))
        assertNull(cache.read(s1.id, s1.url))

        repo.setEnabled(s2.id, false)
        assertEquals(listOf(s1.id, s3.id), repo.enabledSources.first().map { it.id })
    }

    @Test
    fun `test connection on a bad address reports an error`() = runBlocking<Unit> {
        val r = repo.testConnection("not a url")
        assertTrue(r is TestConnectionResult.Failed)
    }

    private fun series(): CatalogTitle {
        val key = "imdb:tt0000002"
        fun ep(n: Int) = CatalogEpisode(CatalogKeys.episodeKey(key, 1, n), 1, n, null, null, null, null, null, null, null, null,
            emptyList(), emptyList(), emptyList())
        return CatalogTitle(key, TitleType.SERIES, TitleMetadata(titleEn = "S"), emptyList(),
            seasons = listOf(CatalogSeason(1, null, listOf(ep(1), ep(2)))))
    }

    @Test
    fun `progress, continue watching removal, next episode, new-episode badges`() = runBlocking<Unit> {
        val dao = FakeWatchProgressDao()
        val progress = WatchProgressRepository(dao, now)
        val s = series()
        progress.save(s.key, s.episodes[0].key, 950_000, 1_000_000)
        clock = 2_000
        progress.save("tmdb:movie:1", "", 100_000, 1_000_000)
        assertTrue(dao.get(s.key, s.episodes[0].key)!!.completed)
        assertEquals(listOf("tmdb:movie:1"), progress.continueWatching.first().map { it.catalogKey })
        assertEquals(s.episodes[1].key, progress.nextEpisode(s)!!.first.key)
        progress.removeFromContinueWatching("tmdb:movie:1")
        assertTrue(progress.continueWatching.first().isEmpty())
        clock = 3_000
        progress.save("tmdb:movie:1", "", 200_000, 1_000_000) // playing again brings it back
        assertEquals(1, progress.continueWatching.first().size)
        progress.markTitleWatched(s)
        assertTrue(dao.forTitle(s.key).all { it.completed } && dao.forTitle(s.key).size == 2)
        progress.markUnwatched(s.key, listOf(s.episodes[1].key))
        assertEquals(1, dao.forTitle(s.key).size)

        val myList = FakeMyListDao(); val seen = FakeSeenEpisodeDao(); val hist = FakePlayHistoryDao()
        val lib = LibraryRepository(myList, hist, seen, now)
        lib.addToMyList(s)
        val catalog = MutableStateFlow(Catalog(titles = listOf(s)))
        assertEquals(emptyMap<String, Int>(), lib.newEpisodeCounts(catalog).first())
        val withNew = s.copy(seasons = listOf(s.seasons[0].copy(episodes = s.seasons[0].episodes +
            s.episodes[0].copy(key = CatalogKeys.episodeKey(s.key, 1, 3), number = 3))))
        catalog.value = Catalog(titles = listOf(withNew))
        assertEquals(mapOf(s.key to 1), lib.newEpisodeCounts(catalog).first())
        lib.markEpisodesSeen(withNew)
        assertEquals(emptyMap<String, Int>(), lib.newEpisodeCounts(catalog).first())

        lib.recordPlay(s.key, TitleType.SERIES, s.episodes[0].key)
        lib.clearHistory()
        assertTrue(hist.all().isEmpty())
        assertEquals(1, myList.all().size) // history clear keeps My List

        val searches = RecentSearchRepository(FakeRecentSearchDao(), now = now)
        searches.add("كارآگاه"); clock++; searches.add("کارآگاه "); clock++; searches.add("  ")
        assertEquals(listOf("کارآگاه"), searches.recent.first())
    }

    @Test
    fun `backup round trip, secrets only when asked`() = runBlocking<Unit> {
        val s1 = repo.add("Mine", "https://mysite.example/rass-feed.json", SourceAuth(bearerToken = "tok", sendToMediaHosts = true))
        val settings = InMemorySettingsRepository(AppSettings(defaultQuality = "720p", autoplayNext = false, updateUrl = "https://u.example/update.json"))
        val dao = FakeWatchProgressDao(); val myList = FakeMyListDao(); val hist = FakePlayHistoryDao()
        val offsets = FakeSubtitleOffsetDao(); val searches = FakeRecentSearchDao(); val seen = FakeSeenEpisodeDao()
        WatchProgressRepository(dao, now).save("tmdb:movie:7", "", 5_000, 10_000)
        LibraryRepository(myList, hist, seen, now).apply { addToMyList(series()); recordPlay("tmdb:movie:7", TitleType.MOVIE) }
        val mgr = BackupManager(repo, settings, dao, myList, hist, offsets, searches, seen, "0.1.0", now)

        val withoutSecrets = mgr.export(includeSecrets = false)
        assertFalse(withoutSecrets.contains("\"tok\""))
        val withSecrets = mgr.export(includeSecrets = true)
        assertTrue(BackupCodec.decode(withSecrets).includesSecrets)
        assertEquals(BackupCodec.decode(withSecrets), BackupCodec.decode(BackupCodec.encode(BackupCodec.decode(withSecrets))))

        // restore into a fresh install
        val store2 = InMemorySourceStore()
        val repo2 = SourceRepository(store2, cipher, FeedDiscovery(fetcher), FeedLoader(fetcher, null), null, now)
        val settings2 = InMemorySettingsRepository()
        val dao2 = FakeWatchProgressDao(); val myList2 = FakeMyListDao(); val hist2 = FakePlayHistoryDao()
        val mgr2 = BackupManager(repo2, settings2, dao2, myList2, hist2, FakeSubtitleOffsetDao(), FakeRecentSearchDao(), FakeSeenEpisodeDao(), null, now)
        val summary = mgr2.import(withSecrets)
        assertEquals(1, summary.sources)
        val restored = repo2.current().single()
        assertEquals(s1.id, restored.id); assertEquals("tok", restored.token); assertTrue(restored.allowTokenToMediaHosts)
        assertEquals("720p", settings2.current().defaultQuality)
        assertEquals(dao.all(), dao2.all())
        assertEquals(myList.all(), myList2.all())
        assertEquals(hist.all(), hist2.all())

        // importing the secret-less file keeps the token already on the device
        mgr2.import(withoutSecrets)
        assertEquals("tok", repo2.current().single().token)

        assertFailsWith<BackupException> { mgr2.import("""{"format":"something-else","version":1}""") }
        assertFailsWith<BackupException> { mgr2.import("""{"format":"rass-player-backup","version":99,"createdAt":1}""") }
        assertFailsWith<BackupException> { mgr2.import("not json") }
    }
}
