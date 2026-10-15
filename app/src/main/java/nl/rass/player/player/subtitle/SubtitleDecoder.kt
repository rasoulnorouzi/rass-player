package nl.rass.player.player.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Turns raw subtitle bytes into text (R6.4).
 *
 * Order: byte-order mark → valid encoding hint → BOM-less UTF-16 → strict UTF-8 → windows-1256 vs
 * windows-1252 by heuristic. Line endings are normalized to `\n`, and a leading U+FEFF is removed.
 */
object SubtitleDecoder {

    private val UTF_16LE: Charset = Charsets.UTF_16LE
    private val UTF_16BE: Charset = Charsets.UTF_16BE

    /** Persian SRT files without a BOM are almost always windows-1256 (Arabic). */
    val WINDOWS_1256: Charset? = charsetOrNull("windows-1256")

    /** Western fallback. ISO-8859-1 is always available. */
    val WINDOWS_1252: Charset = charsetOrNull("windows-1252") ?: Charsets.ISO_8859_1

    /** Share of non-ASCII characters that must be Arabic-script letters to pick windows-1256. */
    private const val ARABIC_RATIO_THRESHOLD = 0.5

    fun decode(bytes: ByteArray, encodingHint: String? = null): String {
        val (charset, skip) = detect(bytes, encodingHint)
        val text = String(bytes, skip, bytes.size - skip, charset)
        return normalize(if (charset == WINDOWS_1256) persianLetters(text) else text)
    }

    /** Returns the charset to use and how many leading BOM bytes to skip. */
    fun detect(bytes: ByteArray, encodingHint: String? = null): Pair<Charset, Int> {
        bomCharset(bytes)?.let { return it }

        val hinted = encodingHint?.trim()?.takeIf { it.isNotEmpty() }?.let(::charsetOrNull)
        if (hinted != null) {
            // A UTF-8 hint is only trusted when the bytes really are UTF-8 (feeds often claim it wrongly).
            if (hinted != Charsets.UTF_8 || isStrictUtf8(bytes)) return hinted to 0
        }

        utf16WithoutBom(bytes)?.let { return it to 0 }
        if (isStrictUtf8(bytes)) return Charsets.UTF_8 to 0
        return legacyCharset(bytes) to 0
    }

    private fun bomCharset(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> UTF_16LE to 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> UTF_16BE to 2
        else -> null
    }

    /** Subtitle text is mostly ASCII digits/punctuation, so BOM-less UTF-16 shows many NUL bytes on one side. */
    private fun utf16WithoutBom(b: ByteArray): Charset? {
        val n = minOf(b.size, 4096) and 1.inv()
        if (n < 8) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until n) {
            if (b[i] == 0.toByte()) if (i % 2 == 0) evenZeros++ else oddZeros++
        }
        val half = n / 2
        return when {
            oddZeros > half * 0.3 && evenZeros < half * 0.05 -> UTF_16LE
            evenZeros > half * 0.3 && oddZeros < half * 0.05 -> UTF_16BE
            else -> null
        }
    }

    fun isStrictUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /**
     * Decodes the non-ASCII bytes as windows-1256 and counts how many become Arabic-script letters.
     * Persian text is nearly all Arabic letters; Western text decoded as 1256 mostly yields Latin
     * accented letters (1256 keeps é, è, à, ç… at their Latin-1 positions) or symbols.
     */
    private fun legacyCharset(bytes: ByteArray): Charset {
        val cp1256 = WINDOWS_1256 ?: return WINDOWS_1252
        val high = bytes.filter { it < 0 }.toByteArray()
        if (high.isEmpty()) return WINDOWS_1252
        val decoded = String(high, cp1256)
        val arabic = decoded.count(::isArabicScriptLetter)
        return if (arabic.toDouble() / decoded.length >= ARABIC_RATIO_THRESHOLD) cp1256 else WINDOWS_1252
    }

    private fun isArabicScriptLetter(c: Char): Boolean =
        c in '\u0600'..'\u06FF' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF' ||
            c == '\u200C' || c == '\u200F' // ZWNJ and RLM are mapped in windows-1256 and common in Persian

    /**
     * windows-1256 has no Persian yeh/kaf, so legacy Persian files use the Arabic letters, which render with
     * the wrong final forms. Only fa/en subtitles are offered (R5.4), so mapping them is safe.
     */
    private fun persianLetters(text: String): String =
        text.replace('\u064A', '\u06CC').replace('\u0643', '\u06A9')

    fun normalize(text: String): String =
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

    private fun charsetOrNull(name: String): Charset? = try {
        if (Charset.isSupported(name)) Charset.forName(name) else null
    } catch (_: IllegalArgumentException) {
        null
    }
}
