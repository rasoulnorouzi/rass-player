package nl.rass.player.data.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.rass.player.data.feed.FeedAssembler
import nl.rass.player.data.feed.FeedParser
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.TitleType
import nl.rass.player.domain.playback.StreamCandidates

class CatalogMergerTest {

    private fun src(id: String, name: String, json: String) =
        MergeInput(id, name, FeedAssembler.assemble(FeedParser().parse(json.toByteArray(), "https://$id.example/feed.json"), emptyList()).catalog)

    private fun feed(name: String, titles: String, extra: String = "") =
        """{"rass_feed_version":1,"name":"$name","updated_at":"2026-10-15T12:00:00Z"$extra,"titles":[$titles]}"""

    private fun stream(url: String, q: String = "720p", p: Int = 0) = """{"url":"$url","type":"mp4","quality":"$q","priority":$p}"""
    private fun sub(lang: String, url: String) = """{"lang":"$lang","url":"$url","format":"srt"}"""

    // Source A (rank 0): has tmdb/imdb ids, a series with s1e1-e2.
    private val a = src("a", "Site A", feed("A", """
        {"id":"1","type":"movie","title_fa":"باک بانی","title_en":"Big Buck Bunny","year":2008,"ids":{"tmdb":10378},
         "backdrop":"https://a.example/bd.jpg","genres":["Animation"],"added_at":"2026-10-01",
         "streams":[${stream("https://a.example/bbb-720.mp4")},${stream("https://a.example/bbb-1080.mp4","1080p")}]},
        {"id":"2","type":"movie","title_en":"Sintel","year":2010,"ids":{"imdb":"tt1727587"},
         "streams":[${stream("https://a.example/sintel.mp4")}]},
        {"id":"3","type":"series","title_en":"The Show","year":2020,"description_en":"From A",
         "seasons":[{"number":1,"episodes":[
           {"id":"e1","number":1,"title_en":"Pilot","streams":[${stream("https://a.example/s1e1.mp4")}]},
           {"id":"e2","number":2,"streams":[${stream("https://a.example/s1e2.mp4")}]}]}]},
        {"id":"4","type":"movie","title_en":"Twin","year":1999,"ids":{"tmdb":1},"streams":[${stream("https://a.example/t1.mp4")}]},
        {"id":"5","type":"movie","title_en":"Twin","year":1999,"ids":{"tmdb":2},"streams":[${stream("https://a.example/t2.mp4")}]}
    """, extra = ""","featured":["1"],"top10":["2","1"],"genres":[{"id":"Animation","name_fa":"انیمیشن"}]"""))

    // Source B (rank 1): same ids written differently, a feed-local id that collides with A's, extra episodes.
    private val b = src("b", "Site B", feed("B", """
        {"id":"1","type":"movie","title_en":"Something Else","year":2015,"streams":[${stream("https://b.example/else.mp4")}]},
        {"id":"bbb","type":"movie","title_en":"BBB other name","ids":{"tmdb":10378,"imdb":"tt1254207"},"rating":7.1,
         "description_fa":"توضیح از ب","streams":[${stream("https://b.example/bbb.mp4","720p")},${stream("https://a.example/bbb-720.mp4")}],
         "subtitles":[${sub("fa","https://b.example/bbb.fa.srt")}]},
        {"id":"sintel","type":"movie","title_en":"Sintel","ids":{"imdb":"TT1727587"},"streams":[${stream("https://b.example/sintel.mp4")}]},
        {"id":"show","type":"series","title_fa":"نمایش","title_en":"the  show!","year":2020,
         "seasons":[{"number":1,"episodes":[
           {"id":"x1","number":1,"title_fa":"شروع","streams":[${stream("https://b.example/s1e1.mp4")}],"subtitles":[${sub("fa","https://b.example/s1e1.fa.srt")}]},
           {"id":"x3","number":3,"streams":[${stream("https://b.example/s1e3.mp4")}]}]},
           {"number":0,"episodes":[{"id":"sp","number":1,"streams":[${stream("https://b.example/sp.mp4")}]}]}]},
        {"id":"twin","type":"movie","title_en":"Twin","year":1999,"streams":[${stream("https://b.example/twin.mp4")}]}
    """, extra = ""","top10":["bbb"],"genres":[{"id":"animation","name_fa":"کارتون"},{"id":"drama","name_en":"Drama"}]"""))

