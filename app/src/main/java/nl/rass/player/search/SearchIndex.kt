package nl.rass.player.search

/**
 * @param titles fa, en, original title (blank entries ignored); the display form is used for suggestions.
 * @param sourceIds ids of the sources this (merged) title is available from.
 */
data class SearchDocument(
    val id: String,
    val sourceIds: Set<String>,
    val titles: List<String>,
    val year: Int? = null,
    val extraTerms: List<String> = emptyList(),
    val popularity: Double = 0.0,
)

enum class MatchType { EXACT_TITLE, TITLE_PREFIX, TOKEN_PREFIX, FUZZY, SUBSTRING }

data class SearchResult(
    val documentId: String,
    val sourceIds: Set<String>,
    val matchType: MatchType,
    /** Display title that matched (exact/prefix); otherwise the first non-blank title. */
    val matchedTitle: String,
)

/**
 * Immutable in-memory index. Build a new one when the catalog changes (cheap: linear tokenizing plus a sort of
 * the distinct tokens / titles).
 */
class SearchIndex(
    documents: List<SearchDocument>,
    private val normalizer: PersianTextNormalizer = PersianTextNormalizer.Default,
) {
    private class Entry(val doc: Int, val display: String?, val compact: String, val tokens: Array<String>)

    private val docs: Array<SearchDocument> = documents.toTypedArray()
    private val docTokens: Array<Array<String>>
    private val docCompacts: Array<Array<String>>
    private val entries: Array<Entry>
    private val compactOrder: Array<Entry>   // title entries sorted by compact form
    private val tokenKeys: Array<String>
    private val tokenPostings: Array<IntArray> // entry indices

    val size: Int get() = docs.size

    init {
        val entryList = ArrayList<Entry>(docs.size * 3)
        docTokens = Array(docs.size) { emptyArray() }
        docCompacts = Array(docs.size) { emptyArray() }
        for ((i, d) in docs.withIndex()) {
            val all = LinkedHashSet<String>()
            val compacts = ArrayList<String>(3)
            val seenTitle = HashSet<String>()
            for (t in d.titles) {
                val display = t.trim()
                if (display.isEmpty()) continue
                val toks = normalizer.tokens(display)
                if (toks.isEmpty()) continue
                val compact = toks.joinToString("")
                if (!seenTitle.add(compact)) continue
                entryList.add(Entry(i, display, compact, toks.toTypedArray()))
                all.addAll(toks)
                compacts.add(compact)
            }
            val extra = ArrayList<String>()
            d.year?.let { extra.add(it.toString()) }
            for (e in d.extraTerms) extra.addAll(normalizer.tokens(e))
            if (extra.isNotEmpty()) {
                entryList.add(Entry(i, null, "", extra.toTypedArray()))
                all.addAll(extra)
            }
            docTokens[i] = all.toTypedArray()
            docCompacts[i] = compacts.toTypedArray()
        }
        entries = entryList.toTypedArray()
        compactOrder = entries.filter { it.display != null }.sortedBy { it.compact }.toTypedArray()

        val map = HashMap<String, ArrayList<Int>>()
        for ((ei, e) in entries.withIndex()) {
            for (t in e.tokens) {
                val l = map.getOrPut(t) { ArrayList(2) }
                if (l.isEmpty() || l[l.size - 1] != ei) l.add(ei)
            }
        }
        val keys = map.keys.sorted()
        tokenKeys = keys.toTypedArray()
        tokenPostings = Array(keys.size) { k -> map.getValue(keys[k]).toIntArray() }
    }

    // ---- prefix range helpers ----

    private fun lowerBound(keys: Array<String>, key: String): Int {
        var lo = 0; var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun tokenRange(prefix: String): IntRange {
        val lo = lowerBound(tokenKeys, prefix)
        var hi = lo
        while (hi < tokenKeys.size && tokenKeys[hi].startsWith(prefix)) hi++
        return lo until hi
    }

    private fun compactRange(prefix: String): IntRange {
        var lo = 0; var hi = compactOrder.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compactOrder[mid].compact < prefix) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < compactOrder.size && compactOrder[end].compact.startsWith(prefix)) end++
        return lo until end
    }

    // ---- token matching ----

    private fun isWord(t: String) = t.any { !it.isDigit() }

    private fun prefixMatches(q: String, toks: Array<String>): Boolean = toks.any { it.startsWith(q) }

    private fun fuzzyMatches(q: String, toks: Array<String>, isLast: Boolean): Boolean {
        if (!isWord(q)) return false
        val k = EditDistance.allowed(q.length)
        if (k == 0) return false
        for (t in toks) {
            if (EditDistance.within(q, t, k)) return true
            if (isLast && t.length > q.length && EditDistance.within(q, t.substring(0, q.length), k)) return true
        }
        return false
    }

    private fun allPrefix(q: List<String>, toks: Array<String>) = q.all { prefixMatches(it, toks) }

    private fun allFuzzy(q: List<String>, toks: Array<String>): Boolean {
        for ((i, t) in q.withIndex()) {
            if (!prefixMatches(t, toks) && !fuzzyMatches(t, toks, i == q.lastIndex)) return false
        }
        return true
    }

    private fun candidateEntries(q: List<String>): IntArray? {
        // Use the longest query token (most selective) to produce candidate entries.
        val t = q.maxByOrNull { it.length } ?: return null
        val r = tokenRange(t)
        if (r.isEmpty()) return IntArray(0)
        var n = 0
        for (k in r) n += tokenPostings[k].size
        val out = IntArray(n)
        var p = 0
        for (k in r) { val a = tokenPostings[k]; System.arraycopy(a, 0, out, p, a.size); p += a.size }
        return out
    }

    private fun fuzzyEntries(q: List<String>): IntArray {
        // Candidates: entries containing a token close to any query token.
        val out = HashSet<Int>()
        for ((qi, t) in q.withIndex()) {
            if (!isWord(t)) continue
            val k = EditDistance.allowed(t.length)
            if (k == 0) continue
            for (ki in tokenKeys.indices) {
                val key = tokenKeys[ki]
                val ok = EditDistance.within(t, key, k) ||
                    (qi == q.lastIndex && key.length > t.length && EditDistance.within(t, key.substring(0, t.length), k))
                if (ok) for (e in tokenPostings[ki]) out.add(e)
            }
        }
        return out.toIntArray()
    }

    // ---- search ----

    fun search(query: String, sourceFilter: String? = null, limit: Int = 50): List<SearchResult> {
        val qn = normalizer.normalize(query)
        if (qn.isEmpty() || limit <= 0) return emptyList()
        val qTokens = qn.split(' ')
        val qc = qTokens.joinToString("")

        val tier = IntArray(docs.size) { Int.MAX_VALUE }
        val titleHit = arrayOfNulls<String>(docs.size)
        var found = 0
        fun allowed(d: Int) = sourceFilter == null || sourceFilter in docs[d].sourceIds
        fun assign(d: Int, t: MatchType, title: String? = null) {
            if (tier[d] != Int.MAX_VALUE || !allowed(d)) return
            tier[d] = t.ordinal; titleHit[d] = title; found++
        }

        // exact + title prefix
        for (i in compactRange(qc)) {
            val e = compactOrder[i]
            assign(e.doc, if (e.compact == qc) MatchType.EXACT_TITLE else MatchType.TITLE_PREFIX, e.display)
        }
        // exact must win over a prefix hit on another title of the same doc
        for (i in compactRange(qc)) {
            val e = compactOrder[i]
            if (e.compact == qc && tier[e.doc] == MatchType.TITLE_PREFIX.ordinal) {
                tier[e.doc] = MatchType.EXACT_TITLE.ordinal; titleHit[e.doc] = e.display
            }
        }
        // token prefix (all query tokens, across titles + extras + year)
        candidateEntries(qTokens)?.forEach { ei ->
            val d = entries[ei].doc
            if (tier[d] == Int.MAX_VALUE && allPrefix(qTokens, docTokens[d])) assign(d, MatchType.TOKEN_PREFIX)
        }
        // fuzzy
        if (found < limit) {
            for (ei in fuzzyEntries(qTokens)) {
                val d = entries[ei].doc
                if (tier[d] == Int.MAX_VALUE && allFuzzy(qTokens, docTokens[d])) assign(d, MatchType.FUZZY)
            }
        }
        // substring
        if (found < limit && qc.length >= 2) {
            for (d in docs.indices) {
                if (tier[d] == Int.MAX_VALUE && docCompacts[d].any { it.contains(qc) }) assign(d, MatchType.SUBSTRING)
            }
        }

        val hits = ArrayList<Int>(found)
        for (d in docs.indices) if (tier[d] != Int.MAX_VALUE) hits.add(d)
        hits.sortWith(
            compareBy<Int> { tier[it] }
                .thenByDescending { docs[it].popularity }
                .thenByDescending { docs[it].year ?: Int.MIN_VALUE }
                .thenBy { it }
        )
        return hits.take(limit).map { d ->
            SearchResult(
                documentId = docs[d].id,
                sourceIds = docs[d].sourceIds,
                matchType = MatchType.entries[tier[d]],
                matchedTitle = titleHit[d] ?: docs[d].titles.firstOrNull { it.isNotBlank() }?.trim() ?: docs[d].id,
            )
        }
    }

    // ---- suggestions ----

    /** Completion strings (display titles) for the predictive strip, ranked like [search], deduplicated. */
    fun suggest(prefix: String, limit: Int = 8, sourceFilter: String? = null): List<String> {
        val qn = normalizer.normalize(prefix)
        if (qn.isEmpty() || limit <= 0) return emptyList()
        val qTokens = qn.split(' ')
        val qc = qTokens.joinToString("")

        val out = ArrayList<String>(limit)
        val seen = HashSet<String>()
        val used = HashSet<Entry>()
        fun flush(group: List<Entry>) {
            val sorted = group.sortedWith(
                compareByDescending<Entry> { docs[it.doc].popularity }
                    .thenByDescending { docs[it.doc].year ?: Int.MIN_VALUE }
                    .thenBy { it.doc }
            )
            for (e in sorted) {
                if (out.size >= limit) return
                if (seen.add(e.compact)) out.add(e.display!!)
            }
        }
        fun ok(e: Entry) = e.display != null && (sourceFilter == null || sourceFilter in docs[e.doc].sourceIds)

        // exact, then title prefix
        val exact = ArrayList<Entry>()
        val pref = ArrayList<Entry>()
        for (i in compactRange(qc)) {
            val e = compactOrder[i]
            if (!ok(e)) continue
            used.add(e)
            if (e.compact == qc) exact.add(e) else pref.add(e)
        }
        flush(exact); flush(pref)
        if (out.size >= limit) return out

        // token prefix inside the title
        val tp = ArrayList<Entry>()
        candidateEntries(qTokens)?.forEach { ei ->
            val e = entries[ei]
            if (ok(e) && e !in used && allPrefix(qTokens, e.tokens)) { used.add(e); tp.add(e) }
        }
        flush(tp)
        if (out.size >= limit) return out

        // fuzzy
        val fz = ArrayList<Entry>()
        for (ei in fuzzyEntries(qTokens)) {
            val e = entries[ei]
            if (ok(e) && e !in used && allFuzzy(qTokens, e.tokens)) { used.add(e); fz.add(e) }
        }
        flush(fz)
        return out
    }
}
