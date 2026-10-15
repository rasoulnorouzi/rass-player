package nl.rass.player.domain

/*
 * Immutable models that mirror docs/feed-spec.md v1. These are the *validated* form of one source's feed:
 * every URL is already resolved and allowed (§2.1), every text is trimmed, invalid entries are gone (§10).
 * Dates are epoch milliseconds (UTC). Marker values are seconds (§9).
 */

enum class TitleType(val wire: String) {
    MOVIE("movie"), SERIES("series");

    companion object {
        fun fromWire(v: String?): TitleType? = entries.firstOrNull { it.wire == v }
    }
}

enum class MediaType(val wire: String) {
    HLS("hls"), MP4("mp4"), DASH("dash");

    companion object {
        fun fromWire(v: String?): MediaType? = entries.firstOrNull { it.wire == v }
    }
}

enum class VideoCodec(val wire: String) {
    H264("h264"), HEVC("hevc"), VP9("vp9"), AV1("av1");

    companion object {
        fun fromWire(v: String?): VideoCodec? = entries.firstOrNull { it.wire == v }
    }
}

enum class SubtitleFileFormat(val wire: String) {
    SRT("srt"), VTT("vtt");

    companion object {
        fun fromWire(v: String?): SubtitleFileFormat? = entries.firstOrNull { it.wire == v }
    }
}

/** §7.1 `quality`. [Unknown] = an mp4 without a valid quality (sorted after every known quality). */
sealed interface Quality {
    data object Auto : Quality
    data class Height(val px: Int) : Quality
    data object Unknown : Quality

    val label: String
        get() = when (this) {
            Auto -> "auto"
            is Height -> "${px}p"
            Unknown -> "?"
        }

    companion object {
        private val HEIGHT = Regex("^[1-9][0-9]{2,3}p$")
        fun parse(v: String?): Quality? = when {
            v == "auto" -> Auto
            v != null && HEIGHT.matches(v) -> Height(v.dropLast(1).toInt())
            else -> null
        }
    }
}

data class ImageSize(val width: Int, val url: String)

/** §2.6. A plain URL string becomes `Image(url)`. */
data class Image(
    val url: String,
    val sizes: List<ImageSize> = emptyList(),
    val template: String? = null,
    val widths: List<Int> = emptyList(),
) {
    /** The URL to request for a slot [widthPx] physical pixels wide (§2.6 steps 1–3). Fallback is [url]. */
    fun urlFor(widthPx: Int): String {
        if (sizes.isNotEmpty()) {
            val pick = sizes.filter { it.width >= widthPx }.minByOrNull { it.width } ?: sizes.maxBy { it.width }
            return pick.url
        }
        if (template != null && widths.isNotEmpty()) {
            val w = widths.filter { it >= widthPx }.minOrNull() ?: widths.max()
            return template.replace("{width}", w.toString())
        }
        return url
    }
}

data class Genre(val id: String, val nameFa: String?, val nameEn: String?) {
    val displayName: String get() = nameFa ?: nameEn ?: id
}

data class Person(val name: String, val role: String? = null, val photo: Image? = null)

data class Trailer(val url: String, val type: MediaType)

data class License(
    val name: String,
    val url: String? = null,
    val attribution: String? = null,
    val sourceUrl: String? = null,
)

/** §4.4. Data only; also the lookup keys for a future online-subtitle module (R6.7). */
data class ExternalIds(val tmdb: Int? = null, val imdb: String? = null) {
    val isEmpty: Boolean get() = tmdb == null && imdb == null
}

data class AudioTrackInfo(val lang: String, val label: String? = null)

/**
 * §7.1. [index] is the position in the feed's `streams` array (sort key 5 of §7.2).
 */
data class Stream(
    val url: String,
    val type: MediaType,
    val quality: Quality,
    val priority: Int = 0,
    val label: String? = null,
    val bitrateKbps: Int? = null,
    val videoCodec: VideoCodec? = null,
    val audio: List<AudioTrackInfo> = emptyList(),
    val index: Int = 0,
)

/** §8. [lang] is the lowercase primary subtag; only `fa` and `en` survive validation (R5.4, R6.1). */
data class Subtitle(
    val lang: String,
    val url: String,
    val format: SubtitleFileFormat,
    val label: String? = null,
    val encoding: String? = null,
)

