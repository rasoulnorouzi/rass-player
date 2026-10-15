package nl.rass.player.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Tests configs. Faked in [ProxyManager] tests. */
interface ConfigTester {
    /**
     * Tests [configs] (the caller keeps batches small, see [TestPlanner]) with [concurrency] parallel probes.
     * Result is sorted: working ones by latency, then failures.
     */
    suspend fun test(configs: List<ProxyConfig>, concurrency: Int): List<LatencyResult>
}

data class LatencyTestSettings(
    val url: String = XrayConfigBuilder.DEFAULT_PROBE_URL,
    val timeoutMs: Long = 5_000,
    /** Upper bound of outbounds in one temporary instance (memory guard). */
    val maxPerInstance: Int = TestPlanner.MAX_PER_RUN,
)

/**
 * Real-delay tests with ONE temporary Xray instance per batch: every config gets its own loopback HTTP inbound
 * routed to its outbound, and we GET [LatencyTestSettings.url] through those ports, at most `concurrency` at a
 * time, each with a hard timeout. Only one test instance exists at a time (sequential batches).
 *
 * If Xray rejects the batch config (one malformed outbound breaks the whole config), the batch is split in
 * halves and retried, so a bad config costs a few extra short starts, not a per-config instance for everyone.
 *
 * Why not the AAR's `measureOutboundDelay`: it can't be cancelled and waits up to 2×12 s on dead servers.
 */
@Singleton
class LatencyTester(
    private val engineFactory: XrayEngineFactory,
    private val ports: PortAllocator,
    private val probe: HttpProbe,
    private val settings: LatencyTestSettings,
    private val clock: () -> Long,
) : ConfigTester {

    @Inject
    constructor(engineFactory: XrayEngineFactory, ports: PortAllocator, probe: HttpProbe) :
        this(engineFactory, ports, probe, LatencyTestSettings(), System::currentTimeMillis)

    override suspend fun test(configs: List<ProxyConfig>, concurrency: Int): List<LatencyResult> {
        val parallel = TestPlanner.clampConcurrency(concurrency)
        val out = ArrayList<LatencyResult>(configs.size)
        for (chunk in configs.chunked(settings.maxPerInstance.coerceAtLeast(1))) out += runBatch(chunk, parallel)
        return sortResults(out)
    }

    private suspend fun runBatch(batch: List<ProxyConfig>, parallel: Int): List<LatencyResult> {
        if (batch.isEmpty()) return emptyList()
        val engine = engineFactory.create()
        try {
            val portList = uniquePorts(batch.size)
            val started = withContext(Dispatchers.IO) {
                try {
                    engine.start(XrayConfigBuilder.toJsonString(XrayConfigBuilder.batchTestConfig(batch, portList)))
                    true
                } catch (e: Exception) {
                    false // never surface the native message: it can quote the config
                }
            }
            if (!started) {
                withContext(NonCancellable + Dispatchers.IO) { engine.stop() }
                if (batch.size == 1) return listOf(LatencyResult(batch[0].id, null, "config rejected", clock()))
                val half = batch.size / 2
                return runBatch(batch.subList(0, half), parallel) + runBatch(batch.subList(half, batch.size), parallel)
            }
            val gate = Semaphore(parallel)
            return coroutineScope {
                batch.mapIndexed { i, cfg -> async { gate.withPermit { probeOne(cfg, portList[i]) } } }.awaitAll()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { engine.stop() }
        }
    }

    private suspend fun probeOne(config: ProxyConfig, port: Int): LatencyResult = try {
        val ms = probe.measure(OkHttpProbe.httpProxy(port), settings.url, settings.timeoutMs)
        LatencyResult(config.id, ms, null, clock())
    } catch (e: TimeoutCancellationException) {
        LatencyResult(config.id, null, "timeout", clock())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Only the exception type: messages can contain addresses.
        LatencyResult(config.id, null, e.javaClass.simpleName.ifEmpty { "error" }, clock())
    }

    private fun uniquePorts(n: Int): List<Int> {
        val set = LinkedHashSet<Int>()
        while (set.size < n) set += ports.freePort()
        return set.toList()
    }

    companion object {
        fun sortResults(results: List<LatencyResult>): List<LatencyResult> =
            results.sortedWith(compareBy<LatencyResult> { it.latencyMs == null }.thenBy { it.latencyMs ?: Long.MAX_VALUE })
    }
}

/** Which configs to test, in which order and how many per run. Pure; unit-tested. */
object TestPlanner {
    const val MAX_PER_RUN = 20
    const val MIN_CONCURRENCY = 1
    const val MAX_CONCURRENCY = 4

    fun clampConcurrency(n: Int): Int = n.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)

    /**
     * Priority: last-known-good by latency, then never tested (subscription order), then previously failed
     * (oldest failure first, so each one gets its turn).
     */
    fun order(configs: List<ProxyConfig>, last: List<LatencyResult>): List<ProxyConfig> {
        val byId = last.associateBy { it.configId }
        val good = configs.filter { byId[it.id]?.ok == true }.sortedBy { byId.getValue(it.id).latencyMs }
        val untested = configs.filter { it.id !in byId }
        val failed = configs.filter { byId[it.id]?.ok == false }.sortedBy { byId.getValue(it.id).testedAtMs }
        return good + untested + failed
    }

    /** Ordered batches of at most [maxPerRun]; automatic runs take one batch at a time, "test all" takes all. */
    fun batches(configs: List<ProxyConfig>, last: List<LatencyResult>, maxPerRun: Int = MAX_PER_RUN): List<List<ProxyConfig>> =
        order(configs, last).chunked(maxPerRun.coerceAtLeast(1))
}
