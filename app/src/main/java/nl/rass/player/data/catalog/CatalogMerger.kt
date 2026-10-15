package nl.rass.player.data.catalog

import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogEpisode
import nl.rass.player.domain.CatalogKeys
import nl.rass.player.domain.CatalogSeason
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.Episode
import nl.rass.player.domain.Genre
import nl.rass.player.domain.Movie
import nl.rass.player.domain.Series
import nl.rass.player.domain.SourceCatalog
import nl.rass.player.domain.SourceRef
import nl.rass.player.domain.SourceStats
import nl.rass.player.domain.SourcedStream
import nl.rass.player.domain.SourcedSubtitle
import nl.rass.player.domain.Subtitle
import nl.rass.player.domain.Title
import nl.rass.player.domain.TitleType
import nl.rass.player.domain.filter.ContentRatings
import nl.rass.player.domain.seasonOrder
import nl.rass.player.search.PersianTextNormalizer

/** One enabled source's catalog; the list passed to [CatalogMerger.merge] is in rank order (0 = top). */
data class MergeInput(val sourceId: String, val sourceName: String, val catalog: SourceCatalog)

/**
 * Merges every enabled source into one [Catalog] (feed-spec §12).
 *
 * - Matching (§12.1): same type, not from a source already in the merged title, then tmdb decides, else imdb
 *   decides, else equal year **and** an equal normalized title in the same field. Feed-local ids are never
 *   compared across sources. Titles of one source never merge with each other.
 * - Normalization: [PersianTextNormalizer.compact] (the search normalizer with spaces removed). It folds more
 *   Arabic variants than §12.1 lists and ignores spaces, so it matches a superset of the spec's pairs.
 * - Streams and subtitles are independent (R6.6): every member contributes its fa/en subtitles to the merged
 *   movie/episode, deduplicated by URL, even a member that has no playable stream. Members without streams
 *   never create a visible title on their own.
 */
class CatalogMerger(private val normalizer: PersianTextNormalizer = PersianTextNormalizer.Default) {

    private class Member(val rank: Int, val sourceId: String, val sourceName: String, val title: Title)

    private enum class Field { EN, FA, ORIGINAL }

    private data class TyKey(val type: TitleType, val year: Int, val field: Field, val norm: String)

    private class Builder(val order: Int, val type: TitleType) {
        val members = ArrayList<Member>(2)
        val sourceIds = HashSet<String>()
        var tmdb: Int? = null
        var imdb: String? = null
        var year: Int? = null
        val titles = LinkedHashSet<Pair<Field, String>>()
    }

    /** Full merge; [firstSeen] supplies the "added" time of titles without `added_at`. */
    fun merge(inputs: List<MergeInput>, firstSeen: Map<String, Long> = emptyMap(), recentLimit: Int = 40): Catalog =
        finish(mergeRaw(inputs), firstSeen, recentLimit)

    /**
     * Stage 2: fills [CatalogTitle.addedAt] from [firstSeen] where the feeds had none, then builds
     * Recently added and the `featured` fallback (§3.2: the 10 newest titles with a backdrop).
     */
    fun finish(raw: Catalog, firstSeen: Map<String, Long>, recentLimit: Int = 40): Catalog {
        val titles = raw.titles.map { t -> if (t.addedAt == null && firstSeen[t.key] != null) t.copy(addedAt = firstSeen[t.key]) else t }
        val byKey = titles.associateBy { it.key }
        val recentlyAdded = titles.filter { it.addedAt != null }.sortedByDescending { it.addedAt }.take(recentLimit)
        var featured = raw.featured.mapNotNull { byKey[it.key] }
        if (featured.isEmpty()) featured = recentlyAdded.filter { it.meta.backdrop != null }.take(10)
        return raw.copy(
            titles = titles,
            featured = featured,
            top10 = raw.top10.mapNotNull { byKey[it.key] },
            recentlyAdded = recentlyAdded,
        )
    }

