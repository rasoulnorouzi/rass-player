package nl.rass.player.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.rass.player.data.settings.ResumeBehavior
import nl.rass.player.domain.Markers
import nl.rass.player.domain.MediaType
import nl.rass.player.domain.Quality
import nl.rass.player.domain.Stream
import nl.rass.player.domain.WatchProgress
import nl.rass.player.domain.playback.QualityPreference
import nl.rass.player.domain.playback.StreamCandidate
import nl.rass.player.ui.player.offsetText
import nl.rass.player.ui.player.speedLabel

class PlayerRulesTest {

    private fun progress(pos: Long, dur: Long = 3_600_000, completed: Boolean = false) =
        WatchProgress("k", "", pos, dur, 0, completed)

    // ---- resume (R4.2) ----

    @Test
    fun resumeAsksForPartlyWatchedItem() {
        assertEquals(ResumeDecision.Ask(754_000), ResumeRules.decide(progress(754_000), ResumeBehavior.ASK, false))
    }

    @Test
    fun resumeHonorsBehaviorSetting() {
        assertEquals(ResumeDecision.Resume(754_000), ResumeRules.decide(progress(754_000), ResumeBehavior.ALWAYS_RESUME, false))
        assertEquals(ResumeDecision.StartOver, ResumeRules.decide(progress(754_000), ResumeBehavior.ALWAYS_START_OVER, false))
    }

    @Test
    fun resumeStartsOverWhenRequestedOrNothingUseful() {
        assertEquals(ResumeDecision.StartOver, ResumeRules.decide(progress(754_000), ResumeBehavior.ASK, startFromBeginning = true))
        assertEquals(ResumeDecision.StartOver, ResumeRules.decide(null, ResumeBehavior.ASK, false))
        assertEquals(ResumeDecision.StartOver, ResumeRules.decide(progress(5_000), ResumeBehavior.ASK, false))
        assertEquals(ResumeDecision.StartOver, ResumeRules.decide(progress(754_000, completed = true), ResumeBehavior.ASK, false))
        // Within the last 30 s.
        assertEquals(ResumeDecision.StartOver, ResumeRules.decide(progress(3_590_000), ResumeBehavior.ASK, false))
    }

    // ---- auto-next (R5.2, §9) ----

    @Test
    fun autoNextStartsAtCreditsMarker() {
        val dur = 1_500_000L
        assertNull(AutoNextRules.remainingMs(1_399_000, dur, 1_400.0, enabled = true, hasNext = true, dismissed = false))
        assertEquals(10_000, AutoNextRules.remainingMs(1_400_000, dur, 1_400.0, enabled = true, hasNext = true, dismissed = false))
        assertEquals(4_000, AutoNextRules.remainingMs(1_406_000, dur, 1_400.0, enabled = true, hasNext = true, dismissed = false))
        assertEquals(0, AutoNextRules.remainingMs(1_420_000, dur, 1_400.0, enabled = true, hasNext = true, dismissed = false))
    }

    @Test
    fun autoNextWithoutCreditsStarts30sBeforeEnd() {
        val dur = 1_500_000L
        assertEquals(1_470_000, AutoNextRules.triggerMs(dur, null))
        assertNull(AutoNextRules.remainingMs(1_469_000, dur, null, true, true, false))
        assertEquals(10_000, AutoNextRules.remainingMs(1_470_000, dur, null, true, true, false))
        // Credits marker outside the media is ignored.
        assertEquals(1_470_000, AutoNextRules.triggerMs(dur, 9_999.0))
    }

    @Test
    fun autoNextNeverRunsPastEndAndHonorsSwitches() {
        // Countdown capped at the end of the media.
        assertEquals(3_000, AutoNextRules.remainingMs(1_472_000, 1_475_000, 1_470.0, true, true, false))
        assertNull(AutoNextRules.remainingMs(1_480_000, 1_500_000, null, enabled = false, hasNext = true, dismissed = false))
        assertNull(AutoNextRules.remainingMs(1_480_000, 1_500_000, null, enabled = true, hasNext = false, dismissed = false))
        assertNull(AutoNextRules.remainingMs(1_480_000, 1_500_000, null, enabled = true, hasNext = true, dismissed = true))
        assertNull(AutoNextRules.remainingMs(10_000, 0, null, true, true, false))
    }

    // ---- seek acceleration (R5.5) ----

    @Test
    fun seekTapIsTenSeconds() {
        val a = SeekAccelerator()
        assertEquals(10_000, a.onKeyDown(0, repeat = false))
        assertEquals(10_000, a.onKeyDown(1_000, repeat = false))
    }

    @Test
    fun heldSeekIsThrottledAndAccelerates() {
        val a = SeekAccelerator()
        assertEquals(10_000, a.onKeyDown(0, repeat = false))
        assertNull(a.onKeyDown(50, repeat = true)) // throttled
        assertEquals(10_000, a.onKeyDown(200, repeat = true))
        assertEquals(30_000, a.onKeyDown(2_500, repeat = true))
        assertEquals(60_000, a.onKeyDown(6_000, repeat = true))
        // Releasing and pressing again starts slow.
        assertEquals(10_000, a.onKeyDown(7_000, repeat = false))
        assertEquals(10_000, a.onKeyDown(7_300, repeat = true))
    }

