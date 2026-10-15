package nl.rass.player.search

/** Damerau-Levenshtein (optimal string alignment) with an upper bound for early exit. */
internal object EditDistance {
    /** Returns true if distance(a, b) <= max. */
    fun within(a: String, b: String, max: Int): Boolean {
        if (a == b) return true
        if (max <= 0) return false
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > max) return false
        var prev2 = IntArray(lb + 1)
        var prev = IntArray(lb + 1) { it }
        var cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prev2[j - 2] + 1)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return false
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[lb] <= max
    }

    /** Allowed typos for a query token of this length: 0 below 4 chars, 1 for 4..7, 2 for >= 8. */
    fun allowed(len: Int): Int = when {
        len >= 8 -> 2
        len >= 4 -> 1
        else -> 0
    }
}
