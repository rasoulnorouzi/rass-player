package nl.rass.player.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fake engine + fake probe: one instance per batch, caps, sorting, timeouts, bad-config bisection. */
class LatencyTesterTest {

    private fun cfg(id: String) = ProxyConfig(
        id = id, name = id, protocol = "trojan",
        outbound = Json.parseToJsonElement("""{"protocol":"trojan","settings":{"servers":[{"address":"$id.example.invalid","port":443,"password":"p"}]}}""").jsonObject,
    )

    /** Records every instance; rejects any config containing "bad". Tracks max simultaneous instances. */
    private class Engines : XrayEngineFactory {
        val starts = mutableListOf<Int>() // outbound count per successful start
        var running = 0
        var maxRunning = 0
        var rejected = 0
        override fun create() = object : XrayEngine {
            private var on = false
            override fun start(configJson: String) {
                if ("bad" in configJson) {
                    synchronized(this@Engines) { rejected++ }
                    throw IllegalArgumentException("rejected")
                }
                val outbounds = Json.parseToJsonElement(configJson).jsonObject["outbounds"]!!.jsonArray.size
                synchronized(this@Engines) {
                    starts += outbounds
                    running++
                    maxRunning = maxOf(maxRunning, running)
                }
                on = true
            }
            override fun stop() {
                if (on) synchronized(this@Engines) { running-- }
                on = false
            }
            override val isRunning get() = on
        }
    }

