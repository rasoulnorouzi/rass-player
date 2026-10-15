package nl.rass.player.player.subtitle

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubtitleTrackTest {

    private fun cue(start: Long, end: Long, text: String) = SubtitleCue(start, end, listOf(StyledLine.plain(text)))

    private val track = SubtitleTrack(
        listOf(
            cue(1_000, 3_000, "A"),
            cue(2_000, 5_000, "B"), // overlaps A and C
            cue(4_000, 4_500, "C"),
            cue(10_000, 12_000, "D"),
        ),
    )

    private fun at(pos: Long, offset: Long = 0) = track.cuesAt(pos, offset).map { it.text }

    @Test
    fun lookupWithOverlaps() {
        assertEquals(emptyList(), at(0))
        assertEquals(emptyList(), at(999))
        assertEquals(listOf("A"), at(1_000)) // start inclusive
        assertEquals(listOf("A", "B"), at(2_500))
        assertEquals(listOf("B"), at(3_000)) // end exclusive
        assertEquals(listOf("B", "C"), at(4_200))
        assertEquals(listOf("B"), at(4_500))
        assertEquals(emptyList(), at(7_000)) // gap
        assertEquals(listOf("D"), at(11_999))
        assertEquals(emptyList(), at(12_000))
        assertEquals(emptyList(), at(Long.MAX_VALUE / 2))
    }

    @Test
    fun positiveOffsetDelaysSubtitles() {
        assertEquals(emptyList(), at(1_000, offset = 500))
        assertEquals(listOf("A"), at(1_500, offset = 500))
        assertEquals(listOf("D"), at(70_000, offset = 60_000))
    }

    @Test
    fun negativeOffsetShowsSubtitlesEarlier() {
        assertEquals(listOf("A"), at(500, offset = -500))
        assertEquals(listOf("D"), at(0, offset = -10_000))
        assertEquals(emptyList(), at(0, offset = -12_000))
    }

    @Test
    fun segmentIndexChangesOnlyWhenVisibleCuesChange() {
        assertEquals(track.segmentIndexAt(1_100), track.segmentIndexAt(1_900))
        assertNotEquals(track.segmentIndexAt(1_900), track.segmentIndexAt(2_100))
        assertEquals(-1, track.segmentIndexAt(6_000))
        assertEquals(emptyList(), track.cuesInSegment(-1))
    }

    @Test
    fun emptyTrack() {
        assertTrue(SubtitleTrack.EMPTY.isEmpty)
        assertEquals(-1, SubtitleTrack.EMPTY.segmentIndexAt(1_000))
        assertEquals(emptyList(), SubtitleTrack.EMPTY.cuesAt(1_000))
    }

    @Test
    fun largeTrackMatchesLinearScan() {
        val cues = (0 until 2_000).map { i -> cue(i * 1_000L, i * 1_000L + 1_500 + (i % 7) * 400, "c$i") }
        val big = SubtitleTrack(cues)
        for (t in 0L until 2_010_000L step 137) {
            val expected = cues.filter { t >= it.startMs && t < it.endMs }.map { it.text }
            assertEquals(expected, big.cuesAt(t).map { it.text }, "t=$t")
        }
    }

    @Test
    fun offsetRulesAndStore() = runTest {
        assertEquals(60_000, SubtitleOffset.normalize(90_000))
        assertEquals(-60_000, SubtitleOffset.normalize(-61_234))
        assertEquals(-200, SubtitleOffset.normalize(-249))
        assertEquals(-300, SubtitleOffset.step(-200, -SubtitleOffset.STEP_MS))

        val store = InMemorySubtitleOffsetStore()
        val k1 = SubtitleOffsetKey.of("title-1", "s1e2", "https://x.test/fa.srt")
        val k2 = SubtitleOffsetKey.of("title-1", "s1e2", "https://x.test/en.srt")
        assertNotEquals(k1.storageKey, k2.storageKey)
        store.set(k1, -1_234)
        assertEquals(-1_200, store.get(k1))
        assertEquals(0, store.get(k2))
        assertEquals(-1_200, store.observe(k1).first())
        store.set(k1, 0)
        assertEquals(0, store.get(k1))
    }
}
