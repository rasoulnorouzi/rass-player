package nl.rass.player.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Downloads a subscription body. Faked in tests. */
fun interface SubscriptionFetcher {
    /** Fetches [url] through [proxy] ([Proxy.NO_PROXY] = direct). Throws IOException without echoing the URL. */
    suspend fun fetch(url: String, proxy: Proxy): String
}

/**
 * OkHttp implementation. [base] is the plain client (no kill-switch wiring); [ProxyManager] decides the route
 * explicitly, because a refresh may be the only way out of a "nothing works" state.
 */
class OkHttpSubscriptionFetcher(private val base: OkHttpClient) : SubscriptionFetcher {

    override suspend fun fetch(url: String, proxy: Proxy): String = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url)
                // Subscription panels (e.g. BPB) pick the output format by client; ask for the v2ray/Xray format.
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .build()
        } catch (e: IllegalArgumentException) {
            throw IOException("invalid subscription URL")
        }
        val client = base.newBuilder()
            .proxy(proxy)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("subscription HTTP ${response.code}")
            val body = response.body
            val length = body.contentLength()
            if (length > MAX_BYTES) throw IOException("subscription too large")
            val source = body.source()
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) throw IOException("subscription too large")
            source.buffer.readUtf8()
        }
    }

    companion object {
        const val USER_AGENT = "v2rayNG/1.10.23"
        const val MAX_BYTES = 5L * 1024 * 1024
    }
}
