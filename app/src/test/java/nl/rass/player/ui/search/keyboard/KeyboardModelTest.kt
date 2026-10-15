package nl.rass.player.ui.search.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardModelTest {
    @Test fun languageToggles() {
        assertEquals(KeyboardLanguage.EN, KeyboardLanguage.FA.toggled())
        assertEquals(KeyboardLanguage.FA, KeyboardLanguage.EN.toggled())
    }

    @Test fun persianLayoutHasSpecialLettersAndZwnj() {
        val keys = KeyboardLayouts.rows(KeyboardLanguage.FA).flatten()
        val text = keys.filterIsInstance<KbKey.Char>().joinToString("") { it.text }
        for (c in "پچژگیک") assertTrue("missing $c", c in text)
        assertTrue(KbKey.Zwnj in keys)
        assertTrue(KbKey.Language in keys)
        assertTrue(text.startsWith("۱۲۳"))
    }

    @Test fun englishLayoutIsQwertyWithoutZwnj() {
        val rows = KeyboardLayouts.rows(KeyboardLanguage.EN)
        assertEquals("qwertyuiop", rows[1].joinToString("") { (it as KbKey.Char).text })
        assertFalse(KbKey.Zwnj in rows.flatten())
    }

    @Test fun micOnlyWhenAvailable() {
        assertFalse(KbKey.Mic in KeyboardLayouts.rows(KeyboardLanguage.FA, false).flatten())
        assertTrue(KbKey.Mic in KeyboardLayouts.rows(KeyboardLanguage.FA, true).flatten())
    }

    @Test fun zwnjInsertion() {
        assertEquals("می" + ZWNJ, KeyboardEdit.apply("می", KbKey.Zwnj))
        assertEquals("می" + ZWNJ, KeyboardEdit.apply("می" + ZWNJ, KbKey.Zwnj))
        assertEquals("", KeyboardEdit.apply("", KbKey.Zwnj))
        assertEquals("a ", KeyboardEdit.apply("a ", KbKey.Zwnj))
    }

    @Test fun spaceNotDoubledOrLeading() {
        assertEquals("", KeyboardEdit.apply("", KbKey.Space))
        assertEquals("a ", KeyboardEdit.apply("a", KbKey.Space))
        assertEquals("a ", KeyboardEdit.apply("a ", KbKey.Space))
    }

    @Test fun backspaceRemovesZwnjAlone() {
        assertEquals("می", KeyboardEdit.backspace("می" + ZWNJ))
    }

    @Test fun backspaceRemovesGraphemeCluster() {
        assertEquals("ab", KeyboardEdit.backspace("abé"))
        assertEquals("ب", KeyboardEdit.backspace("بسَ")) // letter + fatha
        assertEquals("x", KeyboardEdit.backspace("x😀")) // surrogate pair
        assertEquals("", KeyboardEdit.backspace(""))
    }

    @Test fun charAndClear() {
        assertEquals("پچ", KeyboardEdit.apply("پ", KbKey.Char("چ")))
        assertEquals("", KeyboardEdit.apply("abc", KbKey.Clear))
        assertEquals("abc", KeyboardEdit.apply("abc", KbKey.Language))
    }
}
