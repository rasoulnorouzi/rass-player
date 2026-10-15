package nl.rass.player.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// All hosts, ids and keys below are synthetic (".invalid" TLD, dummy UUIDs). No real servers.
class SubscriptionParserTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun JsonObject.obj(vararg path: String): JsonObject =
        path.fold(this) { acc, k -> acc[k]!!.jsonObject }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun firstServer(o: JsonObject, list: String) = o.obj("settings")[list]!!.jsonArray[0].jsonObject

    private fun single(body: String): ProxyConfig {
        val r = SubscriptionParser.parse(body)
        assertEquals(1, r.configs.size, "problems: ${r.problems}")
        return r.configs[0]
    }

    @Test
    fun vlessRealityTcp() {
        val c = single(
            "vless://$uuid@reality.example.invalid:443?encryption=none&flow=xtls-rprx-vision&security=reality" +
                "&sni=www.example.invalid&fp=chrome&pbk=PUBKEYdummy_-abc&sid=0123abcd&spx=%2F&type=tcp#Reality%20NL",
        )
        assertEquals("Reality NL", c.name)
        assertEquals("vless", c.protocol)
        val vnext = firstServer(c.outbound, "vnext")
        assertEquals("reality.example.invalid", vnext.str("address"))
        assertEquals(443, vnext["port"]!!.jsonPrimitive.int)
        val user = vnext["users"]!!.jsonArray[0].jsonObject
        assertEquals(uuid, user.str("id"))
        assertEquals("xtls-rprx-vision", user.str("flow"))
        val ss = c.outbound.obj("streamSettings")
        assertEquals("tcp", ss.str("network"))
        assertEquals("reality", ss.str("security"))
        val rs = ss.obj("realitySettings")
        assertEquals("www.example.invalid", rs.str("serverName"))
        assertEquals("PUBKEYdummy_-abc", rs.str("publicKey"))
        assertEquals("0123abcd", rs.str("shortId"))
        assertEquals("/", rs.str("spiderX"))
        assertNull(c.outbound["tag"])
    }

    @Test
    fun vlessWsTlsWithPathHostAlpn() {
        val c = single(
            "vless://$uuid@cdn.example.invalid:8443?type=ws&security=tls&sni=sni.example.invalid&fp=randomized" +
                "&alpn=h2,http/1.1&path=%2Fws%3Fed%3D2048&host=host.example.invalid&allowInsecure=1#WS",
        )
        val ss = c.outbound.obj("streamSettings")
        assertEquals("ws", ss.str("network"))
        assertEquals("/ws?ed=2048", ss.obj("wsSettings").str("path"))
        assertEquals("host.example.invalid", ss.obj("wsSettings").str("host"))
        val tls = ss.obj("tlsSettings")
        assertEquals("sni.example.invalid", tls.str("serverName"))
        assertEquals("randomized", tls.str("fingerprint"))
        assertEquals(listOf("h2", "http/1.1"), tls["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(tls["allowInsecure"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun vlessGrpcAndXhttp() {
        val grpc = single("vless://$uuid@g.example.invalid:443?type=grpc&serviceName=svc&mode=multi&security=tls#g")
        val gs = grpc.outbound.obj("streamSettings", "grpcSettings")
        assertEquals("svc", gs.str("serviceName"))
        assertTrue(gs["multiMode"]!!.jsonPrimitive.boolean)
        // Without sni, TLS server name falls back to the address.
        assertEquals("g.example.invalid", grpc.outbound.obj("streamSettings", "tlsSettings").str("serverName"))

        val xhttp = single(
            "vless://$uuid@x.example.invalid:443?type=xhttp&path=%2Fxh&host=h.example.invalid&mode=packet-up" +
                "&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D&security=tls&sni=x.example.invalid#x",
        )
        val xs = xhttp.outbound.obj("streamSettings", "xhttpSettings")
        assertEquals("/xh", xs.str("path"))
        assertEquals("packet-up", xs.str("mode"))
        assertEquals("100-1000", xs.obj("extra").str("xPaddingBytes"))
    }

    @Test
    fun vmessBase64Json() {
        val json = """{"v":"2","ps":"VMess test","add":"vm.example.invalid","port":"2053","id":"$uuid","aid":"0",
            "scy":"auto","net":"ws","type":"none","host":"vh.example.invalid","path":"/vm","tls":"tls",
            "sni":"vs.example.invalid","fp":"chrome"}"""
        val c = single("vmess://" + Base64.Default.encode(json.encodeToByteArray()))
        assertEquals("VMess test", c.name)
        val vnext = firstServer(c.outbound, "vnext")
        assertEquals(2053, vnext["port"]!!.jsonPrimitive.int)
        val ss = c.outbound.obj("streamSettings")
        assertEquals("/vm", ss.obj("wsSettings").str("path"))
        assertEquals("vs.example.invalid", ss.obj("tlsSettings").str("serverName"))
    }

    @Test
    fun trojanDefaultsToTls() {
        val c = single("trojan://p%40ss@tr.example.invalid:443?sni=tr.example.invalid&type=tcp#Trojan")
        assertEquals("trojan", c.protocol)
        assertEquals("p@ss", firstServer(c.outbound, "servers").str("password"))
        assertEquals("tls", c.outbound.obj("streamSettings").str("security"))
    }

    @Test
    fun shadowsocksVariants() {
        val sip002 = "ss://" + Base64.UrlSafe.encode("aes-256-gcm:secret".encodeToByteArray()).trimEnd('=') +
            "@ss.example.invalid:8388#SS1"
        val ss2022 = "ss://2022-blake3-aes-128-gcm:a2V5a2V5a2V5a2V5a2V5aw%3D%3D@ss2.example.invalid:443#SS2"
        val legacy = "ss://" + Base64.Default.encode("chacha20-ietf-poly1305:pw@ss3.example.invalid:8000".encodeToByteArray()) + "#SS3"
        val r = SubscriptionParser.parse(listOf(sip002, ss2022, legacy).joinToString("\n"))
        assertEquals(3, r.configs.size, "problems: ${r.problems}")
        val s1 = firstServer(r.configs[0].outbound, "servers")
        assertEquals("aes-256-gcm", s1.str("method"))
        assertEquals("secret", s1.str("password"))
        val s2 = firstServer(r.configs[1].outbound, "servers")
        assertEquals("2022-blake3-aes-128-gcm", s2.str("method"))
        assertEquals("a2V5a2V5a2V5a2V5a2V5aw==", s2.str("password"))
        val s3 = firstServer(r.configs[2].outbound, "servers")
        assertEquals("ss3.example.invalid", s3.str("address"))
        assertEquals(8000, s3["port"]!!.jsonPrimitive.int)
    }

    @Test
    fun hysteria2() {
        val c = single("hysteria2://authpw@hy.example.invalid:8443/?sni=hy.example.invalid&insecure=1#HY")
        assertEquals("hysteria", c.protocol)
        assertEquals(2, c.outbound.obj("settings")["version"]!!.jsonPrimitive.int)
        val ss = c.outbound.obj("streamSettings")
        assertEquals("hysteria", ss.str("network"))
        assertEquals("authpw", ss.obj("hysteriaSettings").str("auth"))
        val skipped = SubscriptionParser.parse("hy2://pw@hy.example.invalid:443?obfs=salamander&obfs-password=x#o")
        assertEquals(0, skipped.configs.size)
        assertEquals(1, skipped.skipped)
    }

    @Test
    fun base64ListWithBadEntriesAndNoSecretsInProblems() {
        val list = listOf(
            "vless://$uuid@a.example.invalid:443?type=ws&security=tls#A",
            "vless://$uuid@b.example.invalid:notaport?type=ws#B",
            "vless://$uuid@c.example.invalid:443?type=quic#C",
            "socks://whatever",
            "trojan://pw@d.example.invalid:443#D",
            "trojan://pw@d.example.invalid:443#D-duplicate",
        ).joinToString("\r\n")
        val r = SubscriptionParser.parse(Base64.Default.encode(list.encodeToByteArray()), subscriptionId = "sub1")
        assertEquals(listOf("A", "D"), r.configs.map { it.name })
        assertTrue(r.configs.all { it.subscriptionId == "sub1" })
        assertEquals(3, r.skipped)
        assertTrue(r.problems.none { it.contains("example.invalid") || it.contains(uuid) }, "${r.problems}")
    }

    @Test
    fun xrayJsonArrayExtractsProxiesHelpersAndChains() {
        val body = """
        [
          {
            "remarks": "BPB fragment",
            "outbounds": [
              {"tag": "proxy", "protocol": "vless",
               "settings": {"vnext": [{"address": "f.example.invalid", "port": 443, "users": [{"id": "$uuid", "encryption": "none"}]}]},
               "streamSettings": {"network": "ws", "security": "tls", "sockopt": {"dialerProxy": "fragment"}}},
              {"tag": "fragment", "protocol": "freedom", "settings": {"fragment": {"packets": "tlshello", "length": "100-200", "interval": "1-1"}}},
              {"tag": "direct", "protocol": "freedom"},
              {"tag": "block", "protocol": "blackhole"},
              {"tag": "dns-out", "protocol": "dns"}
            ]
          },
          {
            "remarks": "BPB best ping",
            "outbounds": [
              {"tag": "proxy-1", "protocol": "trojan", "settings": {"servers": [{"address": "t1.example.invalid", "port": 443, "password": "x"}]}},
              {"tag": "proxy-2", "protocol": "trojan", "settings": {"servers": [{"address": "t2.example.invalid", "port": 443, "password": "y"}]}},
              {"tag": "direct", "protocol": "freedom"}
            ]
          },
          {
            "remarks": "Chain",
            "outbounds": [
              {"tag": "chain", "protocol": "socks", "settings": {"servers": [{"address": "c.example.invalid", "port": 1080}]},
               "streamSettings": {"sockopt": {"dialerProxy": "proxy"}}},
              {"tag": "proxy", "protocol": "vmess", "settings": {"vnext": [{"address": "v.example.invalid", "port": 443, "users": [{"id": "$uuid"}]}]}}
            ]
          },
          {
            "remarks": "Broken",
            "outbounds": [
              {"tag": "proxy", "protocol": "vless", "settings": {}, "streamSettings": {"sockopt": {"dialerProxy": "missing"}}}
            ]
          },
          {"remarks": "Only direct", "outbounds": [{"protocol": "freedom"}]}
        ]
        """.trimIndent()
        val r = SubscriptionParser.parse(body)
        assertEquals(listOf("BPB fragment", "BPB best ping · proxy-1", "BPB best ping · proxy-2", "Chain"), r.configs.map { it.name })
        val fragment = r.configs[0]
        assertEquals(1, fragment.helpers.size)
        assertEquals("fragment", fragment.helpers[0]["tag"]!!.jsonPrimitive.content)
        assertNull(fragment.outbound["tag"])
        val chain = r.configs[3]
        assertEquals("socks", chain.protocol)
        assertEquals(listOf("proxy"), chain.helpers.map { it["tag"]!!.jsonPrimitive.content })
        assertEquals(2, r.skipped)
    }

    @Test
    fun bareOutboundAndIdsAreStableAndContentBased() {
        val outbound = """{"tag":"x","remarks":"Bare","protocol":"trojan","settings":{"servers":[{"address":"b.example.invalid","port":443,"password":"z"}]}}"""
        val a = single(outbound)
        val b = single(outbound.replace("Bare", "Renamed"))
        assertEquals("Bare", a.name)
        assertEquals(a.id, b.id)
        assertEquals(16, a.id.length)
        val c = single(outbound.replace("\"z\"", "\"other\""))
        assertFalse(a.id == c.id)
    }

    @Test
    fun unknownFormatsAreReported() {
        assertTrue(SubscriptionParser.parse("").configs.isEmpty())
        val clash = SubscriptionParser.parse("proxies:\n  - name: x\n    type: ss")
        assertTrue(clash.configs.isEmpty())
        assertEquals(listOf("unknown subscription format"), clash.problems)
        assertNotNull(SubscriptionParser.parse("{not json").problems.firstOrNull())
    }

    @Test
    fun hostPortParsingHandlesIpv6() {
        val c = single("trojan://pw@[2001:db8::1]:443#v6")
        assertEquals("2001:db8::1", firstServer(c.outbound, "servers").str("address"))
        assertEquals(JsonPrimitive(443), firstServer(c.outbound, "servers")["port"])
    }
}
