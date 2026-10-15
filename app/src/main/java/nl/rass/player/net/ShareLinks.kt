package nl.rass.player.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/** A share link we can't convert. [message] must never contain the link or any part of it. */
class UnsupportedLinkException(message: String) : IllegalArgumentException(message)

/** Result of converting one share link: an outbound (no tag) plus its display name. */
data class ConvertedLink(val name: String, val protocol: String, val outbound: JsonObject)

/**
 * Converts standard share links (`vless://`, `vmess://`, `trojan://`, `ss://`, `hysteria2://` / `hy2://`)
 * into Xray outbound JSON. Pure Kotlin, no Android APIs, so it runs in unit tests.
 *
 * Supported transports: tcp/raw (incl. HTTP header), ws, httpupgrade, grpc, xhttp/splithttp, kcp.
 * Supported security: none, tls (sni, fp, alpn, allowInsecure), reality (sni, fp, pbk, sid, spx).
 */
object ShareLinks {

    val SCHEMES = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2")

    fun isShareLink(line: String): Boolean {
        val scheme = line.substringBefore("://", "").lowercase()
        return scheme in SCHEMES
    }

    fun convert(link: String): ConvertedLink {
        val trimmed = link.trim()
        return when (trimmed.substringBefore("://").lowercase()) {
            "vless" -> vless(ShareUri.parse(trimmed))
            "trojan" -> trojan(ShareUri.parse(trimmed))
            "vmess" -> vmess(trimmed)
            "ss" -> shadowsocks(trimmed)
            "hysteria2", "hy2" -> hysteria2(ShareUri.parse(trimmed))
            else -> throw UnsupportedLinkException("unsupported scheme")
        }
    }

    // ---- vless / trojan ------------------------------------------------------------------------

