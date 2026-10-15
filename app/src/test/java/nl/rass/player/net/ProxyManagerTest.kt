package nl.rass.player.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** State machine, scheduling, caps, failover and kill switch with fakes only: no native code, no network. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyManagerTest {

    private fun cfg(name: String) = ProxyConfig(
        id = name, name = name, protocol = "trojan",
        outbound = Json.parseToJsonElement("""{"protocol":"trojan","settings":{"servers":[{"address":"$name.example.invalid","port":443,"password":"p"}]}}""").jsonObject,
    )

    private class MemoryStore(var data: ProxyStoreData) : ProxyStore {
        override suspend fun load() = data
        override suspend fun update(transform: (ProxyStoreData) -> ProxyStoreData) = transform(data).also { data = it }
    }

    /** [alive]: latency per config id when probed through the running instance; missing = down. */
    private class FakeRuntime(var alive: Map<String, Long>) : ProxyRuntime {
        val starts = mutableListOf<List<String>>() // [active, standby...]
        var running: List<String> = emptyList()
        var measures = 0
        private var nextPort = 40000
        override suspend fun start(active: ProxyConfig, standby: List<ProxyConfig>): ProxyEndpoint {
            running = listOf(active.id) + standby.map { it.id }
            starts += running
            return ProxyEndpoint(nextPort++, nextPort++, standby.map { nextPort++ })
        }
        override suspend fun stop() {
            running = emptyList()
        }
        override suspend fun measure(slot: Int, url: String, timeoutMs: Long): Long {
            measures++
            val id = running.getOrNull(slot) ?: throw IllegalStateException("not running")
            return alive[id] ?: throw IOException("down")
        }
    }

    /** Latency per config id; missing = failing. Records each batch. */
    private class FakeTester(var latencies: Map<String, Long>) : ConfigTester {
        val batches = mutableListOf<List<String>>()
        val concurrencies = mutableListOf<Int>()
        override suspend fun test(configs: List<ProxyConfig>, concurrency: Int): List<LatencyResult> {
            batches += configs.map { it.id }
            concurrencies += concurrency
            return LatencyTester.sortResults(configs.map { LatencyResult(it.id, latencies[it.id], null, 1) })
        }
    }

    private val timing = ProxyTiming(
        healthIntervalMs = 300_000, confirmDelayMs = 5_000, urgentDebounceMs = 0, retryDelaysMs = listOf(30_000),
    )

    private class Rig(val manager: ProxyManager, val store: MemoryStore, val runtime: FakeRuntime, val tester: FakeTester)

    private fun TestScope.rig(
        settings: ProxySettings = ProxySettings(enabled = true),
        configs: List<ProxyConfig> = listOf(cfg("slow"), cfg("fast"), cfg("mid")),
        latencies: Map<String, Long> = mapOf("slow" to 300L, "fast" to 50L, "mid" to 120L),
        results: List<LatencyResult> = emptyList(),
        fetcher: SubscriptionFetcher = SubscriptionFetcher { _, _ -> throw IOException("offline") },
    ): Rig {
        val store = MemoryStore(ProxyStoreData(settings = settings, configs = configs, results = results))
        val runtime = FakeRuntime(latencies.mapValues { 80L })
        val tester = FakeTester(latencies)
        val m = ProxyManager(store, runtime, tester, fetcher, backgroundScope, timing)
        m.start()
        runCurrent()
        return Rig(m, store, runtime, tester)
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ---- connect / kill switch ---------------------------------------------------------------------

    @Test
    fun autoModeRunsFastestWithTwoStandbys() = runTest {
        val r = rig(configs = listOf(cfg("slow"), cfg("fast"), cfg("mid"), cfg("slower")),
            latencies = mapOf("slow" to 300L, "fast" to 50L, "mid" to 120L, "slower" to 900L))
        assertEquals(ProxyState.Connected("fast", 80), r.manager.state.value)
        assertEquals(listOf(listOf("fast", "mid", "slow")), r.runtime.starts)
        val route = r.manager.currentRoute()
        assertIs<RouteDecision.Proxied>(route)
        assertEquals(40001, (route.proxy.address() as InetSocketAddress).port)
        assertEquals(Proxy.Type.HTTP, route.proxy.type())
        assertEquals(listOf(2), r.tester.concurrencies, "default concurrency 2")
    }

    @Test
    fun nothingWorksWithKillSwitchBlocks() = runTest {
        val r = rig(latencies = emptyMap())
        assertEquals(ProxyState.Failed(FailReason.NO_WORKING_CONFIG), r.manager.state.value)
        assertEquals(RouteDecision.Blocked(FailReason.NO_WORKING_CONFIG), r.manager.currentRoute())
        assertTrue(r.runtime.starts.isEmpty())
    }

    @Test
    fun nothingWorksWithoutKillSwitchGoesDirect() = runTest {
        val r = rig(settings = ProxySettings(enabled = true, killSwitch = false), latencies = emptyMap())
        assertIs<ProxyState.Failed>(r.manager.state.value)
        assertEquals(RouteDecision.Direct, r.manager.currentRoute())
    }

    @Test
    fun killSwitchToggleAppliesLiveWithoutRestart() = runTest {
        val r = rig(latencies = emptyMap())
        r.manager.setKillSwitch(false)
        assertEquals(RouteDecision.Direct, r.manager.currentRoute())
        r.manager.setKillSwitch(true)
        assertIs<RouteDecision.Blocked>(r.manager.currentRoute())
        assertEquals(1, r.tester.batches.size, "toggling the kill switch does not re-test")
    }

    @Test
    fun failedStateRetriesAndRecovers() = runTest {
        val r = rig(latencies = emptyMap())
        r.tester.latencies = mapOf("slow" to 120L)
        r.runtime.alive = mapOf("slow" to 90L)
        advance(30_001)
        assertEquals(ProxyState.Connected("slow", 90), r.manager.state.value)
    }

    @Test
    fun retryWaitsWhilePlayingOrInBackground() = runTest {
        val r = rig(latencies = emptyMap())
        r.manager.setAppForeground(false)
        advance(10 * 60_000)
        assertEquals(1, r.tester.batches.size, "no retries in background")
        r.manager.setAppForeground(true)
        runCurrent()
        assertEquals(2, r.tester.batches.size)
    }

    // ---- test caps ---------------------------------------------------------------------------------

    @Test
    fun testsAtMostTwentyPerRunAndOnlyGoesFurtherIfNoneWorked() = runTest {
        val many = (1..45).map { cfg("c$it") }
        val r = rig(configs = many, latencies = many.associate { it.id to 100L })
        assertEquals(listOf(20), r.tester.batches.map { it.size })

        val r2 = rig(configs = many, latencies = mapOf("c23" to 70L))
        assertEquals(listOf(20, 20), r2.tester.batches.map { it.size })
        assertEquals(ProxyState.Connected("c23", 80), r2.manager.state.value)
    }

    @Test
    fun testAllWalksEveryBatchSequentiallyInPriorityOrder() = runTest {
        val many = (1..45).map { cfg("c$it") }
        val r = rig(configs = many, latencies = many.associate { it.id to 100L })
        r.tester.batches.clear()
        val all = r.manager.testAll()
        assertEquals(listOf(20, 20, 5), r.tester.batches.map { it.size })
        assertEquals(45, all.size)
        // Last-known-good (the first batch) is tested first.
        assertEquals((1..20).map { "c$it" }.toSet(), r.tester.batches[0].toSet())
    }

    @Test
    fun appStartReusesRecentResultsButEnableTestsAgain() = runTest {
        val now = System.currentTimeMillis()
        val r = rig(results = listOf(LatencyResult("mid", 120, null, now), LatencyResult("fast", 50, null, now)))
        assertEquals(0, r.tester.batches.size, "app start: no test run when recent results still work")
        assertEquals(listOf("fast", "mid"), r.runtime.starts.single())
        r.manager.setEnabled(false)
        r.manager.setEnabled(true)
        runCurrent()
        assertEquals(1, r.tester.batches.size, "enable runs a test")
    }

    @Test
    fun concurrencySettingIsClamped() = runTest {
        val r = rig()
        r.manager.setTestConcurrency(9)
        assertEquals(4, r.store.data.settings.testConcurrency)
        r.manager.setTestConcurrency(0)
        assertEquals(1, r.store.data.settings.testConcurrency)
        r.manager.testAll()
        assertEquals(1, r.tester.concurrencies.last())
    }

    // ---- health checks, pause, failover ------------------------------------------------------------

    @Test
    fun healthChecksActivePlusStandbysEveryFiveMinutes() = runTest {
        val r = rig()
        val before = r.runtime.measures
        advance(299_000)
        assertEquals(before, r.runtime.measures, "nothing before the interval")
        advance(1_001)
        assertEquals(before + 3, r.runtime.measures, "active + 2 standbys")
        assertEquals(1, r.tester.batches.size, "health checks never run a test batch")
    }

    @Test
    fun activeFailureSwitchesToHealthyStandbyWithoutTesting() = runTest {
        val r = rig()
        r.runtime.alive = mapOf("mid" to 130L, "slow" to 310L) // "fast" died
        advance(300_001)
        // First failure: confirm after 5 s.
        assertIs<ProxyState.Connected>(r.manager.state.value)
        advance(5_000)
        assertEquals(ProxyState.Connected("mid", 130), r.manager.state.value)
        assertEquals(listOf("mid", "slow"), r.runtime.starts.last())
        assertEquals(1, r.tester.batches.size, "failover used standbys, no new test run")
    }

    @Test
    fun wholeSetDownTriggersRetest() = runTest {
        val r = rig()
        r.runtime.alive = mapOf("other" to 1L)
        r.tester.latencies = emptyMap()
        advance(305_001)
        assertEquals(2, r.tester.batches.size)
        assertEquals(ProxyState.Failed(FailReason.NO_WORKING_CONFIG), r.manager.state.value)
    }

    @Test
    fun healthChecksPauseDuringPlaybackAndResumeAfter() = runTest {
        val r = rig()
        r.manager.setPlaybackActive(true)
        val before = r.runtime.measures
        advance(3 * 300_000)
        assertEquals(before, r.runtime.measures, "no pings while playing")
        r.manager.setPlaybackActive(false)
        runCurrent()
        assertEquals(before + 3, r.runtime.measures, "overdue check runs right after playback")
    }

    @Test
    fun healthChecksPauseInBackgroundEvenOnFailureHints() = runTest {
        val r = rig()
        r.manager.setAppForeground(false)
        val before = r.runtime.measures
        r.manager.onProxiedCallFailed()
        advance(3 * 300_000)
        assertEquals(before, r.runtime.measures)
    }

    @Test
    fun failingRequestTriggersEarlyCheckEvenWhilePlaying() = runTest {
        val r = rig()
        r.manager.setPlaybackActive(true)
        r.runtime.alive = mapOf("mid" to 130L, "slow" to 310L)
        r.manager.onProxiedCallFailed()
        runCurrent()
        advance(5_000)
        assertEquals(ProxyState.Connected("mid", 130), r.manager.state.value)
    }

    // ---- other transitions -------------------------------------------------------------------------

    @Test
    fun disableStopsXrayAndGoesDirect() = runTest {
        val r = rig()
        r.manager.setEnabled(false)
        runCurrent()
        assertEquals(ProxyState.Off, r.manager.state.value)
        assertEquals(RouteDecision.Direct, r.manager.currentRoute())
        assertTrue(r.runtime.running.isEmpty())
    }

    @Test
    fun noConfigsFailsWithoutRetryLoop() = runTest {
        val r = rig(configs = emptyList())
        assertEquals(ProxyState.Failed(FailReason.NO_CONFIGS), r.manager.state.value)
        advance(60 * 60_000)
        assertEquals(0, r.tester.batches.size)
    }

    @Test
    fun manualModeUsesOnlyTheSelectedConfig() = runTest {
        val r = rig()
        r.manager.selectConfig("slow")
        runCurrent()
        assertEquals(listOf("slow"), r.runtime.starts.last())
        assertEquals(ProxyState.Connected("slow", 80), r.manager.state.value)
        r.manager.selectConfig("does-not-exist")
        runCurrent()
        assertEquals(ProxyState.Failed(FailReason.SELECTED_CONFIG_MISSING), r.manager.state.value)
    }

    @Test
    fun addSubscriptionParsesStoresAndConnectsDirectWhenOff() = runTest {
        var usedProxy: Proxy? = null
        val body = "trojan://pw@n1.example.invalid:443#N1\nvless://11111111-2222-3333-4444-555555555555@n2.example.invalid:443?type=ws&security=tls#N2"
        val r = rig(
            configs = emptyList(),
            fetcher = SubscriptionFetcher { _, proxy -> usedProxy = proxy; body },
        )
        assertEquals(ProxyState.Failed(FailReason.NO_CONFIGS), r.manager.state.value)
        val ids = SubscriptionParser.parse(body).configs.map { it.id }
        r.tester.latencies = mapOf(ids[1] to 40L)
        r.runtime.alive = mapOf(ids[1] to 45L)
        val result = r.manager.addSubscription("https://panel.example.invalid/sub/token")
        runCurrent()
        assertEquals(2, result.configs.size)
        assertEquals(Proxy.NO_PROXY, usedProxy)
        assertEquals(1, r.store.data.subscriptions.size)
        assertEquals(ProxyState.Connected("N2", 45), r.manager.state.value)
    }
}