    // Source C (rank 2): subtitles only for BBB (no streams), title+year match without ids for The Show.
    private val c = src("c", "Subs C", feed("C", """
        {"id":"bbb-subs","type":"movie","title_en":"Big Buck Bunny","year":2008,"ids":{"tmdb":10378},
         "subtitles":[${sub("fa","https://c.example/bbb.fa.srt")},${sub("en","https://c.example/bbb.en.srt")},${sub("fa","https://b.example/bbb.fa.srt")}]},
        {"id":"lonely-subs","type":"movie","title_en":"Nobody Has This","year":1950,"subtitles":[${sub("fa","https://c.example/x.srt")}]},
        {"id":"show-c","type":"series","title_en":"The Show","year":2020,"seasons":[{"number":1,"episodes":[
           {"id":"c2","number":2,"subtitles":[${sub("fa","https://c.example/s1e2.fa.srt")}]}]}]},
        {"id":"show-wrong-year","type":"series","title_en":"The Show","year":2021,"seasons":[{"number":1,"episodes":[
           {"id":"w1","number":1,"streams":[${stream("https://c.example/w1.mp4")}]}]}]}
    """))

    private fun merged(): Catalog = CatalogMerger().merge(listOf(a, b, c), firstSeen = mapOf())

    private fun Catalog.byTitle(en: String, type: TitleType = TitleType.MOVIE): List<CatalogTitle> =
        titles.filter { it.type == type && it.meta.titleEn == en }

    @Test
    fun `dedupe by tmdb then imdb then title+year, never by feed-local id`() {
        val cat = merged()
        val bbb = assertNotNull(cat.title("tmdb:movie:10378"))
        assertEquals(listOf("a", "b", "c"), bbb.sources.map { it.sourceId })
        assertEquals(listOf("1", "bbb", "bbb-subs"), bbb.sources.map { it.titleId })
        assertEquals(listOf(true, true, false), bbb.sources.map { it.hasStreams })
        assertEquals(setOf("a", "b"), bbb.playableSourceIds)

        // "1" in B is a different film: feed-local ids are not compared
        assertEquals(1, cat.byTitle("Something Else").size)

        // imdb compared case-insensitively
        val sintel = cat.titles.single { it.meta.titleEn == "Sintel" }
        assertEquals("imdb:tt1727587", sintel.key)
        assertEquals(2, sintel.sources.size)

        // title+year with normalization ("the  show!" == "The Show"), different year doesn't match
        val shows = cat.titles.filter { it.type == TitleType.SERIES }
        assertEquals(2, shows.size)
        val show = shows.first { it.meta.year == 2020 }
        assertEquals("src:a:3", show.key)
        assertEquals(listOf("a", "b", "c"), show.sources.map { it.sourceId })

        // two A titles with different tmdb ids don't merge; B's "Twin" without ids matches by title+year → first created
        val twins = cat.byTitle("Twin")
        assertEquals(2, twins.size)
        assertEquals(listOf("a", "b"), twins[0].sources.map { it.sourceId })
        assertEquals(listOf("a"), twins[1].sources.map { it.sourceId })

        // subtitle-only title without a playable partner doesn't appear
        assertTrue(cat.titles.none { it.meta.titleEn == "Nobody Has This" })
    }

    @Test
    fun `metadata falls back field by field from the primary`() {
        val bbb = merged().title("tmdb:movie:10378")!!
        assertEquals("Big Buck Bunny", bbb.meta.titleEn)        // primary
        assertEquals("باک بانی", bbb.meta.titleFa)
        assertEquals(7.1, bbb.meta.rating)                      // from B
        assertEquals("توضیح از ب", bbb.meta.descriptionFa)      // from B
        assertEquals("tt1254207", bbb.meta.ids.imdb)            // from B
        assertEquals(listOf("animation"), bbb.meta.genreIds)    // lowercased
        assertTrue(bbb.searchTitles.containsAll(listOf("Big Buck Bunny", "BBB other name")))
    }

