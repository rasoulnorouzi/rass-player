package nl.rass.player.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Measures real delay (HTTP GET round trip) through a given proxy. */
fun interface HttpProbe {
    /** Returns the delay in ms, or throws (IOException / timeout) if the URL can't be reached in [timeoutMs]. */
    suspend fun measure(proxy: Proxy, url: String, timeoutMs: Long): Long
}

/**
 * [HttpProbe] on OkHttp, like v2rayNG's "real delay": two GETs, the second on the warm connection, and the
 * faster one counts. Each probe gets its own connection pool, which is cleared afterwards, so no connection
 * to a short-lived test port lingers. [base] must NOT be the shared app client (no kill-switch interceptor).
 */
class OkHttpProbe(private val base: OkHttpClient) : HttpProbe {

    override suspend fun measure(proxy: Proxy, url: String, timeoutMs: Long): Long = withContext(Dispatchers.IO) { measureIo(proxy, url, timeoutMs) }

    private suspend fun measureIo(proxy: Proxy, url: String, timeoutMs: Long): Long {
        val pool = ConnectionPool(1, 10, TimeUnit.SECONDS)
        val client = base.newBuilder()
            .proxy(proxy)
            .connectionPool(pool)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()
        try {
            val t0 = System.nanoTime()
            val first = withTimeout(timeoutMs) { timedGet(client, url) }
            val left = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            val second = if (left <= 0) null else withTimeoutOrNull(left) {
                try {
                    timedGet(client, url)
                } catch (e: IOException) {
                    null
                }
            }
            return minOf(first, second ?: first)
        } finally {
            pool.evictAll()
        }
    }

    private suspend fun timedGet(client: OkHttpClient, url: String): Long {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        val t0 = System.nanoTime()
        client.newCall(request).await().use { response ->
            if (response.code != 204 && response.code != 200) throw IOException("probe status ${response.code}")
            response.body.bytes()
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
    }

    companion object {
        private val LOOPBACK_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** HTTP proxy on loopback (resolved address, so no DNS lookup for "127.0.0.1"). */
        fun httpProxy(port: Int): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK_ADDRESS, port))
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