    private fun vless(u: ShareUri): ConvertedLink {
        val id = u.userInfo.ifBlank { throw UnsupportedLinkException("vless: missing id") }
        val user = buildJsonObject {
            put("id", id)
            put("encryption", u.param("encryption") ?: "none")
            u.param("flow")?.let { put("flow", it) }
        }
        val outbound = buildJsonObject {
            put("protocol", "vless")
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", u.host)
                        put("port", u.port)
                        put("users", buildJsonArray { add(user) })
                    })
                })
            })
            put("streamSettings", streamSettings(
                network = u.param("type") ?: "tcp",
                security = u.param("security") ?: "none",
                p = u::param,
                address = u.host,
            ))
        }
        return ConvertedLink(u.fragment.ifBlank { "VLESS ${u.host}" }, "vless", outbound)
    }

    private fun trojan(u: ShareUri): ConvertedLink {
        val password = u.userInfo.ifBlank { throw UnsupportedLinkException("trojan: missing password") }
        val outbound = buildJsonObject {
            put("protocol", "trojan")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", u.host)
                        put("port", u.port)
                        put("password", password)
                    })
                })
            })
            put("streamSettings", streamSettings(
                network = u.param("type") ?: "tcp",
                security = u.param("security") ?: "tls",
                p = u::param,
                address = u.host,
            ))
        }
        return ConvertedLink(u.fragment.ifBlank { "Trojan ${u.host}" }, "trojan", outbound)
    }

    // ---- vmess (base64 JSON, v2rayN format) --------------------------------------------------------

    private fun vmess(link: String): ConvertedLink {
        val body = link.substringAfter("://").substringBefore('#').trim()
        val text = decodeBase64OrNull(body) ?: throw UnsupportedLinkException("vmess: not base64")
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw UnsupportedLinkException("vmess: invalid JSON")
        fun s(key: String): String? = (o[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        val address = s("add") ?: throw UnsupportedLinkException("vmess: missing address")
        val port = s("port")?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw UnsupportedLinkException("vmess: bad port")
        val id = s("id") ?: throw UnsupportedLinkException("vmess: missing id")
        val net = s("net") ?: "tcp"
        val params = mapOf(
            "host" to s("host"),
            "path" to s("path"),
            "sni" to s("sni"),
            "alpn" to s("alpn"),
            "fp" to s("fp"),
            "headerType" to s("type"),
            "allowInsecure" to s("allowInsecure"),
            // In v2rayN's vmess JSON, the gRPC service name travels in "path".
            "serviceName" to if (net == "grpc") s("path") else null,
            "mode" to if (net == "grpc" && s("type") == "multi") "multi" else s("mode"),
        )
        val outbound = buildJsonObject {
            put("protocol", "vmess")
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", address)
                        put("port", port)
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("id", id)
                                put("alterId", s("aid")?.toIntOrNull() ?: 0)
                                put("security", s("scy") ?: "auto")
                            })
                        })
                    })
                })
            })
            put("streamSettings", streamSettings(
                network = net,
                security = s("tls") ?: "none",
                p = { params[it] },
                address = address,
            ))
        }
        return ConvertedLink(s("ps") ?: "VMess $address", "vmess", outbound)
    }

    // ---- shadowsocks (SIP002 + legacy) -------------------------------------------------------

    private fun shadowsocks(link: String): ConvertedLink {
        val afterScheme = link.substringAfter("://")
        val name = percentDecode(afterScheme.substringAfter('#', ""))
        var body = afterScheme.substringBefore('#')
        val query = body.substringAfter('?', "")
        body = body.substringBefore('?').trimEnd('/')
        if (query.isNotEmpty()) {
            val plugin = ShareUri.parseQuery(query)["plugin"]
            if (!plugin.isNullOrBlank()) throw UnsupportedLinkException("ss: plugins are not supported")
        }

        val method: String
        val password: String
        val hostPort: String
        if (body.contains('@')) {
            // SIP002: userinfo is base64url(method:password), or percent-encoded "method:password" (SS-2022).
            val userInfo = body.substringBeforeLast('@')
            hostPort = body.substringAfterLast('@')
            val decoded = if (userInfo.contains(':') || userInfo.contains('%')) {
                percentDecode(userInfo)
            } else {
                decodeBase64OrNull(userInfo) ?: throw UnsupportedLinkException("ss: bad user info")
            }
            method = decoded.substringBefore(':')
            password = decoded.substringAfter(':', "")
        } else {
            // Legacy: base64(method:password@host:port)
            val decoded = decodeBase64OrNull(body) ?: throw UnsupportedLinkException("ss: not base64")
            val creds = decoded.substringBeforeLast('@')
            hostPort = decoded.substringAfterLast('@')
            method = creds.substringBefore(':')
            password = creds.substringAfter(':', "")
        }
        if (method.isBlank() || password.isEmpty()) throw UnsupportedLinkException("ss: missing method or password")
        val (host, port) = ShareUri.splitHostPort(hostPort)
        val outbound = buildJsonObject {
            put("protocol", "shadowsocks")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", host)
                        put("port", port)
                        put("method", method)
                        put("password", password)
                    })
                })
            })
            put("streamSettings", buildJsonObject { put("network", "tcp") })
        }
        return ConvertedLink(name.ifBlank { "Shadowsocks $host" }, "shadowsocks", outbound)
    }

    // ---- hysteria2 (Xray "hysteria" outbound, version 2) ----------------------------------------

    private fun hysteria2(u: ShareUri): ConvertedLink {
        if (!u.param("obfs").isNullOrBlank()) throw UnsupportedLinkException("hysteria2: obfs is not supported")
        val auth = u.userInfo
        val outbound = buildJsonObject {
            put("protocol", "hysteria")
            put("settings", buildJsonObject {
                put("version", 2)
                put("address", u.host)
                put("port", u.port)
            })
            put("streamSettings", buildJsonObject {
                put("network", "hysteria")
                put("security", "tls")
                put("tlsSettings", buildJsonObject {
                    put("serverName", u.param("sni") ?: u.host)
                    put("alpn", buildJsonArray { add(JsonPrimitive("h3")) })
                    if (isTrue(u.param("insecure"))) put("allowInsecure", true)
                })
                put("hysteriaSettings", buildJsonObject {
                    put("version", 2)
                    put("auth", auth)
                })
            })
        }
        return ConvertedLink(u.fragment.ifBlank { "Hysteria2 ${u.host}" }, "hysteria", outbound)
    }

    // ---- shared transport / security builder ---------------------------------------------------

    internal fun streamSettings(
        network: String,
        security: String,
        p: (String) -> String?,
        address: String,
    ): JsonObject = buildJsonObject {
        val net = when (network.lowercase()) {
            "", "tcp", "raw" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "xhttp", "splithttp" -> "xhttp"
            "httpupgrade" -> "httpupgrade"
            "kcp", "mkcp" -> "kcp"
            else -> throw UnsupportedLinkException("transport '$network' is not supported")
        }
        put("network", net)
        val host = p("host")
        val path = p("path")
        when (net) {
            "tcp" -> if (p("headerType") == "http") {
                put("tcpSettings", buildJsonObject {
                    put("header", buildJsonObject {
                        put("type", "http")
                        put("request", buildJsonObject {
                            put("path", stringArray(splitList(path).ifEmpty { listOf("/") }))
                            if (host != null) {
                                put("headers", buildJsonObject { put("Host", stringArray(splitList(host))) })
                            }
                        })
                    })
                })
            }
            "ws" -> put("wsSettings", buildJsonObject {
                put("path", path ?: "/")
                if (host != null) put("host", host)
            })
            "httpupgrade" -> put("httpupgradeSettings", buildJsonObject {
                put("path", path ?: "/")
                if (host != null) put("host", host)
            })
            "grpc" -> put("grpcSettings", buildJsonObject {
                put("serviceName", p("serviceName") ?: path ?: "")
                put("multiMode", p("mode") == "multi")
                p("authority")?.let { put("authority", it) }
            })
            "xhttp" -> put("xhttpSettings", buildJsonObject {
                put("path", path ?: "/")
                if (host != null) put("host", host)
                put("mode", p("mode") ?: "auto")
                p("extra")?.let { extra ->
                    val parsed = runCatching { Json.parseToJsonElement(extra) }.getOrNull()
                    if (parsed is JsonObject) put("extra", parsed)
                }
            })
            "kcp" -> put("kcpSettings", buildJsonObject {
                put("header", buildJsonObject { put("type", p("headerType") ?: "none") })
                p("seed")?.let { put("seed", it) }
            })
        }

        when (security.lowercase()) {
            "", "none" -> put("security", "none")
            "tls", "xtls" -> {
                put("security", "tls")
                put("tlsSettings", buildJsonObject {
                    put("serverName", p("sni") ?: host?.substringBefore(',') ?: address)
                    p("fp")?.let { put("fingerprint", it) }
                    val alpn = splitList(p("alpn"))
                    if (alpn.isNotEmpty()) put("alpn", stringArray(alpn))
                    if (isTrue(p("allowInsecure")) || isTrue(p("insecure"))) put("allowInsecure", true)
                })
            }
            "reality" -> {
                val pbk = p("pbk") ?: throw UnsupportedLinkException("reality: missing public key")
                put("security", "reality")
                put("realitySettings", buildJsonObject {
                    put("serverName", p("sni") ?: address)
                    put("fingerprint", p("fp") ?: "chrome")
                    put("publicKey", pbk)
                    put("shortId", p("sid") ?: "")
                    p("spx")?.let { put("spiderX", it) }
                })
            }
            else -> throw UnsupportedLinkException("security '$security' is not supported")
        }
    }

    private fun stringArray(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun splitList(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun isTrue(v: String?) = v == "1" || v.equals("true", ignoreCase = true)

    // ---- encoding helpers ----------------------------------------------------------------------

    private val b64Std = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val b64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /** Decodes standard or URL-safe base64 (padding optional, whitespace ignored) as UTF-8, or null. */
    fun decodeBase64OrNull(input: String): String? {
        val clean = input.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return null
        val bytes = runCatching { b64Std.decode(clean) }.recoverCatching { b64Url.decode(clean) }.getOrNull()
            ?: return null
        return bytes.decodeToString()
    }

    /** Percent-decoding that keeps '+' as '+' (share links are not form-encoded). */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toByteArray().decodeToString()
    }
}

