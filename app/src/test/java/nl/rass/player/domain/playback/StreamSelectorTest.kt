package nl.rass.player.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import nl.rass.player.domain.MediaType
import nl.rass.player.domain.Quality
import nl.rass.player.domain.SourcedStream
import nl.rass.player.domain.Stream
import nl.rass.player.domain.VideoCodec

class StreamSelectorTest {

    private fun s(
        url: String, rank: Int = 0, q: Quality = Quality.Height(720), p: Int = 0, i: Int = 0,
        codec: VideoCodec? = null, label: String? = null,
    ) = SourcedStream("src$rank", "Source $rank", rank,
        Stream(url, if (q == Quality.Auto) MediaType.HLS else MediaType.MP4, q, p, label, videoCodec = codec, index = i), null)

    @Test
    fun `ordering keys - decodable, rank, priority, quality, index - and URL dedupe`() {
        val list = listOf(
            s("r1-a", rank = 1, q = Quality.Auto),
            s("r0-p1", rank = 0, p = 1, q = Quality.Auto),
            s("r0-720b", rank = 0, i = 3),
            s("r0-720a", rank = 0, i = 2),
            s("r0-auto", rank = 0, q = Quality.Auto, i = 5),
            s("r0-1080", rank = 0, q = Quality.Height(1080), i = 1),
            s("r0-2160", rank = 0, q = Quality.Height(2160), i = 4),
            s("r0-1440", rank = 0, q = Quality.Height(1440), i = 7),
            s("r0-unk", rank = 0, q = Quality.Unknown, i = 6),
            s("r0-av1", rank = 0, q = Quality.Auto, codec = VideoCodec.AV1),
            s("r0-720a", rank = 1, i = 9), // duplicate URL from a lower source
        )
        val ordered = StreamCandidates.order(list, QualityPreference.Auto, 1080) { it != VideoCodec.AV1 }
        assertEquals(
            listOf("r0-auto", "r0-1080", "r0-720a", "r0-720b", "r0-1440", "r0-2160", "r0-unk", "r0-p1", "r1-a", "r0-av1"),
            ordered.map { it.url },
        )
        assertEquals(0, ordered.first { it.url == "r0-720a" }.rank)

        val q720 = StreamCandidates.order(list.filter { it.stream.videoCodec == null && it.rank == 0 && it.stream.priority == 0 },
            QualityPreference.Height(720), 1080)
        assertEquals(listOf("r0-720a", "r0-720b", "r0-auto", "r0-1080", "r0-1440", "r0-2160", "r0-unk").let {
            // exact 720 first, then auto, then lower (none), then higher lowest first, unknown last
            it
        }, q720.map { it.url })
        assertEquals(QualityPreference.Height(480), QualityPreference.parse("480p"))
        assertEquals(QualityPreference.Auto, QualityPreference.parse("best"))
    }

    private fun candidates(n: Int) = StreamCandidates.order((0 until n).map { s("u$it", rank = it, label = if (it == 1) "Backup" else null) })

    @Test
    fun `error fails over to the next candidate with a notice, then exhausts`() {
        val sel = StreamSelector(candidates(3))
        val start = assertIs<SelectorDecision.Play>(sel.start(0))
        assertEquals(0, start.index); assertNull(start.notice)
        val d1 = assertIs<SelectorDecision.Play>(sel.onEvent(PlaybackEvent.Error, 1000))
        assertEquals(1, d1.index); assertEquals(PlayReason.FAILOVER, d1.reason)
        assertEquals(FailoverNotice(2, 3, "Backup"), d1.notice)
        assertEquals(2, assertIs<SelectorDecision.Play>(sel.onEvent(PlaybackEvent.Error, 2000)).index)
        assertEquals(SelectorDecision.Exhausted, sel.onEvent(PlaybackEvent.Error, 3000))
        assertEquals(3, sel.failedCount)
        // Try again clears marks and starts at candidates[0]
        val retry = assertIs<SelectorDecision.Play>(sel.retryAll(4000))
        assertEquals(0, retry.index); assertEquals(PlayReason.RETRY_ALL, retry.reason); assertEquals(0, sel.failedCount)
    }

    @Test
    fun `start timeout 20 s without first frame`() {
        val sel = StreamSelector(candidates(2))
        sel.start(0)
        assertEquals(SelectorDecision.None, sel.onEvent(PlaybackEvent.Tick, 19_999))
        assertEquals(1, assertIs<SelectorDecision.Play>(sel.onEvent(PlaybackEvent.Tick, 20_000)).index)
        // the new candidate gets its own 20 s
        assertEquals(SelectorDecision.None, sel.onEvent(PlaybackEvent.Tick, 39_000))
        sel.onEvent(PlaybackEvent.FirstFrame, 39_500)
        assertEquals(SelectorDecision.None, sel.onEvent(PlaybackEvent.Tick, 100_000))
    }

