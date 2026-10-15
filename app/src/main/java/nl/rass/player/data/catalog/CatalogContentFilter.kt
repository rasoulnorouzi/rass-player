package nl.rass.player.data.catalog

import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogEpisode
import nl.rass.player.domain.CatalogSeason
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.TitleType
import nl.rass.player.domain.filter.ContentFilter
import nl.rass.player.domain.filter.ContentFilterConfig
import nl.rass.player.domain.filter.FilterSubject

/**
 * Applies the content filter (R13) to a merged [Catalog]: flags [CatalogTitle.sensitive] (kept off Home) and, with
 * "hide everywhere", removes sensitive titles and episodes (their keys go to [Catalog.hiddenKeys]).
 */
object CatalogContentFilter {

    fun subject(t: CatalogTitle, genreNames: Map<String, List<String>>): FilterSubject = FilterSubject(
        rating = t.meta.contentRating,
        warnings = t.meta.contentWarnings,
        genres = t.meta.genreIds.flatMap { listOf(it) + genreNames[it].orEmpty() },
        texts = t.searchTitles + listOfNotNull(t.meta.titleFa, t.meta.titleEn, t.meta.originalTitle, t.meta.descriptionFa, t.meta.descriptionEn),
    )

    fun subject(e: CatalogEpisode) = FilterSubject(
        rating = e.contentRating,
        warnings = e.contentWarnings,
        texts = listOfNotNull(e.titleFa, e.titleEn, e.descriptionFa, e.descriptionEn),
    )

    fun apply(c: Catalog, config: ContentFilterConfig): Catalog {
        if (!config.enabled) return c.copy(homeShowsSensitive = true, hiddenKeys = emptySet())
        val names = c.genres.associate { it.id to listOfNotNull(it.nameFa, it.nameEn) }
        val flagged = c.titles.map { t -> t.copy(sensitive = ContentFilter.isSensitive(subject(t, names), config)) }
        if (!config.hideEverywhere) {
            val byKey = flagged.associateBy { it.key }
            return c.copy(
                titles = flagged,
                featured = c.featured.mapNotNull { byKey[it.key] },
                top10 = c.top10.mapNotNull { byKey[it.key] },
                recentlyAdded = c.recentlyAdded.mapNotNull { byKey[it.key] },
                homeShowsSensitive = config.showOnHome,
                hiddenKeys = emptySet(),
            )
        }
        val hidden = LinkedHashSet<String>()
        val kept = ArrayList<CatalogTitle>(flagged.size)
        for (t in flagged) {
            if (t.sensitive) { hidden.add(t.key); continue }
            if (t.type == TitleType.SERIES) {
                val seasons = t.seasons.mapNotNull { s ->
                    val eps = s.episodes.filter { e ->
                        val bad = ContentFilter.isSensitive(subject(e), config)
                        if (bad) hidden.add(e.key)
                        !bad
                    }
                    if (eps.isEmpty()) null else CatalogSeason(s.number, s.name, eps)
                }
                if (seasons.isEmpty()) { hidden.add(t.key); continue }
                kept.add(t.copy(seasons = seasons))
            } else kept.add(t)
        }
        val byKey = kept.associateBy { it.key }
        val usedGenres = kept.flatMapTo(HashSet()) { it.meta.genreIds }
        val removedGenres = flagged.filter { it.key in hidden }.flatMapTo(HashSet()) { it.meta.genreIds } - usedGenres
        val genres = c.genres.filter { g ->
            g.id !in removedGenres && listOfNotNull(g.id, g.nameFa, g.nameEn).none { it.let(ContentFilter::normalize) in ContentFilter.BLOCKED_GENRES }
        }
        return c.copy(
            titles = kept, genres = genres,
            featured = c.featured.mapNotNull { byKey[it.key] },
            top10 = c.top10.mapNotNull { byKey[it.key] },
            recentlyAdded = c.recentlyAdded.mapNotNull { byKey[it.key] },
            homeShowsSensitive = true, hiddenKeys = hidden,
        )
    }
}
