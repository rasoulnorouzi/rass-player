package nl.rass.player.player.subtitle.online

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import nl.rass.player.player.subtitle.SubtitleFormat

/**
 * SubDL (https://subdl.com/api-doc): `GET /api/v1/subtitles` with `api_key`, searched by imdb_id / tmdb_id /
 * film_name; the response has `subtitles[]` whose `url` is a path under https://dl.subdl.com (a ZIP).
 * The key goes only to api.subdl.com.
 */
class SubDlProvider(
    private val client: OkHttpClient,
    private val settings: OnlineSubtitleSettings,
    private val apiBase: String = "https://api.subdl.com/api/v1",
    private val downloadBase: String = "https://dl.subdl.com",
) : OnlineSubtitleProvider {

    override val name = NAME

    override suspend fun isAvailable(): Boolean = settings.current().let { it.subDlEnabled && it.subDlApiKey.isNotBlank() }

    override suspend fun search(query: SubtitleQuery): List<OnlineSubtitleResult> {
        val cfg = settings.current()
        if (cfg.subDlApiKey.isBlank()) throw OnlineSubtitleException(OnlineSubtitleException.Kind.NO_KEY, "کلید API سایت SubDL وارد نشده است.")
        val url = buildSearchUrl(query, cfg.subDlApiKey)
        val byId = query.imdbTt != null || query.tmdbId != null
        client.await(Request.Builder().url(url).header("Accept", "application/json").build()).use { resp ->
            val body = resp.body.string()
            when (resp.code) {
                401, 403 -> throw authError()
                429 -> throw OnlineSubtitleException(OnlineSubtitleException.Kind.RATE_LIMIT, "تعداد درخواست‌های SubDL از حد مجاز گذشته است؛ کمی بعد دوباره امتحان کنید.")
                !in 200..299 -> {
                    // SubDL sometimes answers 4xx with a JSON {status:false,error:...}.
                    runCatching { parseJsonObject(body).str("error") }.getOrNull()?.let { if (looksLikeKeyError(it)) throw authError() }
                    throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "SubDL خطا برگرداند (${resp.code}).")
                }
            }
            return parseSearch(body, query, byId)
        }
    }

    internal fun buildSearchUrl(q: SubtitleQuery, apiKey: String): HttpUrl {
        val b = "$apiBase/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("languages", q.languages.joinToString(",") { it.uppercase() })
            .addQueryParameter("type", if (q.isSeries) "tv" else "movie")
            .addQueryParameter("subs_per_page", "30")
        when {
            q.imdbTt != null -> b.addQueryParameter("imdb_id", q.imdbTt)
            q.tmdbId != null -> b.addQueryParameter("tmdb_id", q.tmdbId.toString())
            else -> b.addQueryParameter("film_name", q.title)
        }
        q.year?.let { b.addQueryParameter("year", it.toString()) }
        if (q.isSeries) {
            q.season?.let { b.addQueryParameter("season_number", it.toString()) }
            q.episode?.let { b.addQueryParameter("episode_number", it.toString()) }
        }
        return b.build()
    }

    internal fun parseSearch(json: String, q: SubtitleQuery, byId: Boolean): List<OnlineSubtitleResult> {
        val root = parseJsonObject(json)
        if (root.bool("status").not() && root["status"] != null) {
            val err = root.str("error").orEmpty()
            if (looksLikeKeyError(err)) throw authError()
            // "can't find movie or tv" and similar mean no results.
            return emptyList()
        }
        val out = ArrayList<OnlineSubtitleResult>()
        for (el in root.arr("subtitles").orEmpty()) {
            val o = el.asObj() ?: continue
            val path = o.str("url") ?: continue
            val lang = Lang.normalize(o.str("language")) ?: Lang.normalize(o.str("lang")) ?: continue
            if (lang !in q.languages) continue
            val ep = o.int("episode")
            val season = o.int("season")
            if (q.isSeries && q.episode != null && ep != null && ep != 0 && ep != q.episode) continue
            if (q.isSeries && q.season != null && season != null && season != 0 && season != q.season) continue
            out += OnlineSubtitleResult(
                provider = NAME,
                id = path,
                lang = lang,
                releaseName = o.str("release_name") ?: o.str("name") ?: "",
                hearingImpaired = o.bool("hi"),
                matchedBy = if (byId) MatchedBy.ID else MatchedBy.TITLE,
                format = null,
                season = q.season,
                episode = q.episode,
            )
        }
        return out
    }

    override suspend fun download(result: OnlineSubtitleResult): ByteArray {
        val path = result.id
        if (!path.startsWith("/") || path.contains("..")) throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "نشانی زیرنویس نامعتبر است.")
        val url = downloadBase.trimEnd('/') + path
        client.await(Request.Builder().url(url).build()).use { resp ->
            if (resp.code == 404) throw OnlineSubtitleException(OnlineSubtitleException.Kind.NOT_FOUND, "این زیرنویس دیگر در SubDL موجود نیست.")
            if (!resp.isSuccessful) throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "دانلود از SubDL ناموفق بود (${resp.code}).")
            val bytes = resp.body.readCapped(MAX_DOWNLOAD_BYTES)
            return SubtitleZip.extract(bytes, result.season, result.episode).bytes
        }
    }

    private fun looksLikeKeyError(s: String) = s.contains("api", true) && (s.contains("key", true) || s.contains("invalid", true))
    private fun authError() = OnlineSubtitleException(OnlineSubtitleException.Kind.AUTH, "کلید API سایت SubDL نامعتبر است.")

    companion object {
        const val NAME = "SubDL"
    }
}
