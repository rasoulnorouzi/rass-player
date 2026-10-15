package nl.rass.player.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.rass.player.data.backup.BackupManager
import nl.rass.player.data.cache.FeedClearableCache
import nl.rass.player.data.cache.HttpClearableCache
import nl.rass.player.data.cache.ImageClearableCache
import nl.rass.player.data.cache.SearchHistoryClearableCache
import nl.rass.player.data.cache.WatchHistoryClearableCache
import nl.rass.player.data.catalog.CatalogRepository
import nl.rass.player.data.catalog.FeedCache
import nl.rass.player.data.feed.AssetReader
import nl.rass.player.data.feed.DocumentFetcher
import nl.rass.player.data.feed.FeedLoader
import nl.rass.player.data.feed.FeedParser
import nl.rass.player.data.local.FirstSeenDao
import nl.rass.player.data.local.LibraryRepository
import nl.rass.player.data.local.MyListDao
import nl.rass.player.data.local.PlayHistoryDao
import nl.rass.player.data.local.RassDatabase
import nl.rass.player.data.local.RecentSearchDao
import nl.rass.player.data.local.RecentSearchRepository
import nl.rass.player.data.local.RoomSubtitleOffsetStore
import nl.rass.player.data.local.SeenEpisodeDao
import nl.rass.player.data.local.SubtitleOffsetDao
import nl.rass.player.data.local.WatchProgressDao
import nl.rass.player.data.local.WatchProgressRepository
import nl.rass.player.data.settings.DataStoreSettingsRepository
import kotlinx.coroutines.flow.map
import nl.rass.player.data.settings.SettingsRepository
import nl.rass.player.data.source.DataStoreSourceStore
import nl.rass.player.data.source.FeedDiscovery
import nl.rass.player.data.source.KeystoreSecretCipher
import nl.rass.player.data.source.MediaAuthHeaders
import nl.rass.player.data.source.SecretCipher
import nl.rass.player.data.source.SourceRepository
import nl.rass.player.data.source.SourceStore
import nl.rass.player.domain.ClearableCache
import nl.rass.player.player.subtitle.SubtitleOffsetStore
import okhttp3.OkHttpClient

/** App-lifetime scope for data work (SupervisorJob + Default). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SourcesDataStore
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SettingsDataStore

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- DataStore ----

    @Provides @Singleton @SourcesDataStore
    fun sourcesDataStore(@ApplicationContext c: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { c.preferencesDataStoreFile("sources") })

    @Provides @Singleton @SettingsDataStore
    fun settingsDataStore(@ApplicationContext c: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { c.preferencesDataStoreFile("settings") })

    @Provides @Singleton
    fun settingsRepository(@SettingsDataStore ds: DataStore<Preferences>): SettingsRepository = DataStoreSettingsRepository(ds)

    // ---- Room ----

    @Provides @Singleton
    fun database(@ApplicationContext c: Context): RassDatabase =
        Room.databaseBuilder(c, RassDatabase::class.java, RassDatabase.NAME).build()

    @Provides fun watchProgressDao(db: RassDatabase): WatchProgressDao = db.watchProgress()
    @Provides fun myListDao(db: RassDatabase): MyListDao = db.myList()
    @Provides fun playHistoryDao(db: RassDatabase): PlayHistoryDao = db.playHistory()
    @Provides fun recentSearchDao(db: RassDatabase): RecentSearchDao = db.recentSearches()
    @Provides fun seenEpisodeDao(db: RassDatabase): SeenEpisodeDao = db.seenEpisodes()
    @Provides fun subtitleOffsetDao(db: RassDatabase): SubtitleOffsetDao = db.subtitleOffsets()
    @Provides fun firstSeenDao(db: RassDatabase): FirstSeenDao = db.firstSeen()

    @Provides @Singleton
    fun watchProgressRepository(dao: WatchProgressDao) = WatchProgressRepository(dao)

    @Provides @Singleton
    fun libraryRepository(myList: MyListDao, history: PlayHistoryDao, seen: SeenEpisodeDao) =
        LibraryRepository(myList, history, seen)

    @Provides @Singleton
    fun recentSearchRepository(dao: RecentSearchDao) = RecentSearchRepository(dao)

    @Provides @Singleton
    fun subtitleOffsetStore(dao: SubtitleOffsetDao): SubtitleOffsetStore = RoomSubtitleOffsetStore(dao)

    // ---- feeds, sources, catalog ----

    @Provides @Singleton
    fun feedCache(@ApplicationContext c: Context): FeedCache = FeedCache(File(c.filesDir, "feed-cache"))

    @Provides @Singleton
    fun assetReader(@ApplicationContext c: Context): AssetReader = AssetReader { path -> c.assets.open(path) }

    @Provides @Singleton
    fun documentFetcher(client: OkHttpClient, assets: AssetReader) = DocumentFetcher(client, assets)

    @Provides @Singleton
    fun feedParser() = FeedParser()

    @Provides @Singleton
    fun feedLoader(fetcher: DocumentFetcher, cache: FeedCache, parser: FeedParser) = FeedLoader(fetcher, cache, parser)

    @Provides @Singleton
    fun feedDiscovery(fetcher: DocumentFetcher, parser: FeedParser) = FeedDiscovery(fetcher, parser)

    @Provides @Singleton
    fun secretCipher(): SecretCipher = KeystoreSecretCipher()

    @Provides @Singleton
    fun sourceStore(@SourcesDataStore ds: DataStore<Preferences>): SourceStore = DataStoreSourceStore(ds)

    @Provides @Singleton
    fun sourceRepository(
        store: SourceStore, cipher: SecretCipher, discovery: FeedDiscovery, loader: FeedLoader, cache: FeedCache,
    ) = SourceRepository(store, cipher, discovery, loader, cache)

    @Provides @Singleton
    fun mediaAuthHeaders(repo: SourceRepository, @AppScope scope: CoroutineScope) = MediaAuthHeaders(repo, scope)

    @Provides @Singleton
    fun catalogRepository(
        sources: SourceRepository, loader: FeedLoader, firstSeen: FirstSeenDao, @AppScope scope: CoroutineScope,
        settings: SettingsRepository,
    ) = CatalogRepository(
        sources, loader, firstSeen, scope,
        contentFilter = settings.settings.map { it.contentFilter },
    )

    @Provides @Singleton
    fun backupManager(
        @ApplicationContext c: Context,
        sources: SourceRepository, settings: SettingsRepository,
        progress: WatchProgressDao, myList: MyListDao, history: PlayHistoryDao,
        offsets: SubtitleOffsetDao, searches: RecentSearchDao, seen: SeenEpisodeDao,
    ) = BackupManager(
        sources, settings, progress, myList, history, offsets, searches, seen,
        appVersion = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull(),
    )

    // ---- Settings → Storage (R9.2) ----

    @Provides @IntoSet fun feedClearable(cache: FeedCache): ClearableCache = FeedClearableCache(cache)
    @Provides @IntoSet fun imageClearable(@ApplicationContext c: Context): ClearableCache = ImageClearableCache(c)
    @Provides @IntoSet fun httpClearable(client: OkHttpClient): ClearableCache = HttpClearableCache(client)
    @Provides @IntoSet fun searchClearable(dao: RecentSearchDao): ClearableCache = SearchHistoryClearableCache(dao)
    @Provides @IntoSet fun watchHistoryClearable(dao: PlayHistoryDao): ClearableCache = WatchHistoryClearableCache(dao)
}
