package nl.rass.player.player.subtitle.online

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import nl.rass.player.player.subtitle.SubtitleDecoder
import nl.rass.player.player.subtitle.SubtitleFormat
import nl.rass.player.player.subtitle.SubtitleTrack
import okhttp3.OkHttpClient

data class ProviderError(val provider: String, val error: OnlineSubtitleException)

data class OnlineSearchOutcome(
    val results: List<OnlineSubtitleResult>,
    val errors: List<ProviderError> = emptyList(),
)

data class DownloadedSubtitle(val result: OnlineSubtitleResult, val text: String, val format: SubtitleFormat) {
    fun toTrack(): SubtitleTrack = SubtitleTrack.parse(text, format)
}

/** Searches the enabled providers in parallel, ranks the merge and caches searches (memory, 1 h) and files (disk). */
@Singleton
class OnlineSubtitleRepository(
    private val providers: List<OnlineSubtitleProvider>,
    private val settings: OnlineSubtitleSettings,
    private val diskCache: DiskSubtitleCache,
    private val searchTimeoutMs: Long = 10_000,
    private val downloadTimeoutMs: Long = 20_000,
    private val ttlMs: Long = 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        client: OkHttpClient,
        settings: OnlineSubtitleSettings,
        diskCache: DiskSubtitleCache,
    ) : this(listOf(SubDlProvider(client, settings), OpenSubtitlesProvider(client, settings)), settings, diskCache)

    private class Cached(val at: Long, val outcome: OnlineSearchOutcome)

    private val searchCache = HashMap<String, Cached>()

    suspend fun search(query: SubtitleQuery, forceRefresh: Boolean = false): OnlineSearchOutcome {
        val key = query.cacheKey
        if (!forceRefresh) synchronized(searchCache) { searchCache[key] }?.let { if (clock() - it.at < ttlMs) return it.outcome }

        val active = providers.filter { runCatching { it.isAvailable() }.getOrDefault(false) }
        if (active.isEmpty()) {
            return OnlineSearchOutcome(
                emptyList(),
                listOf(ProviderError("-", OnlineSubtitleException(OnlineSubtitleException.Kind.NO_KEY, "جستجوی آنلاین زیرنویس فعال نیست؛ در تنظیمات کلید API را وارد کنید."))),
            )
        }
        val parts = coroutineScope {
            active.map { p ->
                async {
                    try {
                        val r = withTimeoutOrNull(searchTimeoutMs) { p.search(query) }
                        if (r == null) {
                            Result.failure(OnlineSubtitleException(OnlineSubtitleException.Kind.NETWORK, "پاسخ ${p.name} طول کشید."))
                        } else {
                            Result.success(r)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OnlineSubtitleException) {
                        Result.failure(e)
                    } catch (e: Exception) {
                        Result.failure(OnlineSubtitleException(OnlineSubtitleException.Kind.NETWORK, "خطا در ${p.name}.", e))
                    }.let { p.name to it }
                }
            }.awaitAll()
        }
        val merged = parts.flatMap { it.second.getOrNull().orEmpty() }
        val errors = parts.mapNotNull { (n, r) -> (r.exceptionOrNull() as? OnlineSubtitleException)?.let { ProviderError(n, it) } }
        val ranked = OnlineSubtitleRanking.rank(merged, query, settings.current().preferEnglish)
        val outcome = OnlineSearchOutcome(ranked, errors)
        if (errors.size < parts.size) synchronized(searchCache) { searchCache[key] = Cached(clock(), outcome) }
        return outcome
    }

    /** Downloads (or reads from the disk cache) and decodes a result. */
    suspend fun fetch(result: OnlineSubtitleResult): DownloadedSubtitle {
        val key = result.key + "|" + result.season + "|" + result.episode
        val bytes = diskCache.get(key) ?: run {
            val provider = providers.firstOrNull { it.name == result.provider }
                ?: throw OnlineSubtitleException(OnlineSubtitleException.Kind.NOT_FOUND, "سرویس زیرنویس ناشناخته است.")
            val b = withTimeoutOrNull(downloadTimeoutMs) { provider.download(result) }
                ?: throw OnlineSubtitleException(OnlineSubtitleException.Kind.NETWORK, "دانلود زیرنویس طول کشید.")
            diskCache.put(key, b)
            b
        }
        val text = SubtitleDecoder.decode(bytes)
        return DownloadedSubtitle(result, text, result.format ?: SubtitleFormat.detect(text = text))
    }

    fun clearSearchCache() = synchronized(searchCache) { searchCache.clear() }
}
