package nl.rass.player.ui.format

import java.util.Locale

/** 1_572_864 → «۱٫۵ مگابایت». */
fun formatBytesFa(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    val (v, unit) = when {
        b < 1024 -> return "${b.toString().toPersianDigits()} بایت"
        b < 1024L * 1024 -> b / 1024.0 to "کیلوبایت"
        b < 1024L * 1024 * 1024 -> b / (1024.0 * 1024) to "مگابایت"
        else -> b / (1024.0 * 1024 * 1024) to "گیگابایت"
    }
    val s = if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
    return "${s.removeSuffix(".0").replace('.', '٫').toPersianDigits()} $unit"
}

/** 120 → «۱۲۰ میلی‌ثانیه». */
fun formatLatencyFa(ms: Long): String = "${ms.fa()} میلی‌ثانیه"

/** Refresh interval in minutes → Persian label. */
fun formatIntervalFa(min: Int): String = when {
    min <= 0 -> "فقط دستی"
    min < 60 -> "هر ${min.fa()} دقیقه"
    min % 1440 == 0 -> if (min == 1440) "هر روز" else "هر ${(min / 1440).fa()} روز"
    min % 60 == 0 -> "هر ${(min / 60).fa()} ساعت"
    else -> "هر ${min.fa()} دقیقه"
}
