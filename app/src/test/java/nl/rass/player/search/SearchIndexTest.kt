package nl.rass.player.search

import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchIndexTest {
    private fun doc(
        id: String, vararg titles: String, src: Set<String> = setOf("a"), year: Int? = null,
        extra: List<String> = emptyList(), pop: Double = 0.0,
    ) = SearchDocument(id, src, titles.toList(), year, extra, pop)

    private val catalog = listOf(
        doc("matrix", "ماتریکس", "The Matrix", "The Matrix", year = 1999, pop = 9.0, src = setOf("a", "b")),
        doc("matrix2", "ماتریکس ریلودد", "The Matrix Reloaded", year = 2003, pop = 7.0),
        doc("inception", "تلقین", "Inception", year = 2010, pop = 9.5, src = setOf("b")),
        doc("gladiator", "گلادیاتور", "Gladiator", year = 2000, pop = 8.0),
        doc("lion", "شیر شاه", "The Lion King", year = 1994, pop = 8.5),
        doc("want", "می‌خواهم زندگی کنم", "I Want to Live", year = 1958, pop = 1.0),
        doc("mat", "متریال", "Material Girls", year = 2006, pop = 0.5),
        doc("mat2", "Mat", year = 2001, pop = 0.1),
    )
    private val idx = SearchIndex(catalog)
    private fun ids(q: String, f: String? = null, limit: Int = 20) = idx.search(q, f, limit).map { it.documentId }

    @Test fun exactBeatsPrefixBeatsTokenPrefix() {
        val r = idx.search("the matrix")
        assertEquals("matrix", r[0].documentId)
        assertEquals(MatchType.EXACT_TITLE, r[0].matchType)
        assertEquals("matrix2", r[1].documentId)
        assertEquals(MatchType.TITLE_PREFIX, r[1].matchType)
    }

    @Test fun tierOrderAcrossAllTypes() {
        val d = listOf(
            doc("sub", "Xxmatxx", pop = 100.0),
            doc("tok", "The Mat Man", pop = 50.0),
            doc("pre", "Matter", pop = 10.0),
            doc("exa", "Mat", pop = 0.0),
            doc("fuz", "Maat", pop = 200.0),
        )
        val r = SearchIndex(d).search("mat")
        assertEquals(listOf("exa", "pre", "tok", "sub"), r.take(4).map { it.documentId }.filter { it != "fuz" })
        assertEquals(
            listOf(MatchType.EXACT_TITLE, MatchType.TITLE_PREFIX, MatchType.TOKEN_PREFIX, MatchType.SUBSTRING),
            r.filter { it.documentId != "fuz" }.map { it.matchType },
        )
    }

    @Test fun fuzzyRankedBelowTokenPrefixAboveSubstring() {
        val d = listOf(
            doc("sub", "zzmatrixzz", pop = 100.0),
            doc("fuz", "Matrx Story", pop = 1.0),
            doc("tok", "A Matrix", pop = 0.0),
        )
        val r = SearchIndex(d).search("matrix")
        assertEquals(listOf("tok", "fuz", "sub"), r.map { it.documentId })
        assertEquals(MatchType.FUZZY, r[1].matchType)
    }

    @Test fun tiesByPopularityThenYearDesc() {
        val d = listOf(
            doc("a", "Alpha One", year = 2000, pop = 1.0),
            doc("b", "Alpha Two", year = 2010, pop = 1.0),
            doc("c", "Alpha Three", year = 1990, pop = 5.0),
            doc("d", "Alpha Four", year = null, pop = 1.0),
        )
        assertEquals(listOf("c", "b", "a", "d"), SearchIndex(d).search("alpha").map { it.documentId })
    }

    @Test fun persianQueryMatchesPersianTitle() = assertEquals("gladiator", ids("گلادیاتور").first())
    @Test fun latinQueryMatchesEnglishTitle() = assertEquals("inception", ids("incep").first())
    @Test fun arabicLettersMatchPersianTitles() = assertEquals("matrix", ids("مــاتريكس").first())
    @Test fun digitsMatchYears() {
        assertEquals("lion", ids("1994").first())
        assertEquals("lion", ids("۱۹۹۴").first())
    }
    @Test fun titlePlusYear() = assertEquals("matrix", ids("matrix 1999").first())
    @Test fun extraTermsSearchable() {
        val i = SearchIndex(listOf(doc("x", "Foo", extra = listOf("Keanu Reeves"))))
        assertEquals(listOf("x"), i.search("reeves").map { it.documentId })
    }

    @Test fun zwnjVariantsAllFindTitle() {
        for (q in listOf("می‌خواهم", "میخواهم", "می خواهم", "می‌خواهم زندگی")) {
            assertEquals("want", ids(q).firstOrNull(), "query '$q'")
        }
    }

    @Test fun englishTypos() {
        assertEquals("matrix", ids("matirx").first())   // transposition
        assertEquals("inception", ids("incepton").first()) // deletion
        assertEquals("gladiator", ids("gladiatr").first())
        assertEquals(MatchType.FUZZY, idx.search("incepton")[0].matchType)
    }

    @Test fun persianTypos() {
        assertEquals("gladiator", ids("گلادیتور").first())  // missing letter
        assertEquals("gladiator", ids("گلادیاتر").first())
        assertEquals("inception", ids("تلقن").first())
    }

    @Test fun shortTokensDoNotFuzzyMatch() {
        assertTrue(ids("lian").isNotEmpty() && ids("lian").first() == "lion") // 4 chars: 1 typo ok
        assertTrue(SearchIndex(listOf(doc("c", "Cat"))).search("cot").isEmpty()) // 3 chars: exact only
    }

    @Test fun twoTyposOnlyForLongTokens() {
        val i = SearchIndex(listOf(doc("g", "Gladiator")))
        assertEquals(listOf("g"), i.search("gladitor x".dropLast(2)).map { it.documentId }) // 2 edits, 8+ chars
        assertTrue(SearchIndex(listOf(doc("m", "Matrix"))).search("mtrxx").isEmpty())
    }

    @Test fun yearDigitsAreNotFuzzy() = assertTrue(idx.search("1995").isEmpty())

    @Test fun sourceFilter() {
        assertEquals(listOf("matrix"), ids("the matrix", "b").take(1))
        assertEquals(listOf("inception"), ids("inception", "b"))
        assertTrue(ids("inception", "a").isEmpty())
        assertTrue(ids("matrix", "zzz").isEmpty())
        val r = idx.search("the matrix", "a")[0]
        assertEquals(setOf("a", "b"), r.sourceIds)
    }

    @Test fun resultsCarryMatchedTitle() = assertEquals("The Matrix", idx.search("the matrix")[0].matchedTitle)
    @Test fun limitAndBlankQuery() {
        assertEquals(2, ids("the", limit = 2).size)
        assertTrue(ids("   ").isEmpty())
        assertTrue(ids("!!!").isEmpty())
        assertTrue(SearchIndex(emptyList()).search("x").isEmpty())
    }

    // ---- suggest ----

    @Test fun suggestReturnsDisplayTitlesRanked() {
        val s = idx.suggest("the ma")
        assertEquals(listOf("The Matrix", "The Matrix Reloaded"), s)
    }

    @Test fun suggestPersianKeepsDisplayForm() {
        assertEquals("می‌خواهم زندگی کنم", idx.suggest("ميخواهم").first())
    }

    @Test fun suggestDedupes() {
        val d = listOf(
            doc("1", "Heat", "Heat", pop = 1.0, src = setOf("a")),
            doc("2", "Heat", pop = 2.0, src = setOf("b")),
            doc("3", "HEAT!", pop = 0.5),
            doc("4", "Heathens", pop = 0.1),
        )
        assertEquals(listOf("Heat", "Heathens"), SearchIndex(d).suggest("hea"))
    }

    @Test fun suggestExactBeforePrefixBeforeTokenThenFuzzy() {
        val d = listOf(
            doc("1", "Matrix Revolutions", pop = 9.0),
            doc("2", "Matrix", pop = 0.0),
            doc("3", "Reloaded Matrix", pop = 99.0),
            doc("4", "Matriks", pop = 50.0),
        )
        assertEquals(listOf("Matrix", "Matrix Revolutions", "Reloaded Matrix", "Matriks"), SearchIndex(d).suggest("matrix"))
    }

    @Test fun suggestFuzzyFillsWhenFewPrefixHits() = assertEquals("Inception", idx.suggest("incepton").first())
    @Test fun suggestLimit() = assertEquals(3, idx.suggest("m", limit = 3).size)
    @Test fun suggestFilter() = assertEquals(listOf("Inception"), idx.suggest("i", sourceFilter = "b"))
    @Test fun suggestBlank() = assertTrue(idx.suggest("").isEmpty())

    // ---- performance ----

    @Test fun perf20kDocs() {
        val rnd = java.util.Random(42)
        val syl = listOf("ka", "ra", "mi", "to", "lan", "ver", "sol", "dex", "an", "ul", "bre", "qui", "no", "fa", "zen")
        val fa = listOf("می‌خواهم", "گلادیاتور", "ماتریکس", "شیر", "شاه", "دریا", "آسمان", "عشق", "جنگ", "سفر", "راز", "شب")
        fun word(): String = buildString { repeat(2 + rnd.nextInt(3)) { append(syl[rnd.nextInt(syl.size)]) } }
        val docs = List(20_000) { i ->
            val en = List(1 + rnd.nextInt(4)) { word() }.joinToString(" ")
            val faT = List(1 + rnd.nextInt(3)) { fa[rnd.nextInt(fa.size)] }.joinToString(" ")
            SearchDocument("d$i", setOf("s${i % 3}"), listOf(faT, en, en), 1950 + rnd.nextInt(75), emptyList(), rnd.nextDouble())
        }
        var index: SearchIndex? = null
        val buildMs = measureNanoTime { index = SearchIndex(docs) } / 1e6
        val ix = index!!
        val queries = listOf("k", "ka", "kara", "ver s", "ماتر", "می", "sol", "zzzz", "karamit", "kaxa")
        repeat(30) { queries.forEach { ix.suggest(it) } } // warm-up
        var worstSuggest = 0.0
        var worstSearch = 0.0
        for (q in queries) {
            val s = measureNanoTime { ix.suggest(q) } / 1e6
            val r = measureNanoTime { ix.search(q, limit = 50) } / 1e6
            if (s > worstSuggest) worstSuggest = s
            if (r > worstSearch) worstSearch = r
        }
        println("PERF build=%.1fms worstSuggest=%.2fms worstSearch=%.2fms".format(buildMs, worstSuggest, worstSearch))
        assertTrue(worstSuggest < 5.0 || System.getenv("CI") != null, "suggest too slow: $worstSuggest ms")
        assertTrue(buildMs < 3000, "build too slow: $buildMs ms")
    }
}

class RecentSearchesTest {
    @Test fun mostRecentFirstAndDedupe() {
        val r = RecentSearches().add("a").add("b").add("c").add("a")
        assertEquals(listOf("a", "c", "b"), r.items)
    }
    @Test fun dedupeIsNormalized() {
        val r = RecentSearches().add("ماتريكس").add("Matrix").add("ماتریکس")
        assertEquals(listOf("ماتریکس", "Matrix"), r.items)
    }
    @Test fun maxTen() {
        var r = RecentSearches()
        for (i in 1..15) r = r.add("q$i")
        assertEquals(10, r.items.size)
        assertEquals("q15", r.items.first())
        assertEquals("q6", r.items.last())
    }
    @Test fun blankIgnoredAndRemoveClear() {
        val r = RecentSearches().add("  ").add("x").add("y")
        assertEquals(listOf("y", "x"), r.items)
        assertEquals(listOf("y"), r.remove("X").items)
        assertTrue(r.clear().items.isEmpty())
    }
    @Test fun restoresPersistedListTrimmed() {
        assertEquals(2, RecentSearches(List(30) { "a" } + "b", max = 10).items.size)
    }
}
