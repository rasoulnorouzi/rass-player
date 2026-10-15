package nl.rass.player.data.feed

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nl.rass.player.data.catalog.CacheMeta
import nl.rass.player.data.catalog.FeedCache
import nl.rass.player.data.source.AuthHeaders
import nl.rass.player.data.source.SourceAuth

/** What the loader needs to know about a source. */
data class SourceSpec(val id: String, val name: String, val url: String, val auth: SourceAuth = SourceAuth.None)

data class LoadResult(
    /** The catalog to show: freshly loaded, or the last good cached copy when [error] is set. */
    val feed: AssembledFeed?,
    /** Fatal error of this attempt (the previous good copy, if any, is in [feed]). */
    val error: FeedError? = null,
    val fromCache: Boolean = false,
    /** Every document answered 304: nothing changed. */
    val notModified: Boolean = false,
    /** When the main document was last confirmed current. */
    val fetchedAt: Long? = null,
    val maxAgeSec: Long? = null,
)

/**
 * Loads one source: main document, then its parts (up to 4 at a time, §11), with conditional requests and
 * the on-disk last-good copy (§15). `asset:///` sources are read from the APK and never cached.
 */
class FeedLoader(
    private val fetcher: DocumentFetcher,
    private val cache: FeedCache?,
    private val parser: FeedParser = FeedParser(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Builds the source's catalog from cached documents only (app start, offline). */
    fun loadCached(spec: SourceSpec): LoadResult {
        val c = cache ?: return LoadResult(null, FeedError(FeedErrorKind.NO_CACHE))
        val main = c.read(spec.id, spec.url) ?: return LoadResult(null, FeedError(FeedErrorKind.NO_CACHE), fromCache = true)
        val doc = try {
            parser.parse(main.body, main.meta.finalUrl)
        } catch (e: FeedException) {
            return LoadResult(null, e.error, fromCache = true)
        }
        val partIssues = FeedIssues()
        val parts = doc.parts.mapIndexed { i, url ->
            val cached = c.read(spec.id, url)
            if (cached == null) { partIssues.partError("parts[$i]", "not cached"); null }
            else try { parser.parse(cached.body, cached.meta.finalUrl, expectPart = true, pathPrefix = "parts[$i]:") }
            catch (e: FeedException) { partIssues.partError("parts[$i]", e.error.toString()); null }
        }
        return LoadResult(FeedAssembler.assemble(doc, parts, partIssues), fromCache = true,
            fetchedAt = main.meta.fetchedAt, maxAgeSec = main.meta.maxAgeSec)
    }

    /**
     * Fetches the source from the network. [useCache] = false (Test connection) ignores and doesn't write the
     * cache. On a fatal error the result carries the last good cached catalog, if any.
     */
    suspend fun refresh(spec: SourceSpec, useCache: Boolean = true): LoadResult {
        val c = if (useCache && !FeedUrls.isAsset(spec.url)) cache else null
        val cachedMain = c?.read(spec.id, spec.url)
        fun fallback(error: FeedError): LoadResult {
            val cached = if (c != null) loadCached(spec) else null
            return LoadResult(cached?.feed, error, fromCache = cached?.feed != null, fetchedAt = cached?.fetchedAt)
        }

        val res = fetcher.fetch(
            FetchRequest(
                url = spec.url,
                etag = cachedMain?.meta?.etag,
                lastModified = cachedMain?.meta?.lastModified,
                headersFor = { AuthHeaders.headersFor(it, spec.url, spec.auth) },
            )
        )
        val fetchedAt = now()
        val bytes: ByteArray
        val finalUrl: String
        val maxAge: Long?
        var changed: Boolean
        when (res) {
            is FetchResult.Failed -> return fallback(res.error)
            is FetchResult.NotModified -> {
                if (cachedMain == null) return fallback(FeedError(FeedErrorKind.HTTP, "304 without cached copy", 304))
                bytes = cachedMain.body; finalUrl = cachedMain.meta.finalUrl; maxAge = res.maxAgeSec; changed = false
                c?.touch(spec.id, spec.url, fetchedAt, maxAge)
            }
            is FetchResult.Ok -> { bytes = res.body; finalUrl = res.finalUrl; maxAge = res.maxAgeSec; changed = true }
        }
        val main = try {
            parser.parse(bytes, finalUrl)
        } catch (e: FeedException) {
            return fallback(e.error)
        }
        if (res is FetchResult.Ok) {
            c?.write(spec.id, spec.url, bytes, CacheMeta(spec.url, finalUrl, res.etag, res.lastModified, fetchedAt, maxAge))
        }

        val partOrigins = if (spec.auth.sendToMediaHosts) main.parts.mapNotNull { FeedUrls.origin(it) }.toSet() else emptySet()
        val sem = Semaphore(PART_CONCURRENCY)
        val partResults = coroutineScope {
            main.parts.mapIndexed { i, url -> async { sem.withPermit { loadPart(spec, i, url, c, partOrigins) } } }.awaitAll()
        }
        val partIssues = FeedIssues()
        partResults.forEach { pr -> pr.error?.let { partIssues.partError("parts[${pr.index}]", it) }; if (pr.changed) changed = true }
        val assembled = FeedAssembler.assemble(main, partResults.map { it.doc }, partIssues)
        return LoadResult(assembled, notModified = !changed, fetchedAt = fetchedAt, maxAgeSec = maxAge)
    }

    private class PartResult(val index: Int, val doc: ParsedDocument?, val error: String?, val changed: Boolean)

    private suspend fun loadPart(spec: SourceSpec, i: Int, url: String, c: FeedCache?, origins: Set<FeedUrls.Origin>): PartResult {
        val prefix = "parts[$i]:"
        val cached = c?.read(spec.id, url)
        fun fromCache(err: String): PartResult {
            val doc = cached?.let { runCatching { parser.parse(it.body, it.meta.finalUrl, true, prefix) }.getOrNull() }
            return PartResult(i, doc, if (doc != null) "$err (cached copy used)" else err, false)
        }
        val res = fetcher.fetch(
            FetchRequest(url, cached?.meta?.etag, cached?.meta?.lastModified,
                headersFor = { AuthHeaders.headersFor(it, spec.url, spec.auth, origins) })
        )
        return when (res) {
            is FetchResult.Failed -> fromCache(res.error.toString())
            is FetchResult.NotModified -> {
                if (cached == null) return fromCache("304 without cached copy")
                c?.touch(spec.id, url, now(), res.maxAgeSec)
                fromCache("").let { if (it.doc != null) PartResult(i, it.doc, null, false) else it }
            }
            is FetchResult.Ok -> try {
                val doc = parser.parse(res.body, res.finalUrl, expectPart = true, pathPrefix = prefix)
                c?.write(spec.id, url, res.body, CacheMeta(url, res.finalUrl, res.etag, res.lastModified, now(), res.maxAgeSec))
                PartResult(i, doc, null, true)
            } catch (e: FeedException) {
                fromCache(e.error.toString())
            }
        }
    }

    companion object {
        const val PART_CONCURRENCY = 4
    }
}
