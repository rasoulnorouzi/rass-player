package nl.rass.player.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One usable proxy server, normalised to an Xray outbound.
 *
 * SECRET: [outbound] and [helpers] contain server addresses, UUIDs, passwords and keys. Never log them,
 * never put them in exceptions or analytics. Only [id], [name] and [protocol] may be shown in the UI.
 *
 * @property id stable hash of the outbound content, so test results survive a subscription refresh.
 * @property outbound the main Xray outbound object, without a `tag` (tags are assigned at build time).
 * @property helpers extra outbounds the main one depends on (via `sockopt.dialerProxy` or
 *   `proxySettings.tag`), e.g. a BPB "fragment" freedom outbound. They keep their original tags, which are
 *   what [outbound] refers to; [XrayConfigBuilder] renames both consistently.
 */
@Serializable
data class ProxyConfig(
    val id: String,
    val name: String,
    val protocol: String,
    val outbound: JsonObject,
    val helpers: List<JsonObject> = emptyList(),
    val subscriptionId: String? = null,
) {
    /** Stable identity of the server itself (protocol+host+port+id/password), see [ConfigFingerprint]. */
    val fingerprint: String get() = ConfigFingerprint.of(this)

    /** A single link the user added by hand (not part of any subscription). */
    val isManual: Boolean get() = subscriptionId == null
}

/** A subscription link the user pasted. SECRET: [url] usually embeds an access token. */
@Serializable
data class Subscription(
    val id: String,
    val url: String,
    val name: String = "",
    val lastUpdatedAtMs: Long = 0,
    val lastError: String? = null,
)

/** Result of one real-delay test. [latencyMs] is null when the config did not work. */
@Serializable
data class LatencyResult(
    val configId: String,
    val latencyMs: Long?,
    val error: String? = null,
    val testedAtMs: Long = 0,
) {
    val ok: Boolean get() = latencyMs != null
}

/** User settings for the private connection (R10). */
@Serializable
data class ProxySettings(
    /** R10.1: the Settings switch. */
    val enabled: Boolean = false,
    /** R10.5: on by default. When on and nothing works, the app does not go direct. */
    val killSwitch: Boolean = true,
    /** R10.3: auto = pick the fastest and fail over; false = use [selectedConfigId] only. */
    val autoSelect: Boolean = true,
    val selectedConfigId: String? = null,
    /** Parallel latency probes inside the single test instance, 1..4 (default 2 for the quad A55 TV). */
    val testConcurrency: Int = 2,
)

/** Everything [SecureConfigStore] persists, encrypted as one blob. */
@Serializable
data class ProxyStoreData(
    val settings: ProxySettings = ProxySettings(),
    val subscriptions: List<Subscription> = emptyList(),
    val configs: List<ProxyConfig> = emptyList(),
    val results: List<LatencyResult> = emptyList(),
    /**
     * Servers the user removed from a subscription, per subscription id, as [ConfigFingerprint] hashes (never raw
     * secrets). A refresh drops them again until the user restores them.
     */
    val removed: Map<String, List<String>> = emptyMap(),
    /** [ConfigFingerprint]s of servers the user disabled: kept in the list, skipped by auto-select and tests. */
    val disabled: List<String> = emptyList(),
) {
    /** Configs that take part in auto-select, tests and connecting. */
    val activeConfigs: List<ProxyConfig> get() = if (disabled.isEmpty()) configs else {
        val off = disabled.toSet()
        configs.filter { it.fingerprint !in off }
    }

    fun isDisabled(c: ProxyConfig): Boolean = disabled.isNotEmpty() && c.fingerprint in disabled
}

/**
 * Where the running Xray instance listens. Loopback only. [httpPort]/[socksPort] carry app traffic through the
 * active config; [probePorts] reach the standby configs (health checks only).
 */
data class ProxyEndpoint(val socksPort: Int, val httpPort: Int, val probePorts: List<Int> = emptyList()) {
    val host: String get() = LOOPBACK

    companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

/** Why the private connection is not working. The UI maps these to Persian strings. */
enum class FailReason {
    /** No subscription or no parsed configs yet. */
    NO_CONFIGS,
    /** Every config failed the latency test. */
    NO_WORKING_CONFIG,
    /** Manual mode, but the selected config no longer exists. */
    SELECTED_CONFIG_MISSING,
    /** Xray refused to start (bad config or port problem). */
    ENGINE_ERROR,
}

/** Short, non-secret progress steps while connecting. */
enum class ConnectStep { TESTING, STARTING, RETESTING }

/** Public connection state shown in Settings and used by the kill switch. */
sealed interface ProxyState {
    /** The private connection switch is off: traffic goes direct. */
    data object Off : ProxyState

    /** Testing configs or starting Xray. */
    data class Connecting(val step: ConnectStep = ConnectStep.TESTING) : ProxyState

    /** Xray runs and app traffic goes through it. In auto mode [configName] is the fastest tested config. */
    data class Connected(val configName: String, val latencyMs: Long?) : ProxyState

    /** Nothing works. With the kill switch on, app traffic is blocked until it recovers. */
    data class Failed(val reason: FailReason) : ProxyState
}

/**
 * Thrown by the shared OkHttpClient when the kill switch blocks a request (R10.5).
 * It is an IOException so OkHttp, Coil and Media3 treat it as a normal network failure.
 */
class PrivateConnectionUnavailableException(
    val reason: FailReason?,
    message: String = "Private connection is on but not connected; the kill switch blocked this request",
) : java.io.IOException(message)
