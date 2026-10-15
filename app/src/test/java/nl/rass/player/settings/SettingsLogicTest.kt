package nl.rass.player.settings

import nl.rass.player.data.cache.CacheCap
import nl.rass.player.data.settings.PinHasher
import nl.rass.player.data.settings.SubtitleStyleCodec
import nl.rass.player.data.settings.AppSettings
import nl.rass.player.data.settings.DEFAULT_UPDATE_URL
import nl.rass.player.data.source.LanEntryServer
import nl.rass.player.player.subtitle.SubtitleEdge
import nl.rass.player.player.subtitle.SubtitleStyle
import nl.rass.player.ui.format.formatBytesFa
import nl.rass.player.ui.format.formatIntervalFa
import nl.rass.player.ui.format.formatLatencyFa
import nl.rass.player.ui.settings.SubtitleOptions
import nl.rass.player.ui.settings.qrModules
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsLogicTest {
    @Test fun pinRoundTrip() {
        val stored = PinHasher.encode("1234")
        assertTrue(PinHasher.verify("1234", stored))
        assertFalse(PinHasher.verify("1235", stored))
        assertFalse(PinHasher.verify("12", stored))
        assertFalse(PinHasher.verify("1234", null))
        assertFalse(stored.contains("1234"))
    }

    @Test fun pinValidation() {
        assertTrue(PinHasher.isValid("0000"))
        assertFalse(PinHasher.isValid("123"))
        assertFalse(PinHasher.isValid("12a4"))
        assertFalse(PinHasher.isValid("۱۲۳۴"))
    }

    @Test fun pinSaltsDiffer() {
        assertTrue(PinHasher.encode("1234") != PinHasher.encode("1234"))
    }

    @Test fun cacheCapClearsBiggestFirst() {
        val e = listOf(CacheCap.Entry("a", 50), CacheCap.Entry("b", 300), CacheCap.Entry("c", 100))
        assertEquals(listOf("b"), CacheCap.idsToClear(e, 200))
        assertEquals(listOf("b", "c", "a"), CacheCap.idsToClear(e, 40))
        assertEquals(emptyList(), CacheCap.idsToClear(e, 0))
        assertEquals(emptyList(), CacheCap.idsToClear(e, 1000))
    }

    @Test fun subtitleStyleRoundTripAndBadInput() {
        val s = SubtitleStyle(textColor = 0xFFFFE14D.toInt(), backgroundEnabled = true, backgroundOpacity = 0.4f,
            sizeScale = 1.4f, verticalPosition = 0.5f, edge = SubtitleEdge.DROP_SHADOW)
        assertEquals(s, SubtitleStyleCodec.decode(SubtitleStyleCodec.encode(s)))
        assertEquals(SubtitleStyle.DEFAULT, SubtitleStyleCodec.decode(null))
        assertEquals(SubtitleStyle.DEFAULT, SubtitleStyleCodec.decode("not json"))
        assertEquals(SubtitleStyle.MAX_SCALE, SubtitleStyleCodec.decode("{\"size\": 9}").sizeScale)
    }

    @Test fun formats() {
        assertEquals("۰ بایت", formatBytesFa(0))
        assertEquals("۱٫۵ مگابایت", formatBytesFa(1_572_864))
        assertEquals("۲ کیلوبایت", formatBytesFa(2048))
        assertEquals("۱۲۰ میلی‌ثانیه", formatLatencyFa(120))
        assertEquals("فقط دستی", formatIntervalFa(0))
        assertEquals("هر ۶ ساعت", formatIntervalFa(360))
        assertEquals("هر روز", formatIntervalFa(1440))
        assertEquals("هر ۱۵ دقیقه", formatIntervalFa(15))
    }

    @Test fun nearestOption() {
        assertEquals(1.2f, SubtitleOptions.nearest(SubtitleOptions.SIZES, 1.25f))
    }

    @Test fun defaultUpdateUrl() {
        assertEquals("https://github.com/rasoulnorouzi/rass-player/releases/latest/download/update.json", DEFAULT_UPDATE_URL)
        assertEquals(DEFAULT_UPDATE_URL, AppSettings().updateUrl)
    }

    @Test fun qrHasModules() {
        val m = qrModules("http://192.168.1.20:40000/?c=abc")
        assertTrue(m.size >= 21)
        assertTrue(m.all { it.size == m.size })
        assertTrue(m.any { row -> row.any { it } })
    }

    @Test fun lanServerOneTimeCode() {
        val got = ArrayList<Pair<String, String>>()
        val server = LanEntryServer(hostProvider = { "127.0.0.1" }) { n, a -> got += n to a; null }
        val url = assertNotNull(server.startAndGetUrl())
        try {
            assertTrue(url.startsWith("http://127.0.0.1:"))
            assertEquals(200, get(url))
            assertEquals(403, get(url.substringBefore("?c=") + "?c=wrong"))
            val code = server.code
            val port = URL(url).port
            assertEquals(200, post("http://127.0.0.1:$port/?c=$code", "address=https%3A%2F%2Fmysite.nl&name=Mine"))
            assertEquals(listOf("Mine" to "https://mysite.nl"), got)
            // the code is spent after a successful add
            assertEquals(403, get(url))
        } finally { server.stop() }
        assertNull(null)
    }

    @Test fun lanServerShowsErrorAndKeepsCode() {
        val server = LanEntryServer(hostProvider = { "127.0.0.1" }) { _, _ -> "خطا" }
        val url = assertNotNull(server.startAndGetUrl())
        try {
            assertEquals(200, post(url, "address=x"))
            assertEquals(200, get(url))
        } finally { server.stop() }
    }

    private fun get(u: String): Int {
        val c = URL(u).openConnection() as HttpURLConnection
        return try { c.responseCode } finally { c.disconnect() }
    }

    private fun post(u: String, body: String): Int {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        return try { c.responseCode } finally { c.disconnect() }
    }
}
