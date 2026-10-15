package nl.rass.player.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoutePolicyTest {
    private val ep = ProxyEndpoint(1080, 1081)
    private val on = ProxySettings(enabled = true, killSwitch = true)

    @Test
    fun notReadyIsPending() =
        assertEquals(RouteDecision.Pending, RoutePolicy.decide(false, ProxySettings(), ProxyState.Off, null))

    @Test
    fun switchOffIsDirectEvenIfFailed() =
        assertEquals(RouteDecision.Direct, RoutePolicy.decide(true, ProxySettings(enabled = false), ProxyState.Failed(FailReason.NO_CONFIGS), null))

    @Test
    fun connectedUsesLoopbackHttpProxy() {
        val d = RoutePolicy.decide(true, on, ProxyState.Connected("A", 100), ep)
        assertIs<RouteDecision.Proxied>(d)
        assertEquals(Proxy.Type.HTTP, d.proxy.type())
        assertEquals(1081, (d.proxy.address() as InetSocketAddress).port)
        assertTrue((d.proxy.address() as InetSocketAddress).address.isLoopbackAddress)
    }

    @Test
    fun killSwitchBlocksOnFailureAndWaitsWhileConnecting() {
        assertEquals(RouteDecision.Blocked(FailReason.NO_WORKING_CONFIG), RoutePolicy.decide(true, on, ProxyState.Failed(FailReason.NO_WORKING_CONFIG), null))
        assertEquals(RouteDecision.Pending, RoutePolicy.decide(true, on, ProxyState.Connecting(), null))
        // Connected but endpoint already cleared (restart in progress): never direct.
        assertEquals(RouteDecision.Pending, RoutePolicy.decide(true, on, ProxyState.Connected("A", 1), null))
    }

    @Test
    fun killSwitchOffFailsOpen() {
        val s = on.copy(killSwitch = false)
        assertEquals(RouteDecision.Direct, RoutePolicy.decide(true, s, ProxyState.Failed(FailReason.NO_WORKING_CONFIG), null))
        assertEquals(RouteDecision.Direct, RoutePolicy.decide(true, s, ProxyState.Connecting(), null))
    }
}

/** The shared-client wiring, against a local MockWebServer (no real network). */
class ProxyHttpTest {
    private lateinit var server: MockWebServer

    private class FakeRoutes(var decision: RouteDecision) : RouteSource {
        val listeners = mutableListOf<() -> Unit>()
        override fun currentRoute() = decision
        override fun awaitRoute(maxWaitMs: Long) = decision
        override fun addRouteChangeListener(listener: () -> Unit) {
            listeners += listener
        }
    }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun client(routes: RouteSource) = ProxyHttp.install(OkHttpClient.Builder(), routes)

    private fun get(client: OkHttpClient, url: String) =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun directWhenOff() {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(200, get(client(FakeRoutes(RouteDecision.Direct)), server.url("/feed").toString()))
        assertEquals("/feed", server.takeRequest().path)
    }

    @Test
    fun blockedFailsFastWithoutSendingAnything() {
        val c = client(FakeRoutes(RouteDecision.Blocked(FailReason.NO_WORKING_CONFIG)))
        val e = assertFailsWith<PrivateConnectionUnavailableException> { get(c, server.url("/feed").toString()) }
        assertEquals(FailReason.NO_WORKING_CONFIG, e.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun pendingAfterWaitIsBlocked() {
        val c = client(FakeRoutes(RouteDecision.Pending))
        assertFailsWith<PrivateConnectionUnavailableException> { get(c, server.url("/x").toString()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun proxiedRequestsGoToTheProxy() {
        // MockWebServer plays the local Xray HTTP inbound: a proxied plain-HTTP request arrives in absolute form.
        server.enqueue(MockResponse().setResponseCode(204))
        val proxy = OkHttpProbe.httpProxy(server.port)
        val c = client(FakeRoutes(RouteDecision.Proxied(proxy)))
        assertEquals(204, get(c, "http://feed.example.invalid/catalog.json"))
        val recorded = server.takeRequest()
        assertEquals("GET http://feed.example.invalid/catalog.json HTTP/1.1", recorded.requestLine)
    }

    @Test
    fun routeGuardRejectsAPooledDirectConnectionAfterSwitchingOn() {
        val routes = FakeRoutes(RouteDecision.Direct)
        val c = client(routes)
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(200, get(c, server.url("/1").toString()))
        // Proxy turns on (to some other port) but the listener is NOT fired, so the direct connection is still
        // pooled: the network interceptor must refuse to use it.
        routes.decision = RouteDecision.Proxied(OkHttpProbe.httpProxy(1))
        server.enqueue(MockResponse().setResponseCode(200))
        assertFailsWith<java.io.IOException> { get(c, server.url("/2").toString()) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun routeChangeEvictsIdleConnections() {
        val routes = FakeRoutes(RouteDecision.Direct)
        val c = client(routes)
        server.enqueue(MockResponse().setResponseCode(200))
        get(c, server.url("/1").toString())
        assertEquals(1, c.connectionPool.idleConnectionCount())
        routes.listeners.forEach { it() }
        assertEquals(0, c.connectionPool.idleConnectionCount())
    }
}
