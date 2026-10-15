package nl.rass.player.player.subtitle

/**
 * SRT and WebVTT parsing into [SubtitleCue]s.
 *
 * Both parsers are timing-line driven, which makes them tolerant of real-world files: wrong or missing
 * cue numbers, missing blank lines between cues, comma or dot before milliseconds, 1–3 ms digits,
 * missing hours. Cue text ends at the first blank line or at the next timing line. VTT cue settings and
 * SRT coordinates are ignored (we position subtitles ourselves).
 */
object SubtitleParser {
    fun parse(text: String, format: SubtitleFormat): List<SubtitleCue> = when (format) {
        SubtitleFormat.SRT -> SrtParser.parse(text)
        SubtitleFormat.VTT -> VttParser.parse(text)
    }
}

object SrtParser {
    fun parse(text: String): List<SubtitleCue> =
        CueBlocks.parse(SubtitleDecoder.normalize(text).split('\n'))
}

object VttParser {
    private val BLOCK_KEYWORDS = listOf("NOTE", "STYLE", "REGION")

    fun parse(text: String): List<SubtitleCue> {
        val lines = SubtitleDecoder.normalize(text).split('\n')
        val kept = ArrayList<String>(lines.size)
        var i = 0
        // Header: "WEBVTT" plus any header lines up to the first blank line.
        if (lines.isNotEmpty() && lines[0].trimStart().startsWith("WEBVTT")) {
            while (i < lines.size && lines[i].isNotBlank()) i++
        }
        while (i < lines.size) {
            val line = lines[i]
            val prevBlank = kept.isEmpty() || kept.last().isBlank()
            if (prevBlank && isBlockKeyword(line)) {
                // NOTE / STYLE / REGION block: drop it up to the next blank line.
                while (i < lines.size && lines[i].isNotBlank()) i++
                continue
            }
            kept += line
            i++
        }
        return CueBlocks.parse(kept)
    }

    private fun isBlockKeyword(line: String): Boolean {
        val t = line.trimEnd()
        return BLOCK_KEYWORDS.any { t == it || t.startsWith("$it ") || t.startsWith("$it\t") }
    }
}

internal object CueBlocks {
    private const val TS = """(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[,.](\d{1,3}))?"""
    private val TIMING = Regex("""^\s*$TS\s*-->\s*$TS""")
    private val DIGITS = Regex("""^\s*\d+\s*$""")

    fun parse(lines: List<String>): List<SubtitleCue> {
        val timingIdx = lines.indices.filter { TIMING.containsMatchIn(lines[it]) }
        val cues = ArrayList<SubtitleCue>(timingIdx.size)
        for ((n, ti) in timingIdx.withIndex()) {
            val m = TIMING.find(lines[ti]) ?: continue
            val g = m.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])
            if (end <= start) continue

            val nextTiming = if (n + 1 < timingIdx.size) timingIdx[n + 1] else lines.size
            val body = cueBody(lines, ti + 1, nextTiming)
            val styled = CueTextParser.parse(body)
            if (styled.isNotEmpty()) cues += SubtitleCue(start, end, styled)
        }
        // Stable sort keeps file order for cues with equal start times.
        return cues.sortedBy { it.startMs }
    }

    /** Text lines of a cue: skip leading blanks, stop at the first blank line or the next timing line. */
    private fun cueBody(lines: List<String>, from: Int, until: Int): List<String> {
        var i = from
        while (i < until && lines[i].isBlank()) i++
        val skippedBlank = i > from
        val body = ArrayList<String>()
        var endedByBlank = false
        while (i < until) {
            if (lines[i].isBlank()) {
                endedByBlank = true
                break
            }
            body += lines[i]
            i++
        }
        // Missing blank line before the next cue: its number is glued to the end of this cue's text.
        // An empty cue followed by blank lines likewise leaves only the next cue's number.
        val gluedIndex = body.size >= 2 || (body.size == 1 && skippedBlank)
        if (!endedByBlank && until < lines.size && gluedIndex && DIGITS.matches(body.last())) {
            body.removeAt(body.lastIndex)
        }
        return body
    }

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val ms = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').toLong()
        return ((h.toLongOrNull() ?: 0) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + ms
    }
}

/**
 * Turns cue text with `<i> <b> <u> <font color>` (SRT) and `<c.color> <v> <lang> <ruby>` (VTT) tags into
 * [StyledLine]s. Unknown tags are dropped but their text kept. ASS override blocks like `{\an8}` are removed.
 * Tag state carries across lines, since `<i>` often spans a whole two-line cue.
 */
