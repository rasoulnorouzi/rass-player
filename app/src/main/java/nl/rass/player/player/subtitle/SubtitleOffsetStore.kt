package nl.rass.player.player.subtitle

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Sync offset rules (R6.3): ±60 s in 100 ms steps. Positive = subtitles later, see [SubtitleTrack]. */
object SubtitleOffset {
    const val MIN_MS = -60_000L
    const val MAX_MS = 60_000L
    const val STEP_MS = 100L
    const val FAST_STEP_MS = 1_000L

    /** Clamps to the range and rounds to the nearest 100 ms. */
    fun normalize(offsetMs: Long): Long {
        val clamped = offsetMs.coerceIn(MIN_MS, MAX_MS)
        return Math.floorDiv(clamped + STEP_MS / 2, STEP_MS) * STEP_MS
    }

    fun step(offsetMs: Long, deltaMs: Long): Long = normalize(offsetMs + deltaMs)
}

/**
 * Offsets are remembered per title/episode and per subtitle file. The URL is hashed so keys stay short and
 * never contain tokens from the URL.
 */
data class SubtitleOffsetKey(val titleId: String, val episodeId: String?, val subtitleUrlHash: String) {
    val storageKey: String get() = "$titleId|${episodeId.orEmpty()}|$subtitleUrlHash"

    companion object {
        fun of(titleId: String, episodeId: String?, subtitleUrl: String) =
            SubtitleOffsetKey(titleId, episodeId, hashUrl(subtitleUrl))

        fun hashUrl(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
    }
}

interface SubtitleOffsetStore {
    /** Saved offset, or 0 when none. */
    suspend fun get(key: SubtitleOffsetKey): Long

    /** Saves a normalized offset; 0 removes the entry. */
    suspend fun set(key: SubtitleOffsetKey, offsetMs: Long)

    fun observe(key: SubtitleOffsetKey): Flow<Long>
}

/** Process-lifetime store. A DataStore-backed implementation replaces it once the data layer lands. */
class InMemorySubtitleOffsetStore : SubtitleOffsetStore {
    private val offsets = MutableStateFlow<Map<String, Long>>(emptyMap())

    override suspend fun get(key: SubtitleOffsetKey): Long = offsets.value[key.storageKey] ?: 0L

    override suspend fun set(key: SubtitleOffsetKey, offsetMs: Long) {
        val v = SubtitleOffset.normalize(offsetMs)
        offsets.update { if (v == 0L) it - key.storageKey else it + (key.storageKey to v) }
    }

    override fun observe(key: SubtitleOffsetKey): Flow<Long> =
        offsets.map { it[key.storageKey] ?: 0L }.distinctUntilChanged()
}
