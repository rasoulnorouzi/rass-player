package nl.rass.player.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Failure with a Persian message that can be shown on screen as is. */
class UpdateException(val reasonFa: String, cause: Throwable? = null) : Exception(reasonFa, cause)

/** `update.json` v1 (docs/feed-spec.md section 16). Unknown keys (including `x_*`) are ignored. */
@Serializable
data class UpdateManifest(
    @SerialName("rass_update_version") val rassUpdateVersion: Int,
    val `package`: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    @SerialName("min_sdk") val minSdk: Int,
    @SerialName("released_at") val releasedAt: String? = null,
    val apks: List<ApkEntry>,
    @SerialName("signing_cert_sha256") val signingCertSha256: List<String>,
    @SerialName("release_notes_fa") val releaseNotesFa: String,
    @SerialName("release_notes_en") val releaseNotesEn: String? = null,
    val mandatory: Boolean = false,
)

@Serializable
data class ApkEntry(
    val abi: String,
    val url: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

object UpdateManifestParser {
    const val PACKAGE = "nl.rass.player"
    private val KNOWN_ABIS = setOf("universal", "armeabi-v7a", "arm64-v8a", "x86_64", "x86")
    private val HEX64 = Regex("^[0-9A-Fa-f]{64}$")
    private val HEX_COLON = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){31}$")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): UpdateManifest {
        val m = try {
            json.decodeFromString(UpdateManifest.serializer(), text)
        } catch (e: Exception) {
            throw UpdateException("فایل به‌روزرسانی معتبر نیست.", e)
        }
        validate(m)
        return m
    }

    private fun bad(what: String): Nothing = throw UpdateException("فایل به‌روزرسانی معتبر نیست ($what).")

    private fun validate(m: UpdateManifest) {
        if (m.rassUpdateVersion != 1) bad("نسخه قالب")
        if (m.`package` != PACKAGE) bad("نام بسته")
        if (m.versionCode < 1) bad("version_code")
        if (m.versionName.isBlank()) bad("version_name")
        if (m.minSdk !in 21..100) bad("min_sdk")
        if (m.apks.isEmpty() || m.apks.size > 5) bad("apks")
        for (a in m.apks) {
            if (a.abi !in KNOWN_ABIS) bad("abi")
            if (a.url.isBlank()) bad("url")
            if (!HEX64.matches(a.sha256)) bad("sha256")
            if (a.sizeBytes != null && a.sizeBytes < 1) bad("size_bytes")
        }
        if (m.signingCertSha256.isEmpty() || m.signingCertSha256.size > 5) bad("signing_cert_sha256")
        if (m.signingCertSha256.any { !HEX64.matches(it) && !HEX_COLON.matches(it) }) bad("signing_cert_sha256")
    }
}

/** Pure decision logic, kept free of Android types for unit tests. */
object UpdateLogic {
    fun isNewer(remoteVersionCode: Long, installedVersionCode: Long) = remoteVersionCode > installedVersionCode

    /** First device ABI (most preferred first) that has an entry, else `universal`, else null. First duplicate wins. */
    fun chooseApk(apks: List<ApkEntry>, supportedAbis: List<String>): ApkEntry? {
        for (abi in supportedAbis) apks.firstOrNull { it.abi == abi }?.let { return it }
        return apks.firstOrNull { it.abi == "universal" }
    }

    fun normalizeHex(s: String): String = s.replace(":", "").lowercase()
}
