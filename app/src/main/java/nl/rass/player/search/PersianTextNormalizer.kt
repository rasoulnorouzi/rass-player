package nl.rass.player.search

import java.text.Normalizer

/**
 * Pure-JVM text normalizer for Persian/English search.
 *
 * Rules (output is idempotent: `normalize(normalize(x)) == normalize(x)`):
 *  - Latin letters lowercased; Latin accents stripped (é -> e).
 *  - Arabic letter variants folded to Persian: ي ى ې -> ی, ك ڪ -> ک, ة ۀ ہ ھ -> ه, ؤ -> و, ئ -> ی,
 *    أ إ ٱ -> ا. آ is kept unless [foldAlefMadda] is true (then -> ا).
 *  - Arabic presentation forms are folded through NFKC.
 *  - Tashkeel / Quranic marks and tatweel (ـ) are removed.
 *  - ZWNJ (U+200C) becomes a space, so «می‌خواهم» == «می خواهم». Other zero-width / bidi control
 *    characters are removed. Use [compact] to also ignore spaces («میخواهم»).
 *  - Persian (۰-۹) and Arabic-Indic (٠-٩) digits -> ASCII digits.
 *  - Apostrophes are removed; any other non letter/digit becomes a space; whitespace is collapsed and trimmed.
 */
class PersianTextNormalizer(val foldAlefMadda: Boolean = false) {

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        fun emit(c: Char) {
            if (c == ' ') {
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) { sb.append(' '); pendingSpace = false }
                sb.append(c)
            }
        }
        appendNormalized(text, ::emit)
        return sb.toString()
    }

    /** Normalized form with all spaces removed; used for space-insensitive prefix matching. */
    fun compact(text: String): String = normalize(text).replace(" ", "")

    fun tokens(text: String): List<String> {
        val n = normalize(text)
        return if (n.isEmpty()) emptyList() else n.split(' ')
    }

    private fun appendNormalized(text: String, emit: (Char) -> Unit) {
        for (ch in text) mapChar(ch, emit)
    }

    private fun mapChar(ch: Char, emit: (Char) -> Unit) {
        val c = ch.code
        when {
            ch == '‌' -> emit(' ')
            c in 0x200B..0x200F || c in 0x202A..0x202E || c in 0x2060..0x2064 ||
                c in 0x2066..0x2069 || c == 0xFEFF || c == 0x00AD -> Unit
            c == 0x0640 -> Unit // tatweel
            c in 0x064B..0x065F || c == 0x0670 || c in 0x06D6..0x06DC || c in 0x06DF..0x06E8 ||
                c in 0x06EA..0x06ED || c in 0x0300..0x036F -> Unit // diacritics
            c in 0x06F0..0x06F9 -> emit('0' + (c - 0x06F0))
            c in 0x0660..0x0669 -> emit('0' + (c - 0x0660))
            c == 0x064A || c == 0x0649 || c == 0x06D0 || c == 0x06CC || c == 0x06D2 -> emit('ی')
            c == 0x0643 || c == 0x06AA -> emit('ک')
            c == 0x0629 || c == 0x06C0 || c == 0x06C1 || c == 0x06BE || c == 0x06D5 -> emit('ه')
            c == 0x0624 -> emit('و')
            c == 0x0626 -> emit('ی')
            c == 0x0623 || c == 0x0625 || c == 0x0671 -> emit('ا')
            c == 0x0622 -> emit(if (foldAlefMadda) 'ا' else 'آ')
            ch == '\'' || ch == '’' || ch == '‘' || ch == '`' -> Unit
            c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFE -> {
                val folded = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC)
                if (folded != ch.toString()) folded.forEach { mapChar(it, emit) } else emit(' ')
            }
            c in 0xC0..0x24F -> {
                val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                for (x in d) {
                    if (x.code in 0x0300..0x036F) continue
                    if (x.isLetterOrDigit()) emit(x.lowercaseChar()) else emit(' ')
                }
            }
            ch.isLetterOrDigit() -> emit(ch.lowercaseChar())
            else -> emit(' ')
        }
    }

    companion object {
        val Default = PersianTextNormalizer()
    }
}
