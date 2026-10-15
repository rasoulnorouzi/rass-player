package nl.rass.player.player

import nl.rass.player.data.settings.ResumeBehavior
import nl.rass.player.domain.Quality
import nl.rass.player.domain.WatchProgress
import nl.rass.player.domain.playback.QualityPreference
import nl.rass.player.domain.playback.StreamCandidate

/*
 * Pure player rules (no Android types), unit-tested in app/src/test/.../player/PlayerRulesTest.kt.
 */

/** R4.2: what to do with a stored position when an item starts. */
sealed interface ResumeDecision {
    data object StartOver : ResumeDecision
    data class Resume(val positionMs: Long) : ResumeDecision
    /** Show «ادامه از mm:ss» / «از ابتدا». */
    data class Ask(val positionMs: Long) : ResumeDecision
}

object ResumeRules {
    /** Positions shorter than this are not worth asking about. */
    const val MIN_RESUME_MS = 10_000L
    /** Positions this close to the end start over. */
    const val END_GUARD_MS = 30_000L

    fun decide(progress: WatchProgress?, behavior: ResumeBehavior, startFromBeginning: Boolean): ResumeDecision {
        if (startFromBeginning || progress == null || progress.completed) return ResumeDecision.StartOver
        val pos = progress.positionMs
        if (pos < MIN_RESUME_MS) return ResumeDecision.StartOver
        if (progress.durationMs > 0 && pos >= progress.durationMs - END_GUARD_MS) return ResumeDecision.StartOver
        return when (behavior) {
            ResumeBehavior.ASK -> ResumeDecision.Ask(pos)
            ResumeBehavior.ALWAYS_RESUME -> ResumeDecision.Resume(pos)
            ResumeBehavior.ALWAYS_START_OVER -> ResumeDecision.StartOver
        }
    }
}

/**
 * R5.2 + feed-spec §9: the auto-next countdown starts at `credits_start`, else 30 s before the end, and runs
 * [COUNTDOWN_MS] of *media* time (so it pauses with the video). It never runs past the end.
 */
object AutoNextRules {
    const val COUNTDOWN_MS = 10_000L
    const val DEFAULT_LEAD_MS = 30_000L

    fun triggerMs(durationMs: Long, creditsStartSec: Double?): Long? {
        if (durationMs <= 0) return null
        if (creditsStartSec != null) {
            val ms = (creditsStartSec * 1000).toLong()
            if (ms in 1 until durationMs) return ms
        }
        return (durationMs - DEFAULT_LEAD_MS).coerceAtLeast(0)
    }

    /** Remaining countdown in ms, or null when no countdown shows. 0 = play the next episode now. */
    fun remainingMs(
        positionMs: Long,
        durationMs: Long,
        creditsStartSec: Double?,
        enabled: Boolean,
        hasNext: Boolean,
        dismissed: Boolean,
    ): Long? {
        if (!enabled || !hasNext || dismissed) return null
        val t = triggerMs(durationMs, creditsStartSec) ?: return null
        if (positionMs < t) return null
        val end = minOf(t + COUNTDOWN_MS, durationMs)
        return (end - positionMs).coerceAtLeast(0)
    }
}

/**
 * R5.5: Left/Right seek ±10 s; holding the key accelerates. Key repeat on TVs is ~20/s, so held steps are
 * throttled to one per [REPEAT_INTERVAL_MS] and grow with the hold time.
 */
class SeekAccelerator {
    private var holdStartMs = 0L
    private var lastStepMs = Long.MIN_VALUE / 2

    /**
     * @param repeat true for an auto-repeated key-down (key held), false for the first press.
     * @return the step size in ms to apply now, or null when throttled.
     */
    fun onKeyDown(nowMs: Long, repeat: Boolean): Long? {
        if (!repeat) {
            holdStartMs = nowMs
            lastStepMs = nowMs
            return BASE_STEP_MS
        }
        if (nowMs - lastStepMs < REPEAT_INTERVAL_MS) return null
        lastStepMs = nowMs
        return stepForHold(nowMs - holdStartMs)
    }

