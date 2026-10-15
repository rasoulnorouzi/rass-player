package nl.rass.player.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable
import nl.rass.player.domain.WatchProgress

/*
 * All rows are keyed by the canonical cross-source catalog key (feed-spec §12.5):
 * `tmdb:<movie|series>:<n>`, `imdb:<id>`, or `src:<source-uuid>:<feed id>`; episodes by
 * `<title key>#s<season>e<episode>`. Movies use episode_key = "".
 * The entities are also the backup format's row types (kotlinx.serialization).
 */

@Serializable
@Entity(
    tableName = "watch_progress",
    primaryKeys = ["catalog_key", "episode_key"],
    indices = [Index("updated_at")],
)
data class WatchProgressEntity(
    @ColumnInfo(name = "catalog_key") val catalogKey: String,
    @ColumnInfo(name = "episode_key") val episodeKey: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val completed: Boolean,
    /** R11.5: removed from Continue Watching by the user; reset by the next progress save. */
    @ColumnInfo(name = "hidden_from_continue") val hiddenFromContinue: Boolean = false,
) {
    fun toDomain() = WatchProgress(catalogKey, episodeKey, positionMs, durationMs, updatedAt, completed, hiddenFromContinue)
}

@Serializable
@Entity(tableName = "my_list")
data class MyListEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "catalog_key") val catalogKey: String,
    /** "movie" | "series" (R3.2 tabs). */
    val type: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Recently played (R2.4): one row per title, the last played episode. */
@Serializable
@Entity(tableName = "play_history", indices = [Index("played_at")])
data class PlayHistoryEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "catalog_key") val catalogKey: String,
    @ColumnInfo(name = "episode_key") val episodeKey: String,
    val type: String,
    @ColumnInfo(name = "played_at") val playedAt: Long,
)

@Serializable
@Entity(tableName = "recent_searches", indices = [Index("searched_at")])
data class RecentSearchEntity(
    /** Space-insensitive normalized form (PersianTextNormalizer.compact), so variants collapse. */
    @androidx.room.PrimaryKey @ColumnInfo(name = "norm_key") val normKey: String,
    val query: String,
    @ColumnInfo(name = "searched_at") val searchedAt: Long,
)

/** Episodes the user has already seen listed, for "new episode" badges (R11.3). */
@Serializable
@Entity(tableName = "seen_episodes", primaryKeys = ["catalog_key", "episode_key"])
data class SeenEpisodeEntity(
    @ColumnInfo(name = "catalog_key") val catalogKey: String,
    @ColumnInfo(name = "episode_key") val episodeKey: String,
    @ColumnInfo(name = "seen_at") val seenAt: Long,
)

/** R6.3: sync offset per title/episode and subtitle file (key from SubtitleOffsetKey.storageKey). */
@Serializable
@Entity(tableName = "subtitle_offsets")
data class SubtitleOffsetEntity(
    @androidx.room.PrimaryKey val key: String,
    @ColumnInfo(name = "offset_ms") val offsetMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** First time the app saw a title without `added_at` (feed-spec §4 `added_at` fallback). */
@Entity(tableName = "first_seen")
data class FirstSeenEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
)
