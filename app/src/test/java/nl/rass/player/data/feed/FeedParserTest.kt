package nl.rass.player.data.feed

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.rass.player.domain.Movie
import nl.rass.player.domain.Quality
import nl.rass.player.domain.Series

internal fun repoFile(path: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val f = File(dir, path)
        if (f.exists()) return f
        dir = dir.parentFile
    }
    error("$path not found")
}

internal fun parse(json: String, url: String = "https://site.example/feed.json") =
    FeedParser().parse(json.toByteArray(), url)

internal fun assemble(json: String, url: String = "https://site.example/feed.json") =
    FeedAssembler.assemble(parse(json, url), emptyList())

class FeedParserTest {

    @Test
    fun `every example file is handled - minimal json loads without warnings`() {
        val dir = repoFile("docs/feed-examples")
        val jsonFiles = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(jsonFiles.map { it.name }.containsAll(listOf("minimal.json", "update.json")))
        for (f in jsonFiles) {
            when (f.name) {
                "update.json" -> {
                    // An update manifest is not a feed: rejected as fatal, not crashing.
                    val e = assertFailsWith<FeedException> { FeedParser().parse(f.readBytes(), "https://site.example/update.json") }
                    assertEquals(FeedErrorKind.NOT_JSON, e.error.kind)
                }
                else -> {
                    val a = FeedAssembler.assemble(FeedParser().parse(f.readBytes(), "https://site.example/feed.json"), emptyList())
                    assertEquals(0, a.report.skipped, "${f.name}: ${a.report.issues}")
                    assertEquals(0, a.report.warnings, "${f.name}: ${a.report.issues}")
                    assertTrue(a.report.titles > 0)
                }
            }
        }
    }

    @Test
    fun `minimal json content`() {
        val a = FeedAssembler.assemble(
            FeedParser().parse(repoFile("docs/feed-examples/minimal.json").readBytes(), "https://site.example/.well-known/rass-feed.json"),
            emptyList(),
        )
        val c = a.catalog
        assertEquals("Example feed", c.name)
        assertEquals(1, a.report.movies); assertEquals(1, a.report.series); assertEquals(4, a.report.episodes)
        assertEquals(listOf("bbb"), c.featured)
        assertEquals(listOf("bbb", "sample-series"), c.top10)
        val bbb = c.titles.first { it.id == "bbb" } as Movie
        assertEquals(4, bbb.streams.size)
        assertEquals(Quality.Auto, bbb.streams[0].quality)
        assertEquals(Quality.Height(1080), bbb.streams[1].quality)
        assertEquals(1, bbb.streams[3].priority)
        assertEquals(10378, bbb.meta.ids.tmdb)
        // relative subtitle URL resolves against the feed URL (note the .well-known dir, §2.1)
        assertEquals("https://site.example/.well-known/subs/bbb.fa.srt", bbb.subtitles[0].url)
        assertEquals("windows-1256", bbb.subtitles[0].encoding)
        assertEquals(568.0, bbb.markers?.creditsStart)
        assertEquals("https://media.example.com/bbb/poster-185.jpg", bbb.meta.poster!!.urlFor(150))
        assertEquals("https://media.example.com/bbb/poster-342.jpg", bbb.meta.poster!!.urlFor(300))
        assertEquals("https://media.example.com/bbb/poster-342.jpg", bbb.meta.poster!!.urlFor(5000))
        assertEquals("https://img.example.com/w1280/bbb-backdrop.jpg", bbb.meta.backdrop!!.urlFor(1000))
        assertEquals(FeedDates.parseDate("2026-10-01"), bbb.meta.addedAt)

        val series = c.titles.first { it.id == "sample-series" } as Series
        assertEquals(listOf(1, 2), series.seasons.map { it.number })
        assertEquals("https://site.example/.well-known/media/s1e1/master.m3u8", series.seasons[0].episodes[0].streams[0].url)
        val m2 = series.seasons[0].episodes[1].markers!!
        assertEquals(0.0, m2.recapStart); assertEquals(95.0, m2.introEnd)
    }

    @Test
    fun `fatal documents`() {
        fun kind(s: String) = assertFailsWith<FeedException> { parse(s) }.error.kind
        assertEquals(FeedErrorKind.HTML_PAGE, kind("  <!doctype html><html></html>"))
        assertEquals(FeedErrorKind.NOT_JSON, kind("{not json"))
        assertEquals(FeedErrorKind.NOT_JSON, kind("[1,2]"))
        assertEquals(FeedErrorKind.UNSUPPORTED_VERSION, kind("""{"rass_feed_version":2,"titles":[]}"""))
        assertEquals(FeedErrorKind.NOT_JSON, kind("""{"rass_feed_version":"1","titles":[]}"""))
        assertEquals(FeedErrorKind.MISSING_TITLES, kind("""{"rass_feed_version":1,"name":"x"}"""))
        assertEquals(FeedErrorKind.NOT_A_MAIN_FEED, kind("""{"rass_feed_version":1,"kind":"part","titles":[]}"""))
        val big = ByteArray((FeedParser.MAX_DOCUMENT_BYTES + 1).toInt()) { ' '.code.toByte() }
        assertEquals(FeedErrorKind.TOO_LARGE, assertFailsWith<FeedException> { FeedParser().parse(big, "https://a.example/") }.error.kind)
        // a UTF-8 BOM is fine
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """{"rass_feed_version":1,"name":"n","updated_at":"2026-10-15T12:00:00Z","titles":[]}""".toByteArray()
        FeedParser().parse(bom, "https://a.example/")
    }

