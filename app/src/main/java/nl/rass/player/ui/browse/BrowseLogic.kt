package nl.rass.player.ui.browse

import java.text.Collator
import java.util.Locale
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.Genre
import nl.rass.player.domain.TitleType

/** R11.4 sort keys. */
enum class BrowseSort { Newest, Year, Rating, Alpha }

/** [genreId] null = all genres; [sourceId] null = all sources. Genre ids compare lowercase (feed-spec §12.4). */
data class BrowseFilter(
    val genreId: String? = null,
    val sort: BrowseSort = BrowseSort.Newest,
    val sourceId: String? = null,
)

data class SourceOption(val id: String, val name: String)

/** Pure sort/filter functions for Browse (R2.2, R11.4). No Android or UI types, so they are unit-testable. */
object BrowseLogic {
    /** Persian collation for A–Z; titles with a Persian name sort before English-only ones. */
    fun persianComparator(): Comparator<String> {
        val c = Collator.getInstance(Locale.forLanguageTag("fa-IR")).apply { strength = Collator.SECONDARY }
        return Comparator { a, b -> c.compare(a, b) }
    }

    fun ofType(titles: List<CatalogTitle>, type: TitleType): List<CatalogTitle> = titles.filter { it.type == type }

    /** Genres (catalog order) that have at least one of [titles]. */
    fun genresWithTitles(titles: List<CatalogTitle>, genres: List<Genre>): List<Genre> {
        val used = HashSet<String>()
        titles.forEach { t -> t.meta.genreIds.forEach { used += it.lowercase() } }
        return genres.filter { it.id.lowercase() in used }
    }

    /** Sources that can play at least one of [titles], in Settings rank order. Subtitle-only sources are ignored (R7.5). */
    fun sourceOptions(titles: List<CatalogTitle>): List<SourceOption> {
        val seen = LinkedHashMap<String, Pair<Int, SourceOption>>()
        for (t in titles) for (s in t.sources) if (s.hasStreams && s.sourceId !in seen) {
            seen[s.sourceId] = s.rank to SourceOption(s.sourceId, s.sourceName)
        }
        return seen.values.sortedBy { it.first }.map { it.second }
    }

    fun filter(titles: List<CatalogTitle>, genreId: String?, sourceId: String?): List<CatalogTitle> {
        val g = genreId?.lowercase()
        return titles.filter { t ->
            (g == null || g in t.meta.genreIds.map { it.lowercase() }) &&
                (sourceId == null || sourceId in t.playableSourceIds)
        }
    }

    fun sort(
        titles: List<CatalogTitle>,
        sort: BrowseSort,
        collate: Comparator<String> = persianComparator(),
    ): List<CatalogTitle> {
        val alpha = Comparator<CatalogTitle> { a, b ->
            val fa = (a.meta.titleFa == null).compareTo(b.meta.titleFa == null)
            if (fa != 0) fa else collate.compare(a.meta.displayTitle, b.meta.displayTitle)
        }
        // Missing values always go last; ties fall back to A–Z and finally the key, so the order is stable.
        val primary: Comparator<CatalogTitle> = when (sort) {
            BrowseSort.Newest -> compareBy<CatalogTitle> { it.addedAt == null }.thenByDescending { it.addedAt ?: 0L }
            BrowseSort.Year -> compareBy<CatalogTitle> { it.meta.year == null }.thenByDescending { it.meta.year ?: 0 }
            BrowseSort.Rating -> compareBy<CatalogTitle> { it.meta.rating == null }.thenByDescending { it.meta.rating ?: 0.0 }
            BrowseSort.Alpha -> alpha
        }
        return titles.sortedWith(primary.then(alpha).thenBy { it.key })
    }

    fun apply(
        titles: List<CatalogTitle>,
        filter: BrowseFilter,
        collate: Comparator<String> = persianComparator(),
    ): List<CatalogTitle> = sort(filter(titles, filter.genreId, filter.sourceId), filter.sort, collate)

    /** Key to focus after [removedKey] disappears from [keys]: the next card, else the previous, else null. */
    fun neighborKey(keys: List<String>, removedKey: String): String? {
        val i = keys.indexOf(removedKey)
        if (i < 0) return null
        return keys.getOrNull(i + 1) ?: keys.getOrNull(i - 1)
    }
}
