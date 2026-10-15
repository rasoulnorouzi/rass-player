package nl.rass.player.domain.playback

/** Thresholds of §7.3. */
data class FailoverConfig(
    val startTimeoutMs: Long = 20_000,
    val longStallMs: Long = 15_000,
    val stallWindowMs: Long = 60_000,
    val stallCount: Int = 3,
    /** Buffering that starts this soon after a user seek / manual change is not counted as a stall. */
    val userActionGraceMs: Long = 5_000,
)

/** What the player reports. Times come with [StreamSelector.onEvent] as a monotonic clock in ms. */
sealed interface PlaybackEvent {
    /** The first video frame of the current candidate was rendered. */
    data object FirstFrame : PlaybackEvent
    data object BufferingStarted : PlaybackEvent
    data object BufferingEnded : PlaybackEvent
    /** Media3 fatal error for the media (HTTP ≥ 400, network after retries, format, decoder init). */
    data object Error : PlaybackEvent
    /** The user seeked; next buffering within the grace period isn't a stall. */
    data object UserSeek : PlaybackEvent
    data object NetworkLost : PlaybackEvent
    data object NetworkRestored : PlaybackEvent
    /** Call about once per second so timeouts fire without other events. */
    data object Tick : PlaybackEvent
}

enum class PlayReason { START, FAILOVER, MANUAL, RETRY_ALL, QUALITY_CHANGE, NETWORK_RESTORED }

/** "منبع ۲ از ۴ (Backup)": [position] is 1-based within [total]. */
data class FailoverNotice(val position: Int, val total: Int, val label: String)

sealed interface SelectorDecision {
    /** Nothing to do. */
    data object None : SelectorDecision

    /** Open [candidate] at the current playback position (keep subtitle, offset and audio language). */
    data class Play(
        val index: Int,
        val candidate: StreamCandidate,
        val reason: PlayReason,
        /** Set for automatic failover; the player shows it briefly. */
        val notice: FailoverNotice? = null,
    ) : SelectorDecision

    /** Device is offline: show "No internet connection"; nothing is marked failed. */
    data object WaitingForNetwork : SelectorDecision

    /** Every candidate failed: error screen with Try again ([StreamSelector.retryAll]) and the Source menu. */
    data object Exhausted : SelectorDecision
}

/**
 * The failover policy of §7.3 as a pure state machine. One instance per player session (failed marks last
 * until the player screen closes). Not thread-safe; call from the player's thread.
 */
