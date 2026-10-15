package nl.rass.player.data.feed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.rass.player.domain.filter.ContentRatings
import nl.rass.player.domain.filter.ContentWarning
import nl.rass.player.domain.AudioTrackInfo
import nl.rass.player.domain.Episode
import nl.rass.player.domain.ExternalIds
import nl.rass.player.domain.Genre
import nl.rass.player.domain.Image
import nl.rass.player.domain.ImageSize
import nl.rass.player.domain.License
import nl.rass.player.domain.Markers
import nl.rass.player.domain.MediaType
import nl.rass.player.domain.Movie
import nl.rass.player.domain.Person
import nl.rass.player.domain.Quality
import nl.rass.player.domain.Season
import nl.rass.player.domain.Series
import nl.rass.player.domain.Stream
import nl.rass.player.domain.Subtitle
import nl.rass.player.domain.SubtitleFileFormat
import nl.rass.player.domain.Title
import nl.rass.player.domain.TitleMetadata
import nl.rass.player.domain.TitleType
import nl.rass.player.domain.Trailer
import nl.rass.player.domain.VideoCodec

/** A title with the JSON path it came from (for duplicate/genre warnings in [FeedAssembler]). */
data class ParsedTitle(val path: String, val title: Title)

/** One validated document (main feed or part), before parts are merged (§11). */
data class ParsedDocument(
    val isPart: Boolean,
    val name: String?,
    val updatedAt: Long?,
    val description: String?,
    val test: Boolean,
    val featured: List<String>,
    val top10: List<String>,
    val genres: List<Pair<String, Genre>>,
    val titles: List<ParsedTitle>,
    /** Resolved part URLs (main feed only). */
    val parts: List<String>,
    val issues: FeedIssues,
)

/**
 * Validates a feed document per feed-spec §10: lenient per entry, strict per field. Unknown keys and `x_` keys
 * are ignored. Invalid optional fields become absent (warning); invalid entries are skipped (counted).
 *
 * The JSON is read as a [JsonElement] tree instead of decoding into `@Serializable` DTOs on purpose: typed
 * decoding fails a whole object on one bad optional field, which §10.1 forbids.
 */
class FeedParser(private val json: Json = FeedJson) {

    /** @throws FeedException for the fatal cases of §10.1. */
    fun parse(bytes: ByteArray, documentUrl: String, expectPart: Boolean = false, pathPrefix: String = ""): ParsedDocument {
        if (bytes.size > MAX_DOCUMENT_BYTES) throw FeedException(FeedError(FeedErrorKind.TOO_LARGE))
        val root = parseRoot(bytes)
        return Reader(documentUrl, pathPrefix).document(root, expectPart)
    }

