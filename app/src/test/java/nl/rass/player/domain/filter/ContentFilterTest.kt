package nl.rass.player.domain.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentFilterTest {
    private val on = ContentFilterConfig()
    private fun sensitive(s: FilterSubject, c: ContentFilterConfig = on) = ContentFilter.isSensitive(s, c)
    private fun text(vararg t: String) = FilterSubject(texts = t.toList())

    @Test fun `defaults`() {
        assertTrue(on.enabled); assertFalse(on.showOnHome); assertFalse(on.hideEverywhere)
        assertEquals(16, on.maxAge); assertFalse(on.hideUnrated)
        assertEquals(setOf("sexual", "nudity", "gore", "disturbing"), on.blockedWarnings)
    }

    @Test fun `warnings in the blocked set flag, others do not`() {
        assertTrue(sensitive(FilterSubject(warnings = listOf("nudity"))))
        assertTrue(sensitive(FilterSubject(warnings = listOf("violence", "gore"))))
        assertFalse(sensitive(FilterSubject(warnings = listOf("violence", "language"))))
        assertTrue(sensitive(FilterSubject(warnings = listOf("violence")), on.copy(blockedWarnings = setOf("violence"))))
        assertFalse(sensitive(FilterSubject(warnings = listOf("bogus"))))
    }

    @Test fun `rating to age mapping`() {
        val ages = mapOf("G" to 0, "TV-Y" to 0, "TV-G" to 0, "TV-Y7" to 7, "PG" to 8, "TV-PG" to 8, "PG-13" to 13,
            "TV-14" to 14, "R" to 17, "TV-MA" to 17, "NC-17" to 18, "age:12" to 12, "AGE:21" to 21, "pg-13" to 13)
        ages.forEach { (r, a) -> assertEquals(a, ContentRatings.ageOf(r), r) }
        assertNull(ContentRatings.ageOf("XYZ")); assertNull(ContentRatings.ageOf("age:")); assertNull(ContentRatings.ageOf(null))
        assertEquals("age:16", ContentRatings.canonical("AGE:16")); assertNull(ContentRatings.canonical("adult"))
    }

    @Test fun `rating above max age flags, incl TV and age`() {
        fun s(r: String) = FilterSubject(rating = r)
        assertFalse(sensitive(s("PG-13"))); assertFalse(sensitive(s("TV-14"))); assertFalse(sensitive(s("age:16")))
        assertTrue(sensitive(s("R"))); assertTrue(sensitive(s("TV-MA"))); assertTrue(sensitive(s("NC-17"))); assertTrue(sensitive(s("age:18")))
        assertTrue(sensitive(s("PG-13"), on.copy(maxAge = 8)))
        assertFalse(sensitive(s("NC-17"), on.copy(maxAge = ContentFilterConfig.MAX_AGE_NONE)))
    }

    @Test fun `blocked genres in en and fa`() {
        listOf("Erotic", "erotica", "Adult", "Sexploitation", "اروتیک", "بزرگسالان", " EROTIC ").forEach {
            assertTrue(sensitive(FilterSubject(genres = listOf(it))), it)
        }
        assertFalse(sensitive(FilterSubject(genres = listOf("Drama", "کمدی", "Adventure"))))
    }

    @Test fun `keywords match whole words only`() {
        listOf("A Sex Education", "sexual themes", "Erotic Tales", "full nudity", "an adult film", "Adult Films of the 60s", "exploitation film")
            .forEach { assertTrue(sensitive(text(it)), it) }
        listOf("Sussex", "Essex", "sextant", "Middlesex", "sextet", "Sextus Pompeius", "Unisex", "Sexton Blake", "adulthood", "exploitation of resources".let { "Dunes" })
            .forEach { assertFalse(sensitive(text(it)), it) }
        assertTrue(sensitive(text("The sex-hygiene film")))
        assertTrue(sensitive(text("sex.")))
    }

    @Test fun `persian keywords`() {
        listOf("فیلم سکسی", "صحنه‌های جنسی", "برهنه در باران", "اروتیک", "فیلم بزرگسالان").forEach { assertTrue(sensitive(text(it)), it) }
        assertFalse(sensitive(text("جنسیت و جامعه"))) // different word
        assertFalse(sensitive(text("ماجراجویی در جنگل")))
        // Arabic yeh/kaf variants are folded
        assertTrue(sensitive(text("فيلم سكسي")))
    }

    @Test fun `custom keywords, phrases and genres`() {
        val c = on.copy(customKeywords = listOf("zombie", "blood bath"))
        assertTrue(sensitive(text("Night of the ZOMBIE"), c))
        assertTrue(sensitive(text("A blood   bath at dawn"), c))
        assertFalse(sensitive(text("Zombieland"), c))
        assertTrue(sensitive(FilterSubject(genres = listOf("Zombie")), c))
        assertFalse(sensitive(text("Night of the zombie")))
    }

    @Test fun `hide unrated`() {
        val c = on.copy(hideUnrated = true)
        assertTrue(sensitive(FilterSubject(), c))
        assertFalse(sensitive(FilterSubject(rating = "G"), c))
        assertFalse(sensitive(FilterSubject()))
    }

    @Test fun `disabled filter flags nothing`() {
        val s = FilterSubject(rating = "NC-17", warnings = listOf("sexual"), genres = listOf("Erotic"), texts = listOf("porn"))
        assertTrue(sensitive(s))
        assertFalse(sensitive(s, on.copy(enabled = false)))
        assertNull(ContentFilter.reason(s, ContentFilterConfig.Off))
    }

    @Test fun `ordinary titles pass`() {
        assertFalse(sensitive(FilterSubject(rating = "G", genres = listOf("Animation"), texts = listOf("Big Buck Bunny", "A giant rabbit takes revenge."))))
    }
}
