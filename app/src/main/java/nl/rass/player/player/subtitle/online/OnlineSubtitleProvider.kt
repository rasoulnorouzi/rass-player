package nl.rass.player.player.subtitle.online

import nl.rass.player.player.subtitle.SubtitleFormat

enum class MatchedBy { ID, TITLE }

data class OnlineSubtitleResult(
    /** Provider name, see [OnlineSubtitleProvider.name]. */
    val provider: String,
    /** Provider-specific id; unique within the provider. For SubDL it is the download path, for OpenSubtitles the file id. */
    val id: String,
    /** Normalized: "fa" or "en". */
    val lang: String,
    val releaseName: String,
    val downloads: Int = 0,
    val rating: Double? = null,
    val hearingImpaired: Boolean = false,
    val fps: Double? = null,
    val matchedBy: MatchedBy = MatchedBy.TITLE,
    /** Known only when the provider says so; ZIP results are decided after unpacking. */
    val format: SubtitleFormat? = null,
    /** Requested season/episode (series), used to pick the right file inside season-pack ZIPs. */
    val season: Int? = null,
    val episode: Int? = null,
) {
    val key: String get() = "$provider:$id"
}

interface OnlineSubtitleProvider {
    val name: String

    /** Enabled in settings and has the credentials it needs. */
    suspend fun isAvailable(): Boolean

    suspend fun search(query: SubtitleQuery): List<OnlineSubtitleResult>

    /** Raw subtitle bytes (.srt/.vtt), already unzipped. */
    suspend fun download(result: OnlineSubtitleResult): ByteArray
}

class OnlineSubtitleException(
    val kind: Kind,
    /** Persian text that can be shown to the user as is. */
    val messageFa: String,
    cause: Throwable? = null,
) : java.io.IOException(messageFa, cause) {
    enum class Kind { NO_KEY, AUTH, QUOTA, RATE_LIMIT, NETWORK, BAD_RESPONSE, NOT_FOUND, UNSAFE_ZIP, TOO_LARGE }
}

internal object Lang {
    /** Maps provider language labels to "fa" / "en"; null for everything else. */
    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.lowercase()?.replace('_', '-') ?: return null
        return when {
            s == "fa" || s == "fas" || s == "per" || s.startsWith("fa-") || s.contains("persian") || s.contains("farsi") -> "fa"
            s == "en" || s == "eng" || s.startsWith("en-") || s == "english" -> "en"
            else -> null
        }
    }
}
