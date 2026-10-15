package nl.rass.player.player.subtitle.online

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenSubtitles.com REST API v1. Every request carries `Api-Key` and a descriptive `User-Agent`;
 * downloads need a user token (`POST /login` with the user's own account), which is cached via [OnlineSubtitleSettings].
 * The Api-Key and token are sent only to [apiBase]; the temporary download link is fetched without them.
 */
class OpenSubtitlesProvider(
    private val client: OkHttpClient,
    private val settings: OnlineSubtitleSettings,
    private val apiBase: String = "https://api.opensubtitles.com/api/v1",
    private val userAgent: String = USER_AGENT,
) : OnlineSubtitleProvider {

    override val name = NAME
    private val loginLock = Mutex()
    @Volatile private var memToken: String? = null

    override suspend fun isAvailable(): Boolean = settings.current().let { it.openSubtitlesEnabled && it.openSubtitlesApiKey.isNotBlank() }

    private fun Request.Builder.api(key: String) = this
        .header("Api-Key", key)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")

    override suspend fun search(query: SubtitleQuery): List<OnlineSubtitleResult> {
        val cfg = settings.current()
        if (cfg.openSubtitlesApiKey.isBlank()) throw noKey()
        val byId = query.imdbDigits != null || query.tmdbId != null
        val url = buildSearchUrl(query)
        client.await(Request.Builder().url(url).api(cfg.openSubtitlesApiKey).build()).use { resp ->
            val body = resp.body.string()
            when (resp.code) {
                401, 403 -> throw OnlineSubtitleException(OnlineSubtitleException.Kind.AUTH, "کلید API سایت OpenSubtitles نامعتبر است.")
                429 -> throw OnlineSubtitleException(OnlineSubtitleException.Kind.RATE_LIMIT, "تعداد درخواست‌های OpenSubtitles از حد مجاز گذشته است؛ کمی بعد دوباره امتحان کنید.")
                !in 200..299 -> throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "OpenSubtitles خطا برگرداند (${resp.code}).")
            }
            return parseSearch(body, query, byId)
        }
    }

    internal fun buildSearchUrl(q: SubtitleQuery) = "$apiBase/subtitles".toHttpUrl().newBuilder().apply {
        // The API wants lower-case parameter names in alphabetical order.
        val p = sortedMapOf<String, String>()
        p["languages"] = q.languages.joinToString(",")
        val byId = q.imdbDigits != null || q.tmdbId != null
        if (q.isSeries) {
            q.imdbDigits?.let { p["parent_imdb_id"] = it }
            q.tmdbId?.let { p["parent_tmdb_id"] = it.toString() }
            p["type"] = "episode"
            q.season?.let { p["season_number"] = it.toString() }
            q.episode?.let { p["episode_number"] = it.toString() }
        } else {
            q.imdbDigits?.let { p["imdb_id"] = it }
            q.tmdbId?.let { p["tmdb_id"] = it.toString() }
            p["type"] = "movie"
        }
        if (!byId) p["query"] = q.title.lowercase()
        q.year?.let { p["year"] = it.toString() }
        p.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()

    internal fun parseSearch(json: String, q: SubtitleQuery, byId: Boolean): List<OnlineSubtitleResult> {
        val out = ArrayList<OnlineSubtitleResult>()
        for (el in parseJsonObject(json).arr("data").orEmpty()) {
            val a = el.asObj()?.obj("attributes") ?: continue
            val lang = Lang.normalize(a.str("language")) ?: continue
            if (lang !in q.languages) continue
            val file = a.arr("files")?.firstNotNullOfOrNull { it.asObj() } ?: continue
            val fileId = file.str("file_id") ?: continue
            out += OnlineSubtitleResult(
                provider = NAME,
                id = fileId,
                lang = lang,
                releaseName = a.str("release")?.takeIf { it.isNotBlank() } ?: file.str("file_name").orEmpty(),
                downloads = a.int("download_count") ?: 0,
                rating = a.dbl("ratings"),
                hearingImpaired = a.bool("hearing_impaired"),
                fps = a.dbl("fps")?.takeIf { it > 0 },
                matchedBy = if (byId) MatchedBy.ID else MatchedBy.TITLE,
                season = q.season,
                episode = q.episode,
            )
        }
        return out
    }

    override suspend fun download(result: OnlineSubtitleResult): ByteArray {
        val cfg = settings.current()
        if (cfg.openSubtitlesApiKey.isBlank()) throw noKey()
        var token = ensureToken(cfg, forceLogin = false)
        var link = requestLink(cfg.openSubtitlesApiKey, token, result.id)
        if (link == null) { // 401: token expired
            token = ensureToken(cfg, forceLogin = true)
            link = requestLink(cfg.openSubtitlesApiKey, token, result.id)
                ?: throw authError()
        }
        client.await(Request.Builder().url(link).header("User-Agent", userAgent).build()).use { resp ->
            if (!resp.isSuccessful) throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "دانلود از OpenSubtitles ناموفق بود (${resp.code}).")
            val bytes = resp.body.readCapped(MAX_DOWNLOAD_BYTES)
            return SubtitleZip.extract(bytes, result.season, result.episode).bytes
        }
    }

    /** Returns the link, or null on 401. */
    private suspend fun requestLink(apiKey: String, token: String, fileId: String): String? {
        val payload = buildJsonObject {
            put("file_id", fileId.toLongOrNull() ?: throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "شناسه زیرنویس نامعتبر است."))
            put("sub_format", "srt")
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("$apiBase/download").api(apiKey)
            .header("Authorization", "Bearer $token").post(payload).build()
        client.await(req).use { resp ->
            val body = resp.body.string()
            when (resp.code) {
                200 -> return parseJsonObject(body).str("link")
                    ?: throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "پاسخ دانلود OpenSubtitles نامعتبر است.")
                401 -> return null
                406 -> throw quotaError(body)
                429 -> throw OnlineSubtitleException(OnlineSubtitleException.Kind.RATE_LIMIT, "تعداد درخواست‌های OpenSubtitles از حد مجاز گذشته است؛ کمی بعد دوباره امتحان کنید.")
                403 -> throw authError()
                else -> throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "OpenSubtitles خطا برگرداند (${resp.code}).")
            }
        }
    }

    internal fun quotaError(body: String): OnlineSubtitleException {
        val reset = runCatching { parseJsonObject(body).str("reset_time") }.getOrNull()
        val suffix = if (reset.isNullOrBlank()) "" else " زمان بازنشانی: $reset."
        return OnlineSubtitleException(
            OnlineSubtitleException.Kind.QUOTA,
            "سهمیه دانلود روزانه OpenSubtitles تمام شده است؛ فردا دوباره امتحان کنید یا از SubDL استفاده کنید.$suffix",
        )
    }

    private suspend fun ensureToken(cfg: OnlineSubtitleConfig, forceLogin: Boolean): String = loginLock.withLock {
        if (!forceLogin) {
            memToken?.let { return it }
            cfg.openSubtitlesToken.takeIf { it.isNotBlank() }?.let { memToken = it; return it }
        }
        if (cfg.openSubtitlesUsername.isBlank() || cfg.openSubtitlesPassword.isBlank()) {
            throw OnlineSubtitleException(OnlineSubtitleException.Kind.AUTH, "برای دانلود از OpenSubtitles نام کاربری و رمز عبور حساب رایگان را در تنظیمات وارد کنید.")
        }
        val payload = buildJsonObject {
            put("username", cfg.openSubtitlesUsername)
            put("password", cfg.openSubtitlesPassword)
        }.toString().toRequestBody(JSON)
        client.await(Request.Builder().url("$apiBase/login").api(cfg.openSubtitlesApiKey).post(payload).build()).use { resp ->
            val body = resp.body.string()
            if (resp.code == 400 || resp.code == 401 || resp.code == 403) throw OnlineSubtitleException(OnlineSubtitleException.Kind.AUTH, "نام کاربری یا رمز عبور OpenSubtitles درست نیست.")
            if (resp.code == 429) throw OnlineSubtitleException(OnlineSubtitleException.Kind.RATE_LIMIT, "ورود به OpenSubtitles موقتاً محدود شده است؛ کمی بعد دوباره امتحان کنید.")
            if (!resp.isSuccessful) throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "ورود به OpenSubtitles ناموفق بود (${resp.code}).")
            val token = parseJsonObject(body).str("token")?.takeIf { it.isNotBlank() } ?: throw authError()
            memToken = token
            settings.saveOpenSubtitlesToken(token)
            return token
        }
    }

    private fun noKey() = OnlineSubtitleException(OnlineSubtitleException.Kind.NO_KEY, "کلید API سایت OpenSubtitles وارد نشده است.")
    private fun authError() = OnlineSubtitleException(OnlineSubtitleException.Kind.AUTH, "ورود به OpenSubtitles ناموفق بود؛ نام کاربری، رمز عبور و کلید API را بررسی کنید.")

    companion object {
        const val NAME = "OpenSubtitles"
        const val USER_AGENT = "Rass_player v0.1.0"
        private val JSON = "application/json".toMediaType()
    }
}
