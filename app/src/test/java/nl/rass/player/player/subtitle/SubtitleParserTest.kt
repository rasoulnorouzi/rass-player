package nl.rass.player.player.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleParserTest {

    // --- SRT ---

    @Test
    fun basicSrt() {
        val cues = SrtParser.parse(
            """
            1
            00:00:01,000 --> 00:00:02,500
            Hello
            world

            2
            00:00:03,000 --> 00:00:04,000
            سلام
            """.trimIndent(),
        )
        assertEquals(2, cues.size)
        assertEquals(1_000, cues[0].startMs)
        assertEquals(2_500, cues[0].endMs)
        assertEquals(listOf("Hello", "world"), cues[0].lines.map { it.text })
        assertEquals("سلام", cues[1].text)
    }

    @Test
    fun srtMissingBlankLinesAndBadNumbering() {
        val cues = SrtParser.parse(
            "7\n00:00:01,000 --> 00:00:02,000\nFirst\n8\n00:00:03,000 --> 00:00:04,000\nSecond\n" +
                "00:00:05,000 --> 00:00:06,000\nNo number at all\n\n\n\n42\n00:00:07,000 --> 00:00:08,000\nLast",
        )
        assertEquals(listOf("First", "Second", "No number at all", "Last"), cues.map { it.text })
    }

    @Test
    fun srtDotMillisShortFractionNoHoursAndExtraCoordinates() {
        val cues = SrtParser.parse(
            "1\n00:00:01.5 --> 00:00:02.25 X1:10 X2:100 Y1:10 Y2:50\nA\n\n" +
                "2\n01:02.000 --> 01:03,000\nB\n\n" +
                "3\n1:00:00,001-->1:00:01,000\nC",
        )
        assertEquals(1_500, cues[0].startMs)
        assertEquals(2_250, cues[0].endMs)
        assertEquals(62_000, cues[1].startMs)
        assertEquals(3_600_001, cues[2].startMs)
    }

    @Test
    fun srtSkipsInvalidAndEmptyCuesAndSortsByStart() {
        val cues = SrtParser.parse(
            "1\n00:00:05,000 --> 00:00:06,000\nLater\n\n" +
                "2\n00:00:04,000 --> 00:00:03,000\nNegative duration\n\n" +
                "3\n00:00:07,000 --> 00:00:08,000\n\n\n" +
                "4\n00:00:01,000 --> 00:00:02,000\nEarlier\n",
        )
        assertEquals(listOf("Earlier", "Later"), cues.map { it.text })
    }

    @Test
    fun cueTextThatIsANumberIsKept() {
        val cues = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000\n42\n\n2\n00:00:03,000 --> 00:00:04,000\nX")
        assertEquals("42", cues[0].text)
    }

    // --- Tags ---

    @Test
    fun srtTagsBecomeStyledSpans() {
        val cue = SrtParser.parse(
            "1\n00:00:01,000 --> 00:00:02,000\n<i>Italic <b>bold-italic</b></i> <u>under</u>\n" +
                "<font color=\"#FFFF00\">yellow</font> <font color=red>red</font>",
        ).single()
        val l0 = cue.lines[0].spans
        assertEquals(StyledSpan("Italic ", italic = true), l0[0])
        assertEquals(StyledSpan("bold-italic", bold = true, italic = true), l0[1])
        assertEquals(StyledSpan(" "), l0[2])
        assertEquals(StyledSpan("under", underline = true), l0[3])
        val l1 = cue.lines[1].spans
        assertEquals(StyledSpan("yellow", color = 0xFFFFFF00.toInt()), l1[0])
        assertEquals(StyledSpan("red", color = 0xFFFF0000.toInt()), l1[2])
    }

    @Test
    fun italicSpansAcrossLines() {
        val cue = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000\n<i>one\ntwo</i>").single()
        assertTrue(cue.lines.all { line -> line.spans.all { it.italic } })
    }

    @Test
    fun assTagsAndUnknownTagsAreStripped() {
        val cue = SrtParser.parse(
            "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}Top text {\\i1}here\n<span>kept</span> a < b &amp; c",
        ).single()
        assertEquals(listOf("Top text here", "kept a < b & c"), cue.lines.map { it.text })
    }

    @Test
    fun lineWithOnlyTagsIsDropped() {
        val cue = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000\n<i></i>\nText").single()
        assertEquals(listOf("Text"), cue.lines.map { it.text })
    }

    @Test
    fun colorParsing() {
        assertEquals(0xFFFF0000.toInt(), CueTextParser.parseColor("#f00"))
        assertEquals(0xFF00FFFF.toInt(), CueTextParser.parseColor("00ffff"))
        assertEquals(0x80FFFFFF.toInt(), CueTextParser.parseColor("#80FFFFFF"))
        assertNull(CueTextParser.parseColor("#zzz"))
    }

    // --- WebVTT ---

    @Test
    fun vttWithHeaderNotesStyleAndIdentifiers() {
        val cues = VttParser.parse(
            """
            WEBVTT - Persian subtitles
            Kind: captions
            Language: fa

            STYLE
            ::cue { color: white; }

            NOTE This is a comment
            that spans two lines

            intro
            00:01.000 --> 00:02.000 align:start position:10% line:0
            <v Narrator>Hello <c.yellow>there</c></v>

            NOTE
            another note

            00:00:03.000 --> 00:00:04.500
            <i>Second</i> <00:00:03.500>karaoke

            region-ish id
            00:00:05.000 --> 00:00:06.000
            Third
            """.trimIndent(),
        )
        assertEquals(3, cues.size)
        assertEquals(1_000, cues[0].startMs)
        assertEquals("Hello there", cues[0].text)
        assertEquals(StyledSpan("there", color = 0xFFFFFF00.toInt()), cues[0].lines[0].spans[1])
        assertEquals(4_500, cues[1].endMs)
        assertEquals("Second karaoke", cues[1].text)
        assertEquals("Third", cues[2].text)
    }

    @Test
    fun vttCueIdentifierIsNotPartOfPreviousCue() {
        val cues = VttParser.parse("WEBVTT\n\n00:01.000 --> 00:02.000\nA\n\ncue-2\n00:03.000 --> 00:04.000\nB\n")
        assertEquals(listOf("A", "B"), cues.map { it.text })
    }

    @Test
    fun formatDetection() {
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.detect(text = "\uFEFFWEBVTT\n\n"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.detect(url = "https://x.test/a.vtt?token=1"))
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.detect(url = "https://x.test/a.SRT"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.detect(url = "https://x.test/sub", contentType = "text/vtt"))
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.detect(url = "https://x.test/sub"))
    }
}
