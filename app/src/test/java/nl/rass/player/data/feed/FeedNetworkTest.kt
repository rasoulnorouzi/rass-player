package nl.rass.player.data.feed

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import nl.rass.player.data.catalog.FeedCache
import nl.rass.player.data.source.AuthHeaders
import nl.rass.player.data.source.DiscoveryResult
import nl.rass.player.data.source.FeedDiscovery
import nl.rass.player.data.source.SourceAuth
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before

internal fun movieJson(id: String, title: String, url: String, extra: String = "") =
    """{"id":"$id","type":"movie","title_en":"$title"$extra,"streams":[{"url":"$url","type":"mp4","quality":"720p"}]}"""

internal fun feedJson(name: String, titles: List<String>, extra: String = "") =
    """{"rass_feed_version":1,"name":"$name","updated_at":"2026-10-15T12:00:00Z"$extra,"titles":[${titles.joinToString(",")}]}"""

internal fun partJson(titles: List<String>) =
    """{"rass_feed_version":1,"kind":"part","titles":[${titles.joinToString(",")}]}"""

class FeedNetworkTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private val routes = HashMap<String, (RecordedRequest) -> MockResponse>()
    private val client = OkHttpClient()
    private val noAssets = AssetReader { null }
    private lateinit var cacheDir: File

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path]?.invoke(request) ?: MockResponse().setResponseCode(404)
        }
        server.start()
        other = MockWebServer().apply { start() }
        cacheDir = Files.createTempDirectory("feedcache").toFile()
    }

    @After fun tearDown() {
        server.shutdown(); other.shutdown(); cacheDir.deleteRecursively()
    }

    private fun url(path: String) = server.url(path).toString()
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
    private val fetcher get() = DocumentFetcher(client, noAssets)

    // ---------------- loader ----------------

    @Test
    fun `loads parts, resolves part URLs against the part, caches and revalidates with ETag`() = runBlocking<Unit> {
        routes["/feed.json"] = { r ->
            if (r.getHeader("If-None-Match") == "\"v1\"") MockResponse().setResponseCode(304)
            else json(feedJson("Main", listOf(movieJson("a", "A", "/a.mp4")), extra = ""","parts":["parts/p1.json","/missing.json"]"""))
                .setHeader("ETag", "\"v1\"").setHeader("Cache-Control", "max-age=600")
        }
        routes["/parts/p1.json"] = { json(partJson(listOf(movieJson("b", "B", "b.mp4"), movieJson("a", "Dup", "x.mp4")))) }
        val loader = FeedLoader(fetcher, FeedCache(cacheDir))
        val spec = SourceSpec("s1", "S1", url("/feed.json"))

        val r1 = loader.refresh(spec)
        assertNull(r1.error)
        val feed = assertNotNull(r1.feed)
        assertEquals(listOf("a", "b"), feed.catalog.titles.map { it.id })
        assertEquals(url("/parts/b.mp4"), (feed.catalog.titles[1] as nl.rass.player.domain.Movie).streams[0].url)
        assertEquals(1, feed.report.partErrors)
        assertEquals(1, feed.report.partsLoaded); assertEquals(2, feed.report.partsTotal)
        assertEquals(1, feed.report.skipped) // duplicate "a" in the part
        assertEquals(600L, r1.maxAgeSec)

        // second refresh: main answers 304, the catalog comes from cache
        val r2 = loader.refresh(spec)
        assertNull(r2.error)
        assertEquals(listOf("a", "b"), r2.feed!!.catalog.titles.map { it.id })

        // offline: cache-only load still works (N3)
        server.shutdown()
        val r3 = loader.refresh(spec)
        assertEquals(FeedErrorKind.NETWORK, r3.error?.kind)
        assertTrue(r3.fromCache)
        assertEquals(listOf("a", "b"), r3.feed!!.catalog.titles.map { it.id })
        assertEquals(listOf("a", "b"), loader.loadCached(spec).feed!!.catalog.titles.map { it.id })
    }

    @Test
    fun `a broken refresh keeps the last good copy`() = runBlocking<Unit> {
        var body = feedJson("Main", listOf(movieJson("a", "A", "/a.mp4")))
        routes["/feed.json"] = { json(body) }
        val loader = FeedLoader(fetcher, FeedCache(cacheDir))
        val spec = SourceSpec("s1", "S1", url("/feed.json"))
        assertNull(loader.refresh(spec).error)
        body = "<html>oops</html>"
        val r = loader.refresh(spec)
        assertEquals(FeedErrorKind.HTML_PAGE, r.error?.kind)
        assertEquals(listOf("a"), r.feed!!.catalog.titles.map { it.id })
    }

    @Test
    fun `size limit and missing asset`() = runBlocking<Unit> {
        routes["/big.json"] = { json("x".repeat(2000)) }
        val small = DocumentFetcher(client, noAssets)
        val res = small.fetch(FetchRequest(url("/big.json"), maxBytes = 1000))
        assertEquals(FeedErrorKind.TOO_LARGE, (res as FetchResult.Failed).error.kind)
        val r = FeedLoader(fetcher, null).refresh(SourceSpec("p", "P", "asset:///test-feeds/none.json"))
        assertEquals(FeedErrorKind.ASSET_MISSING, r.error?.kind)
        assertNull(r.feed)
    }

    @Test
    fun `asset sources are read from assets`() = runBlocking<Unit> {
        val files = mapOf("test-feeds/t.json" to feedJson("T", listOf(movieJson("a", "A", "https://cdn.example/a.mp4",
            ""","subtitles":[{"lang":"fa","url":"asset:///test-subs/a.fa.srt","format":"srt"}]"""))))
        val f = DocumentFetcher(client) { p -> files[p]?.byteInputStream() }
        val r = FeedLoader(f, FeedCache(cacheDir)).refresh(SourceSpec("p", "P", "asset:///test-feeds/t.json"))
        assertNull(r.error)
        val m = r.feed!!.catalog.titles[0] as nl.rass.player.domain.Movie
        assertEquals("asset:///test-subs/a.fa.srt", m.subtitles[0].url)
        assertTrue(cacheDir.listFiles().isNullOrEmpty()) // assets are not cached
    }

    // ---------------- headers / token ----------------

    @Test
    fun `header name rules`() {
        for (bad in listOf("Host", "referer", "Origin", "User-Agent", "Cookie", "Authorization", "Content-Length",
                "Transfer-Encoding", "Connection", "Sec-Fetch-Mode", "Proxy-Authorization", "X Feed", "", "X:Y")) {
            assertNotNull(AuthHeaders.validateHeaderName(bad), bad)
        }
        assertNull(AuthHeaders.validateHeaderName("X-Feed-Key"))
    }

    @Test
    fun `token goes only to the feed origin`() {
        val auth = SourceAuth(bearerToken = "secret")
        val feed = "https://site.example/feed.json"
        assertEquals(mapOf("Authorization" to "Bearer secret"), AuthHeaders.headersFor("https://site.example/media/a.mp4", feed, auth))
        assertTrue(AuthHeaders.headersFor("https://cdn.example/a.mp4", feed, auth).isEmpty())
        assertTrue(AuthHeaders.headersFor("https://site.example:8443/a.mp4", feed, auth).isEmpty())
        assertTrue(AuthHeaders.headersFor("http://site.example/a.mp4", feed, auth).isEmpty())
        // media hosts only with the opt-in, and only for origins of the source's own URLs
        val cdn = setOfNotNull(FeedUrls.origin("https://cdn.example/x"))
        assertTrue(AuthHeaders.headersFor("https://cdn.example/a.mp4", feed, auth, cdn).isEmpty())
        assertEquals(1, AuthHeaders.headersFor("https://cdn.example/a.mp4", feed, auth.copy(sendToMediaHosts = true), cdn).size)
        // http feed: only after the warning was accepted
        val httpFeed = "http://home.lan/feed.json"
        assertTrue(AuthHeaders.headersFor("http://home.lan/feed.json", httpFeed, auth).isEmpty())
        assertEquals(1, AuthHeaders.headersFor("http://home.lan/feed.json", httpFeed, auth.copy(allowOverHttp = true)).size)
        // custom header
        val custom = SourceAuth(headerName = "X-Feed-Key", headerValue = "abc")
        assertEquals(mapOf("X-Feed-Key" to "abc"), AuthHeaders.headersFor("https://site.example/x", feed, custom))
        assertTrue(SourceAuth(headerName = "Cookie", headerValue = "a=b").isEmpty)
    }

    @Test
    fun `token is dropped after a redirect that leaves the origin`() = runBlocking<Unit> {
        other.enqueue(json(feedJson("Moved", listOf(movieJson("a", "A", "/a.mp4")))))
        routes["/feed.json"] = { MockResponse().setResponseCode(302).setHeader("Location", other.url("/moved.json").toString()) }
        val auth = SourceAuth(bearerToken = "secret", allowOverHttp = true)
        val r = FeedLoader(fetcher, null).refresh(SourceSpec("s", "S", url("/feed.json"), auth))
        assertNull(r.error)
        assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
        assertNull(other.takeRequest().getHeader("Authorization"))
        // relative URLs resolve against the final URL (after the redirect)
        assertEquals(other.url("/a.mp4").toString(), (r.feed!!.catalog.titles[0] as nl.rass.player.domain.Movie).streams[0].url)
    }

    // ---------------- discovery ----------------

    private fun discover(input: String, auth: SourceAuth = SourceAuth.None) = runBlocking { FeedDiscovery(fetcher).discover(input, auth) }

    @Test
    fun `discovery finds well-known first`() {
        routes["/.well-known/rass-feed.json"] = { json(feedJson("WK", listOf(movieJson("a", "A", "/a.mp4")))) }
        routes["/rass-feed.json"] = { json(feedJson("Root", emptyList())) }
        val r = assertIs<DiscoveryResult.Found>(discover(url("/")))
        assertEquals(url("/.well-known/rass-feed.json"), r.feedUrl)
        assertEquals("WK", r.feedName); assertEquals(1, r.titleCount)
    }

    @Test
    fun `discovery falls back to root file`() {
        routes["/rass-feed.json"] = { json(feedJson("Root", emptyList())) }
        val r = assertIs<DiscoveryResult.Found>(discover(server.hostName + ":" + server.port + "/").let {
            // no scheme → https first (fails to connect), then http
            it
        })
        assertEquals(url("/rass-feed.json"), r.feedUrl)
        assertTrue(r.insecure)
    }

    @Test
    fun `discovery reads the link tag with base href`() {
        routes["/"] = {
            html("""<html><head><base href="/sub/"><link rel="stylesheet" href="a.css">
                <link REL="Alternate feed" type="application/vnd.rass.feed+json; charset=utf-8" href="files/my-feed.json?a=1&amp;b=2">
                </head><body>hi</body></html>""")
        }
        routes["/sub/files/my-feed.json?a=1&b=2"] = { json(feedJson("Linked", emptyList())) }
        val r = assertIs<DiscoveryResult.Found>(discover(url("/")))
        assertEquals(url("/sub/files/my-feed.json?a=1&b=2"), r.feedUrl)
    }

    @Test
    fun `discovery tries the input path itself`() {
        routes["/my/feed.json"] = { json(feedJson("Direct", emptyList())) }
        assertEquals(url("/my/feed.json"), assertIs<DiscoveryResult.Found>(discover(url("/my/feed.json"))).feedUrl)
    }

    @Test
    fun `discovery not found, needs token, invalid address`() {
        routes["/"] = { html("<html><head><title>My site</title></head></html>") }
        val nf = assertIs<DiscoveryResult.Failed>(discover(url("/")))
        assertEquals(FeedErrorKind.NOT_FOUND, nf.error.kind)
        assertTrue(nf.messageFa.isNotBlank())

        routes["/.well-known/rass-feed.json"] = { r ->
            if (r.getHeader("Authorization") == "Bearer t") json(feedJson("Private", emptyList())) else MockResponse().setResponseCode(401)
        }
        assertEquals(FeedErrorKind.NEEDS_TOKEN, assertIs<DiscoveryResult.Failed>(discover(url("/"))).error.kind)
        assertIs<DiscoveryResult.Found>(discover(url("/"), SourceAuth(bearerToken = "t", allowOverHttp = true)))

        assertEquals(FeedErrorKind.INVALID_ADDRESS, assertIs<DiscoveryResult.Failed>(discover("ftp://x.example")).error.kind)
        assertEquals(FeedErrorKind.INVALID_ADDRESS, assertIs<DiscoveryResult.Failed>(discover("   ")).error.kind)
    }
}
