package nl.rass.player.player.subtitle

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleDecoderTest {

    private val persian = "1\n00:00:01,000 --> 00:00:02,000\nسلام دنیا! این یک زیرنویس فارسی است.\nچطوری؟ گربه و پژو\n"
    private val cp1256: Charset = Charset.forName("windows-1256")

    /** Legacy Persian files use Arabic yeh (U+064A) because windows-1256 has no Persian yeh. */
    private fun persianAsLegacy1256(text: String): ByteArray =
        text.replace('ی', 'ي').toByteArray(cp1256)

    @Test
    fun detectsWindows1256Persian() {
        val bytes = persianAsLegacy1256(persian)
        assertFalse(SubtitleDecoder.isStrictUtf8(bytes))
        assertEquals(cp1256, SubtitleDecoder.detect(bytes).first)
        assertEquals(persian, SubtitleDecoder.decode(bytes))
    }

    @Test
    fun windows1256WithCrLfIsNormalized() {
        val bytes = persianAsLegacy1256(persian.replace("\n", "\r\n"))
        assertEquals(persian, SubtitleDecoder.decode(bytes))
    }

    @Test
    fun westernLegacyTextIsWindows1252() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nCafé, crème brûlée, naïve – “quotes”\n"
        val bytes = text.toByteArray(Charset.forName("windows-1252"))
        assertEquals(text, SubtitleDecoder.decode(bytes))
    }

    @Test
    fun utf8WithoutBom() {
        assertEquals(persian, SubtitleDecoder.decode(persian.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun utf8WithBomIsStripped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + persian.toByteArray(Charsets.UTF_8)
        val decoded = SubtitleDecoder.decode(bytes)
        assertEquals(persian, decoded)
        assertTrue(decoded.first() == '1')
    }

    @Test
    fun utf16LittleAndBigEndianWithBom() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + persian.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + persian.toByteArray(Charsets.UTF_16BE)
        assertEquals(persian, SubtitleDecoder.decode(le))
        assertEquals(persian, SubtitleDecoder.decode(be))
    }

    @Test
    fun utf16WithoutBomIsDetected() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nHello world\n"
        assertEquals(text, SubtitleDecoder.decode(text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, SubtitleDecoder.decode(text.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun validHintOverridesHeuristics() {
        val text = "Ð¢ÐµÑÑ"
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        assertEquals(text, SubtitleDecoder.decode(bytes, encodingHint = "ISO-8859-1"))
    }

    @Test
    fun invalidOrWrongUtf8HintIsIgnored() {
        val bytes = persianAsLegacy1256(persian)
        assertEquals(persian, SubtitleDecoder.decode(bytes, encodingHint = "no-such-charset"))
        // Feed claims UTF-8 but the bytes are not valid UTF-8: fall back to detection.
        assertEquals(persian, SubtitleDecoder.decode(bytes, encodingHint = "utf-8"))
    }

    @Test
    fun asciiOnlyIsUtf8() {
        assertEquals(Charsets.UTF_8, SubtitleDecoder.detect("plain ascii\r\n".toByteArray()).first)
        assertEquals("a\nb\nc", SubtitleDecoder.decode("a\r\nb\rc".toByteArray()))
    }
}
