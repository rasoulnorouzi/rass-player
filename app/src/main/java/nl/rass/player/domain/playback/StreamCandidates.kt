package nl.rass.player.domain.playback

import nl.rass.player.domain.Markers
import nl.rass.player.domain.Quality
import nl.rass.player.domain.SourcedStream
import nl.rass.player.domain.Stream
import nl.rass.player.domain.VideoCodec

/** User's preferred quality `Q` (§7.2). */
sealed interface QualityPreference {
    data object Auto : QualityPreference
    data class Height(val px: Int) : QualityPreference

    companion object {
        /** "auto" or "720p" style (the Settings value). Unknown → Auto. */
        fun parse(v: String?): QualityPreference = when (val q = Quality.parse(v)) {
            is Quality.Height -> Height(q.px)
            else -> Auto
        }
    }
}

/** One entry of the candidate list (§7.2). */
data class StreamCandidate(
    val sourceId: String,
    val sourceName: String,
    val rank: Int,
    val stream: Stream,
    val markers: Markers?,
) {
    val url: String get() = stream.url

    /** Source-menu label: `label`, else the URL's host (§7.1). */
    val displayLabel: String
        get() = stream.label ?: runCatching { java.net.URI(stream.url).host }.getOrNull() ?: sourceName
}

/** Builds the ordered candidate list of §7.2 from every source's streams of one movie/episode. */
object StreamCandidates {

    /**
     * @param displayMaxHeight `D`, the display's max video height (1080 on the user's TV).
     * @param isHardwareDecodable whether the device decodes this codec in hardware.
     */
    fun order(
        streams: List<SourcedStream>,
        preference: QualityPreference = QualityPreference.Auto,
        displayMaxHeight: Int = 1080,
        isHardwareDecodable: (VideoCodec) -> Boolean = { true },
    ): List<StreamCandidate> {
        val sorted = streams.sortedWith(
            compareBy<SourcedStream> { s -> s.stream.videoCodec?.let { if (isHardwareDecodable(it)) 0 else 1 } ?: 0 }
                .thenBy { it.rank }
                .thenBy { it.stream.priority }
                .thenComparator { a, b -> compareScores(score(a.stream.quality, preference, displayMaxHeight), score(b.stream.quality, preference, displayMaxHeight)) }
                .thenBy { it.stream.index }
        )
        val seen = HashSet<String>()
        return sorted.filter { seen.add(it.stream.url) }
            .map { StreamCandidate(it.sourceId, it.sourceName, it.rank, it.stream, it.markers) }
    }

    /** Lexicographic (group, value) score; lower sorts first (§7.2 "Quality score"). */
    internal fun score(q: Quality, pref: QualityPreference, d: Int): Pair<Int, Int> = when (pref) {
        QualityPreference.Auto -> when (q) {
            Quality.Auto -> 0 to 0
            is Quality.Height -> if (q.px <= d) 1 to -q.px else 2 to q.px
            Quality.Unknown -> 3 to 0
        }
        is QualityPreference.Height -> when (q) {
            is Quality.Height -> when {
                q.px == pref.px -> 0 to 0
                q.px < pref.px -> 2 to -q.px
                else -> 3 to q.px
            }
            Quality.Auto -> 1 to 0
            Quality.Unknown -> 4 to 0
        }
    }

    private fun compareScores(a: Pair<Int, Int>, b: Pair<Int, Int>): Int =
        if (a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second)
}
