package nl.rass.player.player.subtitle.online

/** A subtitle offered by the feed (pooled from all sources, R6.6). */
data class PooledSubtitle(val lang: String, val sourceName: String, val url: String)

data class AutoPickPrefs(
    val subtitlesOn: Boolean,
    /** "fa", "en" or "off". */
    val preferredLang: String,
)

sealed class AutoPick(val noticeFa: String?) {
    /** Nothing to change (off, or the stream already has the preferred language). */
    data class Keep(val why: String) : AutoPick(null)
    data class Pooled(val subtitle: PooledSubtitle) : AutoPick(
        "زیرنویس ${langFa(subtitle.lang)} از «${subtitle.sourceName}» انتخاب شد",
    )
    data class Online(val result: OnlineSubtitleResult) : AutoPick(
        "زیرنویس ${langFa(result.lang)} از ${result.provider} انتخاب شد",
    )
    /** No pooled subtitle fits; the caller should run an online search and call the picker again. */
    data object NeedsOnlineSearch : AutoPick(null)
    data object NothingFound : AutoPick("زیرنویسی پیدا نشد")
}

internal fun langFa(l: String) = if (l == "fa") "فارسی" else "انگلیسی"

/** R6.8: stream's own subtitle in the preferred language, else best pooled, else best online. */
object SubtitleAutoPicker {
    /**
     * @param streamLangs languages of subtitles that come with the playing stream
     * @param online ranked online results ([OnlineSubtitleRanking.rank]); null if no search was done yet
     */
    fun pick(
        prefs: AutoPickPrefs,
        streamLangs: Set<String>,
        pooled: List<PooledSubtitle>,
        online: List<OnlineSubtitleResult>?,
    ): AutoPick {
        if (!prefs.subtitlesOn || prefs.preferredLang == "off") return AutoPick.Keep("subtitles off")
        val pref = prefs.preferredLang
        val other = if (pref == "fa") "en" else "fa"
        if (pref in streamLangs) return AutoPick.Keep("stream has $pref")

        pooled.firstOrNull { it.lang == pref }?.let { return AutoPick.Pooled(it) }
        if (online == null) return AutoPick.NeedsOnlineSearch
        online.firstOrNull { it.lang == pref }?.let { return AutoPick.Online(it) }
        // Preferred language unavailable everywhere: fall back to the other one.
        if (other in streamLangs) return AutoPick.Keep("stream has $other")
        pooled.firstOrNull { it.lang == other }?.let { return AutoPick.Pooled(it) }
        online.firstOrNull { it.lang == other }?.let { return AutoPick.Online(it) }
        return AutoPick.NothingFound
    }
}
