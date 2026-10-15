package nl.rass.player.player.subtitle.online

import kotlin.math.log10

/** Ordering of merged online results (R6.7): language, then id > title match, then release similarity, then popularity. */
object OnlineSubtitleRanking {

    fun rank(results: List<OnlineSubtitleResult>, query: SubtitleQuery, preferEnglish: Boolean = false): List<OnlineSubtitleResult> {
        val first = if (preferEnglish) "en" else "fa"
        val deduped = results.distinctBy { it.key }
        return deduped.sortedWith(
            compareBy<OnlineSubtitleResult> { if (it.lang == first) 0 else 1 }
                .thenBy { if (it.matchedBy == MatchedBy.ID) 0 else 1 }
                .thenByDescending { score(it, query) },
        )
    }

    internal fun score(r: OnlineSubtitleResult, q: SubtitleQuery): Double {
        val sim = similarity(r.releaseName, q.releaseHint)
        val pop = log10(1.0 + r.downloads.coerceAtLeast(0)) * 10
        val rating = (r.rating ?: 0.0).coerceIn(0.0, 10.0)
        return sim * 100 + pop + rating - if (r.hearingImpaired) 5 else 0
    }

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val NOISE = setOf("srt", "vtt", "zip", "mkv", "mp4", "avi", "the", "and")

    private fun tokens(s: String) = s.lowercase().split(SPLIT).filter { it.length > 1 && it !in NOISE }.toSet()

    /** Jaccard similarity of the release-name tokens, 0..1. */
    fun similarity(a: String?, b: String?): Double {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return 0.0
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }
}
