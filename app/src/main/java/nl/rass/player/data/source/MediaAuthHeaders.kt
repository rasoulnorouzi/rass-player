package nl.rass.player.data.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Access headers for media requests (streams, subtitles, images) of a given source, for the player and the
 * image loader (feed-spec §14). The URL must come from that source's own feed (e.g. a
 * [nl.rass.player.domain.SourcedStream] of that source): with "Also send to media servers" the token is then
 * sent to its origin; otherwise only to the feed URL's origin. Never to another source's URLs.
 */
class MediaAuthHeaders(repository: SourceRepository, scope: CoroutineScope) {
    private val sources: StateFlow<List<Source>> =
        repository.sources.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun headersFor(sourceId: String, url: String): Map<String, String> {
        val s = sources.value.firstOrNull { it.id == sourceId } ?: return emptyMap()
        val auth = s.auth
        if (auth.isEmpty) return emptyMap()
        val origins = if (auth.sendToMediaHosts) setOfNotNull(nl.rass.player.data.feed.FeedUrls.origin(url)) else emptySet()
        return AuthHeaders.headersFor(url, s.url, auth, origins)
    }
}
