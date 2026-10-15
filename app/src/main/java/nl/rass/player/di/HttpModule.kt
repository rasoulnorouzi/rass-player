package nl.rass.player.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import nl.rass.player.net.PlainHttpClient
import nl.rass.player.net.ProxyHttp
import nl.rass.player.net.ProxyManager
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * The app's ONE shared OkHttpClient. Every module (feeds, Coil images, subtitles, Media3 OkHttpDataSource,
 * updater) must inject this client, never build its own: it follows the private connection (R10) live,
 * including the kill switch. See docs/private-connection.md. Add app-wide settings (cache, timeouts,
 * interceptors) here, before ProxyHttp.install, instead of adding a second provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object HttpModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @PlainHttpClient plain: OkHttpClient,
        proxyManager: ProxyManager,
        @ApplicationContext context: Context,
    ): OkHttpClient =
        ProxyHttp.install(
            plain.newBuilder()
                .connectionPool(ConnectionPool())
                // HTTP disk cache (subtitles, small responses; feed-spec §15). Feeds keep their own last-good
                // copy in data/catalog/FeedCache. Listed on Settings → Storage (data/cache/HttpClearableCache).
                .cache(Cache(File(context.cacheDir, "http"), HTTP_CACHE_BYTES)),
            proxyManager,
        )

    private const val HTTP_CACHE_BYTES = 100L * 1024 * 1024
}
