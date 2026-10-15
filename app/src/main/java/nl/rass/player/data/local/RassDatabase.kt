package nl.rass.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        WatchProgressEntity::class,
        MyListEntity::class,
        PlayHistoryEntity::class,
        RecentSearchEntity::class,
        SeenEpisodeEntity::class,
        SubtitleOffsetEntity::class,
        FirstSeenEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class RassDatabase : RoomDatabase() {
    abstract fun watchProgress(): WatchProgressDao
    abstract fun myList(): MyListDao
    abstract fun playHistory(): PlayHistoryDao
    abstract fun recentSearches(): RecentSearchDao
    abstract fun seenEpisodes(): SeenEpisodeDao
    abstract fun subtitleOffsets(): SubtitleOffsetDao
    abstract fun firstSeen(): FirstSeenDao

    companion object {
        const val NAME = "rass.db"
    }
}
