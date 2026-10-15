package nl.rass.player.player.subtitle

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import nl.rass.player.data.feed.AssetReader
import nl.rass.player.data.feed.FeedUrls
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class SubtitleLoadException(val reason: Reason, message: String, cause: Throwable? = null) :
    IOException(message, cause) {
    enum class Reason { HTTP, TOO_LARGE, NETWORK, NO_CUES }
}

/**
 * Downloads, decodes and parses subtitle files, with a small in-memory LRU cache of parsed tracks.
 * [load] is suspending and cancellable: cancelling the coroutine cancels the HTTP call.
 *
 * Pass the app's shared [OkHttpClient] so subtitle traffic follows the same proxy settings as the rest
 * of the app (R10.4).
 *
 * `asset:///` URLs (preset test feeds, feed-spec §2.1.1/§8) are read from the APK through [assets] with the
 * same size cap; without an [assets] reader they fail like a 404.
 */
class SubtitleLoader(
    client: OkHttpClient,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    cacheSize: Int = DEFAULT_CACHE_SIZE,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val assets: AssetReader? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Source access header (feed-spec §14), added only on hops to [origin], so redirects elsewhere drop it. */
    private class AuthTag(val origin: FeedUrls.Origin, val headers: Map<String, String>)

    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val tag = req.tag(AuthTag::class.java)
            if (tag == null || FeedUrls.origin(req.url) != tag.origin) {
                chain.proceed(req)
            } else {
                chain.proceed(req.newBuilder().apply { tag.headers.forEach { (k, v) -> header(k, v) } }.build())
            }
        }
        .build()

    private val cache = object : LinkedHashMap<String, SubtitleTrack>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SubtitleTrack>) = size > cacheSize
    }

    /**
     * @param formatHint format from the feed, if known; otherwise sniffed from content, URL and Content-Type.
     * @param encodingHint charset from the feed, used when valid (see [SubtitleDecoder]).
     * @param headers the source's access header for [url] (from MediaAuthHeaders); sent only to [url]'s origin.
     */
    suspend fun load(
        url: String,
        formatHint: SubtitleFormat? = null,
        encodingHint: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): SubtitleTrack {
        val key = "$url|${formatHint ?: ""}|${encodingHint ?: ""}"
        synchronized(cache) { cache[key] }?.let { return it }

        // The Content-Type charset is ignored on purpose: servers often label windows-1256 files as UTF-8
        // or ISO-8859-1, and content detection is more reliable.
        val (bytes, contentType) = if (FeedUrls.isAsset(url)) readAsset(url) else download(url, headers)
        val track = withContext(parseDispatcher) {
            val text = SubtitleDecoder.decode(bytes, encodingHint)
            val format = formatHint ?: SubtitleFormat.detect(url, contentType, text)
            SubtitleTrack.parse(text, format)
        }
        if (track.isEmpty) throw SubtitleLoadException(SubtitleLoadException.Reason.NO_CUES, "No subtitle cues found")
        synchronized(cache) { cache[key] = track }
        return track
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    private class Download(val bytes: ByteArray, val contentType: String?) {
        operator fun component1() = bytes
        operator fun component2() = contentType
    }

    private suspend fun readAsset(url: String): Download = withContext(ioDispatcher) {
        val path = FeedUrls.assetPath(url)
        val input = path?.let { p -> runCatching { assets?.open(p) }.getOrNull() }
            ?: throw SubtitleLoadException(SubtitleLoadException.Reason.HTTP, "Asset not found")
        Download(input.use { readCappedStream(it) }, null)
    }

    private suspend fun download(url: String, headers: Map<String, String>): Download = suspendCancellableCoroutine { cont ->
        val builder = Request.Builder().url(url).get()
        if (headers.isNotEmpty()) FeedUrls.origin(url)?.let { builder.tag(AuthTag::class.java, AuthTag(it, headers)) }
        val call = client.newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        SubtitleLoadException(SubtitleLoadException.Reason.NETWORK, "Subtitle download failed", e),
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { readCapped(it) } }
                if (!cont.isActive) return
                result.fold({ cont.resume(it) }) { e ->
                    cont.resumeWithException(
                        e as? SubtitleLoadException
                            ?: SubtitleLoadException(SubtitleLoadException.Reason.NETWORK, "Subtitle read failed", e),
                    )
                }
            }
        })
    }

    private fun readCapped(response: Response): Download {
        if (!response.isSuccessful) {
            throw SubtitleLoadException(SubtitleLoadException.Reason.HTTP, "HTTP ${response.code}")
        }
        val body = response.body
        val declared = body.contentLength()
        if (declared > maxBytes) throw tooLarge()
        val bytes = body.byteStream().use { readCappedStream(it, declared) }
        return Download(bytes, body.contentType()?.toString())
    }

    private fun readCappedStream(input: java.io.InputStream, declared: Long = -1): ByteArray {
        val out = ByteArrayOutputStream(if (declared in 1..maxBytes) declared.toInt() else 64 * 1024)
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw tooLarge()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun tooLarge() =
        SubtitleLoadException(SubtitleLoadException.Reason.TOO_LARGE, "Subtitle file is larger than $maxBytes bytes")

    companion object {
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val DEFAULT_CACHE_SIZE = 8
    }
}
