package nl.rass.player.search

/** Immutable most-recent-first list of search queries. Persistence is handled elsewhere. */
class RecentSearches(
    items: List<String> = emptyList(),
    val max: Int = DEFAULT_MAX,
    private val normalizer: PersianTextNormalizer = PersianTextNormalizer.Default,
) {
    val items: List<String>

    init {
        require(max > 0)
        val seen = HashSet<String>()
        this.items = items.map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(normalizer.compact(it)) }
            .take(max)
    }

    /** Adds [query] at the front; an equivalent earlier entry (after normalization) is replaced. Blank ignored. */
    fun add(query: String): RecentSearches {
        val q = query.trim()
        if (normalizer.compact(q).isEmpty()) return this
        return RecentSearches(listOf(q) + items, max, normalizer)
    }

    fun remove(query: String): RecentSearches {
        val key = normalizer.compact(query)
        return RecentSearches(items.filter { normalizer.compact(it) != key }, max, normalizer)
    }

    fun clear(): RecentSearches = RecentSearches(emptyList(), max, normalizer)

    companion object {
        const val DEFAULT_MAX = 10
    }
}
