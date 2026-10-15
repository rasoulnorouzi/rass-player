package nl.rass.player.player.subtitle.online

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer

private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    return out.toByteArray()
}

private val SRT = "1\n00:00:01,000 --> 00:00:02,000\nسلام\n".toByteArray()

class SubtitleZipTest {
    @Test fun rejectsTraversal() {
        val e = assertFailsWith<OnlineSubtitleException> { SubtitleZip.extract(zip("../evil.srt" to SRT)) }
        assertEquals(OnlineSubtitleException.Kind.UNSAFE_ZIP, e.kind)
        assertFailsWith<OnlineSubtitleException> { SubtitleZip.extract(zip("/abs.srt" to SRT)) }
        assertFailsWith<OnlineSubtitleException> { SubtitleZip.extract(zip("a\\..\\b.srt" to SRT)) }
    }

    @Test fun capsBomb() {
        val big = ByteArray(11 * 1024 * 1024) { 'a'.code.toByte() }
        val e = assertFailsWith<OnlineSubtitleException> { SubtitleZip.extract(zip("a.srt" to big)) }
        assertEquals(OnlineSubtitleException.Kind.TOO_LARGE, e.kind)
        val e2 = assertFailsWith<OnlineSubtitleException> { SubtitleZip.extract(zip("x.bin" to big)) }
        assertEquals(OnlineSubtitleException.Kind.TOO_LARGE, e2.kind)
    }

    @Test fun capsEntryCount() {
        val files = (1..51).map { "f$it.txt" to ByteArray(1) }.toTypedArray()
        assertFailsWith<OnlineSubtitleException> { SubtitleZip.extract(zip(*files)) }
    }

    @Test fun picksEpisodeAndIgnoresOthers() {
        val z = zip(
            "Show.S01E01.srt" to "one".toByteArray(),
            "Show.S01E02.srt" to "two!".toByteArray(),
            "Show.S01E02.nfo" to "x".toByteArray(),
            "__MACOSX/._Show.S01E02.srt" to "junk".toByteArray(),
        )
        assertEquals("two!", String(SubtitleZip.extract(z, 1, 2).bytes))
        // Multi-episode pack without the wanted episode: refuse.
        assertFailsWith<OnlineSubtitleException> { SubtitleZip.extract(z, 1, 7) }
    }

    @Test fun singleFileAndNonZipPassThrough() {
        assertEquals("Movie.vtt", SubtitleZip.extract(zip("Movie.vtt" to "WEBVTT\n".toByteArray())).fileName)
        assertContentEquals(SRT, SubtitleZip.extract(SRT).bytes)
        assertFailsWith<OnlineSubtitleException> { SubtitleZip.extract(zip("readme.txt" to SRT)) }
    }
}

class RankingAndPickerTest {
    private fun r(id: String, lang: String, m: MatchedBy = MatchedBy.ID, rel: String = "", dl: Int = 0, hi: Boolean = false) =
        OnlineSubtitleResult("P", id, lang, rel, dl, null, hi, null, m)

    private val q = SubtitleQuery(title = "X", releaseHint = "Movie.2020.1080p.BluRay.x264-GRP")

    @Test fun ranksLangThenMatchThenSimilarityThenDownloads() {
        val list = listOf(
            r("en1", "en", dl = 9999),
            r("fa-title", "fa", MatchedBy.TITLE, dl = 9999),
            r("fa-id-pop", "fa", dl = 500),
            r("fa-id-sim", "fa", rel = "Movie 2020 1080p BluRay x264-GRP", dl = 1),
            r("fa-id-sim", "fa", rel = "dup", dl = 1),
        )
        assertEquals(listOf("fa-id-sim", "fa-id-pop", "fa-title", "en1"), OnlineSubtitleRanking.rank(list, q).map { it.id })
        assertEquals("en1", OnlineSubtitleRanking.rank(list, q, preferEnglish = true).first().id)
    }

    private val on = AutoPickPrefs(true, "fa")

