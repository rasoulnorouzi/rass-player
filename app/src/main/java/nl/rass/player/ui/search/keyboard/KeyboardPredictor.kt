package nl.rass.player.ui.search.keyboard

import nl.rass.player.search.PersianTextNormalizer

/** One chip in the predictor row: [label] is shown, [result] is the whole field text after picking it. */
data class Prediction(val label: String, val result: String)

/**
 * R7.6: word completion for the in-app keyboard (fa + en). Pure logic, no Android.
 * The field is append-only, so the "current word" is the trailing run of non-space characters
 * (a ZWNJ belongs to the word: «می» is still being typed).
 */
object KeyboardPredictor {
    const val MAX = 6
    val urlSnippets = listOf("https://", "www.", ".com", ".ir", ".nl", "/")
    private val urlSuffixes = listOf(".com", ".ir", ".nl", "/")
    private val norm = PersianTextNormalizer.Default

    /** The word being typed at the end of [text] ("" after a space or in an empty field). */
    fun currentWord(text: String): String {
        var i = text.length
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return text.substring(i)
    }

    /** Words of [phrases], most frequent first (ties keep first-seen order); ignores words shorter than 2 chars. */
    fun vocabulary(phrases: Iterable<String>): List<String> {
        val count = LinkedHashMap<String, Int>()
        val shown = HashMap<String, String>()
        for (p in phrases) for (w in p.split(' ', '\t', '\n', '-', ':', '،', ',', '(', ')')) {
            val word = w.trim('.', '!', '؟', '?', '"', '«', '»', '\'', ZWNJ)
            if (word.length < 2) continue
            val k = fold(word)
            count[k] = (count[k] ?: 0) + 1
            shown.putIfAbsent(k, word)
        }
        return count.entries.sortedByDescending { it.value }.map { shown.getValue(it.key) }
    }

    /**
     * Up to [MAX] predictions for the end of [text].
     * Ranking: [recents] words first, then [vocab] (already frequency ordered), then shorter, then alphabetical.
     * In [urlMode], known [domains], `https://`, `www.`, `.com`, `.ir`, `.nl` and `/` are offered.
     */
    fun predict(
        text: String,
        vocab: List<String>,
        recents: List<String> = emptyList(),
        urlMode: Boolean = false,
        domains: List<String> = emptyList(),
    ): List<Prediction> {
        val word = currentWord(text)
        val head = text.dropLast(word.length)
        if (urlMode) return predictUrl(text, word, head, domains)
        val key = fold(word)
        if (key.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Prediction>()
        val candidates = vocabulary(recents).map { 0 to it } + vocab.map { 1 to it }
        candidates
            .filter { (_, w) -> fold(w).startsWith(key) && fold(w) != key }
            .sortedWith(compareBy({ it.first }, { it.second.length }, { it.second }))
            .forEach { (_, w) -> if (seen.add(fold(w))) out += Prediction(w, "$head$w ") }
        return out.take(MAX)
    }

    private fun predictUrl(text: String, word: String, head: String, domains: List<String>): List<Prediction> {
        val out = ArrayList<Prediction>()
        val lower = word.lowercase()
        // Known domains complete the part after the scheme: «https://my» -> «https://myhost.nl».
        val scheme = Regex("^(https?://)?(www\\.)?", RegexOption.IGNORE_CASE).find(word)?.value.orEmpty()
        val typed = lower.removePrefix(scheme.lowercase())
        for (d in domains) if (typed.isNotEmpty() && d.lowercase().startsWith(typed) && d.lowercase() != typed) {
            out += Prediction(d, head + scheme + d)
        }
        for (s in urlSnippets) {
            if (s in urlSuffixes) continue
            if (s.startsWith(lower) && s != lower) out += Prediction(s, head + s)
        }
        if (lower.isNotEmpty() && !lower.endsWith("/")) {
            val hasTld = urlSuffixes.any { it != "/" && lower.endsWith(it) }
            for (s in urlSuffixes) if (!(hasTld && s != "/")) out += Prediction(s, text + s)
        }
        if (lower.isEmpty()) domains.forEach { out += Prediction(it, text + it) }
        return out.distinctBy { it.result }.take(MAX)
    }

    /** Host of [url] without scheme, `www.` and path, or null when it has no usable host. */
    fun domainOf(url: String): String? {
        val s = url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
            .removePrefix("www.").lowercase()
        return s.takeIf { '.' in it && it.length > 3 && it.none(Char::isWhitespace) }
    }

    private fun fold(s: String): String = norm.compact(s)
}