    /** Fatal checks only (used by discovery, §13 "yields a valid feed"). */
    fun parseRoot(bytes: ByteArray): JsonObject {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        var i = start
        while (i < bytes.size && (bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() ||
                bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
        if (i < bytes.size && bytes[i] == '<'.code.toByte()) throw FeedException(FeedError(FeedErrorKind.HTML_PAGE))
        val text = String(bytes, start, bytes.size - start, Charsets.UTF_8)
        val el = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw FeedException(FeedError(FeedErrorKind.NOT_JSON, e.message?.take(200)))
        }
        val obj = el as? JsonObject ?: throw FeedException(FeedError(FeedErrorKind.NOT_JSON, "root is not an object"))
        val v = obj["rass_feed_version"]
        val version = (v as? JsonPrimitive)?.takeIf { !it.isString }?.let { intOf(it) }
        when {
            version == 1 -> Unit
            version != null -> throw FeedException(FeedError(FeedErrorKind.UNSUPPORTED_VERSION, "version $version"))
            else -> throw FeedException(FeedError(FeedErrorKind.NOT_JSON, "rass_feed_version missing"))
        }
        if (obj["titles"] !is JsonArray) throw FeedException(FeedError(FeedErrorKind.MISSING_TITLES))
        return obj
    }

    private inner class Reader(private val base: String, private val prefix: String) {
        val issues = FeedIssues()

        fun document(root: JsonObject, expectPart: Boolean): ParsedDocument {
            val kind = root["kind"]
            val kindStr = (kind as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (expectPart && kindStr != "part") throw FeedException(FeedError(FeedErrorKind.NOT_A_PART))
            if (!expectPart) {
                if (kindStr == "part") throw FeedException(FeedError(FeedErrorKind.NOT_A_MAIN_FEED))
                if (kind != null && kindStr != "feed") issues.warn(p("kind"), "unknown kind")
            }
            val o = Obj(root, "")
            val name = if (expectPart) null else o.shortText("name", required = true)
            val updatedAt = if (expectPart) o.dateTime("updated_at") else o.dateTime("updated_at", required = true)
            val description = if (expectPart) null else o.longText("description")
            val test = o.bool("test") ?: false

            val genres = arrayItems(root, "genres").mapNotNull { (path, el) -> genre(path, el) }
            val titles = (root["titles"] as JsonArray).mapIndexedNotNull { i, el -> title(p("titles[$i]"), el, test) }
            val featured = if (expectPart) emptyList() else idList(o, "featured", 20)
            val top10 = if (expectPart) emptyList() else idList(o, "top10", 10)
            val parts = if (expectPart) emptyList() else partUrls(root)
            return ParsedDocument(expectPart, name, updatedAt, description, test, featured, top10, genres, titles, parts, issues)
        }

        private fun p(path: String) = prefix + path

        private fun arrayItems(obj: JsonObject, key: String): List<Pair<String, JsonElement>> {
            val el = obj[key] ?: return emptyList()
            if (el !is JsonArray) { issues.warn(p(key), "not an array"); return emptyList() }
            return el.mapIndexed { i, e -> p("$key[$i]") to e }
        }

        private fun idList(o: Obj, key: String, max: Int): List<String> {
            val el = o.raw(key) ?: return emptyList()
            if (el !is JsonArray) { issues.warn(p(key), "not an array"); return emptyList() }
            val out = LinkedHashSet<String>()
            el.forEachIndexed { i, e ->
                val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s == null || !ID.matches(s)) issues.warn(p("$key[$i]"), "invalid id")
                else if (!out.add(s)) issues.warn(p("$key[$i]"), "duplicate id")
            }
            if (out.size > max) issues.warn(p(key), "more than $max entries; extra ignored")
            return out.take(max)
        }

        private fun partUrls(root: JsonObject): List<String> {
            val out = LinkedHashSet<String>()
            arrayItems(root, "parts").forEachIndexed { i, (path, e) ->
                if (i >= MAX_PARTS) { if (i == MAX_PARTS) issues.warn(p("parts"), "more than $MAX_PARTS parts; extra ignored"); return@forEachIndexed }
                val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                val url = s?.let { FeedUrls.resolve(it, base) }
                if (url == null) issues.partError(path, "invalid part URL")
                else if (!out.add(url)) issues.warn(path, "duplicate part URL")
            }
            return out.toList()
        }

        private fun genre(path: String, el: JsonElement): Pair<String, Genre>? {
            if (el !is JsonObject) { issues.skip(path, "genre is not an object"); return null }
            val o = Obj(el, path)
            val id = o.id("id") ?: run { issues.skip("$path.id", "missing or invalid id"); return null }
            val fa = o.shortText("name_fa"); val en = o.shortText("name_en")
            if (fa == null && en == null) { issues.skip(path, "genre has no name"); return null }
            return path to Genre(id, fa, en)
        }

        // ---------------- titles ----------------

        private fun title(path: String, el: JsonElement, test: Boolean): ParsedTitle? {
            if (el !is JsonObject) { issues.skip(path, "title is not an object"); return null }
            val o = Obj(el, path)
            val id = o.id("id") ?: run { issues.skip("$path.id", "missing or invalid id"); return null }
            val typeStr = (el["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val type = TitleType.fromWire(typeStr) ?: run { issues.skip("$path.type", "missing or unknown type"); return null }
            val titleFa = o.shortText("title_fa"); val titleEn = o.shortText("title_en")
            if (titleFa == null && titleEn == null) { issues.skip(path, "both title_fa and title_en missing"); return null }

            val license = o.obj("license")?.let { license(it) }
            if (test && license == null) issues.warn("$path.license", "test feed title without license")
            val meta = TitleMetadata(
                titleFa = titleFa,
                titleEn = titleEn,
                originalTitle = o.shortText("original_title"),
                year = o.int("year", 1870..2100),
                rating = o.number("rating", 0.0, 10.0),
                genreIds = idArray(o, "genres"),
                descriptionFa = o.longText("description_fa"),
                descriptionEn = o.longText("description_en"),
                poster = o.image("poster"),
                backdrop = o.image("backdrop"),
                logo = o.image("logo"),
                addedAt = o.dateOrDateTime("added_at"),
                runtimeMin = o.int("runtime_min", 1..1000),
                cast = cast(o),
                trailer = o.obj("trailer")?.let { trailer(it) },
                license = license,
                ids = o.obj("ids")?.let { ids(it) } ?: ExternalIds(),
                contentRating = rating(o),
                contentWarnings = warnings(o),
            )
            val title: Title = when (type) {
                TitleType.MOVIE -> {
                    if ("seasons" in el) issues.warn("$path.seasons", "seasons on a movie ignored")
                    val streams = streams(o, path)
                    val subs = subtitles(o, path)
                    val markers = o.obj("markers")?.let { markers(it) }
                    if (streams.isEmpty()) {
                        if (subs.isEmpty()) { issues.skip(path, "movie has no valid stream"); return null }
                        issues.warn(path, "movie has no valid stream; kept only for its subtitles")
                    }
                    Movie(id, meta, streams, subs, markers)
                }
                TitleType.SERIES -> {
                    for (k in listOf("streams", "subtitles", "markers")) if (k in el) issues.warn("$path.$k", "$k on a series ignored")
                    val seasons = seasons(o, path)
                    if (seasons.isEmpty()) { issues.skip(path, "series has no valid season"); return null }
                    val s = Series(id, meta, seasons)
                    if (!s.isPlayable) issues.warn(path, "series has no playable episode; kept only for its subtitles")
                    s
                }
            }
            return ParsedTitle(path, title)
        }

        private fun rating(o: Obj): String? {
            val raw = o.raw("content_rating") ?: return null
            val s = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            val c = ContentRatings.canonical(s)
            if (c == null) issues.warn(o.path("content_rating"), "unknown content_rating ignored")
            return c
        }

        private fun warnings(o: Obj): List<String> {
            val el = o.raw("content_warnings") ?: return emptyList()
            if (el !is JsonArray) { issues.warn(o.path("content_warnings"), "not an array"); return emptyList() }
            val out = LinkedHashSet<String>()
            el.forEachIndexed { i, e ->
                val w = ContentWarning.fromWire((e as? JsonPrimitive)?.takeIf { it.isString }?.content)
                if (w == null) issues.warn(o.path("content_warnings[$i]"), "unknown content warning ignored") else out.add(w.wire)
            }
            return out.toList()
        }

        private fun idArray(o: Obj, key: String): List<String> {
            val el = o.raw(key) ?: return emptyList()
            if (el !is JsonArray) { issues.warn(o.path(key), "not an array"); return emptyList() }
            val out = LinkedHashSet<String>()
            el.forEachIndexed { i, e ->
                val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s == null || !ID.matches(s)) issues.warn(o.path("$key[$i]"), "invalid id") else out.add(s)
            }
            return out.toList()
        }

        private fun cast(o: Obj): List<Person> {
            val el = o.raw("cast") ?: return emptyList()
            if (el !is JsonArray) { issues.warn(o.path("cast"), "not an array"); return emptyList() }
            return el.take(50).mapIndexedNotNull { i, e ->
                val path = o.path("cast[$i]")
                val po = (e as? JsonObject)?.let { Obj(it, path) } ?: run { issues.warn(path, "not an object"); return@mapIndexedNotNull null }
                val name = po.shortText("name") ?: run { issues.warn(path, "person without name"); return@mapIndexedNotNull null }
                Person(name, po.shortText("role"), po.image("photo"))
            }
        }

        private fun trailer(o: Obj): Trailer? {
            val url = o.url("url", warn = false)
            val type = MediaType.fromWire(o.string("type"))
            if (url == null || type == null) { issues.warn(o.path, "invalid trailer"); return null }
            return Trailer(url, type)
        }

        private fun license(o: Obj): License? {
            val name = o.shortText("name") ?: run { issues.warn(o.path, "license without name"); return null }
            return License(name, o.url("url"), o.shortText("attribution"), o.url("source_url"))
        }

        private fun ids(o: Obj): ExternalIds {
            val tmdb = o.int("tmdb", 1..Int.MAX_VALUE)
            val imdb = o.string("imdb")?.let { s ->
                if (IMDB.matches(s)) s else { issues.warn(o.path("imdb"), "invalid imdb id"); null }
            }
            return ExternalIds(tmdb, imdb)
        }

        private fun seasons(o: Obj, path: String): List<Season> {
            val el = o.raw("seasons")
            if (el !is JsonArray) { if (el != null) issues.warn("$path.seasons", "not an array"); return emptyList() }
            val numbers = HashSet<Int>()
            val episodeIds = HashSet<String>()
            val out = ArrayList<Season>()
            el.forEachIndexed { i, e ->
                val sp = "$path.seasons[$i]"
                val so = (e as? JsonObject)?.let { Obj(it, sp) } ?: run { issues.skip(sp, "season is not an object"); return@forEachIndexed }
                val number = so.int("number", 0..9999, warn = false)
                if (number == null) { issues.skip("$sp.number", "missing or invalid number"); return@forEachIndexed }
                if (!numbers.add(number)) { issues.skip("$sp.number", "duplicate season number"); return@forEachIndexed }
                val episodes = episodes(so, sp, episodeIds)
                if (episodes.isEmpty()) { issues.skip(sp, "season has no valid episode"); return@forEachIndexed }
                out.add(Season(number, so.shortText("name"), episodes.sortedBy { it.number }))
            }
            return out
        }

        private fun episodes(so: Obj, sp: String, episodeIds: MutableSet<String>): List<Episode> {
            val el = so.raw("episodes")
            if (el !is JsonArray) { issues.skip("$sp.episodes", "missing episodes"); return emptyList() }
            val numbers = HashSet<Int>()
            val out = ArrayList<Episode>()
            el.forEachIndexed { i, e ->
                val ep = "$sp.episodes[$i]"
                val eo = (e as? JsonObject)?.let { Obj(it, ep) } ?: run { issues.skip(ep, "episode is not an object"); return@forEachIndexed }
                val id = eo.id("id") ?: run { issues.skip("$ep.id", "missing or invalid id"); return@forEachIndexed }
                if (id in episodeIds) { issues.skip("$ep.id", "duplicate episode id"); return@forEachIndexed }
                val number = eo.int("number", 0..99999, warn = false)
                    ?: run { issues.skip("$ep.number", "missing or invalid number"); return@forEachIndexed }
                if (number in numbers) { issues.skip("$ep.number", "duplicate episode number"); return@forEachIndexed }
                val streams = streams(eo, ep)
                val subs = subtitles(eo, ep)
                if (streams.isEmpty()) {
                    if (subs.isEmpty()) { issues.skip(ep, "episode has no valid stream"); return@forEachIndexed }
                    issues.warn(ep, "episode has no valid stream; kept only for its subtitles")
                }
                episodeIds.add(id); numbers.add(number)
                out.add(
                    Episode(
                        id = id, number = number,
                        titleFa = eo.shortText("title_fa"), titleEn = eo.shortText("title_en"),
                        descriptionFa = eo.longText("description_fa"), descriptionEn = eo.longText("description_en"),
                        still = eo.image("still"), runtimeMin = eo.int("runtime_min", 1..1000),
                        airDate = eo.date("air_date"), addedAt = eo.dateOrDateTime("added_at"),
                        streams = streams, subtitles = subs, markers = eo.obj("markers")?.let { markers(it) },
                        contentRating = rating(eo), contentWarnings = warnings(eo),
                    )
                )
            }
            return out
        }

        private fun streams(o: Obj, path: String): List<Stream> {
            val el = o.raw("streams") ?: return emptyList()
            if (el !is JsonArray) { issues.warn("$path.streams", "not an array"); return emptyList() }
            if (el.size > MAX_STREAMS) issues.warn("$path.streams", "more than $MAX_STREAMS streams; extra ignored")
            return el.take(MAX_STREAMS).mapIndexedNotNull { i, e -> stream("$path.streams[$i]", e, i) }
        }

        private fun stream(sp: String, e: JsonElement, index: Int): Stream? {
            val so = (e as? JsonObject)?.let { Obj(it, sp) } ?: run { issues.skip(sp, "stream is not an object"); return null }
            val url = so.url("url", warn = false) ?: run { issues.skip("$sp.url", "not an http(s) URL"); return null }
            val type = MediaType.fromWire(so.string("type")) ?: run { issues.skip("$sp.type", "missing or unknown type"); return null }
            var quality = Quality.parse(so.string("quality"))
            if (quality == Quality.Auto && type == MediaType.MP4) quality = null
            if (quality == null) {
                issues.warn("$sp.quality", "missing or invalid quality")
                quality = if (type == MediaType.MP4) Quality.Unknown else Quality.Auto
            }
            val audio = so.raw("audio")?.let { a ->
                if (a !is JsonArray) { issues.warn("$sp.audio", "not an array"); null }
                else a.mapIndexedNotNull { i, t ->
                    val ao = (t as? JsonObject)?.let { Obj(it, "$sp.audio[$i]") } ?: return@mapIndexedNotNull null
                    val lang = ao.string("lang")?.takeIf { LANG.matches(it) } ?: run { issues.warn(ao.path, "invalid audio lang"); return@mapIndexedNotNull null }
                    AudioTrackInfo(lang, ao.shortText("label"))
                }
            }.orEmpty()
            return Stream(
                url = url, type = type, quality = quality,
                priority = so.int("priority", 0..1000) ?: 0,
                label = so.shortText("label"),
                bitrateKbps = so.int("bitrate_kbps", 1..1_000_000),
                videoCodec = so.string("video_codec")?.let { c -> VideoCodec.fromWire(c) ?: run { issues.warn("$sp.video_codec", "unknown codec"); null } },
                audio = audio, index = index,
            )
        }

        private fun subtitles(o: Obj, path: String): List<Subtitle> {
            val el = o.raw("subtitles") ?: return emptyList()
            if (el !is JsonArray) { issues.warn("$path.subtitles", "not an array"); return emptyList() }
            if (el.size > MAX_SUBTITLES) issues.warn("$path.subtitles", "more than $MAX_SUBTITLES subtitles; extra ignored")
            return el.take(MAX_SUBTITLES).mapIndexedNotNull { i, e ->
                val sp = "$path.subtitles[$i]"
                val so = (e as? JsonObject)?.let { Obj(it, sp) } ?: run { issues.skip(sp, "subtitle is not an object"); return@mapIndexedNotNull null }
                val langTag = so.string("lang")?.takeIf { LANG.matches(it) } ?: run { issues.skip("$sp.lang", "missing or invalid lang"); return@mapIndexedNotNull null }
                val lang = langTag.substringBefore('-').lowercase()
                val url = so.url("url", warn = false) ?: run { issues.skip("$sp.url", "not an http(s) URL"); return@mapIndexedNotNull null }
                val format = SubtitleFileFormat.fromWire(so.string("format")) ?: run { issues.skip("$sp.format", "missing or unknown format"); return@mapIndexedNotNull null }
                if (lang != "fa" && lang != "en") return@mapIndexedNotNull null // R5.4: ignored silently
                Subtitle(
                    lang = lang, url = url, format = format,
                    label = so.shortText("label"),
                    encoding = so.string("encoding")?.let { if (ENCODING.matches(it)) it else { issues.warn("$sp.encoding", "invalid encoding"); null } },
                )
            }
        }

        private fun markers(o: Obj): Markers? {
            fun n(k: String) = o.number(k, 0.0, Double.MAX_VALUE)
            var introStart = n("intro_start"); var introEnd = n("intro_end")
            var recapStart = n("recap_start"); var recapEnd = n("recap_end")
            if ((introStart == null) != (introEnd == null) || (introStart != null && introEnd!! <= introStart)) {
                if (introStart != null || introEnd != null) issues.warn(o.path("intro_start"), "invalid intro pair")
                introStart = null; introEnd = null
            }
            if ((recapStart == null) != (recapEnd == null) || (recapStart != null && recapEnd!! <= recapStart)) {
                if (recapStart != null || recapEnd != null) issues.warn(o.path("recap_start"), "invalid recap pair")
                recapStart = null; recapEnd = null
            }
            return Markers(introStart, introEnd, recapStart, recapEnd, n("credits_start")).takeUnless { it.isEmpty }
        }

        // ---------------- field reader ----------------

        private inner class Obj(val o: JsonObject, val path: String) {
            fun path(key: String) = if (path.isEmpty()) p(key) else "$path.$key"
            fun raw(key: String): JsonElement? = o[key]

            private fun prim(key: String): JsonPrimitive? {
                val e = o[key] ?: return null
                if (e is JsonNull || e !is JsonPrimitive) { issues.warn(path(key), "wrong type"); return null }
                return e
            }

            fun obj(key: String): Obj? {
                val e = o[key] ?: return null
                if (e !is JsonObject) { issues.warn(path(key), "not an object"); return null }
                return Obj(e, path(key))
            }

            fun string(key: String): String? {
                val pr = prim(key) ?: return null
                if (!pr.isString) { issues.warn(path(key), "not a string"); return null }
                return pr.content
            }

            private fun text(key: String, max: Int, required: Boolean): String? {
                val e = o[key]
                if (e == null) { if (required) issues.warn(path(key), "missing"); return null }
                val s = string(key)?.trim() ?: return null
                if (s.isEmpty() || s.length > max) { issues.warn(path(key), "empty or too long"); return null }
                return s
            }

            fun shortText(key: String, required: Boolean = false) = text(key, 300, required)
            fun longText(key: String) = text(key, 5000, false)

            fun id(key: String): String? {
                val e = o[key] as? JsonPrimitive ?: return null
                if (!e.isString) return null
                return e.content.takeIf { ID.matches(it) }
            }

            fun bool(key: String): Boolean? {
                val pr = prim(key) ?: return null
                if (pr.isString || (pr.content != "true" && pr.content != "false")) { issues.warn(path(key), "not a boolean"); return null }
                return pr.content == "true"
            }

            fun int(key: String, range: IntRange, warn: Boolean = true): Int? {
                val e = o[key] ?: return null
                val v = (e as? JsonPrimitive)?.takeIf { !it.isString && e !is JsonNull }?.let { intOf(it) }
                if (v == null || v !in range) { if (warn) issues.warn(path(key), "invalid integer"); return null }
                return v
            }

            fun number(key: String, min: Double, max: Double): Double? {
                val e = o[key] ?: return null
                val v = (e as? JsonPrimitive)?.takeIf { !it.isString && e !is JsonNull }?.content?.toDoubleOrNull()
                if (v == null || v.isNaN() || v < min || v > max) { issues.warn(path(key), "invalid number"); return null }
                return v
            }

            fun url(key: String, warn: Boolean = true): String? {
                val e = o[key] ?: return null
                val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                val r = s?.let { FeedUrls.resolve(it, base) }
                if (r == null && warn) issues.warn(path(key), "not an http(s) URL")
                return r
            }

            fun date(key: String): Long? = string(key)?.let { FeedDates.parseDate(it) ?: run { issues.warn(path(key), "invalid date"); null } }

            fun dateTime(key: String, required: Boolean = false): Long? {
                if (o[key] == null) { if (required) issues.warn(path(key), "missing"); return null }
                return string(key)?.let { FeedDates.parseDateTime(it) ?: run { issues.warn(path(key), "invalid date-time"); null } }
            }

            fun dateOrDateTime(key: String): Long? =
                string(key)?.let { FeedDates.parseDateOrDateTime(it) ?: run { issues.warn(path(key), "invalid date"); null } }

            fun image(key: String): Image? {
                val e = o[key] ?: return null
                if (e is JsonPrimitive && e.isString) return url(key)?.let { Image(it) }
                if (e !is JsonObject) { issues.warn(path(key), "invalid image"); return null }
                val io = Obj(e, path(key))
                val url = io.url("url") ?: return null
                val widthsSeen = HashSet<Int>()
                val sizes = (e["sizes"] as? JsonArray)?.mapIndexedNotNull { i, s ->
                    val so = (s as? JsonObject)?.let { Obj(it, io.path("sizes[$i]")) } ?: return@mapIndexedNotNull null
                    val w = so.int("width", 1..8192) ?: return@mapIndexedNotNull null
                    val u = so.url("url") ?: return@mapIndexedNotNull null
                    if (!widthsSeen.add(w)) { issues.warn(so.path, "duplicate width"); return@mapIndexedNotNull null }
                    ImageSize(w, u)
                }.orEmpty()
                var template = io.string("template")
                val widths = (e["widths"] as? JsonArray)?.mapNotNull { w ->
                    (w as? JsonPrimitive)?.takeIf { !it.isString }?.let { intOf(it) }?.takeIf { it in 1..8192 }
                }?.distinct().orEmpty()
                if (template != null) {
                    // Resolve with a placeholder so "{" "}" don't upset the URL parser, then put it back.
                    val resolved = if ("{width}" in template) FeedUrls.resolve(template.replace("{width}", "__W__"), base) else null
                    template = resolved?.replace("__W__", "{width}")
                    if (template == null || widths.isEmpty()) { issues.warn(io.path("template"), "invalid template or widths"); template = null }
                }
                return Image(url, sizes, template, if (template != null) widths else emptyList())
            }
        }
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 20L * 1024 * 1024
        const val MAX_PARTS = 50
        const val MAX_STREAMS = 50
        const val MAX_SUBTITLES = 50
        val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        private val IMDB = Regex("^tt[0-9]{7,10}$", RegexOption.IGNORE_CASE)
        private val LANG = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$")
        private val ENCODING = Regex("^[A-Za-z0-9._:-]{1,40}$")

        internal fun intOf(p: JsonPrimitive): Int? {
            p.content.toIntOrNull()?.let { return it }
            val d = p.content.toDoubleOrNull() ?: return null
            return if (d == Math.floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
        }
    }
}

val FeedJson: Json = Json { ignoreUnknownKeys = true; isLenient = false }