/** §9, seconds. Invalid pairs are already dropped. */
data class Markers(
    val introStart: Double? = null,
    val introEnd: Double? = null,
    val recapStart: Double? = null,
    val recapEnd: Double? = null,
    val creditsStart: Double? = null,
) {
    val isEmpty: Boolean
        get() = introStart == null && recapStart == null && creditsStart == null
}

/** Metadata shared by movies and series (§4). Merging falls back field by field (§12.2). */
data class TitleMetadata(
    val titleFa: String? = null,
    val titleEn: String? = null,
    val originalTitle: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val genreIds: List<String> = emptyList(),
    val descriptionFa: String? = null,
    val descriptionEn: String? = null,
    val poster: Image? = null,
    val backdrop: Image? = null,
    val logo: Image? = null,
    val addedAt: Long? = null,
    val runtimeMin: Int? = null,
    val cast: List<Person> = emptyList(),
    val trailer: Trailer? = null,
    val license: License? = null,
    val ids: ExternalIds = ExternalIds(),
) {
    val displayTitle: String get() = titleFa ?: titleEn ?: originalTitle.orEmpty()
    val description: String? get() = descriptionFa ?: descriptionEn

    /** Fields missing here are taken from [other]; list fields only when empty here (§12.2: no union). */
    fun fallbackTo(other: TitleMetadata): TitleMetadata = TitleMetadata(
        titleFa = titleFa ?: other.titleFa,
        titleEn = titleEn ?: other.titleEn,
        originalTitle = originalTitle ?: other.originalTitle,
        year = year ?: other.year,
        rating = rating ?: other.rating,
        genreIds = genreIds.ifEmpty { other.genreIds },
        descriptionFa = descriptionFa ?: other.descriptionFa,
        descriptionEn = descriptionEn ?: other.descriptionEn,
        poster = poster ?: other.poster,
        backdrop = backdrop ?: other.backdrop,
        logo = logo ?: other.logo,
        addedAt = addedAt ?: other.addedAt,
        runtimeMin = runtimeMin ?: other.runtimeMin,
        cast = cast.ifEmpty { other.cast },
        trailer = trailer ?: other.trailer,
        license = license ?: other.license,
        ids = ExternalIds(ids.tmdb ?: other.ids.tmdb, ids.imdb ?: other.ids.imdb),
    )
}

/** §6.2. An episode with no stream but with subtitles is kept only as a subtitle contributor (R6.6). */
data class Episode(
    val id: String,
    val number: Int,
    val titleFa: String? = null,
    val titleEn: String? = null,
    val descriptionFa: String? = null,
    val descriptionEn: String? = null,
    val still: Image? = null,
    val runtimeMin: Int? = null,
    val airDate: Long? = null,
    val addedAt: Long? = null,
    val streams: List<Stream> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val markers: Markers? = null,
) {
    val isPlayable: Boolean get() = streams.isNotEmpty()
}

data class Season(val number: Int, val name: String? = null, val episodes: List<Episode>) {
    val isPlayable: Boolean get() = episodes.any { it.isPlayable }
}

/** One title of one source. Feed-local [id]s are never compared across sources (§12). */
sealed interface Title {
    val id: String
    val type: TitleType
    val meta: TitleMetadata

    /**
     * False for a title that has no valid stream but does have fa/en subtitles. Such a title never shows up on
     * its own; it only adds its subtitles to a matching title from another source (R6.6).
     */
    val isPlayable: Boolean
}

data class Movie(
    override val id: String,
    override val meta: TitleMetadata,
    val streams: List<Stream>,
    val subtitles: List<Subtitle> = emptyList(),
    val markers: Markers? = null,
) : Title {
    override val type: TitleType get() = TitleType.MOVIE
    override val isPlayable: Boolean get() = streams.isNotEmpty()
}

data class Series(
    override val id: String,
    override val meta: TitleMetadata,
    val seasons: List<Season>,
) : Title {
    override val type: TitleType get() = TitleType.SERIES
    override val isPlayable: Boolean get() = seasons.any { it.isPlayable }
}

/** Seasons sorted by number with 0 (specials) last (§6.1). */
val seasonOrder: Comparator<Int> = compareBy<Int> { if (it == 0) 1 else 0 }.thenBy { it }

/** The validated catalog of one source (main document + parts, §11). */
data class SourceCatalog(
    val name: String?,
    val description: String? = null,
    val isTest: Boolean = false,
    val updatedAt: Long? = null,
    val genres: List<Genre> = emptyList(),
    val titles: List<Title> = emptyList(),
    val featured: List<String> = emptyList(),
    val top10: List<String> = emptyList(),
)