    companion object {
        const val BASE_STEP_MS = 10_000L
        const val REPEAT_INTERVAL_MS = 200L

        fun stepForHold(heldMs: Long): Long = when {
            heldMs < 2_000 -> 10_000L
            heldMs < 5_000 -> 30_000L
            else -> 60_000L
        }

        /** New seek target, clamped to [0, duration] (duration ≤ 0 = unknown, only the lower bound applies). */
        fun target(fromMs: Long, deltaMs: Long, durationMs: Long): Long {
            val t = (fromMs + deltaMs).coerceAtLeast(0)
            return if (durationMs > 0) t.coerceAtMost(durationMs) else t
        }
    }
}

/** One video variant of the current adaptive stream (from Media3's tracks). Indices address `Tracks.groups`. */
data class VariantInfo(val groupIndex: Int, val trackIndex: Int, val height: Int, val bitrate: Int)

sealed interface QualityOption {
    val selected: Boolean

    /** «خودکار». */
    data class Auto(override val selected: Boolean) : QualityOption
    /** A variant of the playing HLS/DASH stream (track override). */
    data class Variant(val info: VariantInfo, override val selected: Boolean) : QualityOption
    /** A separate fixed-quality stream in the current candidate's source and tier (re-sorts the list). */
    data class Fixed(val height: Int, override val selected: Boolean) : QualityOption
}

/** Quality menu of feed-spec §7.4. */
object QualityMenu {
    fun build(
        current: StreamCandidate?,
        candidates: List<StreamCandidate>,
        variants: List<VariantInfo>,
        overrideVariant: VariantInfo?,
    ): List<QualityOption> {
        if (current == null) return emptyList()
        val adaptive = current.stream.quality == Quality.Auto
        val out = ArrayList<QualityOption>()
        out += QualityOption.Auto(selected = adaptive && overrideVariant == null)
        if (adaptive) {
            variants.filter { it.height > 0 }
                .groupBy { it.height }
                .map { (_, vs) -> vs.maxBy { it.bitrate } }
                .sortedByDescending { it.height }
                .forEach { v ->
                    out += QualityOption.Variant(v, selected = overrideVariant?.height == v.height)
                }
        }
        val currentHeight = (current.stream.quality as? Quality.Height)?.px
        candidates.asSequence()
            .filter { it.sourceId == current.sourceId && it.stream.priority == current.stream.priority }
            .mapNotNull { (it.stream.quality as? Quality.Height)?.px }
            .distinct()
            .sortedDescending()
            .forEach { h -> out += QualityOption.Fixed(h, selected = !adaptive && h == currentHeight) }
        return out
    }

    /** Session preference after choosing [option] (§7.4: kept for the rest of the player session). */
    fun preferenceAfter(option: QualityOption, previous: QualityPreference): QualityPreference = when (option) {
        is QualityOption.Auto -> QualityPreference.Auto
        is QualityOption.Fixed -> QualityPreference.Height(option.height)
        is QualityOption.Variant -> previous
    }
}

/** R11.10. */
sealed interface SleepTimerOption {
    data object Off : SleepTimerOption
    data class Minutes(val minutes: Int) : SleepTimerOption
    /** Stop at the end of the current movie/episode. */
    data object EndOfItem : SleepTimerOption

    companion object {
        val ALL: List<SleepTimerOption> = listOf(Off, Minutes(15), Minutes(30), Minutes(45), Minutes(60), Minutes(90), EndOfItem)
    }
}

/** R11.11. */
object PlaybackSpeeds {
    val ALL = listOf(0.75f, 1f, 1.25f, 1.5f)
}

/** R11.1 / §9: which skip button shows at [positionMs], and where it jumps. */
enum class SkipKind { INTRO, RECAP }

data class SkipTarget(val kind: SkipKind, val toMs: Long)

object SkipRules {
    fun at(positionMs: Long, markers: nl.rass.player.domain.Markers?): SkipTarget? {
        if (markers == null) return null
        val sec = positionMs / 1000.0
        val rs = markers.recapStart; val re = markers.recapEnd
        if (rs != null && re != null && sec >= rs && sec < re) return SkipTarget(SkipKind.RECAP, (re * 1000).toLong())
        val ist = markers.introStart; val ie = markers.introEnd
        if (ist != null && ie != null && sec >= ist && sec < ie) return SkipTarget(SkipKind.INTRO, (ie * 1000).toLong())
        return null
    }
}