/**
 * Minimal, lenient parser for `scheme://userinfo@host:port/path?query#fragment` share links.
 * java.net.URI rejects many real-world names (spaces, '|', emoji in odd places), so we split by hand.
 */
internal data class ShareUri(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int,
    val params: Map<String, String>,
    val fragment: String,
) {
    fun param(key: String): String? = params[key]?.takeIf { it.isNotEmpty() }

    companion object {
        fun parse(link: String): ShareUri {
            val scheme = link.substringBefore("://").lowercase()
            var rest = link.substringAfter("://")
            val fragment = ShareLinks.percentDecode(rest.substringAfter('#', "")).trim()
            rest = rest.substringBefore('#')
            val query = rest.substringAfter('?', "")
            rest = rest.substringBefore('?')
            val authority = rest.substringBefore('/')
            val userInfo = if ('@' in authority) ShareLinks.percentDecode(authority.substringBeforeLast('@')) else ""
            val (host, port) = splitHostPort(authority.substringAfterLast('@'))
            return ShareUri(scheme, userInfo, host, port, parseQuery(query), fragment)
        }

        fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                ShareLinks.percentDecode(pair.substringBefore('=')) to ShareLinks.percentDecode(pair.substringAfter('=', ""))
            }

        fun splitHostPort(hostPort: String): Pair<String, Int> {
            val hp = hostPort.trim().trimEnd('/')
            val host: String
            val portText: String
            if (hp.startsWith('[')) {
                host = hp.substringAfter('[').substringBefore(']')
                portText = hp.substringAfter("]:", "")
            } else {
                host = hp.substringBeforeLast(':')
                portText = hp.substringAfterLast(':', "")
            }
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw UnsupportedLinkException("bad or missing port")
            if (host.isBlank()) throw UnsupportedLinkException("missing host")
            return host to port
        }
    }
}