    @Test
    fun `one stall longer than 15 s`() {
        val sel = StreamSelector(candidates(2))
        sel.start(0); sel.onEvent(PlaybackEvent.FirstFrame, 1_000)
        sel.onEvent(PlaybackEvent.BufferingStarted, 10_000)
        assertEquals(SelectorDecision.None, sel.onEvent(PlaybackEvent.Tick, 25_000))
        assertEquals(1, assertIs<SelectorDecision.Play>(sel.onEvent(PlaybackEvent.Tick, 25_001)).index)
    }

    @Test
    fun `three stalls within 60 s, seek grace not counted`() {
        val sel = StreamSelector(candidates(2))
        sel.start(0); sel.onEvent(PlaybackEvent.FirstFrame, 1_000)
        fun stall(t: Long): SelectorDecision {
            val d = sel.onEvent(PlaybackEvent.BufferingStarted, t)
            if (d is SelectorDecision.Play) return d
            return sel.onEvent(PlaybackEvent.BufferingEnded, t + 1_000)
        }
        assertEquals(SelectorDecision.None, stall(10_000))
        // a stall right after a user seek is not counted
        sel.onEvent(PlaybackEvent.UserSeek, 20_000)
        assertEquals(SelectorDecision.None, stall(22_000))
        assertEquals(SelectorDecision.None, stall(30_000))
        // stall at 10 s has left the window by 75 s
        assertEquals(SelectorDecision.None, stall(75_000))
        assertEquals(1, assertIs<SelectorDecision.Play>(stall(80_000)).index)
    }

    @Test
    fun `nothing fails while offline - same stream is retried when the network returns`() {
        val sel = StreamSelector(candidates(2))
        sel.start(0)
        assertEquals(SelectorDecision.WaitingForNetwork, sel.onEvent(PlaybackEvent.NetworkLost, 1_000))
        assertEquals(SelectorDecision.WaitingForNetwork, sel.onEvent(PlaybackEvent.Error, 2_000))
        assertEquals(SelectorDecision.None, sel.onEvent(PlaybackEvent.Tick, 60_000))
        sel.onEvent(PlaybackEvent.BufferingStarted, 61_000)
        assertEquals(SelectorDecision.None, sel.onEvent(PlaybackEvent.Tick, 90_000))
        val back = assertIs<SelectorDecision.Play>(sel.onEvent(PlaybackEvent.NetworkRestored, 91_000))
        assertEquals(0, back.index); assertEquals(PlayReason.NETWORK_RESTORED, back.reason)
        assertEquals(0, sel.failedCount)
        assertEquals(SelectorDecision.None, sel.onEvent(PlaybackEvent.Tick, 110_000))
        assertEquals(1, assertIs<SelectorDecision.Play>(sel.onEvent(PlaybackEvent.Tick, 111_000)).index)
    }

    @Test
    fun `manual choice, then failover continues after it and wraps`() {
        val sel = StreamSelector(candidates(4))
        sel.start(0)
        assertEquals(1, assertIs<SelectorDecision.Play>(sel.onEvent(PlaybackEvent.Error, 1)).index) // 0 failed
        val manual = assertIs<SelectorDecision.Play>(sel.select(3, 2))
        assertEquals(PlayReason.MANUAL, manual.reason)
        // after 3 comes (wrap) 0 → failed, 1 → not failed (only 0 failed so far)
        assertEquals(1, assertIs<SelectorDecision.Play>(sel.onEvent(PlaybackEvent.Error, 3)).index)
        assertEquals(2, assertIs<SelectorDecision.Play>(sel.onEvent(PlaybackEvent.Error, 4)).index)
        assertEquals(SelectorDecision.Exhausted, sel.onEvent(PlaybackEvent.Error, 5))
    }

    @Test
    fun `quality change keeps failed marks by URL`() {
        val list = listOf(s("a720", q = Quality.Height(720), i = 0), s("a1080", q = Quality.Height(1080), i = 1), s("a480", q = Quality.Height(480), i = 2))
        val sel = StreamSelector(StreamCandidates.order(list))
        assertEquals("a1080", assertIs<SelectorDecision.Play>(sel.start(0)).candidate.url)
        sel.onEvent(PlaybackEvent.Error, 1) // a1080 failed → a720
        val d = assertIs<SelectorDecision.Play>(sel.changeQuality(StreamCandidates.order(list, QualityPreference.Height(1080)), 2))
        assertEquals("a720", d.candidate.url) // a1080 is still failed
        assertEquals(PlayReason.QUALITY_CHANGE, d.reason)
    }
}
