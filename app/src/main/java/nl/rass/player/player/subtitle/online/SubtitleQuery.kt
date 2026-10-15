package nl.rass.player.player.subtitle.online

/** What to look for online (R6.7). Deliberately independent of the domain models. */
data class SubtitleQuery(
    /** IMDb id of the title (the series for episodes), with or without the `tt` prefix. */
    val imdbId: String? = null,
    val tmdbId: Long? = null,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val isSeries: Boolean = false,
    /** Wanted languages, normalized codes: "fa", "en". */
    val languages: List<String> = listOf("fa", "en"),
    /** Release / file name of the playing stream, used to rank by similarity. */
    val releaseHint: String? = null,
) {
    /** "tt1234567" form, or null if blank. */
    val imdbTt: String? get() = imdbId?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.startsWith("tt", ignoreCase = true)) "tt" + it.substring(2) else "tt$it"
    }

    /** Digits only, as OpenSubtitles wants it. */
    val imdbDigits: String? get() = imdbId?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }?.trimStart('0')?.ifEmpty { "0" }

    val cacheKey: String
        get() = listOf(imdbDigits, tmdbId, title.trim().lowercase(), year, season, episode, isSeries, languages.joinToString(","))
            .joinToString("|")
}
