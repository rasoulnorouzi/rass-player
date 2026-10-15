package nl.rass.player.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import nl.rass.player.data.cache.OnlineSubtitleClearableCache
import nl.rass.player.data.cache.UpdateClearableCache
import nl.rass.player.data.settings.DataStoreOnlineSubtitleSettings
import nl.rass.player.data.update.UpdateRepository
import nl.rass.player.domain.ClearableCache
import nl.rass.player.player.subtitle.online.DiskSubtitleCache
import nl.rass.player.player.subtitle.online.OnlineSubtitleSettings

/** Glue owned by Settings: the real online-subtitle settings and the remaining Storage-page caches. */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    fun onlineSubtitleSettings(impl: DataStoreOnlineSubtitleSettings): OnlineSubtitleSettings = impl

    @Provides @IntoSet
    fun updateClearable(repo: UpdateRepository): ClearableCache = UpdateClearableCache(repo)

    @Provides @IntoSet
    fun subtitleClearable(cache: DiskSubtitleCache): ClearableCache = OnlineSubtitleClearableCache(cache)
}
