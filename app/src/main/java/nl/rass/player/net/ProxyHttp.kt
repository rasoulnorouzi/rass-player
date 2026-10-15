package nl.rass.player.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** How app HTTP traffic must be routed right now. */
sealed interface RouteDecision {
    /** Private connection off (or kill switch off and not connected): go direct. */
    data object Direct : RouteDecision

    /** Go through the local Xray proxy. */
    data class Proxied(val proxy: Proxy) : RouteDecision

    /** Still connecting; callers may wait a little. */
    data object Pending : RouteDecision

    /** Kill switch: do not send anything. */
    data class Blocked(val reason: FailReason?) : RouteDecision
}

/** Pure kill-switch policy (R10.5), unit-tested in isolation. */
object RoutePolicy {
    fun decide(ready: Boolean, settings: ProxySettings, state: ProxyState, endpoint: ProxyEndpoint?): RouteDecision {
        if (!ready) return RouteDecision.Pending
        if (!settings.enabled) return RouteDecision.Direct
        if (state is ProxyState.Connected && endpoint != null) {
            return RouteDecision.Proxied(OkHttpProbe.httpProxy(endpoint.httpPort))
        }
        if (!settings.killSwitch) return RouteDecision.Direct
        return when (state) {
            is ProxyState.Connecting -> RouteDecision.Pending
            is ProxyState.Failed -> RouteDecision.Blocked(state.reason)
            // Enabled but Off/Connected-without-endpoint is a transient state: treat as still connecting.
            else -> RouteDecision.Pending
        }
    }
}

/** Live routing source for the shared client; implemented by [ProxyManager]. */
interface RouteSource {
    fun currentRoute(): RouteDecision

    /** Waits up to [maxWaitMs] while the decision is [RouteDecision.Pending]. Called on OkHttp threads only. */
    fun awaitRoute(maxWaitMs: Long): RouteDecision

    /** Called after the route changes (e.g. new proxy port) so idle pooled connections can be dropped. */
    fun addRouteChangeListener(listener: () -> Unit)

    /** A request through the proxy failed at the network level: a hint to health-check the active config now. */
    fun onProxiedCallFailed() = Unit
}

/**
 * Wires the private connection into an OkHttpClient. Use it once, on the app's single shared client
 * (di/HttpModule.kt). Everything that client carries (feeds, Coil images, subtitles, Media3 via
 * OkHttpDataSource, updater) then follows the proxy state live.
 *
 * Three layers, so a race can never leak a direct request while the kill switch is on:
 *  1. [KillSwitchInterceptor] (application): waits while connecting, then fails fast when blocked.
 *  2. [LiveProxySelector]: picks the current proxy for each new connection; when not allowed to go direct,
 *     returns an unroutable proxy instead of NO_PROXY.
 *  3. [RouteGuardInterceptor] (network): checks the actual connection's proxy before any byte is sent.
 * Idle pooled connections are evicted whenever the route changes.
 */
object ProxyHttp {
    /** How long a request waits for "Connecting" to finish before the kill switch fails it. */
    const val CONNECT_WAIT_MS = 20_000L

    fun install(builder: OkHttpClient.Builder, routes: RouteSource): OkHttpClient {
        val client = builder
            .proxySelector(LiveProxySelector(routes))
            .addInterceptor(KillSwitchInterceptor(routes))
            .addNetworkInterceptor(RouteGuardInterceptor(routes))
            .build()
        routes.addRouteChangeListener { client.connectionPool.evictAll() }
        return client
    }

    /** Points at a closed loopback port, so a connection attempt fails instead of going direct. */
    internal val UNROUTABLE: Proxy =
        Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1))
}

class KillSwitchInterceptor(private val routes: RouteSource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val d = routes.awaitRoute(ProxyHttp.CONNECT_WAIT_MS)
        when (d) {
            is RouteDecision.Blocked -> throw PrivateConnectionUnavailableException(d.reason)
            RouteDecision.Pending -> throw PrivateConnectionUnavailableException(null)
            else -> Unit
        }
        if (d !is RouteDecision.Proxied) return chain.proceed(chain.request())
        return try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            // Failures through the proxy trigger an early (debounced) health check instead of waiting 5 minutes.
            if (e !is PrivateConnectionUnavailableException && !chain.call().isCanceled()) routes.onProxiedCallFailed()
            throw e
        }
    }
}

class LiveProxySelector(private val routes: RouteSource) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = when (val d = routes.currentRoute()) {
        RouteDecision.Direct -> listOf(Proxy.NO_PROXY)
        is RouteDecision.Proxied -> listOf(d.proxy)
        else -> listOf(ProxyHttp.UNROUTABLE)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

class RouteGuardInterceptor(private val routes: RouteSource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val used = chain.connection()?.route()?.proxy
        when (val d = routes.currentRoute()) {
            RouteDecision.Direct -> Unit
            is RouteDecision.Proxied -> if (used != d.proxy) {
                throw PrivateConnectionUnavailableException(null, "Connection does not use the current private connection")
            }
            is RouteDecision.Blocked -> throw PrivateConnectionUnavailableException(d.reason)
            RouteDecision.Pending -> throw PrivateConnectionUnavailableException(null)
        }
        return chain.proceed(chain.request())
    }
}
