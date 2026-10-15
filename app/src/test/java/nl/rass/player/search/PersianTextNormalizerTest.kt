package nl.rass.player.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersianTextNormalizerTest {
    private val n = PersianTextNormalizer()

    @Test fun arabicYehFoldsToPersian() = assertEquals(n.normalize("علی"), n.normalize("علي"))
    @Test fun alefMaksuraFolds() = assertEquals("موسی", n.normalize("موسى"))
    @Test fun arabicKafFolds() = assertEquals("کتاب", n.normalize("كتاب"))
    @Test fun tehMarbutaFolds() = assertEquals("رحمه", n.normalize("رحمة"))
    @Test fun hamzaAlefFolds() = assertEquals("احمد", n.normalize("أحمد"))
    @Test fun alefMaddaKeptByDefault() = assertEquals("آب", n.normalize("آب"))
    @Test fun alefMaddaFoldWhenConfigured() = assertEquals("اب", PersianTextNormalizer(true).normalize("آب"))

    @Test fun zwnjBecomesSpace() = assertEquals("می خواهم", n.normalize("می‌خواهم"))

    @Test fun zwnjVariantsAreEqualWhenCompacted() {
        val a = n.compact("می‌خواهم")
        assertEquals(a, n.compact("میخواهم"))
        assertEquals(a, n.compact("می خواهم"))
    }

    @Test fun otherZeroWidthRemoved() = assertEquals("ab", n.normalize("a​b‏﻿"))

    @Test fun digitsMapToAscii() {
        assertEquals("1994", n.normalize("۱۹۹۴"))
        assertEquals("1994", n.normalize("١٩٩٤"))
        assertEquals("2024 film", n.normalize("۲۰۲۴ Film"))
    }

    @Test fun diacriticsAndTatweelRemoved() {
        assertEquals("محمد", n.normalize("مُحَمَّد"))
        assertEquals("سلام", n.normalize("سلـــام"))
    }

    @Test fun punctuationStripped() {
        assertEquals("چرا", n.normalize("چرا؟"))
        assertEquals("a b", n.normalize("a, b!"))
        assertEquals("dont stop", n.normalize("Don't  Stop..."))
    }

    @Test fun whitespaceCollapsedAndTrimmed() = assertEquals("a b c", n.normalize("  a \t b\n\nc  "))
    @Test fun latinLowercaseAndAccents() = assertEquals("amelie", n.normalize("Amélie"))
    @Test fun emptyAndSymbolsOnly() { assertEquals("", n.normalize("")); assertEquals("", n.normalize(" ... ")) }
    @Test fun presentationFormsFold() = assertEquals("ب", n.normalize("ﺏ"))

    @Test fun idempotent() {
        val samples = listOf("ي ك ة أ إ آ", "می‌خواهم ۱۹۹۴ ـ", "Amélie, Don't!", "مُحَمَّد​", "  ", "ﺏﻛ")
        for (s in samples) {
            val once = n.normalize(s)
            assertEquals(once, n.normalize(once), "not idempotent for '$s'")
            assertTrue(!once.contains("  "))
        }
    }
}