    @Test
    fun `invalid entries are skipped and counted, invalid optional fields become warnings`() {
        val a = assemble(
            """
            {
              "rass_feed_version": 1, "name": "T", "updated_at": "2026-10-15T12:00:00Z",
              "x_note": "ignored", "unknown_key": 5,
              "genres": [
                {"id": "drama", "name_en": "Drama"},
                {"id": "drama", "name_en": "Dup"},
                {"id": "nameless"}
              ],
              "featured": ["m1", "nope"],
              "titles": [
                "not an object",
                {"id": "m1", "type": "movie", "title_en": "Good", "year": 1700, "rating": 11, "genres": ["drama", "ghost"],
                 "poster": "ftp://x/y.jpg", "seasons": [],
                 "streams": [
                   {"url": "https://cdn.example/a.mp4", "type": "mp4", "quality": "auto"},
                   {"url": "javascript:alert(1)", "type": "mp4", "quality": "720p"},
                   {"url": "https://cdn.example/b.m3u8", "type": "rtmp", "quality": "auto"},
                   {"url": "/c.m3u8", "type": "hls", "quality": "best", "headers": {"Referer": "x"}}
                 ],
                 "subtitles": [
                   {"lang": "fa-IR", "url": "s.fa.srt", "format": "srt"},
                   {"lang": "de", "url": "s.de.srt", "format": "srt"},
                   {"lang": "en", "url": "s.en.ass", "format": "ass"}
                 ],
                 "markers": {"intro_start": 50, "intro_end": 40, "credits_start": 100}
                },
                {"id": "m1", "type": "movie", "title_en": "Dup", "streams": [{"url": "https://a.example/x.mp4", "type": "mp4", "quality": "720p"}]},
                {"id": "bad id!", "type": "movie", "title_en": "X"},
                {"id": "m2", "type": "documentary", "title_en": "X"},
                {"id": "m3", "type": "movie"},
                {"id": "m4", "type": "movie", "title_en": "No streams", "streams": []},
                {"id": "s1", "type": "series", "title_fa": "سریال", "streams": [],
                 "seasons": [
                   {"number": 1, "episodes": [
                     {"id": "e1", "number": 1, "streams": [{"url": "https://a.example/e1.mp4", "type": "mp4", "quality": "480p"}]},
                     {"id": "e1", "number": 2, "streams": [{"url": "https://a.example/e2.mp4", "type": "mp4", "quality": "480p"}]},
                     {"id": "e3", "number": 1, "streams": [{"url": "https://a.example/e3.mp4", "type": "mp4", "quality": "480p"}]},
                     {"id": "e4", "number": 4, "streams": [{"url": "nope://a", "type": "mp4"}]}
                   ]},
                   {"number": 1, "episodes": []},
                   {"number": 2, "episodes": []}
                 ]}
              ]
            }
            """.trimIndent()
        )
        val c = a.catalog
        assertEquals(listOf("m1", "s1"), c.titles.map { it.id })
        assertEquals(listOf("m1"), c.featured)
        val m1 = c.titles[0] as Movie
        assertNull(m1.meta.year); assertNull(m1.meta.rating); assertNull(m1.meta.poster)
        assertEquals(listOf("drama"), m1.meta.genreIds)
        assertEquals(2, m1.streams.size)
        assertEquals(Quality.Unknown, m1.streams[0].quality) // mp4 "auto" is invalid
        assertEquals(Quality.Auto, m1.streams[1].quality) // hls with bad quality → auto
        assertEquals("https://site.example/c.m3u8", m1.streams[1].url)
        assertEquals(3, m1.streams[1].index)
        assertEquals(listOf("fa"), m1.subtitles.map { it.lang })
        assertNull(m1.markers?.introStart); assertEquals(100.0, m1.markers?.creditsStart)
        val s1 = c.titles[1] as Series
        assertEquals(1, s1.seasons.size)
        assertEquals(listOf(1), s1.seasons[0].episodes.map { it.number })

        // skipped: "not an object", dup m1, bad id, unknown type, no title, m4 (no stream), dup genre, nameless genre,
        //   2 bad streams, 1 bad subtitle format, dup ep id, dup ep number, e4 (bad stream → stream skip + episode skip),
        //   season dup number, season 2 without episodes
        assertEquals(17, a.report.skipped, a.report.issues.joinToString("\n"))
        assertTrue(a.report.warnings >= 8, a.report.issues.joinToString("\n"))
        assertTrue(a.report.issues.any { it.path == "titles[1].streams[1].url" })
        assertTrue(a.report.issues.any { it.path == "featured" && "nope" in it.message })
    }