    /** Stage 1 (§12): matching and building; `featured` holds only feed-supplied entries, no Recently added yet. */
    fun mergeRaw(inputs: List<MergeInput>): Catalog {
        val builders = ArrayList<Builder>()
        val tmdbIdx = HashMap<Pair<TitleType, Int>, MutableList<Builder>>()
        val imdbIdx = HashMap<Pair<TitleType, String>, MutableList<Builder>>()
        val tyIdx = HashMap<TyKey, MutableList<Builder>>()
        val memberOf = HashMap<Pair<String, String>, Builder>() // (sourceId, titleId) → builder

        fun normTitles(t: Title): List<Pair<Field, String>> = buildList {
            t.meta.titleEn?.let { normalizer.compact(it) }?.takeIf { it.isNotEmpty() }?.let { add(Field.EN to it) }
            t.meta.titleFa?.let { normalizer.compact(it) }?.takeIf { it.isNotEmpty() }?.let { add(Field.FA to it) }
            t.meta.originalTitle?.let { normalizer.compact(it) }?.takeIf { it.isNotEmpty() }?.let { add(Field.ORIGINAL to it) }
        }

        fun matches(c: Title, cTitles: List<Pair<Field, String>>, m: Builder): Boolean {
            val ct = c.meta.ids.tmdb; val mt = m.tmdb
            if (ct != null && mt != null) return ct == mt
            val ci = c.meta.ids.imdb; val mi = m.imdb
            if (ci != null && mi != null) return ci.equals(mi, ignoreCase = true)
            val cy = c.meta.year; val my = m.year
            return cy != null && my != null && cy == my && cTitles.any { it in m.titles }
        }

        fun join(b: Builder, member: Member, cTitles: List<Pair<Field, String>>) {
            b.members.add(member); b.sourceIds.add(member.sourceId)
            memberOf[member.sourceId to member.title.id] = b
            val ids = member.title.meta.ids
            if (b.tmdb == null && ids.tmdb != null) {
                b.tmdb = ids.tmdb; tmdbIdx.getOrPut(b.type to ids.tmdb) { ArrayList(1) }.add(b)
            }
            if (b.imdb == null && ids.imdb != null) {
                b.imdb = ids.imdb.lowercase(); imdbIdx.getOrPut(b.type to b.imdb!!) { ArrayList(1) }.add(b)
            }
            val y = member.title.meta.year
            if (b.year == null && y != null) {
                b.year = y
                for ((f, n) in b.titles) tyIdx.getOrPut(TyKey(b.type, y, f, n)) { ArrayList(1) }.add(b)
            }
            for (ft in cTitles) if (b.titles.add(ft)) b.year?.let { yr -> tyIdx.getOrPut(TyKey(b.type, yr, ft.first, ft.second)) { ArrayList(1) }.add(b) }
        }

        inputs.forEachIndexed { rank, input ->
            for (t in input.catalog.titles) {
                val cTitles = normTitles(t)
                val candidates = LinkedHashSet<Builder>()
                t.meta.ids.tmdb?.let { tmdbIdx[t.type to it]?.let(candidates::addAll) }
                t.meta.ids.imdb?.let { imdbIdx[t.type to it.lowercase()]?.let(candidates::addAll) }
                t.meta.year?.let { y -> for ((f, n) in cTitles) tyIdx[TyKey(t.type, y, f, n)]?.let(candidates::addAll) }
                val target = candidates
                    .filter { it.type == t.type && input.sourceId !in it.sourceIds && matches(t, cTitles, it) }
                    .minByOrNull { it.order }
                    ?: Builder(builders.size, t.type).also { builders.add(it) }
                join(target, Member(rank, input.sourceId, input.sourceName, t), cTitles)
            }
        }

        // ---- build ----
        val usedKeys = HashSet<String>()
        val byBuilder = HashMap<Builder, CatalogTitle>()
        val titles = ArrayList<CatalogTitle>(builders.size)
        for (b in builders) {
            val ct = build(b, usedKeys) ?: continue
            byBuilder[b] = ct
            titles.add(ct)
        }

        val genres = LinkedHashMap<String, Genre>()
        for (input in inputs) for (g in input.catalog.genres) {
            val id = g.id.lowercase()
            if (id !in genres) genres[id] = g.copy(id = id)
        }

        fun resolve(sourceId: String, ids: List<String>): List<CatalogTitle> =
            ids.mapNotNull { memberOf[sourceId to it]?.let(byBuilder::get) }.distinct()

        val featured = inputs.flatMap { resolve(it.sourceId, it.catalog.featured) }
            .distinct().filter { it.meta.backdrop != null }.take(10)
        val top10 = inputs.asSequence().map { resolve(it.sourceId, it.catalog.top10) }.firstOrNull { it.isNotEmpty() }.orEmpty().take(10)

        val stats = inputs.associate { input ->
            val playable = input.catalog.titles.filter { it.isPlayable }
            input.sourceId to SourceStats(
                sourceId = input.sourceId,
                movies = playable.count { it is Movie },
                series = playable.count { it is Series },
                episodes = playable.filterIsInstance<Series>().sumOf { s -> s.seasons.sumOf { se -> se.episodes.count { it.isPlayable } } },
            )
        }
        return Catalog(titles, genres.values.toList(), featured, top10, emptyList(), stats)
    }