class StreamSelector(
    candidates: List<StreamCandidate>,
    private val config: FailoverConfig = FailoverConfig(),
    online: Boolean = true,
) {
    var candidates: List<StreamCandidate> = candidates
        private set

    private var failed = BooleanArray(candidates.size)

    /** Index of the candidate being played, or -1 before [start] / after exhaustion. */
    var currentIndex: Int = -1
        private set

    var isOnline: Boolean = online
        private set

    private var openedAt = 0L
    private var firstFrameSeen = false
    private var bufferingSince: Long? = null
    private val stallStarts = ArrayDeque<Long>()
    private var lastUserActionAt = Long.MIN_VALUE / 2

    val current: StreamCandidate? get() = candidates.getOrNull(currentIndex)
    fun isFailed(index: Int): Boolean = failed.getOrElse(index) { false }
    val failedCount: Int get() = failed.count { it }

    fun start(nowMs: Long): SelectorDecision {
        if (candidates.isEmpty()) return SelectorDecision.Exhausted
        val i = nextNonFailed(0, inclusive = true) ?: return exhausted()
        return play(i, PlayReason.START, nowMs, notice = false)
    }

    /** Source menu choice (§7.4). Plays [index] even if it failed before. */
    fun select(index: Int, nowMs: Long): SelectorDecision {
        require(index in candidates.indices)
        failed[index] = false
        lastUserActionAt = nowMs
        return play(index, PlayReason.MANUAL, nowMs, notice = false)
    }

    /** "Try again": clears the failed marks and starts again from `candidates[0]`. */
    fun retryAll(nowMs: Long): SelectorDecision {
        failed = BooleanArray(candidates.size)
        return play(0, PlayReason.RETRY_ALL, nowMs, notice = false)
    }

    /**
     * Quality menu chose a fixed quality: [reordered] is the list re-sorted with the new `Q`. Failed marks carry
     * over by URL; the first non-failed candidate plays.
     */
    fun changeQuality(reordered: List<StreamCandidate>, nowMs: Long): SelectorDecision {
        val failedUrls = candidates.filterIndexed { i, _ -> failed[i] }.mapTo(HashSet()) { it.url }
        candidates = reordered
        failed = BooleanArray(reordered.size) { reordered[it].url in failedUrls }
        lastUserActionAt = nowMs
        val i = nextNonFailed(0, inclusive = true) ?: return exhausted()
        return play(i, PlayReason.QUALITY_CHANGE, nowMs, notice = false)
    }

    fun onEvent(event: PlaybackEvent, nowMs: Long): SelectorDecision {
        if (currentIndex < 0) return SelectorDecision.None
        return when (event) {
            PlaybackEvent.FirstFrame -> { firstFrameSeen = true; SelectorDecision.None }
            PlaybackEvent.UserSeek -> { lastUserActionAt = nowMs; SelectorDecision.None }
            PlaybackEvent.NetworkLost -> {
                isOnline = false
                bufferingSince = null
                stallStarts.clear()
                SelectorDecision.WaitingForNetwork
            }
            PlaybackEvent.NetworkRestored -> {
                if (isOnline) SelectorDecision.None
                else {
                    isOnline = true
                    play(currentIndex, PlayReason.NETWORK_RESTORED, nowMs, notice = false)
                }
            }
            PlaybackEvent.Error -> if (!isOnline) SelectorDecision.WaitingForNetwork else fail(nowMs)
            PlaybackEvent.BufferingStarted -> onBufferingStarted(nowMs)
            PlaybackEvent.BufferingEnded -> {
                val since = bufferingSince
                bufferingSince = null
                if (isOnline && firstFrameSeen && since != null && nowMs - since > config.longStallMs) fail(nowMs)
                else SelectorDecision.None
            }
            PlaybackEvent.Tick -> checkTimeouts(nowMs)
        }
    }

    private fun onBufferingStarted(nowMs: Long): SelectorDecision {
        if (!isOnline) return SelectorDecision.None
        if (bufferingSince == null) bufferingSince = nowMs
        if (!firstFrameSeen) return SelectorDecision.None
        if (nowMs - lastUserActionAt < config.userActionGraceMs) return SelectorDecision.None
        while (stallStarts.isNotEmpty() && nowMs - stallStarts.first() > config.stallWindowMs) stallStarts.removeFirst()
        stallStarts.addLast(nowMs)
        return if (stallStarts.size >= config.stallCount) fail(nowMs) else SelectorDecision.None
    }

    private fun checkTimeouts(nowMs: Long): SelectorDecision {
        if (!isOnline) return SelectorDecision.None
        if (!firstFrameSeen && nowMs - openedAt >= config.startTimeoutMs) return fail(nowMs)
        val since = bufferingSince
        if (firstFrameSeen && since != null && nowMs - since > config.longStallMs) return fail(nowMs)
        return SelectorDecision.None
    }

    private fun fail(nowMs: Long): SelectorDecision {
        failed[currentIndex] = true
        val next = nextNonFailed(currentIndex, inclusive = false) ?: return exhausted()
        return play(next, PlayReason.FAILOVER, nowMs, notice = true)
    }

    private fun exhausted(): SelectorDecision {
        currentIndex = -1
        return SelectorDecision.Exhausted
    }

    /** Next non-failed index after [from] in list order, wrapping to the top (§7.4). */
    private fun nextNonFailed(from: Int, inclusive: Boolean): Int? {
        val n = candidates.size
        if (n == 0) return null
        val start = if (inclusive) 0 else 1
        for (k in start until n + start) {
            val i = (from + k).mod(n)
            if (!failed[i]) return i
        }
        return null
    }

    private fun play(index: Int, reason: PlayReason, nowMs: Long, notice: Boolean): SelectorDecision {
        currentIndex = index
        openedAt = nowMs
        firstFrameSeen = false
        bufferingSince = null
        stallStarts.clear()
        val c = candidates[index]
        return SelectorDecision.Play(
            index = index,
            candidate = c,
            reason = reason,
            notice = if (notice) FailoverNotice(index + 1, candidates.size, c.displayLabel) else null,
        )
    }
}