    @Test
    fun `subtitle-only titles are kept but not counted as playable`() {
        val a = assemble(
            """{"rass_feed_version":1,"name":"S","updated_at":"2026-10-15T12:00:00Z","titles":[
              {"id":"m","type":"movie","title_en":"Only Subs","year":2001,"ids":{"imdb":"tt0000001"},
               "subtitles":[{"lang":"fa","url":"https://subs.example/m.fa.srt","format":"srt"}]}]}"""
        )
        assertEquals(1, a.catalog.titles.size)
        assertEquals(false, a.catalog.titles[0].isPlayable)
        assertEquals(0, a.report.movies)
    }

    @Test
    fun `relative URL resolution and scheme rules`() {
        val base = "https://site.example/.well-known/rass-feed.json"
        assertEquals("https://site.example/.well-known/media/a.mp4", FeedUrls.resolve("media/a.mp4", base))
        assertEquals("https://site.example/media/a.mp4", FeedUrls.resolve("/media/a.mp4", base))
        assertEquals("https://site.example/x.vtt", FeedUrls.resolve("../x.vtt", base))
        assertEquals("https://cdn.example.com/a.m3u8", FeedUrls.resolve("//cdn.example.com/a.m3u8", base))
        assertEquals("http://home.lan:8080/a.mp4", FeedUrls.resolve("HTTP://home.lan:8080/a.mp4", base))
        for (bad in listOf("file:///etc/passwd", "content://x/y", "data:text/plain,hi", "javascript:x", "ftp://a/b",
                "rtmp://a/b", "https://", "a b.mp4", "", "asset:///test-feeds/x.json")) {
            assertNull(FeedUrls.resolve(bad, base), bad)
        }
        // part documents resolve against their own URL
        val part = FeedParser().parse(
            """{"rass_feed_version":1,"kind":"part","titles":[{"id":"p","type":"movie","title_en":"P",
              "streams":[{"url":"v/p.mp4","type":"mp4","quality":"720p"}]}]}""".toByteArray(),
            "https://other.example/parts/1.json", expectPart = true,
        )
        assertEquals("https://other.example/parts/v/p.mp4", (part.titles[0].title as Movie).streams[0].url)
    }

    @Test
    fun `asset scheme only inside asset documents`() {
        val base = "asset:///test-feeds/blender.json"
        assertEquals("asset:///test-feeds/subs/a.fa.srt", FeedUrls.resolve("subs/a.fa.srt", base))
        assertEquals("asset:///test-subs/a.srt", FeedUrls.resolve("asset:///test-subs/a.srt", base))
        assertEquals("https://download.blender.org/a.mp4", FeedUrls.resolve("https://download.blender.org/a.mp4", base))
        assertNull(FeedUrls.resolve("../../etc/x", base))
        assertNull(FeedUrls.assetPath("asset:///a/../b"))
        assertEquals("test-feeds/blender.json", FeedUrls.assetPath(base))
    }

    @Test
    fun `dates`() {
        assertEquals(0L, FeedDates.parseDate("1970-01-01"))
        assertEquals(1_760_529_600_000L, FeedDates.parseDateTime("2025-10-15T12:00:00Z"))
        assertEquals(1_760_529_600_000L, FeedDates.parseDateTime("2025-10-15T14:00+02:00"))
        assertEquals(1_760_529_600_500L, FeedDates.parseDateTime("2025-10-15T12:00:00.5Z"))
        assertNull(FeedDates.parseDate("2025-02-29"))
        assertEquals(FeedDates.parseDate("2024-02-29")!! + 86_400_000L, FeedDates.parseDate("2024-03-01"))
        assertNull(FeedDates.parseDateTime("2025-10-15T12:00:00"))
    }
}

class PresetFeedsTest {
    @Test
    fun `bundled preset test feeds load as asset sources`() {
        val dir = repoFile("app/src/main/assets/test-feeds")
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        for (f in files) {
            val a = FeedAssembler.assemble(FeedParser().parse(f.readBytes(), "asset:///test-feeds/${f.name}"), emptyList())
            println("PRESET ${f.name}: movies=${a.report.movies} series=${a.report.series} episodes=${a.report.episodes} " +
                "skipped=${a.report.skipped} warnings=${a.report.warnings} ${a.report.issues.take(5)}")
            assertTrue(a.report.titles > 0, f.name)
            assertEquals(0, a.report.skipped, "${f.name}: ${a.report.issues}")
            assertTrue(a.catalog.isTest, f.name)
        }
    }
}
