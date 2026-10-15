package nl.rass.player.data.feed

/**
 * Fatal problems with a feed or a fetch, with the Persian message shown in Settings → Sources (§10.1, §13).
 */
enum class FeedErrorKind(val messageFa: String) {
    NOT_JSON("پاسخ این نشانی یک فید JSON معتبر نیست."),
    HTML_PAGE("این نشانی یک صفحهٔ وب است، نه فید Rass_player."),
    UNSUPPORTED_VERSION("این فید به نسخهٔ جدیدتری از Rass_player نیاز دارد."),
    MISSING_TITLES("فید فهرست «titles» ندارد."),
    NOT_A_MAIN_FEED("این سند بخشی از یک فید است، نه خود فید."),
    NOT_A_PART("سند بخش (part) معتبر نیست."),
    TOO_LARGE("حجم فید بیش از ۲۰ مگابایت است."),
    HTTP("سرور خطا برگرداند."),
    NEEDS_TOKEN("این سایت به توکن دسترسی نیاز دارد."),
    NETWORK("اتصال به سرور برقرار نشد."),
    INSECURE_REDIRECT("سرور به یک نشانی ناامن (http) هدایت کرد."),
    TOO_MANY_REDIRECTS("تعداد هدایت‌ها (redirect) بیش از حد است."),
    INVALID_ADDRESS("نشانی معتبر نیست. فقط نشانی‌های http و https پذیرفته می‌شوند."),
    NOT_FOUND("فید Rass_player در این سایت پیدا نشد. راهنمای راه‌اندازی سایت را ببینید."),
    ASSET_MISSING("فایل این منبع آزمایشی در برنامه پیدا نشد."),
    NO_CACHE("هنوز نسخه‌ای از این منبع ذخیره نشده است."),
}

data class FeedError(val kind: FeedErrorKind, val detail: String? = null, val httpCode: Int? = null) {
    val messageFa: String
        get() = when {
            kind == FeedErrorKind.HTTP && httpCode != null -> "سرور خطای $httpCode برگرداند."
            else -> kind.messageFa
        }

    override fun toString(): String = "$kind${httpCode?.let { " $it" } ?: ""}${detail?.let { ": $it" } ?: ""}"
}

class FeedException(val error: FeedError) : Exception(error.toString())

/** One entry of the report (§10.2): `JSON path: message`. */
data class FeedIssue(val path: String, val message: String, val severity: Severity) {
    enum class Severity { SKIPPED, WARNING, PART_ERROR }

    override fun toString() = "$path: $message"
}

/** Mutable collector used while validating; keeps the first [MAX_ISSUES] issues. */
class FeedIssues {
    var skipped = 0; private set
    var warnings = 0; private set
    var partErrors = 0; private set
    private val list = ArrayList<FeedIssue>()
    val issues: List<FeedIssue> get() = list

    fun skip(path: String, message: String) { skipped++; add(FeedIssue(path, message, FeedIssue.Severity.SKIPPED)) }
    fun warn(path: String, message: String) { warnings++; add(FeedIssue(path, message, FeedIssue.Severity.WARNING)) }
    fun partError(path: String, message: String) { partErrors++; add(FeedIssue(path, message, FeedIssue.Severity.PART_ERROR)) }

    fun addAll(other: FeedIssues) {
        skipped += other.skipped; warnings += other.warnings; partErrors += other.partErrors
        other.list.forEach(::add)
    }

    private fun add(i: FeedIssue) { if (list.size < MAX_ISSUES) list.add(i) }

    companion object { const val MAX_ISSUES = 50 }
}

/** Result of loading one source (§10.2). */
data class FeedReport(
    val movies: Int,
    val series: Int,
    val episodes: Int,
    val skipped: Int,
    val warnings: Int,
    val partErrors: Int,
    val partsLoaded: Int,
    val partsTotal: Int,
    val issues: List<FeedIssue>,
) {
    val titles: Int get() = movies + series
    val fullyLoaded: Boolean get() = partsLoaded == partsTotal
}
