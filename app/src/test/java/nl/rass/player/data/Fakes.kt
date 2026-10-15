package nl.rass.player.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import nl.rass.player.data.local.MyListDao
import nl.rass.player.data.local.MyListEntity
import nl.rass.player.data.local.PlayHistoryDao
import nl.rass.player.data.local.PlayHistoryEntity
import nl.rass.player.data.local.RecentSearchDao
import nl.rass.player.data.local.RecentSearchEntity
import nl.rass.player.data.local.SeenEpisodeDao
import nl.rass.player.data.local.SeenEpisodeEntity
import nl.rass.player.data.local.SubtitleOffsetDao
import nl.rass.player.data.local.SubtitleOffsetEntity
import nl.rass.player.data.local.WatchProgressDao
import nl.rass.player.data.local.WatchProgressEntity
import nl.rass.player.data.source.SecretCipher

/** Reversible fake: makes it visible in the stored record that the value was "encrypted". */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:").reversed() else null
}

class FakeWatchProgressDao : WatchProgressDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, WatchProgressEntity>>(emptyMap())
    override suspend fun upsert(row: WatchProgressEntity) = rows.update { it + ((row.catalogKey to row.episodeKey) to row) }
    override suspend fun upsertAll(rows: List<WatchProgressEntity>) = rows.forEach { upsert(it) }
    override suspend fun get(key: String, episodeKey: String) = rows.value[key to episodeKey]
    override fun observeTitle(key: String): Flow<List<WatchProgressEntity>> = rows.map { m -> m.values.filter { it.catalogKey == key } }
    override suspend fun forTitle(key: String) = rows.value.values.filter { it.catalogKey == key }
    override fun observeRecent(limit: Int): Flow<List<WatchProgressEntity>> =
        rows.map { m -> m.values.filter { !it.hiddenFromContinue }.sortedByDescending { it.updatedAt }.take(limit) }
    override suspend fun all() = rows.value.values.toList()
    override suspend fun hideTitle(key: String) = rows.update { m -> m.mapValues { (_, v) -> if (v.catalogKey == key) v.copy(hiddenFromContinue = true) else v } }
    override suspend fun delete(key: String, episodeKey: String) = rows.update { it - (key to episodeKey) }
    override suspend fun deleteTitle(key: String) = rows.update { m -> m.filterValues { it.catalogKey != key } }
    override suspend fun clear() = rows.update { emptyMap() }
}

class FakeMyListDao : MyListDao {
    val rows = MutableStateFlow<Map<String, MyListEntity>>(emptyMap())
    override suspend fun upsert(row: MyListEntity) = rows.update { it + (row.catalogKey to row) }
    override suspend fun upsertAll(rows: List<MyListEntity>) = rows.forEach { upsert(it) }
    override suspend fun delete(key: String) = rows.update { it - key }
    override fun observeAll() = rows.map { m -> m.values.sortedByDescending { it.addedAt } }
    override suspend fun all() = rows.value.values.sortedByDescending { it.addedAt }
    override fun observeContains(key: String) = rows.map { key in it }
    override suspend fun clear() = rows.update { emptyMap() }
}

class FakePlayHistoryDao : PlayHistoryDao {
    val rows = MutableStateFlow<Map<String, PlayHistoryEntity>>(emptyMap())
    override suspend fun upsert(row: PlayHistoryEntity) = rows.update { it + (row.catalogKey to row) }
    override suspend fun upsertAll(rows: List<PlayHistoryEntity>) = rows.forEach { upsert(it) }
    override fun observeRecent(limit: Int) = rows.map { m -> m.values.sortedByDescending { it.playedAt }.take(limit) }
    override suspend fun all() = rows.value.values.toList()
    override suspend fun delete(key: String) = rows.update { it - key }
    override suspend fun count() = rows.value.size
    override suspend fun clear() = rows.update { emptyMap() }
}

class FakeRecentSearchDao : RecentSearchDao {
    val rows = MutableStateFlow<Map<String, RecentSearchEntity>>(emptyMap())
    override suspend fun upsert(row: RecentSearchEntity) = rows.update { it + (row.normKey to row) }
    override fun observeRecent(limit: Int) = rows.map { m -> m.values.sortedByDescending { it.searchedAt }.take(limit) }
    override suspend fun all() = rows.value.values.sortedByDescending { it.searchedAt }
    override suspend fun delete(normKey: String) = rows.update { it - normKey }
    override suspend fun trim(keep: Int) = rows.update { m -> m.values.sortedByDescending { it.searchedAt }.take(keep).associateBy { it.normKey } }
    override suspend fun count() = rows.value.size
    override suspend fun clear() = rows.update { emptyMap() }
}

class FakeSeenEpisodeDao : SeenEpisodeDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, SeenEpisodeEntity>>(emptyMap())
    override suspend fun insertAll(rows: List<SeenEpisodeEntity>) =
        this.rows.update { m -> m + rows.filter { (it.catalogKey to it.episodeKey) !in m }.associateBy { it.catalogKey to it.episodeKey } }
    override fun observeAll() = rows.map { it.values.toList() }
    override suspend fun forTitle(key: String) = rows.value.values.filter { it.catalogKey == key }.map { it.episodeKey }
    override suspend fun deleteTitle(key: String) = rows.update { m -> m.filterValues { it.catalogKey != key } }
}

class FakeSubtitleOffsetDao : SubtitleOffsetDao {
    val rows = MutableStateFlow<Map<String, SubtitleOffsetEntity>>(emptyMap())
    override suspend fun upsert(row: SubtitleOffsetEntity) = rows.update { it + (row.key to row) }
    override suspend fun upsertAll(rows: List<SubtitleOffsetEntity>) = rows.forEach { upsert(it) }
    override suspend fun get(key: String) = rows.value[key]
    override fun observe(key: String) = rows.map { it[key]?.offsetMs }
    override suspend fun delete(key: String) = rows.update { it - key }
    override suspend fun all() = rows.value.values.toList()
}
