package nl.rass.player.data.feed

/**
 * Date parsing of feed-spec §2.3 without java.time (minSdk 24 has no java.time without desugaring).
 * Returns epoch milliseconds (UTC) or null.
 */
object FeedDates {
    private val DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    private val DATE_TIME =
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})[Tt](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?([Zz]|[+\\-]\\d{2}:\\d{2})$")

    fun parseDate(s: String): Long? {
        val m = DATE.matchEntire(s) ?: return null
        val (y, mo, d) = m.destructured
        return epochDayMillis(y.toInt(), mo.toInt(), d.toInt())
    }

    fun parseDateTime(s: String): Long? {
        val m = DATE_TIME.matchEntire(s) ?: return null
        val g = m.groupValues
        val day = epochDayMillis(g[1].toInt(), g[2].toInt(), g[3].toInt()) ?: return null
        val h = g[4].toInt(); val mi = g[5].toInt(); val sec = g[6].ifEmpty { "0" }.toInt()
        if (h > 23 || mi > 59 || sec > 59) return null
        val frac = g[7].take(3).padEnd(3, '0').ifEmpty { "000" }.toInt()
        val offsetMin = when (val z = g[8]) {
            "Z", "z" -> 0
            else -> {
                val oh = z.substring(1, 3).toInt(); val om = z.substring(4, 6).toInt()
                if (oh > 23 || om > 59) return null
                (oh * 60 + om) * if (z[0] == '-') -1 else 1
            }
        }
        return day + ((h * 60L + mi) * 60 + sec) * 1000 + frac - offsetMin * 60_000L
    }

    fun parseDateOrDateTime(s: String): Long? = parseDate(s) ?: parseDateTime(s)

    private fun epochDayMillis(y: Int, m: Int, d: Int): Long? {
        if (m !in 1..12 || d < 1) return null
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val dim = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (d > dim[m - 1]) return null
        // days_from_civil (H. Hinnant)
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468
        return days * 86_400_000L
    }
}
