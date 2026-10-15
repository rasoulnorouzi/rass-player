package nl.rass.player.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What [ProxyManager] needs from the running proxy. Faked in unit tests. */
interface ProxyRuntime {
    /** (Re)starts the proxy: app traffic → [active]; [standby] configs only get probe inbounds. */
    suspend fun start(active: ProxyConfig, standby: List<ProxyConfig>): ProxyEndpoint

    suspend fun stop()

    /** Real delay through the running instance: slot 0 = active config, slot i = standby[i-1]. Throws on failure. */
    suspend fun measure(slot: Int, url: String, timeoutMs: Long): Long
}

/**
 * Owns the one long-lived Xray instance. Xray runs in-process (AAR, gomobile), tied to the app process:
 * no foreground service, no VpnService. Listens only on 127.0.0.1 random free ports.
 */
@Singleton
class XrayController @Inject constructor(
    private val engineFactory: XrayEngineFactory,
    private val ports: PortAllocator,
    private val probe: HttpProbe,
) : ProxyRuntime {

    private var engine: XrayEngine? = null

    @Volatile
    var endpoint: ProxyEndpoint? = null
        private set

    override suspend fun start(active: ProxyConfig, standby: List<ProxyConfig>): ProxyEndpoint =
        withContext(Dispatchers.IO) {
            synchronized(this@XrayController) {
                stopLocked()
                // A freshly picked free port can be taken before Xray binds it; retry with new ports.
                repeat(START_ATTEMPTS) {
                    val ep = pickEndpoint(standby.size)
                    val json = XrayConfigBuilder.toJsonString(XrayConfigBuilder.runtimeConfig(active, standby, ep))
                    val e = engineFactory.create()
                    try {
                        e.start(json)
                        engine = e
                        endpoint = ep
                        return@withContext ep
                    } catch (ex: Exception) {
                        e.stop()
                    }
                }
                // Message deliberately generic: the native error can quote the config.
                throw IllegalStateException("Xray failed to start")
            }
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        synchronized(this@XrayController) { stopLocked() }
    }

    override suspend fun measure(slot: Int, url: String, timeoutMs: Long): Long {
        val ep = endpoint ?: throw IllegalStateException("not running")
        val port = if (slot == 0) ep.httpPort else ep.probePorts.getOrNull(slot - 1)
            ?: throw IllegalArgumentException("no such slot")
        return probe.measure(OkHttpProbe.httpProxy(port), url, timeoutMs)
    }

    private fun stopLocked() {
        engine?.stop()
        engine = null
        endpoint = null
    }

    private fun pickEndpoint(standbyCount: Int): ProxyEndpoint {
        val used = HashSet<Int>()
        fun next(): Int {
            var p = ports.freePort()
            while (!used.add(p)) p = ports.freePort()
            return p
        }
        return ProxyEndpoint(socksPort = next(), httpPort = next(), probePorts = List(standbyCount) { next() })
    }

    private companion object {
        const val START_ATTEMPTS = 2
    }
}
