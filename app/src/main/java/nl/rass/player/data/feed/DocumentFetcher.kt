package nl.rass.player.data.feed

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Reads files from the APK's assets (the `asset:///` preset scheme). Null when the file is missing. */
fun interface AssetReader {
    fun open(path: String): InputStream?
}

data class FetchRequest(
    val url: String,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Auth headers per hop (recomputed after every redirect, feed-spec §14). */
    val headersFor: (String) -> Map<String, String> = { emptyMap() },
    val maxBytes: Long = FeedParser.MAX_DOCUMENT_BYTES,
    /** Return the first [maxBytes] instead of failing (HTML scan for discovery, §13 step 5). */
    val truncate: Boolean = false,
)

sealed interface FetchResult {
    data class Ok(
        val body: ByteArray,
        /** URL after redirects; relative URLs in the document resolve against it (§2.1). */
        val finalUrl: String,
        val etag: String? = null,
        val lastModified: String? = null,
        /** `Cache-Control: max-age`, or null. */
        val maxAgeSec: Long? = null,
        val contentType: String? = null,
    ) : FetchResult {
        val isHtml: Boolean
            get() = contentType?.lowercase()?.let { "text/html" in it || "application/xhtml+xml" in it } == true

        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class NotModified(val finalUrl: String, val maxAgeSec: Long?) : FetchResult
    data class Failed(val error: FeedError, val isConnectOrTls: Boolean = false) : FetchResult
}

/**
 * GETs a feed document (or reads it from assets). Follows up to 5 redirects by hand so the access token
 * is decided per hop; https→http redirects are refused (§13). Gzip is handled by OkHttp; the body limit
 * applies to decompressed bytes (§10.3).
 */
class DocumentFetcher(
    client: OkHttpClient,
    private val assets: AssetReader,
) {
    // No OkHttp HTTP cache here: feeds keep their own last-good copy (FeedCache) and send their own
    // conditional headers, so storing them again in the HTTP cache would only double the disk use.
    private val client: OkHttpClient =
        client.newBuilder().followRedirects(false).followSslRedirects(false).cache(null).build()

    /** Runs entirely on the IO dispatcher: closing an HTTP/2 response also writes to the socket. */
    suspend fun fetch(req: FetchRequest): FetchResult = withContext(Dispatchers.IO) { fetchBlocking(req) }

    private suspend fun fetchBlocking(req: FetchRequest): FetchResult {
        if (FeedUrls.isAsset(req.url)) return fetchAsset(req)
        var url = req.url
        repeat(MAX_REDIRECTS + 1) {
            val httpUrl = url.toHttpUrlOrNull() ?: return FetchResult.Failed(FeedError(FeedErrorKind.INVALID_ADDRESS))
            val b = Request.Builder().url(httpUrl).get()
                .header("Accept", "application/vnd.rass.feed+json, application/json;q=0.9, */*;q=0.5")
            req.headersFor(url).forEach { (k, v) -> b.header(k, v) }
            req.etag?.let { b.header("If-None-Match", it) }
            req.lastModified?.let { b.header("If-Modified-Since", it) }
            val response = try {
                client.newCall(b.build()).await()
            } catch (e: IOException) {
                val connectOrTls = e is ConnectException || e is SSLException || e is UnknownHostException
                return FetchResult.Failed(FeedError(FeedErrorKind.NETWORK, e.javaClass.simpleName), connectOrTls)
            }
            response.use { r ->
                if (r.isRedirect) {
                    val loc = r.header("Location") ?: return FetchResult.Failed(FeedError(FeedErrorKind.HTTP, "redirect without Location", r.code))
                    val next = httpUrl.resolve(loc) ?: return FetchResult.Failed(FeedError(FeedErrorKind.INVALID_ADDRESS))
                    if (httpUrl.isHttps && !next.isHttps) return FetchResult.Failed(FeedError(FeedErrorKind.INSECURE_REDIRECT))
                    url = next.toString()
                    return@repeat
                }
                val maxAge = r.cacheControl.maxAgeSeconds.takeIf { it >= 0 }?.toLong()
                if (r.code == 304) return FetchResult.NotModified(url, maxAge)
                if (r.code == 401 || r.code == 403) return FetchResult.Failed(FeedError(FeedErrorKind.NEEDS_TOKEN, httpCode = r.code))
                if (r.code != 200) return FetchResult.Failed(FeedError(FeedErrorKind.HTTP, httpCode = r.code))
                val body = r.body
                val declared = body.contentLength()
                if (!req.truncate && declared > req.maxBytes) return FetchResult.Failed(FeedError(FeedErrorKind.TOO_LARGE))
                val bytes = try {
                    withContext(Dispatchers.IO) { readLimited(body.byteStream(), req.maxBytes, req.truncate) }
                } catch (e: IOException) {
                    return FetchResult.Failed(FeedError(FeedErrorKind.NETWORK, e.javaClass.simpleName))
                } ?: return FetchResult.Failed(FeedError(FeedErrorKind.TOO_LARGE))
                return FetchResult.Ok(bytes, url, r.header("ETag"), r.header("Last-Modified"), maxAge, r.header("Content-Type"))
            }
        }
        return FetchResult.Failed(FeedError(FeedErrorKind.TOO_MANY_REDIRECTS))
    }

    private suspend fun fetchAsset(req: FetchRequest): FetchResult = withContext(Dispatchers.IO) {
        val path = FeedUrls.assetPath(req.url) ?: return@withContext FetchResult.Failed(FeedError(FeedErrorKind.INVALID_ADDRESS))
        val stream = try { assets.open(path) } catch (e: IOException) { null }
            ?: return@withContext FetchResult.Failed(FeedError(FeedErrorKind.ASSET_MISSING, path))
        val bytes = stream.use { readLimited(it, req.maxBytes, req.truncate) }
            ?: return@withContext FetchResult.Failed(FeedError(FeedErrorKind.TOO_LARGE))
        FetchResult.Ok(bytes, req.url, contentType = "application/json")
    }

    companion object {
        const val MAX_REDIRECTS = 5

        /** Reads up to [max] bytes; null when the stream is longer and [truncate] is false. */
        fun readLimited(input: InputStream, max: Long, truncate: Boolean): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (total + n > max) {
                    if (!truncate) return null
                    out.write(buf, 0, (max - total).toInt())
                    break
                }
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
