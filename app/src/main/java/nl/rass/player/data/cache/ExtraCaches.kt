package nl.rass.player.data.cache

import nl.rass.player.data.update.UpdateRepository
import nl.rass.player.domain.ClearableCache
import nl.rass.player.player.subtitle.online.DiskSubtitleCache

/** Downloaded update APKs (R9.2). */
class UpdateClearableCache(private val repo: UpdateRepository) : ClearableCache {
    override val id = "update_apk"
    override val labelFa = "فایل‌های دانلودشدهٔ به‌روزرسانی"
    override val order = 40
    override suspend fun sizeBytes() = repo.sizeBytes()
    override suspend fun clear() = repo.clear()
}

/** Downloaded online subtitles (R9.2). */
class OnlineSubtitleClearableCache(private val cache: DiskSubtitleCache) : ClearableCache {
    override val id = DiskSubtitleCache.ID
    override val labelFa = DiskSubtitleCache.LABEL_FA
    override val order = 50
    override suspend fun sizeBytes() = cache.sizeBytes()
    override suspend fun clear() = cache.clear()
}
