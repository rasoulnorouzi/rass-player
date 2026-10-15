package nl.rass.player.data.source

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.rass.player.data.catalog.FeedCache
import nl.rass.player.data.feed.FeedError
import nl.rass.player.data.feed.FeedErrorKind
import nl.rass.player.data.feed.FeedLoader
import nl.rass.player.data.feed.FeedReport
import nl.rass.player.data.feed.FeedUrls
import nl.rass.player.data.feed.SourceSpec

sealed interface TestConnectionResult {
    /** [report] has the counts and the first 50 issues (§10.2). */
    data class Ok(val feedName: String?, val report: FeedReport) : TestConnectionResult
    data class Failed(val error: FeedError) : TestConnectionResult {
        val messageFa: String get() = error.messageFa
    }
}

class SourceValidationException(val messageFa: String) : IllegalArgumentException(messageFa)

/**
 * Settings → Sources (R1.1–R1.6, R12.1): unlimited sources, add/edit/remove/enable/reorder, discovery and
 * "Test connection". Changing a source's URL or removing it clears its feed cache (CLAUDE.md §3).
 */
class SourceRepository(
    private val store: SourceStore,
    private val cipher: SecretCipher,
    private val discovery: FeedDiscovery,
    private val loader: FeedLoader,
    private val cache: FeedCache?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** All sources, Settings order. */
    val sources: Flow<List<Source>> = store.data.map { f -> f.sources.sortedBy { it.order }.map(::toSource) }

    val enabledSources: Flow<List<Source>> = sources.map { l -> l.filter { it.enabled } }

    suspend fun current(): List<Source> = sources.first()

    suspend fun get(id: String): Source? = current().firstOrNull { it.id == id }

    /** Adds the R12 presets once. A preset the user removed or disabled stays that way. */
    suspend fun seedPresets() = mutex.withLock {
        store.update { f ->
            val missing = PresetSources.ALL.filter { it.url !in f.seededPresets }
            if (missing.isEmpty()) return@update f
            var order = (f.sources.maxOfOrNull { it.order } ?: -1) + 1
            val added = missing.filter { p -> f.sources.none { it.url == p.url } }.map { p ->
                SourceRecord(id = p.id, name = p.name, url = p.url, order = order++, isPreset = true, addedAt = now())
            }
            f.copy(sources = f.sources + added, seededPresets = f.seededPresets + missing.map { it.url })
        }
    }

    /** @throws SourceValidationException with a Persian message. */
    suspend fun add(
        name: String,
        url: String,
        auth: SourceAuth = SourceAuth.None,
        refreshIntervalMin: Int = RefreshInterval.DEFAULT,
        enabled: Boolean = true,
    ): Source = mutex.withLock {
        val u = url.trim()
        validate(u, auth, allowAsset = false)
        val existing = current()
        if (existing.any { it.url == u }) throw SourceValidationException("این منبع قبلاً اضافه شده است.")
        val src = Source(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { u },
            url = u,
            enabled = enabled,
            order = (existing.maxOfOrNull { it.order } ?: -1) + 1,
            token = auth.bearerToken, customHeaderName = auth.headerName, customHeaderValue = auth.headerValue,
            allowTokenToMediaHosts = auth.sendToMediaHosts, allowTokenOverHttp = auth.allowOverHttp,
            refreshIntervalMin = refreshIntervalMin,
            addedAt = now(),
        )
        store.update { f -> f.copy(sources = f.sources + toRecord(src)) }
        src
    }

    /** Replaces a source (matched by id). A URL change clears that source's cache. */
    suspend fun update(source: Source) = mutex.withLock {
        val old = current().firstOrNull { it.id == source.id } ?: throw SourceValidationException("منبع پیدا نشد.")
        validate(source.url, source.auth, allowAsset = old.isPreset && source.url == old.url)
        store.update { f -> f.copy(sources = f.sources.map { if (it.id == source.id) toRecord(source) else it }) }
        if (old.url != source.url) cache?.clear(source.id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutex.withLock {
        store.update { f -> f.copy(sources = f.sources.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    suspend fun remove(id: String) = mutex.withLock {
        store.update { f -> f.copy(sources = f.sources.filterNot { it.id == id }) }
        cache?.clear(id)
    }

    /** Removes every preset test source (R12) and clears their caches. Own sources are untouched. */
    suspend fun removeAllPresets(): Int = mutex.withLock {
        val gone = current().filter { it.isPreset }
        if (gone.isNotEmpty()) store.update { f -> f.copy(sources = f.sources.filterNot { it.isPreset }) }
        gone.forEach { cache?.clear(it.id) }
        gone.size
    }

    /** Brings back the preset test sources that are missing (enabled, at the end). Returns how many came back. */
    suspend fun restorePresets(): Int = mutex.withLock {
        var count = 0
        store.update { f ->
            val missing = PresetSources.ALL.filter { p -> f.sources.none { it.url == p.url } }
            count = missing.size
            if (missing.isEmpty()) return@update f
            var order = (f.sources.maxOfOrNull { it.order } ?: -1) + 1
            val added = missing.map { p ->
                SourceRecord(id = p.id, name = p.name, url = p.url, order = order++, isPreset = true, addedAt = now())
            }
            f.copy(sources = f.sources + added, seededPresets = (f.seededPresets + missing.map { it.url }).distinct())
        }
        count
    }

    /** Moves source [id] to position [toIndex] in Settings order. */
    suspend fun move(id: String, toIndex: Int) = mutex.withLock {
        store.update { f ->
            val list = f.sources.sortedBy { it.order }.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return@update f
            val item = list.removeAt(i)
            list.add(toIndex.coerceIn(0, list.size), item)
            f.copy(sources = list.mapIndexed { idx, r -> r.copy(order = idx) })
        }
    }

    /** Sets the full order; ids not listed keep their relative order after the listed ones. */
    suspend fun reorder(ids: List<String>) = mutex.withLock {
        store.update { f ->
            val sorted = f.sources.sortedBy { it.order }
            val listed = ids.mapNotNull { id -> sorted.firstOrNull { it.id == id } }
            val rest = sorted.filterNot { it.id in ids }
            f.copy(sources = (listed + rest).mapIndexed { idx, r -> r.copy(order = idx) })
        }
    }

    /** Replace all sources (backup restore). */
    suspend fun replaceAll(sources: List<Source>) = mutex.withLock {
        val old = current()
        store.update { f -> f.copy(sources = sources.map(::toRecord), seededPresets = (f.seededPresets + PresetSources.ALL.map { it.url }).distinct()) }
        old.filter { o -> sources.none { it.id == o.id && it.url == o.url } }.forEach { cache?.clear(it.id) }
    }

    suspend fun discover(address: String, auth: SourceAuth = SourceAuth.None): DiscoveryResult = discovery.discover(address, auth)

    /** R1.2: loads the feed (and its parts) fresh, without touching the cache. */
    suspend fun testConnection(url: String, auth: SourceAuth = SourceAuth.None): TestConnectionResult {
        val u = url.trim()
        if (!FeedUrls.isValidSourceUrl(u, allowAsset = FeedUrls.isAsset(u))) {
            return TestConnectionResult.Failed(FeedError(FeedErrorKind.INVALID_ADDRESS))
        }
        val r = loader.refresh(SourceSpec("test", "test", u, auth), useCache = false)
        val feed = r.feed
        return if (r.error != null || feed == null) TestConnectionResult.Failed(r.error ?: FeedError(FeedErrorKind.NOT_JSON))
        else TestConnectionResult.Ok(feed.catalog.name, feed.report)
    }

    private fun validate(url: String, auth: SourceAuth, allowAsset: Boolean) {
        if (!FeedUrls.isValidSourceUrl(url, allowAsset)) throw SourceValidationException(FeedErrorKind.INVALID_ADDRESS.messageFa)
        val hn = auth.headerName
        if (!hn.isNullOrBlank()) AuthHeaders.validateHeaderName(hn)?.let { throw SourceValidationException(it.messageFa) }
    }

    private fun toSource(r: SourceRecord) = Source(
        id = r.id, name = r.name, url = r.url, enabled = r.enabled, order = r.order,
        token = r.tokenEnc?.let(cipher::decrypt),
        customHeaderName = r.headerName,
        customHeaderValue = r.headerValueEnc?.let(cipher::decrypt),
        allowTokenToMediaHosts = r.allowTokenToMediaHosts, allowTokenOverHttp = r.allowTokenOverHttp,
        refreshIntervalMin = r.refreshIntervalMin, isPreset = r.isPreset, addedAt = r.addedAt,
    )

    private fun toRecord(s: Source) = SourceRecord(
        id = s.id, name = s.name, url = s.url, enabled = s.enabled, order = s.order,
        tokenEnc = s.token?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt),
        headerName = s.customHeaderName?.trim()?.takeIf { it.isNotEmpty() },
        headerValueEnc = s.customHeaderValue?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt),
        allowTokenToMediaHosts = s.allowTokenToMediaHosts, allowTokenOverHttp = s.allowTokenOverHttp,
        refreshIntervalMin = s.refreshIntervalMin, isPreset = s.isPreset, addedAt = s.addedAt,
    )
}
