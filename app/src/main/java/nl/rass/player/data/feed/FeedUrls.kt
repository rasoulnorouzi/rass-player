package nl.rass.player.data.feed

import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * URL rules of feed-spec §2.1, plus the client-only `asset:///` scheme for preset test sources
 * (feed-spec §2.1.1): a document loaded from `asset:///…` may contain `asset:///` URLs and relative URLs
 * that resolve to the same scheme; remote documents may only contain http(s).
 */
object FeedUrls {
    const val ASSET_PREFIX = "asset:///"
    private const val MAX_LEN = 4096
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    fun isAsset(url: String): Boolean = url.startsWith(ASSET_PREFIX)

    /** Path inside the APK assets for an `asset:///` URL, or null. Rejects `..` segments. */
    fun assetPath(url: String): String? {
        if (!isAsset(url)) return null
        val path = url.removePrefix(ASSET_PREFIX).substringBefore('?').substringBefore('#')
        if (path.isEmpty() || path.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return path
    }

    /**
     * Resolves [ref] against the document URL [base]. Returns null when the result is not allowed:
     * empty, whitespace, too long, a scheme other than http/https (or `asset` inside an asset document),
     * or an http(s) URL without a host.
     */
    fun resolve(ref: String, base: String): String? {
        if (ref.isEmpty() || ref.length > MAX_LEN || ref.any { it.isWhitespace() }) return null
        val baseIsAsset = isAsset(base)
        val scheme = SCHEME.find(ref)?.value?.dropLast(1)?.lowercase()
        if (scheme != null) {
            return when (scheme) {
                "http", "https" -> ref.toHttpUrlOrNull()?.toString()
                "asset" -> if (baseIsAsset && assetPath(ref) != null) ref else null
                else -> null
            }
        }
        return if (baseIsAsset) {
            val resolved = runCatching { URI(base).resolve(ref).toString() }.getOrNull() ?: return null
            // URI.resolve collapses "asset:///" to "asset:/"; put the empty authority back.
            val fixed = if (resolved.startsWith("asset:/") && !resolved.startsWith(ASSET_PREFIX)) {
                ASSET_PREFIX + resolved.removePrefix("asset:/")
            } else resolved
            fixed.takeIf { assetPath(it) != null }
        } else {
            base.toHttpUrlOrNull()?.resolve(ref)?.toString()
        }
    }

    /** Validates a user-entered or stored source URL (http/https, or `asset:///` when [allowAsset]). */
    fun isValidSourceUrl(url: String, allowAsset: Boolean): Boolean =
        if (isAsset(url)) allowAsset && assetPath(url) != null
        else url.toHttpUrlOrNull() != null && SCHEME.find(url) != null

    data class Origin(val scheme: String, val host: String, val port: Int) {
        override fun toString() = "$scheme://$host:$port"
    }

    fun origin(url: HttpUrl): Origin = Origin(url.scheme, url.host, url.port)
    fun origin(url: String): Origin? = url.toHttpUrlOrNull()?.let(::origin)
}
