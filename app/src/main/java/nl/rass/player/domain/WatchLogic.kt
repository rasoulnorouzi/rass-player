package nl.rass.player.domain

/** Stored position of one movie or episode. [episodeKey] is "" for a movie. */
data class WatchProgress(
    val catalogKey: String,
    val episodeKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean,
    val hiddenFromContinue: Boolean = false,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

object WatchRules {
    const val COMPLETE_FRACTION = 0.90

    /** R4.3: watched at ≥ 90 %, or from `credits_start` on (§9). */
    fun isCompleted(positionMs: Long, durationMs: Long, creditsStartSec: Double? = null): Boolean {
        if (durationMs > 0 && positionMs >= durationMs * COMPLETE_FRACTION) return true
        if (creditsStartSec != null && positionMs >= (creditsStartSec * 1000).toLong() && positionMs > 0) return true
        return false
    }

    /** Continue Watching (R4.3, R11.5): unfinished, not removed by the user, newest first, one entry per title. */
    fun continueWatching(rows: List<WatchProgress>, minPositionMs: Long = 1_000): List<WatchProgress> =
        rows.sortedByDescending { it.updatedAt }
            .distinctBy { it.catalogKey }
            .filter { !it.completed && !it.hiddenFromContinue && it.positionMs >= minPositionMs }

    /** Episodes of a series in play order (§6.1: seasons ascending, specials last; episodes ascending). */
    fun playOrder(title: CatalogTitle): List<CatalogEpisode> =
        title.seasons.sortedWith(compareBy(seasonOrder) { it.number })
            .flatMap { s -> s.episodes.sortedBy { it.number } }

    /**
     * R4.4: which episode the detail page's Play button starts, with the position to resume at (null = start).
     *  - nothing watched → the first episode;
     *  - the most recently watched episode is unfinished → resume it;
     *  - it is finished → the next episode after it that isn't finished; if none, the first unfinished episode
     *    of the series; if everything is finished → the first episode.
     * [progress] maps episode keys to their progress (rows of other titles are ignored).
     */
    fun nextEpisode(title: CatalogTitle, progress: Map<String, WatchProgress>): Pair<CatalogEpisode, Long?>? {
        val eps = playOrder(title)
        if (eps.isEmpty()) return null
        val last = eps.mapNotNull { e -> progress[e.key]?.let { e to it } }.maxByOrNull { it.second.updatedAt }
            ?: return eps.first() to null
        val (lastEp, lastP) = last
        if (!lastP.completed) return lastEp to lastP.positionMs.takeIf { it > 0 }
        val idx = eps.indexOf(lastEp)
        val after = eps.drop(idx + 1).firstOrNull { progress[it.key]?.completed != true }
        val pick = after ?: eps.firstOrNull { progress[it.key]?.completed != true } ?: return eps.first() to null
        val p = progress[pick.key]
        return pick to p?.positionMs?.takeIf { it > 0 && !p.completed }
    }

    /** "New episode" badge (R11.3): episodes the user hasn't seen listed yet. */
    fun newEpisodeCount(title: CatalogTitle, seenEpisodeKeys: Set<String>): Int =
        title.episodes.count { it.key !in seenEpisodeKeys }
}
