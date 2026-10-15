package nl.rass.player.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class XrayConfigBuilderTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun golden(name: String): JsonObject =
        obj(checkNotNull(javaClass.getResource("/net/$name")) { "missing golden $name" }.readText())

    private val configA = ProxyConfig(
        id = "a", name = "A", protocol = "vless",
        outbound = obj(
            """{"protocol":"vless","settings":{"vnext":[{"address":"a.example.invalid","port":443,
            "users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},
            "streamSettings":{"network":"ws","security":"tls","sockopt":{"dialerProxy":"fragment"}}}""",
        ),
        helpers = listOf(
            obj("""{"tag":"fragment","protocol":"freedom","settings":{"fragment":{"packets":"tlshello","length":"100-200","interval":"1-1"}}}"""),
        ),
    )

    private val configB = ProxyConfig(
        id = "b", name = "B", protocol = "trojan",
        outbound = obj("""{"protocol":"trojan","settings":{"servers":[{"address":"b.example.invalid","port":443,"password":"pw"}]}}"""),
    )

    @Test
    fun runtimeConfigMatchesGolden() {
        val built = XrayConfigBuilder.runtimeConfig(configA, listOf(configB), ProxyEndpoint(10808, 10809, listOf(10810)))
        assertEquals(golden("xray-runtime.golden.json"), built)
        // Outbound order matters: px-0 (active) must be first (Xray's default outbound).
        assertEquals("px-0", built["outbounds"]!!.jsonArray[0].jsonObject["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun batchTestConfigMatchesGolden() {
        assertEquals(golden("xray-batch-test.golden.json"), XrayConfigBuilder.batchTestConfig(listOf(configA, configB), listOf(30000, 30001)))
    }

    @Test
    fun noObservatoryOrBalancer() {
        val built = XrayConfigBuilder.runtimeConfig(configA, listOf(configB), ProxyEndpoint(1, 2, listOf(3)))
        assertNull(built["observatory"])
        assertNull(built["burstObservatory"])
        assertNull(built["routing"]!!.jsonObject["balancers"])
    }

    @Test
    fun inboundsListenOnLoopbackOnly() {
        val built = XrayConfigBuilder.runtimeConfig(configA, listOf(configB), ProxyEndpoint(1, 2, listOf(3)))
        built["inbounds"]!!.jsonArray.forEach {
            assertEquals("127.0.0.1", it.jsonObject["listen"]!!.jsonPrimitive.content)
        }
        // No direct (freedom) outbound except helpers that a config explicitly dials through.
        val freedomTags = built["outbounds"]!!.jsonArray.map { it.jsonObject }
            .filter { it["protocol"]!!.jsonPrimitive.content == "freedom" }
            .map { it["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("hp-0-0"), freedomTags)
    }

    @Test
    fun portCountsMustMatch() {
        assertFailsWith<IllegalArgumentException> {
            XrayConfigBuilder.runtimeConfig(configA, listOf(configB), ProxyEndpoint(1, 2))
        }
        assertFailsWith<IllegalArgumentException> { XrayConfigBuilder.batchTestConfig(listOf(configA), listOf(1, 2)) }
        assertFailsWith<IllegalArgumentException> { XrayConfigBuilder.batchTestConfig(emptyList(), emptyList()) }
    }
}