    private fun build(b: Builder, usedKeys: MutableSet<String>): CatalogTitle? {
        val playable = b.members.filter { it.title.isPlayable }
        if (playable.isEmpty()) return null
        val primary = playable.first()
        val metaOrder = listOf(primary) + b.members.filter { it !== primary }
        val meta = metaOrder.drop(1).fold(primary.title.meta) { acc, m -> acc.fallbackTo(m.title.meta) }
            .let { it.copy(genreIds = it.genreIds.map(String::lowercase).distinct()) }

        var key = CatalogKeys.titleKey(b.type, b.tmdb, b.imdb, primary.sourceId, primary.title.id)
        if (!usedKeys.add(key)) {
            key = CatalogKeys.sourceKey(primary.sourceId, primary.title.id)
            usedKeys.add(key)
        }
        val sources = b.members.map { SourceRef(it.sourceId, it.sourceName, it.rank, it.title.id, it.title.isPlayable) }
        val addedAt = meta.addedAt
        val searchTitles = metaOrder.flatMap { m -> listOfNotNull(m.title.meta.titleFa, m.title.meta.titleEn, m.title.meta.originalTitle) }.distinct()

        return when (b.type) {
            TitleType.MOVIE -> {
                val movies = metaOrder.map { it to it.title as Movie }
                CatalogTitle(
                    key = key, type = b.type, meta = meta, sources = sources,
                    streams = b.members.flatMap { m ->
                        val mv = m.title as Movie
                        mv.streams.map { SourcedStream(m.sourceId, m.sourceName, m.rank, it, mv.markers) }
                    },
                    subtitles = poolSubtitles(movies.map { (m, mv) -> m to mv.subtitles }),
                    addedAt = addedAt, searchTitles = searchTitles,
                )
            }
            TitleType.SERIES -> CatalogTitle(
                key = key, type = b.type, meta = meta, sources = sources,
                seasons = mergeSeasons(key, metaOrder),
                addedAt = addedAt, searchTitles = searchTitles,
            )
        }
    }

    private fun poolSubtitles(perMember: List<Pair<Member, List<Subtitle>>>): List<SourcedSubtitle> {
        val seen = HashSet<String>()
        return perMember.flatMap { (m, subs) ->
            subs.filter { seen.add(it.url) }.map { SourcedSubtitle(m.sourceId, m.sourceName, it) }
        }
    }

    /** §12.2 "Series": seasons united by number, episodes by (season, episode) number. */
    private fun mergeSeasons(titleKey: String, metaOrder: List<Member>): List<CatalogSeason> {
        // season number → episode number → members having that episode (meta order)
        val seasons = LinkedHashMap<Int, LinkedHashMap<Int, MutableList<Pair<Member, Episode>>>>()
        val seasonNames = HashMap<Int, String>()
        for (m in metaOrder) for (s in (m.title as Series).seasons) {
            if (s.name != null) seasonNames.putIfAbsent(s.number, s.name)
            val eps = seasons.getOrPut(s.number) { LinkedHashMap() }
            for (e in s.episodes) eps.getOrPut(e.number) { ArrayList(1) }.add(m to e)
        }
        return seasons.keys.sortedWith(seasonOrder).mapNotNull { sn ->
            val episodes = seasons.getValue(sn).keys.sorted().mapNotNull { en ->
                val having = seasons.getValue(sn).getValue(en)
                val withStreams = having.filter { it.second.isPlayable }
                if (withStreams.isEmpty()) return@mapNotNull null
                val ordered = withStreams + having.filter { !it.second.isPlayable }
                fun <T> first(f: (Episode) -> T?): T? = ordered.firstNotNullOfOrNull { f(it.second) }
                CatalogEpisode(
                    key = CatalogKeys.episodeKey(titleKey, sn, en),
                    seasonNumber = sn, number = en,
                    titleFa = first { it.titleFa }, titleEn = first { it.titleEn },
                    descriptionFa = first { it.descriptionFa }, descriptionEn = first { it.descriptionEn },
                    still = first { it.still }, runtimeMin = first { it.runtimeMin },
                    airDate = first { it.airDate }, addedAt = first { it.addedAt },
                    streams = having.sortedBy { it.first.rank }.flatMap { (m, e) ->
                        e.streams.map { SourcedStream(m.sourceId, m.sourceName, m.rank, it, e.markers) }
                    },
                    subtitles = poolSubtitles(ordered.map { it.first to it.second.subtitles }),
                    contentRating = having.mapNotNull { it.second.contentRating }.maxByOrNull { ContentRatings.ageOf(it) ?: -1 },
                    contentWarnings = having.flatMap { it.second.contentWarnings }.distinct(),
                    sources = having.sortedBy { it.first.rank }.map { (m, e) ->
                        SourceRef(m.sourceId, m.sourceName, m.rank, e.id, e.isPlayable)
                    },
                )
            }
            if (episodes.isEmpty()) null else CatalogSeason(sn, seasonNames[sn], episodes)
        }
    }
}
