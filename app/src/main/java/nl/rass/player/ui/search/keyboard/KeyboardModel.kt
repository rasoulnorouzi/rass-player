package nl.rass.player.ui.search.keyboard

import java.text.BreakIterator

const val ZWNJ = '‌'

enum class KeyboardLanguage {
    FA, EN;

    fun toggled(): KeyboardLanguage = if (this == FA) EN else FA
}

/** One key of the on-screen keyboard. */
sealed interface KbKey {
    /** Inserts [text]. */
    data class Char(val text: String) : KbKey
    data object Space : KbKey
    data object Zwnj : KbKey
    data object Backspace : KbKey
    data object Clear : KbKey
    data object Language : KbKey
    data object Mic : KbKey
}

object KeyboardLayouts {
    private fun chars(s: String): List<KbKey> = s.map { KbKey.Char(it.toString()) }

    private val faRows = listOf(
        chars("۱۲۳۴۵۶۷۸۹۰"),
        chars("ضصثقفغعهخحجچ"),
        chars("شسیبلاتنمکگ"),
        chars("ظطزرذدپوژآئ"),
    )
    private val enRows = listOf(
        chars("1234567890"),
        chars("qwertyuiop"),
        chars("asdfghjkl"),
        chars("zxcvbnm-'."),
    )

    /** Bottom row: language, ZWNJ (fa only), space, optional mic, backspace, clear. */
    private fun bottom(lang: KeyboardLanguage, mic: Boolean): List<KbKey> = buildList {
        add(KbKey.Language)
        if (lang == KeyboardLanguage.FA) add(KbKey.Zwnj)
        add(KbKey.Space)
        if (mic) add(KbKey.Mic)
        add(KbKey.Backspace)
        add(KbKey.Clear)
    }

    fun rows(lang: KeyboardLanguage, micAvailable: Boolean = false): List<List<KbKey>> =
        (if (lang == KeyboardLanguage.FA) faRows else enRows) + listOf(bottom(lang, micAvailable))
}

/** Pure text edits applied by the keyboard. The field is append-only (cursor is always at the end). */
object KeyboardEdit {
    /** Applies [key] to [text]; [Language]/[Mic] are not text edits and return [text] unchanged. */
    fun apply(text: String, key: KbKey): String = when (key) {
        is KbKey.Char -> text + key.text
        KbKey.Space -> if (text.isEmpty() || text.last() == ' ') text else text + ' '
        KbKey.Zwnj -> if (text.isEmpty() || text.last() == ZWNJ || text.last() == ' ') text else text + ZWNJ
        KbKey.Backspace -> backspace(text)
        KbKey.Clear -> ""
        KbKey.Language, KbKey.Mic -> text
    }

    /**
     * Removes the last user-perceived character: a lone trailing ZWNJ first (so «می‌» -> «می»), otherwise the
     * last grapheme cluster (surrogate pairs and letter + combining marks go together).
     */
    fun backspace(text: String): String {
        if (text.isEmpty()) return text
        if (text.last() == ZWNJ) return text.dropLast(1)
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val start = it.preceding(text.length)
        return if (start == BreakIterator.DONE || start < 0) "" else text.substring(0, start)
    }
}
