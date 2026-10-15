package nl.rass.player.player.subtitle

/**
 * Parsed subtitle model. Pure Kotlin (no Android/Compose types) so it can be unit-tested on the JVM.
 *
 * Subtitles are NOT rendered by Media3's text renderer: we parse them ourselves and draw them with
 * [SubtitleOverlay] (see MEMORY.md, Decisions).
 */

/** A run of text with one style. [color] is ARGB, or null to use the user's chosen text color. */
data class StyledSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Int? = null,
)

/** One visual line of a cue. Each line is laid out separately so its bidi direction comes from its own content. */
data class StyledLine(val spans: List<StyledSpan>) {
    val text: String get() = spans.joinToString("") { it.text }

    companion object {
        fun plain(text: String) = StyledLine(listOf(StyledSpan(text)))
    }
}

/** A cue shown from [startMs] (inclusive) until [endMs] (exclusive). Cues may overlap. */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val lines: List<StyledLine>,
) {
    val text: String get() = lines.joinToString("\n") { it.text }
}

enum class SubtitleFormat {
    SRT,
    VTT;

    companion object {
        /**
         * Picks a format: content sniffing first (a `WEBVTT` header is decisive), then the URL extension,
         * then the Content-Type. Defaults to SRT, whose parser is the most tolerant.
         */
        fun detect(url: String? = null, contentType: String? = null, text: String? = null): SubtitleFormat {
            if (text != null && text.trimStart('\uFEFF', ' ', '\t', '\n', '\r').startsWith("WEBVTT")) return VTT
            val path = url?.substringBefore('#')?.substringBefore('?')?.lowercase()
            if (path != null) {
                if (path.endsWith(".vtt") || path.endsWith(".webvtt")) return VTT
                if (path.endsWith(".srt")) return SRT
            }
            if (contentType?.lowercase()?.contains("vtt") == true) return VTT
            return SRT
        }
    }
}
