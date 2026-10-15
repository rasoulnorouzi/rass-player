package nl.rass.player.domain

/**
 * One source that carries (part of) a merged title. [titleId] is that source's feed-local id; it is only ever
 * used together with [sourceId].
 *
 * @param rank position of the source in Settings among enabled sources (0 = top, wins ties, §12).
 * @param hasStreams false when this source only contributes subtitles (R6.6). Badges and the per-source
 *   filter (R7.5) use only sources with streams.
 */
data class SourceRef(
    val sourceId: String,
    val sourceName: String,
    val rank: Int,
    val titleId: String,
    val hasStreams: Boolean = true,
)

/** A stream plus where it came from; the input of [nl.rass.player.domain.playback.StreamCandidates]. */
data class SourcedStream(
    val sourceId: String,
    val sourceName: String,
    val rank: Int,
    val stream: Stream,
    /** Markers of this source for this item (§9: markers belong to the source the candidate came from). */
    val markers: Markers?,
)

/** A subtitle pooled from any source (R6.6). Independent from the stream that plays. */
data class SourcedSubtitle(
    val sourceId: String,
    val sourceName: String,
    val subtitle: Subtitle,
) {
    val lang: String get() = subtitle.lang
    val url: String get() = subtitle.url
}

data class CatalogEpisode(
    /** `<title key>#s<season>e<episode>` (§12.5). */
    val key: String,
    val seasonNumber: Int,
    val number: Int,
    val titleFa: String?,
    val titleEn: String?,
    val descriptionFa: String?,
    val descriptionEn: String?,
    val still: Image?,
    val runtimeMin: Int?,
    val airDate: Long?,
    val addedAt: Long?,
    /** All streams from every source that has this episode, rank order. Ordering for play: StreamCandidates. */
    val streams: List<SourcedStream>,
    /** fa/en subtitles from every source that has this episode, primary first, deduplicated by URL. */
    val subtitles: List<SourcedSubtitle>,
    /** Per-source episode ids, rank order. */
    val sources: List<SourceRef>,
) {
    val displayTitle: String get() = titleFa ?: titleEn ?: "قسمت $number"
    fun markersFor(sourceId: String): Markers? = streams.firstOrNull { it.sourceId == sourceId }?.markers
}

data class CatalogSeason(val number: Int, val name: String?, val episodes: List<CatalogEpisode>) {
    val displayName: String get() = name ?: if (number == 0) "ویژه" else "فصل $number"
}

/**
 * A title after merging all enabled sources (§12). [key] is the canonical local-data key (§12.5).
 * [meta] already has the field fallback applied; [meta].ids/year/titles are what an online subtitle
 * provider (R6.7) needs.
 */
data class CatalogTitle(
    val key: String,
    val type: TitleType,
    val meta: TitleMetadata,
    /** Every source that carries this title, rank order (incl. subtitle-only sources, `hasStreams = false`). */
    val sources: List<SourceRef>,
    /** Movies: streams of every member source. Empty for series. */
    val streams: List<SourcedStream> = emptyList(),
    /** Movies: pooled fa/en subtitles of every member source (R6.6). Empty for series. */
    val subtitles: List<SourcedSubtitle> = emptyList(),
    /** Series only, ordered per §6.1 (specials last). */
    val seasons: List<CatalogSeason> = emptyList(),
    /** Effective "added" time: feed `added_at`, else first time the app saw it. */
    val addedAt: Long? = null,
    /** fa/en/original titles of every member source (primary first), for search. */
    val searchTitles: List<String> = emptyList(),
) {
    /** Highest-ranked source with streams; metadata comes from it first (§12.2). */
    val primary: SourceRef get() = sources.firstOrNull { it.hasStreams } ?: sources.first()
    val playableSourceIds: Set<String> get() = sources.filter { it.hasStreams }.mapTo(LinkedHashSet()) { it.sourceId }
    val episodes: List<CatalogEpisode> get() = seasons.flatMap { it.episodes }

    fun episode(key: String): CatalogEpisode? = seasons.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.key == key } }
    fun markersFor(sourceId: String): Markers? = streams.firstOrNull { it.sourceId == sourceId }?.markers
}

/** Counts for Settings → Sources and "Test connection" (§10.2). */
data class SourceStats(
    val sourceId: String,
    val movies: Int,
    val series: Int,
    val episodes: Int,
)

data class Catalog(
    val titles: List<CatalogTitle> = emptyList(),
    /** United by id case-insensitively (ids lowercased), name from the highest-ranked source (§12.4). */
    val genres: List<Genre> = emptyList(),
    val featured: List<CatalogTitle> = emptyList(),
    val top10: List<CatalogTitle> = emptyList(),
    /** Newest first by [CatalogTitle.addedAt]. */
    val recentlyAdded: List<CatalogTitle> = emptyList(),
    val sourceStats: Map<String, SourceStats> = emptyMap(),
) {
    private val byKey: Map<String, CatalogTitle> by lazy { titles.associateBy { it.key } }

    fun title(key: String): CatalogTitle? = byKey[key]
    fun movies(): List<CatalogTitle> = titles.filter { it.type == TitleType.MOVIE }
    fun series(): List<CatalogTitle> = titles.filter { it.type == TitleType.SERIES }
    fun byGenre(genreId: String): List<CatalogTitle> = genreId.lowercase().let { g -> titles.filter { g in it.meta.genreIds } }
    fun fromSource(sourceId: String): List<CatalogTitle> = titles.filter { sourceId in it.playableSourceIds }

    companion object {
        val Empty = Catalog()
    }
}

/** Canonical keys for local data (§12.5). */
object CatalogKeys {
    fun titleKey(type: TitleType, tmdb: Int?, imdb: String?, primarySourceId: String, primaryTitleId: String): String =
        when {
            tmdb != null -> "tmdb:${type.wire}:$tmdb"
            imdb != null -> "imdb:${imdb.lowercase()}"
            else -> sourceKey(primarySourceId, primaryTitleId)
        }

    fun sourceKey(sourceId: String, titleId: String) = "src:$sourceId:$titleId"

    fun episodeKey(titleKey: String, season: Int, episode: Int) = "$titleKey#s${season}e$episode"

    /** Title key of an episode key (or the key itself for a movie key). */
    fun titleKeyOf(key: String): String = key.substringBefore('#')

    private val EP = Regex("#s(\\d+)e(\\d+)$")

    /** (season, episode) of an episode key, or null. */
    fun episodeNumbers(key: String): Pair<Int, Int>? =
        EP.find(key)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
}
