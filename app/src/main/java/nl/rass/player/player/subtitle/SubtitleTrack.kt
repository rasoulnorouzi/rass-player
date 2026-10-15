package nl.rass.player.player.subtitle

/**
 * Immutable, parsed subtitle track with O(log n) lookup of the active cues at a playback position.
 *
 * At build time the timeline is cut into segments at every cue start and end, and each segment stores the
 * cues active in it (in start order). Overlapping cues are therefore handled without scanning. A lookup is a
 * binary search over the segment boundaries. The segment index changes only when the set of visible cues
 * changes, which is what [SubtitleOverlay] keys its recomposition on.
 *
 * **Offset convention:** a positive `offsetMs` shows subtitles later (delays them), a negative one shows them
 * earlier. The cue time looked up is `positionMs - offsetMs`.
 */
class SubtitleTrack(cues: List<SubtitleCue>) {

    val cues: List<SubtitleCue> = cues.filter { it.endMs > it.startMs }.sortedBy { it.startMs }

    /** Segment i covers [boundaries[i], boundaries[i + 1]). */
    private val boundaries: LongArray
    private val active: Array<List<SubtitleCue>>

    init {
        val points = LongArray(this.cues.size * 2)
        this.cues.forEachIndexed { i, c ->
            points[2 * i] = c.startMs
            points[2 * i + 1] = c.endMs
        }
        boundaries = points.distinct().sorted().toLongArray()
        val segCount = (boundaries.size - 1).coerceAtLeast(0)

        // Sweep: cues sorted by start; drop the ones that ended. Active list stays in start order.
        val segs = arrayOfNulls<List<SubtitleCue>>(segCount)
        val open = ArrayList<SubtitleCue>()
        var next = 0
        for (s in 0 until segCount) {
            val t = boundaries[s]
            while (next < this.cues.size && this.cues[next].startMs <= t) open += this.cues[next++]
            open.removeAll { it.endMs <= t }
            segs[s] = if (open.isEmpty()) emptyList() else open.toList()
        }
        @Suppress("UNCHECKED_CAST")
        active = segs as Array<List<SubtitleCue>>
    }

    val isEmpty: Boolean get() = cues.isEmpty()

    /** Index of the segment containing the shifted position, or -1 when no cue is visible there. */
    fun segmentIndexAt(positionMs: Long, offsetMs: Long = 0): Int {
        if (active.isEmpty()) return -1
        val t = positionMs - offsetMs
        if (t < boundaries[0] || t >= boundaries[boundaries.size - 1]) return -1
        // Last boundary <= t.
        var lo = 0
        var hi = boundaries.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (boundaries[mid] <= t) lo = mid else hi = mid - 1
        }
        return if (active[lo].isEmpty()) -1 else lo
    }

    /** Cues visible in segment [index] (from [segmentIndexAt]); empty for -1. */
    fun cuesInSegment(index: Int): List<SubtitleCue> =
        if (index < 0 || index >= active.size) emptyList() else active[index]

    /** All cues visible at [positionMs] shifted by [offsetMs], in start order. */
    fun cuesAt(positionMs: Long, offsetMs: Long = 0): List<SubtitleCue> =
        cuesInSegment(segmentIndexAt(positionMs, offsetMs))

    companion object {
        val EMPTY = SubtitleTrack(emptyList())

        fun parse(text: String, format: SubtitleFormat): SubtitleTrack =
            SubtitleTrack(SubtitleParser.parse(text, format))
    }
}
