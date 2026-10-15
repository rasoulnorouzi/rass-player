package nl.rass.player.player.subtitle

import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubtitleLoaderTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun loader(maxBytes: Long = SubtitleLoader.DEFAULT_MAX_BYTES) =
        SubtitleLoader(client, maxBytes = maxBytes, parseDispatcher = Dispatchers.Unconfined)

    @Test
    fun downloadsDecodesParsesAndCaches() = runBlocking {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nسلام دنیا\r\n"
        // windows-1256 has no Persian yeh; legacy files use the Arabic one, which the decoder maps back.
        val bytes = srt.replace('ی', 'ي').toByteArray(Charset.forName("windows-1256"))
        // Server wrongly labels the file as UTF-8; detection must still pick windows-1256.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody(Buffer().write(bytes)),
        )
        val l = loader()
        val url = server.url("/subs/fa.srt").toString()
        val track = l.load(url)
        assertEquals("سلام دنیا", track.cuesAt(1_500).single().text)
        // Second load comes from the cache: no second request.
        l.load(url)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sniffsVttWithoutExtension() = runBlocking {
        server.enqueue(MockResponse().setBody("WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n"))
        val track = loader().load(server.url("/sub").toString())
        assertEquals("Hi", track.cuesAt(1_000).single().text)
    }

    @Test
    fun rejectsTooLargeFiles() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(2_000)))
        val e = assertFailsWith<SubtitleLoadException> { loader(maxBytes = 1_000).load(server.url("/big.srt").toString()) }
        assertEquals(SubtitleLoadException.Reason.TOO_LARGE, e.reason)
    }

    @Test
    fun httpErrorAndNoCues() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val e1 = assertFailsWith<SubtitleLoadException> { loader().load(server.url("/missing.srt").toString()) }
        assertEquals(SubtitleLoadException.Reason.HTTP, e1.reason)

        server.enqueue(MockResponse().setBody("not a subtitle file"))
        val e2 = assertFailsWith<SubtitleLoadException> { loader().load(server.url("/junk.srt").toString()) }
        assertEquals(SubtitleLoadException.Reason.NO_CUES, e2.reason)
    }

    @Test
    fun readsAssetUrlsThroughTheAssetReader() = runBlocking {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nAsset line\n".toByteArray()
        val assets = nl.rass.player.data.feed.AssetReader { path ->
            if (path == "test-feeds/subs/a.en.srt") java.io.ByteArrayInputStream(srt) else null
        }
        val l = SubtitleLoader(client, parseDispatcher = Dispatchers.Unconfined, assets = assets, ioDispatcher = Dispatchers.Unconfined)
        assertEquals("Asset line", l.load("asset:///test-feeds/subs/a.en.srt").cuesAt(1_500).single().text)
        val e = assertFailsWith<SubtitleLoadException> { l.load("asset:///test-feeds/subs/missing.srt") }
        assertEquals(SubtitleLoadException.Reason.HTTP, e.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sourceHeaderGoesOnlyToTheSubtitleOrigin() = runBlocking {
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nMoved\n"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/moved.srt").toString()))
            val track = loader().load(server.url("/a.srt").toString(), headers = mapOf("X-Feed-Key" to "secret"))
            assertEquals("Moved", track.cuesAt(1_500).single().text)
            assertEquals("secret", server.takeRequest().getHeader("X-Feed-Key"))
            assertEquals(null, other.takeRequest().getHeader("X-Feed-Key"))
        } finally {
            other.shutdown()
        }
    }
}
