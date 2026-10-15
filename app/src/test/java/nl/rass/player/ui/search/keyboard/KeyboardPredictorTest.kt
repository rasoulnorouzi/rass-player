package nl.rass.player.ui.search.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPredictorTest {
    private val z = ZWNJ

    @Test fun currentWord() {
        assertEquals("", KeyboardPredictor.currentWord(""))
        assertEquals("", KeyboardPredictor.currentWord("abc "))
        assertEquals("def", KeyboardPredictor.currentWord("abc def"))
        assertEquals("می${z}خ", KeyboardPredictor.currentWord("من می${z}خ"))
        assertEquals("می$z", KeyboardPredictor.currentWord("می$z"))
    }

    @Test fun completesCurrentWordAndKeepsHead() {
        val p = KeyboardPredictor.predict("فیلم گر", listOf("گرگ", "گربه", "سفر"))
        assertEquals(listOf("گرگ", "گربه"), p.map { it.label })
        assertEquals("فیلم گرگ ", p.first().result)
    }

    @Test fun zwnjIsIgnoredWhenMatching() {
        val p = KeyboardPredictor.predict("می", listOf("می${z}خواهم", "میز"))
        assertTrue(p.any { it.label == "می${z}خواهم" })
        assertEquals(1, KeyboardPredictor.predict("میخ", listOf("می${z}خواهم")).size)
    }

    @Test fun rankingRecentsFirstThenShorter() {
        val p = KeyboardPredictor.predict("ab", listOf("abcdef", "abc"), recents = listOf("abxyz film"))
        assertEquals(listOf("abxyz", "abc", "abcdef"), p.map { it.label })
    }

    @Test fun maxSixNoDuplicatesNoExactMatch() {
        val vocab = (1..10).map { "ab$it" } + "ab1" + "ab"
        val p = KeyboardPredictor.predict("ab", vocab)
        assertEquals(6, p.size)
        assertEquals(p.size, p.map { it.label }.toSet().size)
        assertTrue(p.none { it.label == "ab" })
        assertTrue(KeyboardPredictor.predict("", vocab).isEmpty())
    }

    @Test fun englishIsCaseInsensitive() {
        assertEquals("Matrix ", KeyboardPredictor.predict("mat", listOf("Matrix")).single().result)
    }

    @Test fun vocabularyCountsAndSplits() {
        val v = KeyboardPredictor.vocabulary(listOf("The Dark Knight", "Dark City", "a"))
        assertEquals("Dark", v.first())
        assertTrue("Knight" in v && "a" !in v)
    }

    @Test fun urlMode() {
        val empty = KeyboardPredictor.predict("", emptyList(), urlMode = true)
        assertEquals(listOf("https://", "www."), empty.map { it.label })
        assertEquals("https://", KeyboardPredictor.predict("htt", emptyList(), urlMode = true).first().result)
        val w = KeyboardPredictor.predict("https://site", emptyList(), urlMode = true)
        assertEquals(listOf(".com", ".ir", ".nl", "/"), w.map { it.label })
        assertEquals("https://site.ir", w[1].result)
        val d = KeyboardPredictor.predict("https://my", emptyList(), urlMode = true, domains = listOf("myhost.nl"))
        assertEquals("https://myhost.nl", d.first().result)
        assertTrue(KeyboardPredictor.predict("a.com", emptyList(), urlMode = true).none { it.label == ".ir" })
    }

    @Test fun domainOf() {
        assertEquals("example.nl", KeyboardPredictor.domainOf("https://www.Example.nl/feed.json?x=1"))
        assertEquals("a.example.com", KeyboardPredictor.domainOf("http://a.example.com:8080/x"))
        assertNull(KeyboardPredictor.domainOf("nonsense"))
    }
}
