package nl.rass.player.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/**
 * Result of parsing one subscription body. [problems] holds short, non-secret reasons for skipped
 * entries (e.g. "vless: bad or missing port"); never the entry itself.
 */
data class ParseResult(val configs: List<ProxyConfig>, val skipped: Int, val problems: List<String>)

/**
 * Turns a subscription body into [ProxyConfig]s. Accepted formats:
 *  - Xray JSON (BPB panel "Xray" subscriptions): an array of full configs, one full config, or a bare outbound.
 *    Proxy outbounds are extracted; direct/block/dns outbounds are skipped unless a proxy outbound depends on
 *    them via `sockopt.dialerProxy` / `proxySettings.tag` (e.g. BPB's fragment outbound), in which case they
 *    travel along as [ProxyConfig.helpers]. `remarks` becomes the name.
 *  - Share-link lists, plain text or base64 (one link per line).
 */
object SubscriptionParser {

    /** Outbound protocols that carry traffic to a remote server. */
    val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "wireguard")

    private const val MAX_CHAIN_DEPTH = 8
    private val BOM = Char(0xFEFF)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, subscriptionId: String? = null): ParseResult {
        val text = body.trim().trimStart(BOM)
        if (text.isEmpty()) return ParseResult(emptyList(), 0, listOf("empty subscription"))

        if (text.startsWith('[') || text.startsWith('{')) {
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
                ?: return ParseResult(emptyList(), 0, listOf("invalid JSON"))
            return parseJson(element, subscriptionId)
        }
        if (text.lineSequence().any { ShareLinks.isShareLink(it.trim()) }) {
            return parseLinks(text, subscriptionId)
        }
        val decoded = ShareLinks.decodeBase64OrNull(text)?.trim()
            ?: return ParseResult(emptyList(), 0, listOf("unknown subscription format"))
        return when {
            decoded.startsWith('[') || decoded.startsWith('{') ->
                runCatching { json.parseToJsonElement(decoded) }.getOrNull()
                    ?.let { parseJson(it, subscriptionId) }
                    ?: ParseResult(emptyList(), 0, listOf("invalid JSON"))
            decoded.lineSequence().any { ShareLinks.isShareLink(it.trim()) } -> parseLinks(decoded, subscriptionId)
            else -> ParseResult(emptyList(), 0, listOf("unknown subscription format"))
        }
    }

    // ---- share links -------------------------------------------------------------------------------

    private fun parseLinks(text: String, subscriptionId: String?): ParseResult {
        val configs = mutableListOf<ProxyConfig>()
        val problems = mutableListOf<String>()
        var skipped = 0
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { line ->
            if (!ShareLinks.isShareLink(line)) {
                skipped++
                problems += "unsupported line"
                return@forEach
            }
            try {
                val c = ShareLinks.convert(line)
                configs += makeConfig(c.name, c.protocol, c.outbound, emptyList(), subscriptionId)
            } catch (e: UnsupportedLinkException) {
                skipped++
                problems += e.message ?: "unsupported link"
            } catch (e: RuntimeException) {
                // Never include the exception text: it may echo parts of the link.
                skipped++
                problems += "malformed link"
            }
        }
        return ParseResult(dedupe(configs), skipped, problems)
    }

    // ---- Xray JSON ---------------------------------------------------------------------------------

    private fun parseJson(root: JsonElement, subscriptionId: String?): ParseResult {
        val configs = mutableListOf<ProxyConfig>()
        val problems = mutableListOf<String>()
        var skipped = 0
        val documents: List<JsonObject> = when (root) {
            is JsonArray -> root.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        documents.forEachIndexed { docIndex, doc ->
            when {
                doc["outbounds"] is JsonArray -> {
                    val r = extractFromFullConfig(doc, docIndex, subscriptionId)
                    configs += r.configs
                    skipped += r.skipped
                    problems += r.problems
                }
                doc.str("protocol") != null -> {
                    val protocol = doc.str("protocol")!!
                    if (protocol in PROXY_PROTOCOLS) {
                        val name = doc.str("remarks") ?: doc.str("tag") ?: "Config ${docIndex + 1}"
                        configs += makeConfig(name, protocol, doc.withoutKeys("tag", "remarks"), emptyList(), subscriptionId)
                    } else {
                        skipped++
                    }
                }
                else -> {
                    skipped++
                    problems += "JSON entry without outbounds"
                }
            }
        }
        return ParseResult(dedupe(configs), skipped, problems)
    }

    private fun extractFromFullConfig(doc: JsonObject, docIndex: Int, subscriptionId: String?): ParseResult {
        val remarks = doc.str("remarks")?.trim()?.takeIf { it.isNotEmpty() } ?: "Config ${docIndex + 1}"
        val outbounds = (doc["outbounds"] as JsonArray).filterIsInstance<JsonObject>()
        val byTag = outbounds.mapNotNull { o -> o.str("tag")?.let { it to o } }.toMap()
        val proxies = outbounds.filter { it.str("protocol") in PROXY_PROTOCOLS }
        val referenced = proxies.flatMap { referencesOf(it) }.toSet()
        // Entry points: proxy outbounds that no other proxy outbound dials through.
        val entries = proxies.filter { o -> o.str("tag").let { it == null || it !in referenced } }

        val configs = mutableListOf<ProxyConfig>()
        val problems = mutableListOf<String>()
        var skipped = 0
        entries.forEachIndexed { i, entry ->
            val helpers = collectHelpers(entry, byTag)
            if (helpers == null) {
                skipped++
                problems += "outbound depends on a missing or cyclic outbound"
                return@forEachIndexed
            }
            val name = if (entries.size == 1) remarks else "$remarks · ${entry.str("tag") ?: (i + 1).toString()}"
            configs += makeConfig(name, entry.str("protocol")!!, entry.withoutKeys("tag"), helpers, subscriptionId)
        }
        if (entries.isEmpty()) {
            skipped++
            problems += "config without proxy outbounds"
        }
        return ParseResult(configs, skipped, problems)
    }

    /** Tags an outbound dials through. */
    internal fun referencesOf(o: JsonObject): List<String> {
        val dialer = ((o["streamSettings"] as? JsonObject)?.get("sockopt") as? JsonObject)?.str("dialerProxy")
        val proxyTag = (o["proxySettings"] as? JsonObject)?.str("tag")
        return listOfNotNull(dialer, proxyTag).filter { it.isNotBlank() }
    }

    /** Transitive dependencies of [entry] (with their original tags), or null if one is missing/cyclic. */
    private fun collectHelpers(entry: JsonObject, byTag: Map<String, JsonObject>): List<JsonObject>? {
        val result = LinkedHashMap<String, JsonObject>()
        val entryTag = entry.str("tag")
        fun visit(o: JsonObject, depth: Int): Boolean {
            if (depth > MAX_CHAIN_DEPTH) return false
            for (ref in referencesOf(o)) {
                if (ref == entryTag) return false
                if (ref in result) continue
                val dep = byTag[ref] ?: return false
                result[ref] = dep
                if (!visit(dep, depth + 1)) return false
            }
            return true
        }
        return if (visit(entry, 0)) result.values.toList() else null
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun makeConfig(
        name: String,
        protocol: String,
        outbound: JsonObject,
        helpers: List<JsonObject>,
        subscriptionId: String?,
    ) = ProxyConfig(
        id = configId(outbound, helpers),
        name = name.trim().ifEmpty { protocol },
        protocol = protocol,
        outbound = outbound,
        helpers = helpers,
        subscriptionId = subscriptionId,
    )

    /** Stable id: SHA-256 over the outbound content (not the name), first 16 hex chars. */
    fun configId(outbound: JsonObject, helpers: List<JsonObject>): String {
        val canonical = buildString {
            append(outbound.toString())
            helpers.forEach { append('|').append(it.toString()) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun dedupe(configs: List<ProxyConfig>): List<ProxyConfig> = configs.distinctBy { it.id }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.withoutKeys(vararg keys: String): JsonObject = JsonObject(this.filterKeys { it !in keys })
}
