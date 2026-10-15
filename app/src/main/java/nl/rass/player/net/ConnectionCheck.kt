package nl.rass.player.net

import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Exit address as seen by a public echo service. [country] is an ISO code when the service reports one. */
data class ExitInfo(val ip: String, val country: String?)

/** Parsers for the two echo services (pure, unit-tested). */
object ExitInfoParser {
    /** Cloudflare `/cdn-cgi/trace`: plain `key=value` lines (`ip=…`, `loc=NL`, …). */
    fun parseTrace(body: String): ExitInfo? {
        val map = body.lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
            .toMap()
        val ip = map["ip"]?.takeIf { it.isNotEmpty() } ?: return null
        return ExitInfo(ip, map["loc"]?.takeIf { it.isNotEmpty() && it != "XX" })
    }

    /** ipify `?format=json`: `{"ip":"…"}` (no country). */
    fun parseIpify(body: String): ExitInfo? = runCatching {
        Json.parseToJsonElement(body).jsonObject["ip"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { ExitInfo(it, null) }
    }.getOrNull()
}

sealed interface CheckOutcome {
    data class Ok(val info: ExitInfo) : CheckOutcome
    /** The kill switch blocked the request (private connection on but not connected). */
    data object Blocked : CheckOutcome
    data object Failed : CheckOutcome
}

/**
 * Settings → Private connection → "connection check": which address does the internet see?
 * [viaApp] uses the app's shared client (exactly the route feeds/images/video take). [direct] deliberately
 * bypasses the private connection, only when the user taps "compare with direct", to show that the address changed.
 * Neither result is logged or stored.
 */
@Singleton
class ConnectionChecker @Inject constructor(
    private val shared: OkHttpClient,
    @PlainHttpClient private val plain: OkHttpClient,
) {
    suspend fun viaApp(): CheckOutcome = fetch(shared.newBuilder().callTimeout(25, TimeUnit.SECONDS).build())

    suspend fun direct(): CheckOutcome =
        // Own pool: the deliberate direct connection is closed right after, never reused by anything else.
        fetch(
            plain.newBuilder().proxy(Proxy.NO_PROXY).connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
                .callTimeout(15, TimeUnit.SECONDS).build(),
        )

    private suspend fun fetch(client: OkHttpClient): CheckOutcome = withContext(Dispatchers.IO) {
        var blocked = false
        for ((url, parse) in ENDPOINTS) {
            try {
                client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    if (r.isSuccessful) parse(r.body.string())?.let { return@withContext CheckOutcome.Ok(it) }
                }
            } catch (e: PrivateConnectionUnavailableException) {
                blocked = true
            } catch (e: java.io.IOException) {
                // try the next service
            }
        }
        if (blocked) CheckOutcome.Blocked else CheckOutcome.Failed
    }

    private companion object {
        val ENDPOINTS: List<Pair<String, (String) -> ExitInfo?>> = listOf(
            "https://www.cloudflare.com/cdn-cgi/trace" to ExitInfoParser::parseTrace,
            "https://api.ipify.org?format=json" to ExitInfoParser::parseIpify,
        )
    }
}