    @Test fun autoPick() {
        val pooled = listOf(PooledSubtitle("en", "A", "u1"), PooledSubtitle("fa", "B", "u2"))
        val online = listOf(r("1", "fa"))
        assertTrue(SubtitleAutoPicker.pick(AutoPickPrefs(false, "fa"), emptySet(), pooled, online) is AutoPick.Keep)
        assertTrue(SubtitleAutoPicker.pick(AutoPickPrefs(true, "off"), emptySet(), pooled, online) is AutoPick.Keep)
        assertTrue(SubtitleAutoPicker.pick(on, setOf("fa"), pooled, online) is AutoPick.Keep)
        assertEquals("u2", (SubtitleAutoPicker.pick(on, setOf("en"), pooled, online) as AutoPick.Pooled).subtitle.url)
        val enOnly = listOf(PooledSubtitle("en", "A", "u1"))
        assertEquals(AutoPick.NeedsOnlineSearch, SubtitleAutoPicker.pick(on, emptySet(), enOnly, null))
        assertEquals("1", (SubtitleAutoPicker.pick(on, emptySet(), enOnly, online) as AutoPick.Online).result.id)
        assertTrue(SubtitleAutoPicker.pick(on, emptySet(), enOnly, emptyList()) is AutoPick.Pooled) // falls back to en
        assertEquals(AutoPick.NothingFound, SubtitleAutoPicker.pick(on, emptySet(), emptyList(), emptyList()))
        assertNotNull(SubtitleAutoPicker.pick(on, emptySet(), pooled, null).noticeFa)
    }
}

class ProvidersTest {
    private fun server() = MockWebServer().also { it.start() }
    private val client = OkHttpClient()

    private val subDlJson = """
        {"status":true,"results":[{"sd_id":1,"type":"movie","name":"Inception","imdb_id":"tt1375666","tmdb_id":27205,"year":2010}],
         "subtitles":[
          {"release_name":"Inception.2010.BluRay","name":"SUBDL::x","lang":"farsi_persian","language":"FA","author":"a","url":"/subtitle/1-2.zip","subtitlePage":"/s/1","season":0,"episode":0,"hi":false,"full_season":false},
          {"release_name":"Inception.2010.WEB","name":"y","lang":"english","language":"EN","url":"/subtitle/3-4.zip","season":0,"episode":0,"hi":true},
          {"release_name":"Inception.FR","lang":"french","language":"FR","url":"/subtitle/5-6.zip"}
         ]}
    """.trimIndent()

