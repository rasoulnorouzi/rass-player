package nl.rass.player.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExitInfoParserTest {
    // Documentation addresses (RFC 5737 / RFC 3849), never real ones.
    @Test fun traceIpAndCountry() {
        val body = "fl=123f\nh=www.cloudflare.com\nip=203.0.113.7\nts=1700000000.1\nvisit_scheme=https\nuag=okhttp\ncolo=AMS\nloc=NL\ntls=TLSv1.3\nwarp=off\n"
        assertEquals(ExitInfo("203.0.113.7", "NL"), ExitInfoParser.parseTrace(body))
    }

    @Test fun traceIpv6AndUnknownCountry() {
        assertEquals(ExitInfo("2001:db8::1", null), ExitInfoParser.parseTrace("ip=2001:db8::1\r\nloc=XX\r\n"))
        assertEquals(ExitInfo("2001:db8::1", null), ExitInfoParser.parseTrace("ip=2001:db8::1"))
    }

    @Test fun traceWithoutIpIsNull() {
        assertNull(ExitInfoParser.parseTrace("<html>blocked</html>"))
        assertNull(ExitInfoParser.parseTrace("ip=\nloc=NL"))
        assertNull(ExitInfoParser.parseTrace(""))
    }

    @Test fun ipify() {
        assertEquals(ExitInfo("198.51.100.4", null), ExitInfoParser.parseIpify("""{"ip":"198.51.100.4"}"""))
        assertNull(ExitInfoParser.parseIpify("not json"))
        assertNull(ExitInfoParser.parseIpify("""{"ip":""}"""))
    }
}
