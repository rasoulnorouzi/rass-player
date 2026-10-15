package nl.rass.player.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** App-lifetime scope for the private connection (SupervisorJob + Default dispatcher). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProxyScope

/** Timings and caps, sized for the TV (quad Cortex-A55, ~2 GB RAM). */
data class ProxyTiming(
    val probeUrl: String = XrayConfigBuilder.DEFAULT_PROBE_URL,
    val probeTimeoutMs: Long = 5_000,
    /** Background health check of the active config (+ standby) through the running instance. */
    val healthIntervalMs: Long = 5 * 60_000L,
    /** When the active config fails a check, re-check once after this before switching. */
    val confirmDelayMs: Long = 5_000,
    /** Minimum gap between failure-triggered checks (from failing app requests). */
    val urgentDebounceMs: Long = 30_000,
    /** Retry delays while nothing works, then the last value repeats. */
    val retryDelaysMs: List<Long> = listOf(60_000, 120_000, 300_000, 600_000),
    /** Next-best working configs kept in the running instance as failover candidates. */
    val standbyCount: Int = 2,
    /** Automatic runs test at most this many configs per batch; "test all" walks every batch. */
    val maxConfigsPerRun: Int = TestPlanner.MAX_PER_RUN,
    /** App start: reuse last results younger than this instead of testing first. */
    val reuseResultsMaxAgeMs: Long = 24 * 60 * 60 * 1000L,
)

/**
 * The private connection (R10): state machine, auto-select, health checks, failover and kill switch.
 *
 * When tests run (and nothing else): on enable / config change, on the user's "test", and when the active
 * config fails and no standby works. Tests use one temporary Xray instance per batch of ≤20 configs, best
 * candidates first; the next batch is only tested if the previous one had no working config.
 *
 * While connected, the running instance holds the active config + up to [ProxyTiming.standbyCount] standby
 * configs. Every [ProxyTiming.healthIntervalMs] the active one (+ standbys) is pinged through that instance.
 * Health checks and retries are PAUSED while video plays ([setPlaybackActive]) and while the app is in the
 * background ([setAppForeground]). A failing app request triggers an early (debounced) check even during
 * playback, because playback is then broken anyway. Active fails twice (5 s apart) → switch to a healthy
 * standby (restart, no test run) → else re-test.
 *
 * The shared OkHttpClient reads [currentRoute]/[awaitRoute] (via [ProxyHttp]) on every request.
 */
