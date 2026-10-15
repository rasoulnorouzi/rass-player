package nl.rass.player.data.source

import nl.rass.player.data.feed.DocumentFetcher
import nl.rass.player.data.feed.FeedError
import nl.rass.player.data.feed.FeedErrorKind
import nl.rass.player.data.feed.FeedException
import nl.rass.player.data.feed.FeedParser
import nl.rass.player.data.feed.FetchRequest
import nl.rass.player.data.feed.FetchResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface DiscoveryResult {
    /**
     * @param feedUrl the URL requested in the successful step (before redirects), to save in the source (§13).
     * @param insecure discovery fell back to http (show "not secure").
     */
    data class Found(val feedUrl: String, val feedName: String?, val titleCount: Int, val insecure: Boolean) : DiscoveryResult

    data class Failed(val error: FeedError) : DiscoveryResult {
        val messageFa: String get() = error.messageFa
    }
}

/**
 * "Add a site by its address" (R1.5, feed-spec §13): the input itself, `/.well-known/rass-feed.json`,
 * `/rass-feed.json`, then a `<link rel="alternate" type="application/vnd.rass.feed+json">` on the home page.
 * Never renders HTML or runs scripts; the link tag is found with a plain text scan of the first 512 KB.
 */
class FeedDiscovery(
    private val fetcher: DocumentFetcher,
    private val parser: FeedParser = FeedParser(),
) {
    suspend fun discover(input: String, auth: SourceAuth = SourceAuth.None): DiscoveryResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return fail(FeedErrorKind.INVALID_ADDRESS)
        val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") return fail(FeedErrorKind.INVALID_ADDRESS)
        val url = (if (scheme == null) "https://$trimmed" else trimmed).toHttpUrlOrNull() ?: return fail(FeedErrorKind.INVALID_ADDRESS)

        val first = attempt(url, auth)
        if (first is Outcome.ConnectFailed && scheme == null) {
            val httpUrl = url.newBuilder().scheme("http").port(if (url.port == 443) 80 else url.port).build()
            return when (val second = attempt(httpUrl, auth)) {
                is Outcome.Done -> second.result.let { if (it is DiscoveryResult.Found) it.copy(insecure = true) else it }
                is Outcome.ConnectFailed -> fail(FeedErrorKind.NETWORK)
            }
        }
        return when (first) {
            is Outcome.Done -> first.result
            is Outcome.ConnectFailed -> fail(FeedErrorKind.NETWORK)
        }
    }

    private sealed interface Outcome {
        data class Done(val result: DiscoveryResult) : Outcome
        data object ConnectFailed : Outcome
    }

    private sealed interface Step {
        data class Feed(val found: DiscoveryResult.Found) : Step
        data class Html(val body: ByteArray, val pageUrl: String) : Step
        data class Stop(val error: FeedError) : Step
        data class Miss(val connectFailure: Boolean) : Step
    }

    private suspend fun attempt(site: HttpUrl, auth: SourceAuth): Outcome {
        val siteUrl = site.toString()
        val insecure = !site.isHttps
        var anyResponse = false
        var connectFailure = false

        suspend fun tryUrl(u: String, truncate: Boolean = false): Step {
            val res = fetcher.fetch(
                FetchRequest(
                    url = u,
                    headersFor = { AuthHeaders.headersFor(it, siteUrl, auth) },
                    maxBytes = if (truncate) HTML_SCAN_BYTES else FeedParser.MAX_DOCUMENT_BYTES,
                    truncate = truncate,
                )
            )
            return when (res) {
                is FetchResult.Failed -> {
                    if (res.isConnectOrTls) connectFailure = true else anyResponse = true
                    if (res.error.kind == FeedErrorKind.NEEDS_TOKEN) Step.Stop(res.error) else Step.Miss(res.isConnectOrTls)
                }
                is FetchResult.NotModified -> { anyResponse = true; Step.Miss(false) }
                is FetchResult.Ok -> {
                    anyResponse = true
                    if (res.isHtml) return Step.Html(res.body, res.finalUrl)
                    try {
                        val doc = parser.parse(res.body, res.finalUrl)
                        Step.Feed(DiscoveryResult.Found(u, doc.name, doc.titles.count { it.title.isPlayable }, insecure))
                    } catch (e: FeedException) {
                        if (e.error.kind == FeedErrorKind.HTML_PAGE) Step.Html(res.body, res.finalUrl) else Step.Miss(false)
                    }
                }
            }
        }

        suspend fun viaLink(html: Step.Html): Step? {
            val href = findFeedLink(String(html.body, Charsets.UTF_8), html.pageUrl) ?: return null
            return tryUrl(href)
        }

        fun done(s: Step): Outcome? = when (s) {
            is Step.Feed -> Outcome.Done(s.found)
            is Step.Stop -> Outcome.Done(DiscoveryResult.Failed(s.error))
            else -> null
        }

        // 2. the input itself, when it has a path
        if (site.encodedPath != "/") {
            val s = tryUrl(siteUrl)
            done(s)?.let { return it }
            if (s is Step.Html) viaLink(s)?.let { st -> done(st)?.let { return it } }
        }
        val origin = site.newBuilder().encodedPath("/").query(null).fragment(null).build()
        // 3, 4
        for (path in WELL_KNOWN) {
            done(tryUrl(origin.resolve(path).toString()))?.let { return it }
        }
        // 5. home page link tag
        val home = tryUrl(origin.toString(), truncate = true)
        done(home)?.let { return it }
        if (home is Step.Html) viaLink(home)?.let { st -> done(st)?.let { return it } }

        if (!anyResponse && connectFailure) return Outcome.ConnectFailed
        return Outcome.Done(fail(FeedErrorKind.NOT_FOUND))
    }

    private fun fail(kind: FeedErrorKind) = DiscoveryResult.Failed(FeedError(kind))

    companion object {
        const val HTML_SCAN_BYTES = 512L * 1024
        const val FEED_MEDIA_TYPE = "application/vnd.rass.feed+json"
        private val WELL_KNOWN = listOf("/.well-known/rass-feed.json", "/rass-feed.json")
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://")
        private val TAG = Regex("<(link|base)\\b([^>]*)>", RegexOption.IGNORE_CASE)
        private val ATTR = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))")

        /** First matching `<link>` href, resolved against the page (respecting the first `<base href>`). */
        fun findFeedLink(html: String, pageUrl: String): String? {
            var base = pageUrl.toHttpUrlOrNull() ?: return null
            var baseSeen = false
            for (m in TAG.findAll(html)) {
                val attrs = HashMap<String, String>()
                for (a in ATTR.findAll(m.groupValues[2])) {
                    val v = a.groups[3]?.value ?: a.groups[4]?.value ?: a.groups[5]?.value ?: ""
                    attrs.putIfAbsent(a.groupValues[1].lowercase(), decodeEntities(v))
                }
                if (m.groupValues[1].equals("base", ignoreCase = true)) {
                    if (!baseSeen) { attrs["href"]?.let { h -> base.resolve(h)?.let { base = it } }; baseSeen = true }
                    continue
                }
                val rel = attrs["rel"]?.lowercase()?.split(Regex("\\s+")) ?: continue
                if ("alternate" !in rel) continue
                val type = attrs["type"]?.substringBefore(';')?.trim()?.lowercase() ?: continue
                if (type != FEED_MEDIA_TYPE) continue
                val href = attrs["href"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                return base.resolve(href)?.toString()
            }
            return null
        }

        private fun decodeEntities(s: String) =
            s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    }
}
