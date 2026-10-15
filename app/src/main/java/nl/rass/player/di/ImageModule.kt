package nl.rass.player.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.data.feed.FeedUrls
import nl.rass.player.data.source.MediaAuthHeaders
import nl.rass.player.data.source.Source
import nl.rass.player.data.source.SourceRepository
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.Image
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Path.Companion.toOkioPath

/** OkHttpClient for media requests (images, trailers): the shared client + per-source access headers per hop. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MediaHttpClient

/**
 * Decides which source's access header (feed-spec §14) a media URL gets, for URLs whose source is not known
 * at the call site (Coil image requests, trailers).
 *
 * A source qualifies for a URL when the URL's origin is the origin of its feed URL, or — only with
 * "Also send to media servers" — the origin of a URL that is attributable to that source's own feed (its
 * streams, subtitles, and the artwork of titles only that source carries). The final decision (https rule,
 * origin rule) stays with [MediaAuthHeaders]. Sources without a credential are never indexed.
 */
@Singleton
class MediaSourceResolver @Inject constructor(
    catalogRepository: CatalogRepository,
    sourceRepository: SourceRepository,
    private val mediaAuthHeaders: MediaAuthHeaders,
    @AppScope scope: CoroutineScope,
) {
    @Volatile private var index: Map<FeedUrls.Origin, List<String>> = emptyMap()

    init {
        scope.launch {
            combine(catalogRepository.catalog, sourceRepository.enabledSources) { c, s -> build(c, s) }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { index = it }
        }
    }

    /** Access headers for [url], or empty. Cheap; safe from any thread. */
    fun headersFor(url: String): Map<String, String> {
        val idx = index
        if (idx.isEmpty()) return emptyMap()
        val origin = FeedUrls.origin(url) ?: return emptyMap()
        for (sourceId in idx[origin] ?: return emptyMap()) {
            val h = mediaAuthHeaders.headersFor(sourceId, url)
            if (h.isNotEmpty()) return h
        }
        return emptyMap()
    }

    /** Header names any configured source may add (so a redirect hop can drop them). */
    @Volatile var headerNames: Set<String> = emptySet()
        private set

    private fun build(catalog: Catalog, sources: List<Source>): Map<FeedUrls.Origin, List<String>> {
        val withAuth = sources.filter { !it.auth.isEmpty }
        headerNames = withAuth.mapNotNullTo(HashSet()) { it.auth.header()?.first?.lowercase() }
        if (withAuth.isEmpty()) return emptyMap()
        val out = LinkedHashMap<FeedUrls.Origin, MutableList<String>>()
        fun put(origin: FeedUrls.Origin?, id: String) {
            if (origin == null) return
            val l = out.getOrPut(origin) { ArrayList(2) }
            if (id !in l) l.add(id)
        }
        fun putUrl(url: String?, id: String) = url?.let { put(FeedUrls.origin(it), id) }
        fun putImage(img: Image?, id: String) {
            if (img == null) return
            putUrl(img.url, id)
            img.sizes.forEach { putUrl(it.url, id) }
            img.template?.let { t -> img.widths.firstOrNull()?.let { putUrl(t.replace("{width}", it.toString()), id) } }
        }
        for (s in withAuth) {
            put(FeedUrls.origin(s.url), s.id)
            if (!s.auth.sendToMediaHosts) continue
            for (t in catalog.titles) {
                if (t.sources.none { it.sourceId == s.id }) continue
                t.streams.forEach { if (it.sourceId == s.id) putUrl(it.stream.url, s.id) }
                t.subtitles.forEach { if (it.sourceId == s.id) putUrl(it.url, s.id) }
                for (e in t.episodes) {
                    e.streams.forEach { if (it.sourceId == s.id) putUrl(it.stream.url, s.id) }
                    e.subtitles.forEach { if (it.sourceId == s.id) putUrl(it.url, s.id) }
                }
                // Artwork is merged field by field, so it is attributable only when this source is the sole one.
                if (t.sources.all { it.sourceId == s.id }) {
                    putImage(t.meta.poster, s.id); putImage(t.meta.backdrop, s.id); putImage(t.meta.logo, s.id)
                    t.meta.cast.forEach { putImage(it.photo, s.id) }
                    t.meta.trailer?.let { putUrl(it.url, s.id) }
                    t.episodes.forEach { putImage(it.still, s.id) }
                }
            }
        }
        return out
    }
}

/**
 * Network interceptor (runs for every redirect hop): sets the access header the hop's URL qualifies for and
 * removes any access header it does not qualify for, so a redirect that leaves the origin drops it (§14).
 */
class MediaAuthInterceptor(private val resolver: MediaSourceResolver) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val names = resolver.headerNames
        if (names.isEmpty()) return chain.proceed(req)
        val want = resolver.headersFor(req.url.toString())
        val b = req.newBuilder()
        names.forEach { n -> if (want.keys.none { it.equals(n, ignoreCase = true) }) b.removeHeader(n) }
        want.forEach { (k, v) -> b.header(k, v) }
        return chain.proceed(b.build())
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    @Provides @Singleton @MediaHttpClient
    fun mediaHttpClient(client: OkHttpClient, resolver: MediaSourceResolver): OkHttpClient =
        // Derived from the shared client (same proxy, kill switch, pool). No OkHttp cache: Coil keeps its own.
        client.newBuilder().cache(null).addNetworkInterceptor(MediaAuthInterceptor(resolver)).build()

    /** The app's single Coil ImageLoader (installed as Coil's singleton in MainActivity). */
    @Provides @Singleton
    fun imageLoader(@ApplicationContext c: Context, @MediaHttpClient client: OkHttpClient): ImageLoader =
        ImageLoader.Builder(c)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            // ~2 GB TV: keep the decoded-bitmap cache small.
            .memoryCache { MemoryCache.Builder().maxSizePercent(c, 0.15).build() }
            .diskCache {
                DiskCache.Builder().directory(c.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_BYTES).build()
            }
            .build()

    private const val IMAGE_DISK_BYTES = 150L * 1024 * 1024
}
