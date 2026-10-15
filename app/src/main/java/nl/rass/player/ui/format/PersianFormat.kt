package nl.rass.player.ui.format

private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

/** Replaces ASCII digits with Persian digits. Use for every number shown in the UI. */
fun String.toPersianDigits(): String = buildString(length) {
    for (c in this@toPersianDigits) append(if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c)
}

fun Int.fa(): String = toString().toPersianDigits()
fun Long.fa(): String = toString().toPersianDigits()

/** 5_025_000 ms → «۱:۲۳:۴۵», 125_000 → «۲:۰۵». */
fun formatDurationFa(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    val raw = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    return raw.toPersianDigits()
}
