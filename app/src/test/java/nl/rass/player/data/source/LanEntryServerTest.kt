package nl.rass.player.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanEntryServerTest {
    private fun p(vararg kv: Pair<String, String>) = kv.associate { it.first to listOf(it.second) }
    private fun eval(mode: LanMode, post: Boolean, vararg kv: Pair<String, String>, used: Boolean = false) =
        LanEntryServer.evaluate(mode, "abc", used, post, p(*kv))

    @Test fun wrongOrUsedCodeIsForbidden() {
        assertEquals(PhoneRequest.Forbidden, eval(LanMode.TEXT, false, "c" to "zzz"))
        assertEquals(PhoneRequest.Forbidden, eval(LanMode.TEXT, false))
        assertEquals(PhoneRequest.Forbidden, eval(LanMode.TEXT, true, "c" to "abc", "value" to "x", used = true))
    }

    @Test fun getShowsForm() {
        assertEquals(PhoneRequest.ShowForm, eval(LanMode.SOURCE, false, "c" to "abc"))
    }

    @Test fun textKeepsInnerSpacesAndTrims() {
        val r = eval(LanMode.TEXT, true, "c" to "abc", "value" to "  the dark  knight ")
        assertEquals(PhoneRequest.Submit("", "the dark  knight"), r)
    }

    @Test fun textRejectsEmptyAndTooLong() {
        assertTrue(eval(LanMode.TEXT, true, "c" to "abc", "value" to "  ") is PhoneRequest.Invalid)
        assertTrue(eval(LanMode.TEXT, true, "c" to "abc", "value" to "a".repeat(LanEntryServer.MAX_TEXT + 1)) is PhoneRequest.Invalid)
    }

    @Test fun subscriptionNeedsHttpAndStripsWhitespace() {
        assertEquals(
            PhoneRequest.Submit("n", "https://x.io/sub?t=1"),
            eval(LanMode.SUBSCRIPTION, true, "c" to "abc", "value" to " https://x.io/sub?t=1\n", "name" to " n "),
        )
        assertTrue(eval(LanMode.SUBSCRIPTION, true, "c" to "abc", "value" to "vless://abc") is PhoneRequest.Invalid)
    }

    @Test fun legacyAddressFieldStillWorks() {
        assertEquals(PhoneRequest.Submit("", "https://s.nl"), eval(LanMode.SOURCE, true, "c" to "abc", "address" to "https://s.nl"))
    }
}