internal object CueTextParser {
    private val ASS_BLOCK = Regex("""\{\\[^}]*\}""")
    private val TOKEN = Regex("""<(/?)([a-zA-Z]+|\d)([^>]*)>""")
    private val FONT_COLOR = Regex("""color\s*=\s*["']?\s*([#\w]+)""", RegexOption.IGNORE_CASE)

    private class State {
        var bold = 0
        var italic = 0
        var underline = 0
        val colors = ArrayList<Int?>() // font/c color stack; null entries keep push/pop balanced
        val tagStack = ArrayList<String>() // which tag pushed onto colors
        fun color(): Int? = colors.lastOrNull { it != null }
    }

    fun parse(rawLines: List<String>): List<StyledLine> {
        val state = State()
        val out = ArrayList<StyledLine>()
        val text = rawLines.joinToString("\n").replace("\\N", "\n").replace("\\n", "\n")
        for (raw in text.split('\n')) {
            val line = parseLine(ASS_BLOCK.replace(raw, ""), state)
            if (line.text.isNotBlank()) out += line
        }
        return out
    }

    private fun parseLine(line: String, st: State): StyledLine {
        val spans = ArrayList<StyledSpan>()
        var pos = 0
        fun emit(chunk: String) {
            if (chunk.isEmpty()) return
            val txt = decodeEntities(chunk)
            val span = StyledSpan(txt, st.bold > 0, st.italic > 0, st.underline > 0, st.color())
            val last = spans.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) {
                spans[spans.lastIndex] = last.copy(text = last.text + txt)
            } else {
                spans += span
            }
        }
        for (m in TOKEN.findAll(line)) {
            emit(line.substring(pos, m.range.first))
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val rest = m.groupValues[3]
            when (name) {
                "b" -> st.bold = (st.bold + if (closing) -1 else 1).coerceAtLeast(0)
                "i" -> st.italic = (st.italic + if (closing) -1 else 1).coerceAtLeast(0)
                "u" -> st.underline = (st.underline + if (closing) -1 else 1).coerceAtLeast(0)
                "font", "c" -> if (closing) pop(st, name) else {
                    val color = if (name == "font") {
                        FONT_COLOR.find(rest)?.groupValues?.get(1)?.let(::parseColor)
                    } else {
                        // <c.yellow.bg_black>: first class that is a known color name wins.
                        rest.split('.').firstNotNullOfOrNull { NAMED_COLORS[it.trim().lowercase()] }
                    }
                    st.colors += color
                    st.tagStack += name
                }
                else -> Unit // <v>, <lang>, <ruby>, <rt>, timestamps, unknown: drop the tag, keep the text
            }
        }
        emit(line.substring(pos))
        // Trim outer whitespace of the line while keeping the span structure.
        if (spans.isNotEmpty()) {
            spans[0] = spans[0].copy(text = spans[0].text.trimStart())
            spans[spans.lastIndex] = spans.last().copy(text = spans.last().text.trimEnd())
        }
        return StyledLine(spans.filter { it.text.isNotEmpty() })
    }

    private fun pop(st: State, name: String) {
        val idx = st.tagStack.lastIndexOf(name)
        if (idx < 0) return
        st.tagStack.removeAt(idx)
        st.colors.removeAt(idx)
    }

    private val NAMED_COLORS: Map<String, Int> = mapOf(
        "white" to 0xFFFFFFFF.toInt(),
        "black" to 0xFF000000.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "lime" to 0xFF00FF00.toInt(),
        "green" to 0xFF00FF00.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
        "aqua" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "fuchsia" to 0xFFFF00FF.toInt(),
        "orange" to 0xFFFFA500.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
        "silver" to 0xFFC0C0C0.toInt(),
    )

    /** `#RGB`, `#RRGGBB`, `#AARRGGBB`, bare hex, or a named color. Returns ARGB or null. */
    internal fun parseColor(value: String): Int? {
        val v = value.trim().lowercase()
        NAMED_COLORS[v]?.let { return it }
        val hex = v.removePrefix("#")
        if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return when (hex.length) {
            3 -> (0xFF000000 or hex.map { "$it$it" }.joinToString("").toLong(16)).toInt()
            6 -> (0xFF000000 or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> null
        }
    }

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", "\u00A0")
            .replace("&lrm;", "\u200E").replace("&rlm;", "\u200F").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&")
    }
}