    @Test
    fun seekTargetIsClamped() {
        assertEquals(0, SeekAccelerator.target(5_000, -10_000, 60_000))
        assertEquals(60_000, SeekAccelerator.target(55_000, 10_000, 60_000))
        assertEquals(65_000, SeekAccelerator.target(55_000, 10_000, 0)) // unknown duration
    }

    // ---- quality menu (§7.4) ----

    private fun cand(url: String, q: Quality, sourceId: String = "a", priority: Int = 0, type: MediaType = MediaType.MP4) =
        StreamCandidate(sourceId, "Src $sourceId", 0, Stream(url, type, q, priority), null)

    @Test
    fun qualityMenuForAdaptiveStreamListsVariantsAndSameTierFixed() {
        val hls = cand("h", Quality.Auto, type = MediaType.HLS)
        val list = listOf(
            hls,
            cand("1080", Quality.Height(1080)),
            cand("720", Quality.Height(720)),
            cand("720b", Quality.Height(720), priority = 1), // other tier
            cand("480x", Quality.Height(480), sourceId = "b"), // other source
        )
        val variants = listOf(
            VariantInfo(0, 0, 360, 800_000),
            VariantInfo(0, 1, 720, 2_000_000),
            VariantInfo(0, 2, 720, 2_500_000),
            VariantInfo(0, 3, 1080, 5_000_000),
            VariantInfo(1, 0, -1, 0),
        )
        val menu = QualityMenu.build(hls, list, variants, overrideVariant = null)
        assertEquals(QualityOption.Auto(selected = true), menu[0])
        val v = menu.filterIsInstance<QualityOption.Variant>()
        assertEquals(listOf(1080, 720, 360), v.map { it.info.height })
        assertEquals(2_500_000, v[1].info.bitrate) // highest bitrate per height
        assertEquals(listOf(1080, 720), menu.filterIsInstance<QualityOption.Fixed>().map { it.height })
        assertTrue(menu.none { it is QualityOption.Fixed && it.selected })
    }

    @Test
    fun qualityMenuMarksOverrideAndFixedSelection() {
        val hls = cand("h", Quality.Auto, type = MediaType.HLS)
        val v720 = VariantInfo(0, 1, 720, 2_000_000)
        val withOverride = QualityMenu.build(hls, listOf(hls), listOf(v720), overrideVariant = v720)
        assertEquals(QualityOption.Auto(selected = false), withOverride[0])
        assertTrue((withOverride[1] as QualityOption.Variant).selected)

        val mp4 = cand("720", Quality.Height(720))
        val fixed = QualityMenu.build(mp4, listOf(hls, mp4, cand("1080", Quality.Height(1080))), emptyList(), null)
        assertEquals(QualityOption.Auto(selected = false), fixed[0])
        assertEquals(listOf(QualityOption.Fixed(1080, false), QualityOption.Fixed(720, true)), fixed.drop(1))
        assertTrue(QualityMenu.build(null, emptyList(), emptyList(), null).isEmpty())
    }

    @Test
    fun qualityPreferenceAfterChoice() {
        assertEquals(QualityPreference.Height(720), QualityMenu.preferenceAfter(QualityOption.Fixed(720, false), QualityPreference.Auto))
        assertEquals(QualityPreference.Auto, QualityMenu.preferenceAfter(QualityOption.Auto(false), QualityPreference.Height(720)))
        val v = QualityOption.Variant(VariantInfo(0, 0, 480, 1), false)
        assertEquals(QualityPreference.Height(1080), QualityMenu.preferenceAfter(v, QualityPreference.Height(1080)))
    }

    // ---- skip markers (R11.1) ----

    @Test
    fun skipButtonsFollowMarkers() {
        val m = Markers(introStart = 60.0, introEnd = 90.0, recapStart = 0.0, recapEnd = 30.0)
        assertEquals(SkipTarget(SkipKind.RECAP, 30_000), SkipRules.at(5_000, m))
        assertNull(SkipRules.at(45_000, m))
        assertEquals(SkipTarget(SkipKind.INTRO, 90_000), SkipRules.at(60_000, m))
        assertNull(SkipRules.at(90_000, m))
        assertNull(SkipRules.at(60_000, null))
    }

    // ---- Persian labels ----

    @Test
    fun persianLabels() {
        assertEquals("۱٫۲۵×", speedLabel(1.25f))
        assertEquals("۱×", speedLabel(1f))
        assertEquals("۰٫۷۵×", speedLabel(0.75f))
        assertEquals("\u200E+۱٫۲", offsetText(1_200))
        assertEquals("\u200E-۶۰٫۰", offsetText(-60_000))
    }
}
