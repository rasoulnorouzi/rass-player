package nl.rass.player.data.cache

import android.content.Context
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.rass.player.data.catalog.FeedCache
import nl.rass.player.data.local.PlayHistoryDao
import nl.rass.player.data.local.RecentSearchDao
import nl.rass.player.domain.ClearableCache
import okhttp3.OkHttpClient

/** Raw feed documents (feed-spec §15). Clearing it keeps the catalog in memory until the next refresh. */
class FeedClearableCache(private val cache: FeedCache) : ClearableCache {
    override val id = "feed"
    override val labelFa = "حافظهٔ فیدها"
    override val order = 10
    override suspend fun sizeBytes() = withContext(Dispatchers.IO) { cache.sizeBytes() }
    override suspend fun clear() = withContext(Dispatchers.IO) { cache.clearAll() }
}

/** Coil's disk + memory cache of posters and backdrops. */
class ImageClearableCache(private val context: Context) : ClearableCache {
    override val id = "images"
    override val labelFa = "تصاویر و پوسترها"
    override val order = 20
    override suspend fun sizeBytes() = withContext(Dispatchers.IO) {
        SingletonImageLoader.get(context).diskCache?.size ?: 0L
    }
    override suspend fun clear() = withContext(Dispatchers.IO) {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        Unit
    }
}

/** The shared OkHttpClient's HTTP cache (subtitle files and other small responses). */
class HttpClearableCache(private val client: OkHttpClient) : ClearableCache {
    override val id = "http"
    override val labelFa = "حافظهٔ موقت شبکه"
    override val order = 30
    override suspend fun sizeBytes() = withContext(Dispatchers.IO) { client.cache?.size() ?: 0L }
    override suspend fun clear() = withContext(Dispatchers.IO) { client.cache?.evictAll(); Unit }
}

class SearchHistoryClearableCache(private val dao: RecentSearchDao) : ClearableCache {
    override val id = "search_history"
    override val labelFa = "تاریخچهٔ جستجو"
    override val order = 80
    override suspend fun sizeBytes() = dao.count() * ROW_BYTES
    override suspend fun clear() = dao.clear()
}

/** "Recently played" only. My List and watch progress are not touched (R9.2). */
class WatchHistoryClearableCache(private val dao: PlayHistoryDao) : ClearableCache {
    override val id = "watch_history"
    override val labelFa = "تاریخچهٔ تماشا"
    override val order = 90
    override suspend fun sizeBytes() = dao.count() * ROW_BYTES
    override suspend fun clear() = dao.clear()
}

private const val ROW_BYTES = 128L
