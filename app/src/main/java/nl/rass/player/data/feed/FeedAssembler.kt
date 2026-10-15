package nl.rass.player.data.feed

import nl.rass.player.domain.Genre
import nl.rass.player.domain.Movie
import nl.rass.player.domain.Series
import nl.rass.player.domain.SourceCatalog
import nl.rass.player.domain.Title

data class AssembledFeed(val catalog: SourceCatalog, val report: FeedReport)

/**
 * Merges a main document with its parts (feed-spec §11) and runs the cross-document checks of §10.1:
 * duplicate ids (first in document order wins), unknown genre / featured / top10 references, the
 * 20,000-titles-per-source limit.
 *
 * @param parts one entry per URL in `main.parts`, null when that part failed and has no cached copy.
 */
object FeedAssembler {
    const val MAX_TITLES_PER_SOURCE = 20_000

    fun assemble(main: ParsedDocument, parts: List<ParsedDocument?>, extraIssues: FeedIssues? = null): AssembledFeed {
        val issues = FeedIssues()
        issues.addAll(main.issues)
        parts.forEach { it?.let { d -> issues.addAll(d.issues) } }
        extraIssues?.let { issues.addAll(it) }

        val docs = listOf(main) + parts.filterNotNull()
        val genres = LinkedHashMap<String, Genre>()
        for (d in docs) for ((path, g) in d.genres) {
            if (genres.containsKey(g.id)) issues.skip("$path.id", "duplicate genre id") else genres[g.id] = g
        }

        val titles = LinkedHashMap<String, Title>()
        var overLimit = false
        for (d in docs) for (pt in d.titles) {
            if (titles.containsKey(pt.title.id)) { issues.skip("${pt.path}.id", "duplicate title id"); continue }
            if (titles.size >= MAX_TITLES_PER_SOURCE) {
                if (!overLimit) issues.warn(pt.path, "more than $MAX_TITLES_PER_SOURCE titles; extra dropped")
                overLimit = true
                continue
            }
            val t = pt.title
            val known = t.meta.genreIds.filter { it in genres }
            if (known.size != t.meta.genreIds.size) issues.warn("${pt.path}.genres", "unknown genre id dropped")
            titles[t.id] = if (known.size == t.meta.genreIds.size) t else when (t) {
                is Movie -> t.copy(meta = t.meta.copy(genreIds = known))
                is Series -> t.copy(meta = t.meta.copy(genreIds = known))
            }
        }

        fun refs(list: List<String>, key: String): List<String> = list.filter { id ->
            (id in titles).also { if (!it) issues.warn(key, "unknown title id '$id'") }
        }
        val featured = refs(main.featured, "featured")
        val top10 = refs(main.top10, "top10")

        val catalog = SourceCatalog(
            name = main.name,
            description = main.description,
            isTest = main.test,
            updatedAt = main.updatedAt,
            genres = genres.values.toList(),
            titles = titles.values.toList(),
            featured = featured,
            top10 = top10,
        )
        val playable = catalog.titles.filter { it.isPlayable }
        val report = FeedReport(
            movies = playable.count { it is Movie },
            series = playable.count { it is Series },
            episodes = playable.filterIsInstance<Series>().sumOf { s -> s.seasons.sumOf { se -> se.episodes.count { it.isPlayable } } },
            skipped = issues.skipped,
            warnings = issues.warnings,
            partErrors = issues.partErrors,
            partsLoaded = parts.count { it != null },
            partsTotal = main.parts.size,
            issues = issues.issues,
        )
        return AssembledFeed(catalog, report)
    }
}