@Singleton
class ProxyManager @Inject constructor(
    private val store: ProxyStore,
    private val runtime: ProxyRuntime,
    private val tester: ConfigTester,
    private val fetcher: SubscriptionFetcher,
    @ProxyScope private val scope: CoroutineScope,
    private val timing: ProxyTiming,
) : RouteSource {

    private val _state = MutableStateFlow<ProxyState>(ProxyState.Off)
    val state: StateFlow<ProxyState> = _state.asStateFlow()

    private val _data = MutableStateFlow(ProxyStoreData())
    /** Settings, subscriptions, configs and last results (SECRET content: UI shows names/latency only). */
    val data: StateFlow<ProxyStoreData> = _data.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private data class Activity(val foreground: Boolean = true, val playback: Boolean = false)
    private val activity = MutableStateFlow(Activity())
    private val urgentPending = MutableStateFlow(false)
    @Volatile private var lastUrgentNanos = 0L

    @Volatile private var ready = false
    @Volatile private var endpoint: ProxyEndpoint? = null
    private val routeLock = Object()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val control = Mutex()
    private var loop: Job? = null
    private var started = false

    /** Loads the store and connects if the switch is on. Call once at app start (RassApp). Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            _data.value = store.load()
            ready = true
            routeChanged()
            // App start: reuse recent results first (no test run if the last good config still works).
            control.withLock { restartLocked(reuseRecent = true) }
        }
    }

    // ---- signals from the rest of the app ------------------------------------------------------------

    /** The player calls this: health checks and retries pause while video plays. */
    fun setPlaybackActive(active: Boolean) {
        activity.value = activity.value.copy(playback = active)
    }

    /** App visibility (wired in RassApp): everything pauses in the background. */
    fun setAppForeground(foreground: Boolean) {
        activity.value = activity.value.copy(foreground = foreground)
    }

    override fun onProxiedCallFailed() {
        if (_state.value !is ProxyState.Connected) return
        val now = System.nanoTime()
        if (lastUrgentNanos != 0L && now - lastUrgentNanos < timing.urgentDebounceMs * 1_000_000) return
        lastUrgentNanos = now
        urgentPending.value = true
    }

    // ---- user actions (Settings) ---------------------------------------------------------------------

    suspend fun setEnabled(enabled: Boolean) = updateSettings { it.copy(enabled = enabled) }
    suspend fun setKillSwitch(on: Boolean) = updateSettings(restart = false) { it.copy(killSwitch = on) }
    suspend fun setAutoSelect(auto: Boolean) = updateSettings { it.copy(autoSelect = auto) }
    suspend fun selectConfig(id: String) = updateSettings { it.copy(autoSelect = false, selectedConfigId = id) }
    suspend fun setTestConcurrency(n: Int) =
        updateSettings(restart = false) { it.copy(testConcurrency = TestPlanner.clampConcurrency(n)) }

    /** Adds a subscription and fetches it. Returns the parse result (counts + non-secret problems). */
    suspend fun addSubscription(url: String, name: String = ""): ParseResult {
        val sub = Subscription(id = UUID.randomUUID().toString(), url = url.trim(), name = name)
        _data.value = store.update { it.copy(subscriptions = it.subscriptions + sub) }
        return refreshSubscription(sub.id)
    }

    suspend fun removeSubscription(id: String) {
        _data.value = store.update { d ->
            val configs = d.configs.filter { it.subscriptionId != id }
            d.copy(
                subscriptions = d.subscriptions.filter { it.id != id },
                configs = configs,
                results = d.results.filter { r -> configs.any { it.id == r.configId } },
            )
        }
        control.withLock { restartLocked() }
    }

    /**
     * Re-downloads every subscription (user-initiated). Goes through the proxy when connected; otherwise
     * direct, because a refresh can be the only way to recover when every config is dead. Automatic
     * background traffic never takes this direct path.
     */
    suspend fun refreshSubscriptions(): List<ParseResult> =
        _data.value.subscriptions.map { refreshSubscription(it.id) }

    private suspend fun refreshSubscription(id: String): ParseResult {
        val sub = _data.value.subscriptions.firstOrNull { it.id == id }
            ?: return ParseResult(emptyList(), 0, listOf("unknown subscription"))
        val route = currentRoute()
        val proxy = if (route is RouteDecision.Proxied) route.proxy else Proxy.NO_PROXY
        val result = try {
            SubscriptionParser.parse(fetcher.fetch(sub.url, proxy), sub.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = if (e is java.io.IOException) (e.message ?: "network error") else "fetch failed"
            _data.value = store.update { d ->
                d.copy(subscriptions = d.subscriptions.map { if (it.id == id) it.copy(lastError = reason) else it })
            }
            return ParseResult(emptyList(), 0, listOf(reason))
        }
        _data.value = store.update { d ->
            val others = d.configs.filter { it.subscriptionId != id }
            val merged = (others + result.configs).distinctBy { it.id }
            d.copy(
                subscriptions = d.subscriptions.map {
                    if (it.id == id) it.copy(lastUpdatedAtMs = System.currentTimeMillis(), lastError = null) else it
                },
                configs = merged,
                results = d.results.filter { r -> merged.any { it.id == r.configId } },
            )
        }
        control.withLock { restartLocked() }
        return result
    }

    /** The user's "test all": every config, in batches of ≤20, one temporary instance at a time. */
    suspend fun testAll(): List<LatencyResult> {
        val d = _data.value
        val all = TestPlanner.batches(d.configs, d.results, timing.maxConfigsPerRun).flatMap { runTests(it) }
        return LatencyTester.sortResults(all)
    }

    // ---- connection loop -----------------------------------------------------------------------------

    private suspend fun updateSettings(restart: Boolean = true, change: (ProxySettings) -> ProxySettings) {
        _data.value = store.update { it.copy(settings = change(it.settings)) }
        routeChanged()
        if (restart) control.withLock { restartLocked() }
    }

    /** Cancels the running cycle and starts a new one if enabled. Caller holds [control]. */
    private suspend fun restartLocked(reuseRecent: Boolean = false) {
        loop?.let {
            it.cancel()
            it.join()
        }
        loop = null
        if (!_data.value.settings.enabled) {
            withContext(NonCancellable) { runtime.stop() }
            setDisconnected(ProxyState.Off)
            return
        }
        loop = scope.launch { connectionLoop(reuseRecent) }
    }

    private suspend fun connectionLoop(reuseRecent: Boolean) {
        var attempt = 0
        var step = ConnectStep.TESTING
        var fastPath = reuseRecent
        try {
            while (true) {
                setDisconnected(ProxyState.Connecting(step))
                when (val outcome = connectOnce(fastPath)) {
                    is Outcome.Up -> {
                        attempt = 0
                        monitor(outcome) // returns when the active config and all standbys failed
                    }
                    is Outcome.Down -> {
                        runtime.stop()
                        setDisconnected(ProxyState.Failed(outcome.reason))
                        if (outcome.reason == FailReason.NO_CONFIGS || outcome.reason == FailReason.SELECTED_CONFIG_MISSING) {
                            return // nothing to retry until the user changes something (which restarts the loop)
                        }
                        delay(timing.retryDelaysMs[attempt.coerceAtMost(timing.retryDelaysMs.lastIndex)])
                        attempt++
                        activity.first { it.foreground && !it.playback }
                    }
                }
                step = ConnectStep.RETESTING
                fastPath = false
            }
        } finally {
            withContext(NonCancellable) {
                if (!_data.value.settings.enabled) runtime.stop()
            }
        }
    }

    private sealed interface Outcome {
        data class Up(val active: ProxyConfig, val standby: List<ProxyConfig>) : Outcome
        data class Down(val reason: FailReason) : Outcome
    }

    private suspend fun connectOnce(fastPath: Boolean): Outcome {
        val d = _data.value
        if (d.configs.isEmpty()) return Outcome.Down(FailReason.NO_CONFIGS)

        val candidates: List<ProxyConfig> = if (d.settings.autoSelect) {
            val recent = if (fastPath) recentWorking(d) else emptyList()
            recent.ifEmpty { findWorking() }
        } else {
            val selected = d.configs.firstOrNull { it.id == d.settings.selectedConfigId }
                ?: return Outcome.Down(FailReason.SELECTED_CONFIG_MISSING)
            if (runTests(listOf(selected)).firstOrNull()?.ok == true) listOf(selected) else emptyList()
        }
        if (candidates.isEmpty()) return Outcome.Down(FailReason.NO_WORKING_CONFIG)

        val up = startWith(candidates.first(), candidates.drop(1).take(timing.standbyCount))
        if (up is Outcome.Down && up.reason == FailReason.NO_WORKING_CONFIG && fastPath && d.settings.autoSelect) {
            return connectOnce(fastPath = false) // recent results were stale: test now
        }
        return up
    }

    /** Starts the main instance and verifies the active config end to end before routing traffic. */
    private suspend fun startWith(active: ProxyConfig, standby: List<ProxyConfig>): Outcome {
        setDisconnected(ProxyState.Connecting(ConnectStep.STARTING))
        val ep = try {
            runtime.start(active, standby)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Down(FailReason.ENGINE_ERROR)
        }
        val latency = measureOrNull(0)
        if (latency == null) {
            runtime.stop()
            return Outcome.Down(FailReason.NO_WORKING_CONFIG)
        }
        endpoint = ep
        _state.value = ProxyState.Connected(active.name, latency)
        routeChanged()
        return Outcome.Up(active, standby)
    }

    /** Health checks + failover to standbys. Returns when nothing in the running set works any more. */
    private suspend fun monitor(initial: Outcome.Up) {
        var up = initial
        while (true) {
            val urgent = awaitNextCheck()
            var ms = measureOrNull(0)
            if (ms == null) {
                delay(timing.confirmDelayMs)
                ms = measureOrNull(0)
            }
            if (ms != null) {
                _state.value = ProxyState.Connected(up.active.name, ms)
                val checked = mutableListOf(LatencyResult(up.active.id, ms, null, System.currentTimeMillis()))
                if (!urgent) {
                    up.standby.forEachIndexed { i, cfg ->
                        checked += LatencyResult(cfg.id, measureOrNull(i + 1), null, System.currentTimeMillis())
                    }
                }
                recordResults(checked) // keeps the startup fast path and the Settings list current
                continue
            }
            // Active is down. Promote the fastest healthy standby without a test run.
            val healthy = up.standby.mapIndexedNotNull { i, cfg -> measureOrNull(i + 1)?.let { cfg to it } }
                .sortedBy { it.second }.map { it.first }
            runtime.stop()
            setDisconnected(ProxyState.Connecting(ConnectStep.STARTING))
            if (healthy.isEmpty()) return
            val next = startWith(healthy.first(), healthy.drop(1))
            if (next !is Outcome.Up) return
            up = next
        }
    }

    /**
     * Waits for the next health check: the interval, or earlier on a failure hint. Never runs in the
     * background; during playback only failure-triggered checks run. Returns true if failure-triggered.
     */
    private suspend fun awaitNextCheck(): Boolean {
        withTimeoutOrNull(timing.healthIntervalMs) { urgentPending.first { it } }
        combine(activity, urgentPending) { a, u -> a.foreground && (!a.playback || u) }.first { it }
        val urgent = urgentPending.value
        urgentPending.value = false
        return urgent
    }

    private suspend fun measureOrNull(slot: Int): Long? = try {
        runtime.measure(slot, timing.probeUrl, timing.probeTimeoutMs)
    } catch (e: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Batches of ≤20 in priority order; stops at the first batch that has a working config. */
    private suspend fun findWorking(): List<ProxyConfig> {
        val d = _data.value
        for (batch in TestPlanner.batches(d.configs, d.results, timing.maxConfigsPerRun)) {
            val working = workingFrom(runTests(batch), d.configs)
            if (working.isNotEmpty()) return working
        }
        return emptyList()
    }

    private suspend fun runTests(configs: List<ProxyConfig>): List<LatencyResult> {
        if (configs.isEmpty()) return emptyList()
        _testing.value = true
        try {
            val results = tester.test(configs, _data.value.settings.testConcurrency)
            recordResults(results)
            return results
        } finally {
            _testing.value = false
        }
    }

    private suspend fun recordResults(results: List<LatencyResult>) {
        _data.value = store.update { d ->
            val tested = results.associateBy { it.configId }
            val kept = d.results.filter { it.configId !in tested }
            d.copy(results = LatencyTester.sortResults(kept + results))
        }
    }

    private fun recentWorking(d: ProxyStoreData): List<ProxyConfig> {
        val now = System.currentTimeMillis()
        val fresh = d.results.filter { it.ok && now - it.testedAtMs in 0..timing.reuseResultsMaxAgeMs }
        return workingFrom(fresh, d.configs)
    }

    private fun workingFrom(results: List<LatencyResult>, configs: List<ProxyConfig>): List<ProxyConfig> {
        val byId = configs.associateBy { it.id }
        return LatencyTester.sortResults(results).filter { it.ok }.mapNotNull { byId[it.configId] }
    }

    private fun setDisconnected(state: ProxyState) {
        endpoint = null
        _state.value = state
        routeChanged()
    }

    // ---- RouteSource (called from OkHttp threads) ----------------------------------------------------

    override fun currentRoute(): RouteDecision =
        RoutePolicy.decide(ready, _data.value.settings, _state.value, endpoint)

    override fun awaitRoute(maxWaitMs: Long): RouteDecision {
        val deadline = System.nanoTime() + maxWaitMs * 1_000_000
        synchronized(routeLock) {
            while (true) {
                val d = currentRoute()
                if (d != RouteDecision.Pending) return d
                val leftMs = (deadline - System.nanoTime()) / 1_000_000
                if (leftMs <= 0) return d
                routeLock.wait(leftMs)
            }
        }
    }

    override fun addRouteChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun routeChanged() {
        synchronized(routeLock) { routeLock.notifyAll() }
        listeners.forEach { runCatching { it() } }
    }
}
