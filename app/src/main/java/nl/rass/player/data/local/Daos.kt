package nl.rass.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {
    @Upsert suspend fun upsert(row: WatchProgressEntity)
    @Upsert suspend fun upsertAll(rows: List<WatchProgressEntity>)

    @Query("SELECT * FROM watch_progress WHERE catalog_key = :key AND episode_key = :episodeKey")
    suspend fun get(key: String, episodeKey: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE catalog_key = :key")
    fun observeTitle(key: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE catalog_key = :key")
    suspend fun forTitle(key: String): List<WatchProgressEntity>

    /** Candidates for Continue Watching; final filtering/dedupe in WatchRules.continueWatching. */
    @Query("SELECT * FROM watch_progress WHERE hidden_from_continue = 0 ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress")
    suspend fun all(): List<WatchProgressEntity>

    @Query("UPDATE watch_progress SET hidden_from_continue = 1 WHERE catalog_key = :key")
    suspend fun hideTitle(key: String)

    @Query("DELETE FROM watch_progress WHERE catalog_key = :key AND episode_key = :episodeKey")
    suspend fun delete(key: String, episodeKey: String)

    @Query("DELETE FROM watch_progress WHERE catalog_key = :key")
    suspend fun deleteTitle(key: String)

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}

@Dao
interface MyListDao {
    @Upsert suspend fun upsert(row: MyListEntity)
    @Upsert suspend fun upsertAll(rows: List<MyListEntity>)
    @Query("DELETE FROM my_list WHERE catalog_key = :key") suspend fun delete(key: String)
    @Query("SELECT * FROM my_list ORDER BY added_at DESC") fun observeAll(): Flow<List<MyListEntity>>
    @Query("SELECT * FROM my_list ORDER BY added_at DESC") suspend fun all(): List<MyListEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM my_list WHERE catalog_key = :key)") fun observeContains(key: String): Flow<Boolean>
    @Query("DELETE FROM my_list") suspend fun clear()
}

@Dao
interface PlayHistoryDao {
    @Upsert suspend fun upsert(row: PlayHistoryEntity)
    @Upsert suspend fun upsertAll(rows: List<PlayHistoryEntity>)
    @Query("SELECT * FROM play_history ORDER BY played_at DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<PlayHistoryEntity>>
    @Query("SELECT * FROM play_history") suspend fun all(): List<PlayHistoryEntity>
    @Query("DELETE FROM play_history WHERE catalog_key = :key") suspend fun delete(key: String)
    @Query("SELECT COUNT(*) FROM play_history") suspend fun count(): Int
    @Query("DELETE FROM play_history") suspend fun clear()
}

@Dao
interface RecentSearchDao {
    @Upsert suspend fun upsert(row: RecentSearchEntity)
    @Query("SELECT * FROM recent_searches ORDER BY searched_at DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>>
    @Query("SELECT * FROM recent_searches ORDER BY searched_at DESC") suspend fun all(): List<RecentSearchEntity>
    @Query("DELETE FROM recent_searches WHERE norm_key = :normKey") suspend fun delete(normKey: String)
    @Query("DELETE FROM recent_searches WHERE norm_key NOT IN (SELECT norm_key FROM recent_searches ORDER BY searched_at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
    @Query("SELECT COUNT(*) FROM recent_searches") suspend fun count(): Int
    @Query("DELETE FROM recent_searches") suspend fun clear()
}

@Dao
interface SeenEpisodeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<SeenEpisodeEntity>)
    @Query("SELECT * FROM seen_episodes") fun observeAll(): Flow<List<SeenEpisodeEntity>>
    @Query("SELECT episode_key FROM seen_episodes WHERE catalog_key = :key") suspend fun forTitle(key: String): List<String>
    @Query("DELETE FROM seen_episodes WHERE catalog_key = :key") suspend fun deleteTitle(key: String)
}

@Dao
interface SubtitleOffsetDao {
    @Upsert suspend fun upsert(row: SubtitleOffsetEntity)
    @Upsert suspend fun upsertAll(rows: List<SubtitleOffsetEntity>)
    @Query("SELECT * FROM subtitle_offsets WHERE `key` = :key") suspend fun get(key: String): SubtitleOffsetEntity?
    @Query("SELECT offset_ms FROM subtitle_offsets WHERE `key` = :key") fun observe(key: String): Flow<Long?>
    @Query("DELETE FROM subtitle_offsets WHERE `key` = :key") suspend fun delete(key: String)
    @Query("SELECT * FROM subtitle_offsets") suspend fun all(): List<SubtitleOffsetEntity>
}

@Dao
interface FirstSeenDao {
    @Query("SELECT * FROM first_seen") suspend fun all(): List<FirstSeenEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<FirstSeenEntity>)

    @Transaction
    suspend fun recordAndGet(keys: Collection<String>, now: Long): Map<String, Long> {
        val known = all().associate { it.itemKey to it.firstSeenAt }
        val missing = keys.filter { it !in known }
        if (missing.isEmpty()) return known
        insertAll(missing.map { FirstSeenEntity(it, now) })
        return known + missing.associateWith { now }
    }
}
