package nl.rass.player.data.source

import nl.rass.player.data.feed.FeedUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The one access credential of a source (feed-spec §14), configured by the user, never by a feed.
 * When both are set, the bearer token wins.
 */
data class SourceAuth(
    val bearerToken: String? = null,
    val headerName: String? = null,
    val headerValue: String? = null,
    /** "Also send to media servers": the origins of URLs in this source's own feed. */
    val sendToMediaHosts: Boolean = false,
    /** The user accepted the §2.1 warning for sending a credential over plain http. */
    val allowOverHttp: Boolean = false,
) {
    /** (name, value) to send, or null when no credential is configured. */
    fun header(): Pair<String, String>? = when {
        !bearerToken.isNullOrEmpty() -> "Authorization" to "Bearer $bearerToken"
        !headerName.isNullOrEmpty() && !headerValue.isNullOrEmpty() &&
            AuthHeaders.validateHeaderName(headerName) == null -> headerName to headerValue
        else -> null
    }

    val isEmpty: Boolean get() = header() == null

    override fun toString(): String = "SourceAuth(<redacted>, sendToMediaHosts=$sendToMediaHosts)"

    companion object {
        val None = SourceAuth()
    }
}

object AuthHeaders {
    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val BLOCKED = setOf(
        "host", "referer", "origin", "user-agent", "cookie", "authorization",
        "content-length", "transfer-encoding", "connection",
    )

    enum class HeaderNameError(val messageFa: String) {
        EMPTY("نام سرآیند خالی است."),
        NOT_A_TOKEN("نام سرآیند فقط می‌تواند حروف انگلیسی، رقم و - داشته باشد."),
        BLOCKED("این نام سرآیند مجاز نیست."),
    }

    /** Null when [name] is allowed as a custom header (§14), else the reason. */
    fun validateHeaderName(name: String): HeaderNameError? {
        val n = name.trim()
        if (n.isEmpty()) return HeaderNameError.EMPTY
        if (!TOKEN.matches(n)) return HeaderNameError.NOT_A_TOKEN
        val l = n.lowercase()
        if (l in BLOCKED || l.startsWith("sec-") || l.startsWith("proxy-")) return HeaderNameError.BLOCKED
        return null
    }

    /**
     * Headers for one request (feed-spec §14):
     *  - only when the request origin equals the feed URL's origin, or — with [SourceAuth.sendToMediaHosts] —
     *    is one of [feedUrlOrigins] (origins of URLs in that source's own feed);
     *  - never over http unless [SourceAuth.allowOverHttp];
     *  - never for `asset:///` or non-http URLs.
     * Call again for every redirect hop so a redirect that leaves the origin drops the header.
     */
    fun headersFor(
        requestUrl: String,
        feedUrl: String,
        auth: SourceAuth?,
        feedUrlOrigins: Set<FeedUrls.Origin> = emptySet(),
    ): Map<String, String> {
        val h = auth?.header() ?: return emptyMap()
        val req = requestUrl.toHttpUrlOrNull() ?: return emptyMap()
        val feedOrigin = FeedUrls.origin(feedUrl) ?: return emptyMap()
        val reqOrigin = FeedUrls.origin(req)
        val allowedOrigin = reqOrigin == feedOrigin || (auth.sendToMediaHosts && reqOrigin in feedUrlOrigins)
        if (!allowedOrigin) return emptyMap()
        if (!req.isHttps && !auth.allowOverHttp) return emptyMap()
        return mapOf(h)
    }
}
