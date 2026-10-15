package nl.rass.player.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Builds complete Xray JSON configs from [ProxyConfig]s. Pure function of its inputs (golden-tested).
 *
 * Runtime config (the one long-lived instance):
 *  - `socks-in` + `http-in` on 127.0.0.1 (no auth, sniffing routeOnly) → `px-0`, the ACTIVE config;
 *  - standby configs `px-1..` are reachable only through their own loopback probe inbounds `probe-1..`, so the
 *    app can health-check them through the running instance without touching real traffic;
 *  - no observatory: it can't be paused while video plays, so [ProxyManager] runs (pausable) probes instead;
 *  - Xray's own DNS is tagged and routed to `px-0`, so no lookups leak; no direct outbound exists;
 *  - logs off, so nothing about the servers reaches logcat (gomobile pipes stdout there).
 *
 * Test config (one temporary instance for a whole batch): inbound `t-i` → outbound `px-i`, one per config.
 */
object XrayConfigBuilder {

    const val SOCKS_TAG = "socks-in"
    const val HTTP_TAG = "http-in"
    const val DNS_TAG = "dns-internal"
    const val PROBE_PREFIX = "probe-"
    const val TEST_PREFIX = "t-"
    const val PROXY_PREFIX = "px-"
    const val HELPER_PREFIX = "hp-"
    const val DEFAULT_PROBE_URL = "https://cp.cloudflare.com/generate_204"

    private val pretty = Json { prettyPrint = true }

    data class Options(val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"))

    /** [endpoint].probePorts[i] serves standby[i] (outbound `px-(i+1)`). */
    fun runtimeConfig(
        active: ProxyConfig,
        standby: List<ProxyConfig>,
        endpoint: ProxyEndpoint,
        options: Options = Options(),
    ): JsonObject {
        require(endpoint.probePorts.size == standby.size) { "one probe port per standby config" }
        return buildJsonObject {
            put("log", logOff())
            put("dns", buildJsonObject {
                put("servers", buildJsonArray { options.dnsServers.forEach { add(JsonPrimitive(it)) } })
                put("queryStrategy", "UseIPv4")
                put("tag", DNS_TAG)
            })
            put("inbounds", buildJsonArray {
                add(inbound(SOCKS_TAG, "socks", endpoint.socksPort, sniff = true, settings = buildJsonObject {
                    put("auth", "noauth")
                    put("udp", false)
                }))
                add(inbound(HTTP_TAG, "http", endpoint.httpPort, sniff = true))
                endpoint.probePorts.forEachIndexed { i, port -> add(inbound("$PROBE_PREFIX${i + 1}", "http", port)) }
            })
            put("outbounds", outbounds(listOf(active) + standby))
            put("routing", buildJsonObject {
                put("domainStrategy", "AsIs")
                put("rules", buildJsonArray {
                    add(rule(listOf(SOCKS_TAG, HTTP_TAG, DNS_TAG), "${PROXY_PREFIX}0"))
                    standby.indices.forEach { i -> add(rule(listOf("$PROBE_PREFIX${i + 1}"), "$PROXY_PREFIX${i + 1}")) }
                })
            })
        }
    }

    /** One temporary instance testing many configs: loopback HTTP inbound `t-i` (on [ports][i]) → `px-i`. */
    fun batchTestConfig(configs: List<ProxyConfig>, ports: List<Int>): JsonObject {
        require(configs.isNotEmpty() && configs.size == ports.size) { "one port per config" }
        return buildJsonObject {
            put("log", logOff())
            put("inbounds", buildJsonArray {
                ports.forEachIndexed { i, port -> add(inbound("$TEST_PREFIX$i", "http", port)) }
            })
            put("outbounds", outbounds(configs))
            put("routing", buildJsonObject {
                put("domainStrategy", "AsIs")
                put("rules", buildJsonArray {
                    configs.indices.forEach { i -> add(rule(listOf("$TEST_PREFIX$i"), "$PROXY_PREFIX$i")) }
                })
            })
        }
    }

    fun toJsonString(config: JsonObject, prettyPrint: Boolean = false): String =
        if (prettyPrint) pretty.encodeToString(JsonObject.serializer(), config) else config.toString()

    // ---- parts -------------------------------------------------------------------------------------

    private fun logOff() = buildJsonObject {
        put("loglevel", "none")
        put("access", "none")
        put("dnsLog", false)
    }

    private fun rule(inboundTags: List<String>, outboundTag: String) = buildJsonObject {
        put("type", "field")
        put("inboundTag", buildJsonArray { inboundTags.forEach { add(JsonPrimitive(it)) } })
        put("outboundTag", outboundTag)
    }

    private fun inbound(
        tag: String,
        protocol: String,
        port: Int,
        sniff: Boolean = false,
        settings: JsonObject = buildJsonObject { put("allowTransparent", false) },
    ) = buildJsonObject {
        put("tag", tag)
        put("protocol", protocol)
        put("listen", ProxyEndpoint.LOOPBACK)
        put("port", port)
        put("settings", settings)
        if (sniff) {
            put("sniffing", buildJsonObject {
                put("enabled", true)
                put("destOverride", buildJsonArray { add(JsonPrimitive("http")); add(JsonPrimitive("tls")) })
                put("routeOnly", true)
            })
        }
    }

    /** Main outbounds first (px-0 is also Xray's default outbound), then all helpers, references rewritten. */
    private fun outbounds(configs: List<ProxyConfig>): JsonArray {
        val mains = mutableListOf<JsonObject>()
        val helpers = mutableListOf<JsonObject>()
        configs.forEachIndexed { i, cfg ->
            val mapping = cfg.helpers.mapIndexedNotNull { j, h ->
                (h["tag"] as? JsonPrimitive)?.contentOrNull?.let { it to "$HELPER_PREFIX$i-$j" }
            }.toMap()
            mains += retag(cfg.outbound, "$PROXY_PREFIX$i", mapping)
            cfg.helpers.forEachIndexed { j, h -> helpers += retag(h, "$HELPER_PREFIX$i-$j", mapping) }
        }
        return JsonArray(mains + helpers)
    }

    /** Sets the outbound's tag and rewrites `sockopt.dialerProxy` / `proxySettings.tag` via [mapping]. */
    internal fun retag(outbound: JsonObject, tag: String, mapping: Map<String, String>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["tag"] = JsonPrimitive(tag)
        for ((k, v) in outbound) {
            if (k == "tag") continue
            m[k] = when {
                k == "streamSettings" && v is JsonObject -> rewriteSockopt(v, mapping)
                k == "proxySettings" && v is JsonObject -> rewriteField(v, "tag", mapping)
                else -> v
            }
        }
        return JsonObject(m)
    }

    private fun rewriteSockopt(stream: JsonObject, mapping: Map<String, String>): JsonObject {
        val sockopt = stream["sockopt"] as? JsonObject ?: return stream
        return JsonObject(stream + ("sockopt" to rewriteField(sockopt, "dialerProxy", mapping)))
    }

    private fun rewriteField(o: JsonObject, key: String, mapping: Map<String, String>): JsonObject {
        val old = (o[key] as? JsonPrimitive)?.contentOrNull ?: return o
        val new = mapping[old] ?: return o
        return JsonObject(o + (key to JsonPrimitive(new)))
    }
}