    /** Port → behaviour comes from the config the port was assigned to (ports are handed out in order). */
    private class Probe(private val portToId: () -> Map<Int, String>) : HttpProbe {
        val inFlight = AtomicInteger()
        var maxInFlight = 0
        override suspend fun measure(proxy: java.net.Proxy, url: String, timeoutMs: Long): Long {
            val id = portToId()[(proxy.address() as InetSocketAddress).port] ?: error("unknown port")
            val now = inFlight.incrementAndGet()
            synchronized(this) { maxInFlight = maxOf(maxInFlight, now) }
            try {
                delay(10)
                return when {
                    id.startsWith("fast") -> 40L
                    id.startsWith("slow") -> 300L
                    id.startsWith("dead") -> throw IOException("refused")
                    else -> withTimeout(timeoutMs) { delay(timeoutMs * 10); 1L }
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun rig(maxPerInstance: Int = 20): Triple<LatencyTester, Engines, Probe> {
        val engines = Engines()
        val next = AtomicInteger(50000)
        val assigned = LinkedHashMap<Int, String>()
        val probe = Probe { synchronized(assigned) { assigned.toMap() } }
        // Learn port → config id from the batch config itself (inbound t-i ↔ outbound px-i).
        val recordingEngines = object : XrayEngineFactory {
            override fun create(): XrayEngine {
                val inner = engines.create()
                return object : XrayEngine by inner {
                    override fun start(configJson: String) {
                        inner.start(configJson)
                        val root = Json.parseToJsonElement(configJson).jsonObject
                        val ports = root["inbounds"]!!.jsonArray.map { it.jsonObject["port"]!!.toString().toInt() }
                        val ids = root["outbounds"]!!.jsonArray.map { it.jsonObject }
                            .filter { it["tag"]!!.toString().contains("px-") }
                            .map { o -> o.toString().substringAfter("\"address\":\"").substringBefore(".example") }
                        synchronized(assigned) { ports.zip(ids).forEach { (p, i) -> assigned[p] = i } }
                    }
                }
            }
        }
        val tester = LatencyTester(
            recordingEngines, { next.getAndIncrement() }, probe,
            LatencyTestSettings(timeoutMs = 5_000, maxPerInstance = maxPerInstance),
        ) { 7L }
        return Triple(tester, engines, probe)
    }

    @Test
    fun oneInstancePerBatchSortedResultsAndConcurrencyCap() = runTest {
        val (tester, engines, probe) = rig()
        val configs = listOf(cfg("slow1"), cfg("fast1"), cfg("dead1"), cfg("hang1"), cfg("fast2"))
        val results = tester.test(configs, concurrency = 2)

        assertEquals(listOf(5), engines.starts, "a single instance with all 5 outbounds")
        assertEquals(1, engines.maxRunning)
        assertEquals(0, engines.running, "instance stopped afterwards")
        assertTrue(probe.maxInFlight <= 2, "max in flight ${probe.maxInFlight}")
        assertEquals(listOf("fast1", "fast2", "slow1"), results.filter { it.ok }.map { it.configId })
        val failed = results.filter { !it.ok }.associateBy { it.configId }
        assertEquals("timeout", failed.getValue("hang1").error)
        assertEquals("IOException", failed.getValue("dead1").error)
        assertTrue(results.none { (it.error ?: "").contains("example.invalid") })
        assertTrue(results.all { it.testedAtMs == 7L })
    }

    @Test
    fun concurrencyIsClampedToOneToFour() = runTest {
        val (tester, _, probe) = rig()
        tester.test((1..10).map { cfg("fast$it") }, concurrency = 99)
        assertTrue(probe.maxInFlight <= 4)
        val (tester1, _, probe1) = rig()
        tester1.test((1..5).map { cfg("fast$it") }, concurrency = 0)
        assertEquals(1, probe1.maxInFlight)
    }

    @Test
    fun largeInputIsSplitIntoSequentialInstances() = runTest {
        val (tester, engines, _) = rig(maxPerInstance = 20)
        val results = tester.test((1..45).map { cfg("fast$it") }, concurrency = 2)
        assertEquals(45, results.size)
        assertEquals(listOf(20, 20, 5), engines.starts)
        assertEquals(1, engines.maxRunning, "never two test instances at once")
    }

    @Test
    fun rejectedBatchIsBisectedToIsolateTheBadConfig() = runTest {
        val (tester, engines, _) = rig()
        val configs = listOf(cfg("fast1"), cfg("slow1"), cfg("bad1"), cfg("fast2"))
        val results = tester.test(configs, concurrency = 2)
        assertEquals("config rejected", results.single { it.configId == "bad1" }.error)
        assertEquals(listOf("fast1", "fast2", "slow1"), results.filter { it.ok }.map { it.configId })
        assertEquals(1, engines.maxRunning)
        // [4] rejected → [2] ok + [2] rejected → [1] rejected + [1] ok
        assertEquals(3, engines.rejected)
    }
}

class TestPlannerTest {
    private fun cfg(id: String) = ProxyConfig(id, id, "trojan", kotlinx.serialization.json.JsonObject(emptyMap()))

    @Test
    fun ordersGoodByLatencyThenUntestedThenOldestFailure() {
        val configs = listOf("u1", "f-new", "g-slow", "u2", "f-old", "g-fast").map(::cfg)
        val last = listOf(
            LatencyResult("g-slow", 300, null, 10),
            LatencyResult("g-fast", 50, null, 10),
            LatencyResult("f-new", null, "timeout", 20),
            LatencyResult("f-old", null, "timeout", 5),
            LatencyResult("gone", 10, null, 1), // result for a config that no longer exists
        )
        assertEquals(
            listOf("g-fast", "g-slow", "u1", "u2", "f-old", "f-new"),
            TestPlanner.order(configs, last).map { it.id },
        )
    }

    @Test
    fun batchesAreCappedAtTwenty() {
        val configs = (1..47).map { cfg("c$it") }
        assertEquals(listOf(20, 20, 7), TestPlanner.batches(configs, emptyList()).map { it.size })
        assertEquals(TestPlanner.MAX_PER_RUN, 20)
    }

    @Test
    fun concurrencyClamp() {
        assertEquals(1, TestPlanner.clampConcurrency(0))
        assertEquals(4, TestPlanner.clampConcurrency(10))
        assertEquals(2, ProxySettings().testConcurrency)
    }
}