    @Test
    fun `lower sources' streams become backups and subtitles are pooled from all sources`() {
        val bbb = merged().title("tmdb:movie:10378")!!
        val candidates = StreamCandidates.order(bbb.streams)
        assertEquals(
            listOf("https://a.example/bbb-1080.mp4", "https://a.example/bbb-720.mp4", "https://b.example/bbb.mp4"),
            candidates.map { it.url },
        ) // duplicate URL from B dropped, A first
        assertEquals(listOf("a", "a", "b"), candidates.map { it.sourceId })

        // R6.6: A has no subtitles, B and the stream-less C contribute; deduplicated by URL, with their source
        assertEquals(
            listOf("https://b.example/bbb.fa.srt" to "b", "https://c.example/bbb.fa.srt" to "c", "https://c.example/bbb.en.srt" to "c"),
            bbb.subtitles.map { it.url to it.sourceId },
        )
        assertEquals(listOf("fa", "fa", "en"), bbb.subtitles.map { it.lang })
    }

    @Test
    fun `series episodes are united by season and episode number with pooled subtitles`() {
        val show = merged().titles.first { it.type == TitleType.SERIES && it.meta.year == 2020 }
        assertEquals(listOf(1, 0), show.seasons.map { it.number }) // specials last
        val s1 = show.seasons[0]
        assertEquals(listOf(1, 2, 3), s1.episodes.map { it.number })
        val e1 = s1.episodes[0]
        assertEquals("src:a:3#s1e1", e1.key)
        assertEquals("Pilot", e1.titleEn); assertEquals("شروع", e1.titleFa) // fallback from B
        assertEquals(listOf("a", "b"), e1.streams.map { it.sourceId })
        assertEquals(listOf("b"), e1.subtitles.map { it.sourceId })
        // e2 plays from A only, but gets C's subtitle (C has no stream for it)
        val e2 = s1.episodes[1]
        assertEquals(listOf("a"), e2.streams.map { it.sourceId })
        assertEquals(listOf("https://c.example/s1e2.fa.srt"), e2.subtitles.map { it.url })
        assertEquals(listOf(true, false), e2.sources.map { it.hasStreams })
        // e3 only in B is added
        assertEquals(listOf("b"), s1.episodes[2].streams.map { it.sourceId })
        assertEquals("From A", show.meta.descriptionEn)
    }

    @Test
    fun `featured, top10, genres, recently added, stats`() {
        val cat = CatalogMerger().merge(listOf(a, b, c), firstSeen = mapOf("imdb:tt1727587" to 5L))
        assertEquals(listOf("tmdb:movie:10378"), cat.featured.map { it.key })
        assertEquals(listOf("imdb:tt1727587", "tmdb:movie:10378"), cat.top10.map { it.key }) // from A only, never mixed
        assertEquals(listOf("animation", "drama"), cat.genres.map { it.id })
        assertEquals("انیمیشن", cat.genres[0].nameFa) // name from the highest-ranked source
        assertEquals("tmdb:movie:10378", cat.recentlyAdded.first().key)
        assertEquals(5L, cat.title("imdb:tt1727587")!!.addedAt)
        assertEquals(4, cat.sourceStats["a"]!!.movies)
        assertEquals(0, cat.sourceStats["c"]!!.movies)
        assertEquals(3, cat.sourceStats["b"]!!.episodes)
        assertEquals(listOf("tmdb:movie:10378"), cat.byGenre("Animation").map { it.key })
        assertEquals(cat.titles.count { "c" in it.playableSourceIds }, cat.fromSource("c").size)
    }

    @Test
    fun `featured falls back to newest titles with a backdrop`() {
        val only = src("z", "Z", feed("Z", """
            {"id":"old","type":"movie","title_en":"Old","added_at":"2020-01-01","backdrop":"https://z.example/1.jpg","streams":[${stream("https://z.example/1.mp4")}]},
            {"id":"new","type":"movie","title_en":"New","added_at":"2026-01-01","backdrop":"https://z.example/2.jpg","streams":[${stream("https://z.example/2.mp4")}]},
            {"id":"nobd","type":"movie","title_en":"NoBd","added_at":"2026-05-01","streams":[${stream("https://z.example/3.mp4")}]}
        """))
        val cat = CatalogMerger().merge(listOf(only))
        assertEquals(listOf("src:z:new", "src:z:old"), cat.featured.map { it.key })
        assertTrue(cat.top10.isEmpty())
        assertNull(cat.title("src:z:nobd")!!.meta.backdrop)
    }

    @Test
    fun `rank order decides the primary`() {
        val cat = CatalogMerger().merge(listOf(b, a))
        val bbb = cat.title("tmdb:movie:10378")!!
        assertEquals("b", bbb.primary.sourceId)
        assertEquals("BBB other name", bbb.meta.titleEn)
        assertEquals("b", StreamCandidates.order(bbb.streams).first().sourceId)
    }
}
