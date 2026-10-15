package nl.rass.player.data.cache

/** Pure planning for the optional total-size cap (R9.2). */
object CacheCap {
    data class Entry(val id: String, val bytes: Long, val order: Int = 0)

    /**
     * Which caches to clear so the total fits under [capBytes]: biggest first, until it fits.
     * Returns empty when [capBytes] <= 0 (cap off) or the total is already within it.
     */
    fun idsToClear(entries: List<Entry>, capBytes: Long): List<String> {
        if (capBytes <= 0) return emptyList()
        var total = entries.sumOf { it.bytes }
        val out = ArrayList<String>()
        for (e in entries.filter { it.bytes > 0 }.sortedWith(compareByDescending<Entry> { it.bytes }.thenBy { it.order })) {
            if (total <= capBytes) break
            out += e.id
            total -= e.bytes
        }
        return out
    }
}
