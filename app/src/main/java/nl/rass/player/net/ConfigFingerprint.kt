package nl.rass.player.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/**
 * Stable identity of a proxy server, independent of its display name and of transport tweaks:
 * SHA-256 over `protocol|host|port|id-or-password`, first 16 hex chars. Used to remember which servers the
 * user removed or disabled across subscription refreshes. The raw secret never leaves this function; only the
 * hash is stored (inside [SecureConfigStore]'s encrypted blob), and it is never logged.
 */
object ConfigFingerprint {

    fun of(c: ProxyConfig): String = of(c.protocol, c.outbound)

    fun of(protocol: String, outbound: JsonObject): String {
        val settings = outbound["settings"] as? JsonObject
        // Xray shapes: settings.vnext[0] (vless/vmess, users[0].id), settings.servers[0] (trojan/ss/socks/http),
        // or flat settings (hysteria, newer vless).
        val server = ((settings?.get("vnext") ?: settings?.get("servers")) as? JsonArray)
            ?.firstOrNull() as? JsonObject ?: settings
        val user = (server?.get("users") as? JsonArray)?.firstOrNull() as? JsonObject
        val host = server.str("address")
        val port = server.str("port")
        val secret = user.str("id") ?: server.str("id") ?: server.str("password")
            ?: ((outbound["streamSettings"] as? JsonObject)?.get("hysteriaSettings") as? JsonObject).str("auth")
        val basis = if (host == null && port == null && secret == null) {
            outbound.toString() // unknown shape: still stable and unique
        } else {
            listOf(protocol, host.orEmpty(), port.orEmpty(), secret.orEmpty()).joinToString("|")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun JsonElement?.str(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}
