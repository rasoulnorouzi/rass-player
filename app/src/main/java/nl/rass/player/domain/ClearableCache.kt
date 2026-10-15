package nl.rass.player.domain

/**
 * One entry on Settings → Storage (R9.2). Every module that keeps a cache contributes one with a Hilt
 * multibinding:
 *
 * ```
 * @Binds @IntoSet abstract fun bindMyCache(impl: MyCache): ClearableCache
 * ```
 *
 * and the Storage page injects `Set<@JvmSuppressWildcards ClearableCache>` (sort by [order]).
 */
interface ClearableCache {
    /** Stable id, e.g. "feed", "images". */
    val id: String

    /** Persian label shown in the list. */
    val labelFa: String

    /**
     * Whether "Clear all cache" includes it. User data (My List, watch progress, sources, settings) is never a
     * [ClearableCache]; it is cleared only through its own repository after a confirmation.
     */
    val includedInClearAll: Boolean get() = true

    /** Display order on the Storage page (lower first). */
    val order: Int get() = 100

    /** Current size in bytes; for database-backed entries an estimate (rows × typical row size). */
    suspend fun sizeBytes(): Long

    suspend fun clear()
}