    @Test fun subDlParseAndDownload() = runTest {
        val s = server()
        val settings = InMemoryOnlineSubtitleSettings(OnlineSubtitleConfig(subDlEnabled = true, subDlApiKey = "KEY123"))
        val p = SubDlProvider(client, settings, s.url("/api/v1").toString().trimEnd('/'), s.url("/").toString().trimEnd('/'))
        s.enqueue(MockResponse().setBody(subDlJson))
        val q = SubtitleQuery(imdbId = "1375666", title = "Inception", year = 2010)
        val res = p.search(q)
        assertEquals(listOf("fa", "en"), res.map { it.lang })
        assertEquals(MatchedBy.ID, res[0].matchedBy)
        assertTrue(res[1].hearingImpaired)
        val req = s.takeRequest()
        assertEquals("/api/v1/subtitles", req.requestUrl!!.encodedPath)
        assertEquals("tt1375666", req.requestUrl!!.queryParameter("imdb_id"))
        assertEquals("FA,EN", req.requestUrl!!.queryParameter("languages"))
        assertEquals("movie", req.requestUrl!!.queryParameter("type"))

        s.enqueue(MockResponse().setBody(Buffer().write(zip("a.srt" to SRT))))
        assertContentEquals(SRT, p.download(res[0]))
        assertEquals("/subtitle/1-2.zip", s.takeRequest().path)
        assertNull(s.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS)) // no extra requests
        s.shutdown()
    }

    @Test fun subDlSeriesParamsAndTitleFallback() {
        val p = SubDlProvider(client, InMemoryOnlineSubtitleSettings())
        val u = p.buildSearchUrl(SubtitleQuery(title = "Show", isSeries = true, season = 2, episode = 3), "k")
        assertEquals("tv", u.queryParameter("type"))
        assertEquals("Show", u.queryParameter("film_name"))
        assertEquals("2", u.queryParameter("season_number"))
        assertEquals("3", u.queryParameter("episode_number"))
        assertNull(u.queryParameter("imdb_id"))
    }

    @Test fun subDlBadKey() = runTest {
        val s = server()
        val p = SubDlProvider(client, InMemoryOnlineSubtitleSettings(OnlineSubtitleConfig(subDlEnabled = true, subDlApiKey = "k")), s.url("/api/v1").toString().trimEnd('/'))
        s.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":false,"error":"invalid api key"}"""))
        val e = assertFailsWith<OnlineSubtitleException> { p.search(SubtitleQuery(title = "x")) }
        assertEquals(OnlineSubtitleException.Kind.AUTH, e.kind)
        s.shutdown()
    }

    private val osSearch = """
        {"total_pages":1,"total_count":3,"per_page":50,"page":1,"data":[
         {"id":"10","type":"subtitle","attributes":{"subtitle_id":"10","language":"fa","download_count":1200,"hearing_impaired":false,"fps":23.976,"ratings":8.0,"release":"Inception.2010.BluRay","files":[{"file_id":555,"cd_number":1,"file_name":"inception.srt"}]}},
         {"id":"11","type":"subtitle","attributes":{"subtitle_id":"11","language":"pt-BR","download_count":5,"release":"x","files":[{"file_id":556}]}},
         {"id":"12","type":"subtitle","attributes":{"subtitle_id":"12","language":"en","download_count":10,"hearing_impaired":true,"fps":0,"release":"","files":[{"file_id":557,"file_name":"en.srt"}]}}
        ]}
    """.trimIndent()

    private fun os(s: MockWebServer, cfg: OnlineSubtitleConfig) =
        OpenSubtitlesProvider(client, InMemoryOnlineSubtitleSettings(cfg), s.url("/api/v1").toString().trimEnd('/'))

    private val osCfg = OnlineSubtitleConfig(
        openSubtitlesEnabled = true, openSubtitlesApiKey = "OSKEY", openSubtitlesUsername = "u", openSubtitlesPassword = "p",
    )

    @Test fun openSubtitlesSearch() = runTest {
        val s = server()
        val p = os(s, osCfg)
        s.enqueue(MockResponse().setBody(osSearch))
        val res = p.search(SubtitleQuery(imdbId = "tt0123", title = "Inception", isSeries = true, season = 1, episode = 2))
        assertEquals(listOf("555", "557"), res.map { it.id })
        assertEquals(23.976, res[0].fps)
        assertNull(res[1].fps)
        assertEquals("en.srt", res[1].releaseName)
        assertEquals(1200, res[0].downloads)
        val req = s.takeRequest()
        assertEquals("OSKEY", req.getHeader("Api-Key"))
        assertEquals("Rass_player v0.1.0", req.getHeader("User-Agent"))
        val u = req.requestUrl!!
        assertEquals("123", u.queryParameter("parent_imdb_id"))
        assertEquals("episode", u.queryParameter("type"))
        assertEquals("fa,en", u.queryParameter("languages"))
        assertEquals("2", u.queryParameter("episode_number"))
        assertEquals(u.queryParameterNames.toList(), u.queryParameterNames.sorted())
        s.shutdown()
    }

    @Test fun openSubtitlesTitleSearchUsesQuery() {
        val p = OpenSubtitlesProvider(client, InMemoryOnlineSubtitleSettings())
        val u = p.buildSearchUrl(SubtitleQuery(title = "Inception", year = 2010))
        assertEquals("inception", u.queryParameter("query"))
        assertEquals("2010", u.queryParameter("year"))
    }

    @Test fun openSubtitlesLoginAndDownload() = runTest {
        val s = server()
        val settings = InMemoryOnlineSubtitleSettings(osCfg)
        val p = OpenSubtitlesProvider(client, settings, s.url("/api/v1").toString().trimEnd('/'))
        s.enqueue(MockResponse().setBody("""{"user":{"allowed_downloads":20},"base_url":"api.opensubtitles.com","token":"TOK","status":200}"""))
        s.enqueue(MockResponse().setBody("""{"link":"${s.url("/files/a.srt")}","file_name":"a.srt","requests":1,"remaining":19,"message":"ok"}"""))
        s.enqueue(MockResponse().setBody(Buffer().write(SRT)))
        val bytes = p.download(OnlineSubtitleResult("OpenSubtitles", "555", "fa", "", matchedBy = MatchedBy.ID))
        assertContentEquals(SRT, bytes)
        val login = s.takeRequest()
        assertEquals("/api/v1/login", login.path)
        val dl = s.takeRequest()
        assertEquals("/api/v1/download", dl.path)
        assertEquals("Bearer TOK", dl.getHeader("Authorization"))
        assertEquals("OSKEY", dl.getHeader("Api-Key"))
        assertTrue(dl.body.readUtf8().contains("\"file_id\":555"))
        val file = s.takeRequest()
        assertEquals("/files/a.srt", file.path)
        assertNull(file.getHeader("Api-Key"))
        assertNull(file.getHeader("Authorization"))
        assertEquals("TOK", settings.current().openSubtitlesToken)
        s.shutdown()
    }

    @Test fun openSubtitlesQuotaError() = runTest {
        val s = server()
        val p = os(s, osCfg.copy(openSubtitlesToken = "TOK"))
        s.enqueue(MockResponse().setResponseCode(406).setBody("""{"requests":21,"remaining":0,"message":"You have downloaded your allowed 20 subtitles for 24h","reset_time":"11 hours","reset_time_utc":"2026-10-16T00:00:00.000Z"}"""))
        val e = assertFailsWith<OnlineSubtitleException> { p.download(OnlineSubtitleResult("OpenSubtitles", "555", "fa", "")) }
        assertEquals(OnlineSubtitleException.Kind.QUOTA, e.kind)
        assertTrue(e.messageFa.contains("سهمیه"))
        assertTrue(e.messageFa.contains("11 hours"))
        assertTrue(s.requestCount == 1) // token reused, no login
        s.shutdown()
    }

    @Test fun openSubtitlesNeedsCredentials() = runTest {
        val s = server()
        val p = os(s, osCfg.copy(openSubtitlesUsername = ""))
        val e = assertFailsWith<OnlineSubtitleException> { p.download(OnlineSubtitleResult("OpenSubtitles", "1", "fa", "")) }
        assertEquals(OnlineSubtitleException.Kind.AUTH, e.kind)
        s.shutdown()
    }

    @Test fun repositoryMergesCachesAndSurvivesProviderFailure() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("subcache").toFile()
        val disk = DiskSubtitleCache(dir, maxBytes = 100)
        var searches = 0
        val good = object : OnlineSubtitleProvider {
            override val name = "G"
            override suspend fun isAvailable() = true
            override suspend fun search(query: SubtitleQuery): List<OnlineSubtitleResult> { searches++; return listOf(OnlineSubtitleResult("G", "1", "en", "r"), OnlineSubtitleResult("G", "2", "fa", "r")) }
            override suspend fun download(result: OnlineSubtitleResult) = SRT
        }
        val bad = object : OnlineSubtitleProvider {
            override val name = "B"
            override suspend fun isAvailable() = true
            override suspend fun search(query: SubtitleQuery): List<OnlineSubtitleResult> = throw OnlineSubtitleException(OnlineSubtitleException.Kind.QUOTA, "q")
            override suspend fun download(result: OnlineSubtitleResult) = throw IllegalStateException()
        }
        val off = object : OnlineSubtitleProvider {
            override val name = "O"
            override suspend fun isAvailable() = false
            override suspend fun search(query: SubtitleQuery): List<OnlineSubtitleResult> = error("not called")
            override suspend fun download(result: OnlineSubtitleResult) = error("not called")
        }
        var now = 0L
        val repo = OnlineSubtitleRepository(listOf(good, bad, off), InMemoryOnlineSubtitleSettings(), disk, clock = { now })
        val q = SubtitleQuery(title = "T")
        val out = repo.search(q)
        assertEquals(listOf("2", "1"), out.results.map { it.id })
        assertEquals(1, out.errors.size)
        repo.search(q)
        assertEquals(1, searches)
        now = 61 * 60 * 1000L
        repo.search(q)
        assertEquals(2, searches)

        val d = repo.fetch(out.results[0])
        assertTrue(d.text.contains("سلام"))
        assertEquals(1, d.toTrack().cues.size)
        assertTrue(disk.sizeBytes() > 0)
        disk.clear()
        assertEquals(0, disk.sizeBytes())
        dir.deleteRecursively()
    }

    @Test fun diskCacheEvictsLeastRecentlyUsed() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("subcache2").toFile()
        val c = DiskSubtitleCache(dir, maxBytes = 25)
        c.put("a", ByteArray(10)); dir.listFiles()!!.forEach { it.setLastModified(1000) }
        c.put("b", ByteArray(10))
        c.get("a") // touch a
        dir.listFiles()!!.first { it.length() > 0 }.let { }
        c.put("c", ByteArray(10))
        assertNotNull(c.get("a"))
        assertNull(c.get("b"))
        dir.deleteRecursively()
    }
}
