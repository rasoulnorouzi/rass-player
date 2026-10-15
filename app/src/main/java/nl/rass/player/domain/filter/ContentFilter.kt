package nl.rass.player.domain.filter

import kotlinx.serialization.Serializable

/** feed-spec §4.5 `content_warnings` values. */
enum class ContentWarning(val wire: String) {
    SEXUAL("sexual"), NUDITY("nudity"), GORE("gore"), DISTURBING("disturbing"),
    VIOLENCE("violence"), HORROR("horror"), DRUGS("drugs"), LANGUAGE("language");

    companion object {
        fun fromWire(v: String?): ContentWarning? = v?.trim()?.lowercase()?.let { w -> entries.firstOrNull { it.wire == w } }
    }
}

/** feed-spec §4.5 `content_rating` → minimum age. */
object ContentRatings {
    private val AGES = mapOf(
        "G" to 0, "TV-Y" to 0, "TV-G" to 0, "TV-Y7" to 7, "PG" to 8, "TV-PG" to 8,
        "PG-13" to 13, "TV-14" to 14, "R" to 17, "TV-MA" to 17, "NC-17" to 18,
    )
    private val AGE = Regex("^AGE:([0-9]{1,2})$")

    /** Canonical form (`PG-13`, `age:16`) or null for unknown values. */
    fun canonical(v: String?): String? {
        val u = v?.trim()?.uppercase() ?: return null
        if (u in AGES) return u
        return AGE.matchEntire(u)?.let { "age:" + it.groupValues[1].toInt() }
    }

    fun ageOf(v: String?): Int? {
        val u = v?.trim()?.uppercase() ?: return null
        AGES[u]?.let { return it }
        return AGE.matchEntire(u)?.groupValues?.get(1)?.toInt()
    }
}

/**
 * User settings of the content filter (R13). "Sensitive" titles are kept off Home; with [hideEverywhere] they are
 * removed from the whole catalog. [showOnHome] turns the Home exclusion off.
 */
@Serializable
data class ContentFilterConfig(
    val enabled: Boolean = true,
    val showOnHome: Boolean = false,
    val hideEverywhere: Boolean = false,
    val maxAge: Int = 16,
    /** [ContentWarning.wire] values. */
    val blockedWarnings: Set<String> = DEFAULT_WARNINGS,
    val hideUnrated: Boolean = false,
    val customKeywords: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT_WARNINGS = setOf("sexual", "nudity", "gore", "disturbing")
        const val MAX_AGE_NONE = 99
        val Off = ContentFilterConfig(enabled = false)
    }
}

/** What the filter looks at; built from a title or an episode. */
data class FilterSubject(
    val rating: String? = null,
    val warnings: Collection<String> = emptyList(),
    /** Genre ids and display names (fa and en). */
    val genres: Collection<String> = emptyList(),
    /** Titles, descriptions, any other free text. */
    val texts: Collection<String> = emptyList(),
)

object ContentFilter {
    /** Genres treated as sexual content (normalized, see [normalize]). */
    val BLOCKED_GENRES: Set<String> = setOf(
        "erotic", "erotica", "adult", "adults", "adult film", "sexploitation", "exploitation", "porn", "porno",
        "pornography", "xxx", "softcore", "hardcore",
        "اروتیک", "بزرگسالان", "بزرگسال", "سکسی", "پورن", "پورنو", "عریان",
    )

    /** Whole-word keywords (en + fa). Matched with Unicode word boundaries, never as substrings. */
    val DEFAULT_KEYWORDS: List<String> = listOf(
        "sex", "sexy", "sexual", "sexually", "sexuality", "sexploitation", "erotic", "erotica", "erotically",
        "nude", "nudes", "nudity", "porn", "porno", "pornographic", "pornography", "adult film",
        "adult films", "adult movie", "exploitation film", "exploitation films", "sex hygiene", "striptease", "stripper",
        "سکسی", "سکس", "جنسی", "برهنه", "برهنگی", "عریان", "اروتیک", "بزرگسالان", "پورن", "پورنو",
    )

    private val PRECOMPILED = HashMap<String, Regex>()

    /** Lowercase, Persian letter forms, no diacritics/ZWNJ/tatweel, single spaces. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            when (ch) {
                'ي', 'ى' -> sb.append('ی')
                'ك' -> sb.append('ک')
                '‌', '‍', 'ـ' -> sb.append(' ')
                in 'ً'..'ٟ', 'ٰ' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun wordRegex(keyword: String): Regex? {
        val k = normalize(keyword)
        if (k.isEmpty()) return null
        synchronized(PRECOMPILED) {
            return PRECOMPILED.getOrPut(k) {
                Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(k).replace(" ", "\\E[^\\p{L}\\p{N}]+\\Q") + "(?![\\p{L}\\p{N}])")
            }
        }
    }

    fun matchesKeyword(text: String, keyword: String): Boolean {
        val re = wordRegex(keyword) ?: return false
        return re.containsMatchIn(normalize(text))
    }

    /** Why a subject is sensitive, or null. Used by tests and the report; [isSensitive] is the boolean form. */
    fun reason(subject: FilterSubject, config: ContentFilterConfig): String? {
        if (!config.enabled) return null
        val blocked = config.blockedWarnings.mapNotNullTo(HashSet()) { ContentWarning.fromWire(it)?.wire }
        subject.warnings.firstOrNull { ContentWarning.fromWire(it)?.wire in blocked }?.let { return "warning:$it" }
        val age = ContentRatings.ageOf(subject.rating)
        if (age != null && age > config.maxAge) return "rating:${subject.rating}"
        if (age == null && config.hideUnrated) return "unrated"
        for (g in subject.genres) {
            val n = normalize(g)
            if (n in BLOCKED_GENRES) return "genre:$g"
        }
        val keywords = DEFAULT_KEYWORDS + config.customKeywords
        for (text in subject.texts) {
            if (text.isBlank()) continue
            val n = normalize(text)
            for (k in keywords) {
                val re = wordRegex(k) ?: continue
                if (re.containsMatchIn(n)) return "keyword:$k"
            }
        }
        for (g in subject.genres) { // custom keywords also apply to genre names
            for (k in config.customKeywords) if (matchesKeyword(g, k)) return "keyword:$k"
        }
        return null
    }

    fun isSensitive(subject: FilterSubject, config: ContentFilterConfig): Boolean = reason(subject, config) != null
}
