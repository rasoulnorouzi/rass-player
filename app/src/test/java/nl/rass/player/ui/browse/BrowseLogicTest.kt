package nl.rass.player.ui.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.Genre
import nl.rass.player.domain.SourceRef
import nl.rass.player.domain.TitleMetadata
import nl.rass.player.domain.TitleType

class BrowseLogicTest {
    private fun t(
        key: String,
        fa: String? = null,
        en: String? = null,
        year: Int? = null,
        rating: Double? = null,
        added: Long? = null,
        genres: List<String> = emptyList(),
        type: TitleType = TitleType.MOVIE,
        sources: List<SourceRef> = listOf(SourceRef("s1", "یک", 0, key)),
    ) = CatalogTitle(
        key = key, type = type,
        meta = TitleMetadata(titleFa = fa, titleEn = en, year = year, rating = rating, genreIds = genres),
        sources = sources, addedAt = added,
    )

    // Plain code-point order keeps the test independent of the JDK's fa collation rules.
    private val plain = Comparator<String> { a, b -> a.compareTo(b) }
    private fun keys(l: List<CatalogTitle>) = l.map { it.key }

    @Test
    fun `newest sorts by added_at desc with missing last`() {
        val l = listOf(t("a", "الف", added = 10), t("b", "ب", added = null), t("c", "ج", added = 30))
        assertEquals(listOf("c", "a", "b"), keys(BrowseLogic.sort(l, BrowseSort.Newest, plain)))
    }

    @Test
    fun `year and rating sort desc with missing last`() {
        val l = listOf(t("a", "الف", year = 1999, rating = 6.0), t("b", "ب", year = null, rating = null), t("c", "ج", year = 2020, rating = 8.5))
        assertEquals(listOf("c", "a", "b"), keys(BrowseLogic.sort(l, BrowseSort.Year, plain)))
        assertEquals(listOf("c", "a", "b"), keys(BrowseLogic.sort(l, BrowseSort.Rating, plain)))
    }

    @Test
    fun `alpha puts Persian titles before English-only`() {
        val l = listOf(t("e", en = "Apple"), t("f2", "ب"), t("f1", "الف"))
        assertEquals(listOf("f1", "f2", "e"), keys(BrowseLogic.sort(l, BrowseSort.Alpha, plain)))
    }

    @Test
    fun `ties fall back to title then key and are stable`() {
        val l = listOf(t("k2", "ب", year = 2000), t("k1", "ب", year = 2000), t("k0", "الف", year = 2000))
        assertEquals(listOf("k0", "k1", "k2"), keys(BrowseLogic.sort(l, BrowseSort.Year, plain)))
    }

    @Test
    fun `real persian collator orders alef before be`() {
        val l = listOf(t("b", "ب"), t("a", "الف"))
        assertEquals(listOf("a", "b"), keys(BrowseLogic.sort(l, BrowseSort.Alpha)))
    }

    @Test
    fun `genre filter is case-insensitive and source filter ignores subtitle-only sources`() {
        val subOnly = listOf(SourceRef("s2", "دو", 1, "x", hasStreams = false), SourceRef("s1", "یک", 0, "x"))
        val l = listOf(t("a", "الف", genres = listOf("Drama")), t("b", "ب", genres = listOf("comedy")), t("x", "ایکس", sources = subOnly))
        assertEquals(listOf("a"), keys(BrowseLogic.filter(l, "drama", null)))
        assertEquals(listOf("x"), keys(BrowseLogic.filter(l, null, "s1").filter { it.key == "x" }))
        assertEquals(emptyList(), keys(BrowseLogic.filter(listOf(l[2]), null, "s2")))
    }

    @Test
    fun `genres and sources offered only when titles exist`() {
        val genres = listOf(Genre("drama", "درام", null), Genre("scifi", "علمی", null))
        val l = listOf(t("a", genres = listOf("Drama"), sources = listOf(SourceRef("s2", "دو", 1, "a"), SourceRef("s1", "یک", 0, "a"))))
        assertEquals(listOf("drama"), BrowseLogic.genresWithTitles(l, genres).map { it.id })
        assertEquals(listOf("s1", "s2"), BrowseLogic.sourceOptions(l).map { it.id })
    }

    @Test
    fun `apply combines filter and sort and type filter`() {
        val l = listOf(
            t("a", "الف", year = 2001, genres = listOf("drama")),
            t("b", "ب", year = 2010, genres = listOf("drama")),
            t("c", "ج", year = 2020, genres = listOf("comedy")),
            t("s", "س", year = 2030, genres = listOf("drama"), type = TitleType.SERIES),
        )
        val movies = BrowseLogic.ofType(l, TitleType.MOVIE)
        assertEquals(listOf("b", "a"), keys(BrowseLogic.apply(movies, BrowseFilter("drama", BrowseSort.Year), plain)))
    }

    @Test
    fun `neighbor key prefers next then previous`() {
        assertEquals("c", BrowseLogic.neighborKey(listOf("a", "b", "c"), "b"))
        assertEquals("b", BrowseLogic.neighborKey(listOf("a", "b", "c"), "c"))
        assertNull(BrowseLogic.neighborKey(listOf("a"), "a"))
        assertNull(BrowseLogic.neighborKey(listOf("a"), "zz"))
    }
}
