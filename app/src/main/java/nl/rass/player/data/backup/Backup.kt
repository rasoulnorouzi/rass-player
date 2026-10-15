package nl.rass.player.data.backup

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import nl.rass.player.data.settings.AppSettings
import nl.rass.player.data.settings.SettingsRepository
import nl.rass.player.data.source.Source
import nl.rass.player.data.source.SourceRepository

/** Backup file, format documented in docs/backup-format.md (R11.2). */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: Long,
    val appVersion: String? = null,
    /** True when tokens / header values are included in plain text. */
    val includesSecrets: Boolean = false,
    val sources: List<BackupSource> = emptyList(),
    val settings: AppSettings? = null,
    val myList: List<MyListEntity> = emptyList(),
    val progress: List<WatchProgressEntity> = emptyList(),
    val history: List<PlayHistoryEntity> = emptyList(),
    val subtitleOffsets: List<SubtitleOffsetEntity> = emptyList(),
    val recentSearches: List<RecentSearchEntity> = emptyList(),
    val seenEpisodes: List<SeenEpisodeEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "rass-player-backup"
        const val VERSION = 1
    }
}

@Serializable
data class BackupSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    val token: String? = null,
    val headerName: String? = null,
    val headerValue: String? = null,
    val allowTokenToMediaHosts: Boolean = false,
    val allowTokenOverHttp: Boolean = false,
    val refreshIntervalMin: Int = 360,
    val isPreset: Boolean = false,
    val addedAt: Long = 0,
) {
    fun toSource() = Source(
        id, name, url, enabled, order, token, headerName, headerValue,
        allowTokenToMediaHosts, allowTokenOverHttp, refreshIntervalMin, isPreset, addedAt,
    )

    companion object {
        fun of(s: Source, includeSecrets: Boolean) = BackupSource(
            s.id, s.name, s.url, s.enabled, s.order,
            token = if (includeSecrets) s.token else null,
            headerName = s.customHeaderName,
            headerValue = if (includeSecrets) s.customHeaderValue else null,
            allowTokenToMediaHosts = s.allowTokenToMediaHosts, allowTokenOverHttp = s.allowTokenOverHttp,
            refreshIntervalMin = s.refreshIntervalMin, isPreset = s.isPreset, addedAt = s.addedAt,
        )
    }
}

class BackupException(val messageFa: String) : Exception(messageFa)

object BackupCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; coerceInputValues = true }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /** @throws BackupException for a file that isn't a backup, or comes from a newer app. */
    fun decode(text: String): BackupFile {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw BackupException("این فایل پشتیبان معتبر نیست.")
        val format = runCatching { obj["format"]?.jsonPrimitive?.content }.getOrNull()
        if (format != BackupFile.FORMAT) throw BackupException("این فایل پشتیبان Rass_player نیست.")
        val version = runCatching { obj["version"]?.jsonPrimitive?.int }.getOrNull() ?: 0
        if (version > BackupFile.VERSION) throw BackupException("این پشتیبان با نسخهٔ جدیدتری از برنامه ساخته شده است.")
        return runCatching { json.decodeFromJsonElement(BackupFile.serializer(), obj) }.getOrElse {
            throw BackupException("این فایل پشتیبان معتبر نیست.")
        }
    }
}

data class RestoreSummary(val sources: Int, val myList: Int, val progress: Int, val history: Int)

/**
 * Export/import of sources, settings, My List, progress, history, subtitle offsets, recent searches (R11.2).
 * Import merges: sources by id (a source without a token in the file keeps its current token), progress and
 * history keep the newer row, settings are replaced.
 */
class BackupManager(
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    private val progressDao: WatchProgressDao,
    private val myListDao: MyListDao,
    private val historyDao: PlayHistoryDao,
    private val offsetDao: SubtitleOffsetDao,
    private val searchDao: RecentSearchDao,
    private val seenDao: SeenEpisodeDao,
    private val appVersion: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun export(includeSecrets: Boolean): String = BackupCodec.encode(
        BackupFile(
            createdAt = now(),
            appVersion = appVersion,
            includesSecrets = includeSecrets,
            sources = sources.current().map { BackupSource.of(it, includeSecrets) },
            settings = settings.current(),
            myList = myListDao.all(),
            progress = progressDao.all(),
            history = historyDao.all(),
            subtitleOffsets = offsetDao.all(),
            recentSearches = searchDao.all(),
            seenEpisodes = seenDao.observeAll().first(),
        )
    )

    suspend fun import(text: String): RestoreSummary {
        val file = BackupCodec.decode(text)
        val existing = sources.current()
        val byId = existing.associateBy { it.id }
        val imported = file.sources.map { b ->
            val s = b.toSource()
            val old = byId[s.id]
            if (old != null && s.token == null && s.customHeaderValue == null) {
                s.copy(token = old.token, customHeaderValue = old.customHeaderValue)
            } else s
        }
        val importedIds = imported.map { it.id }.toSet()
        val importedUrls = imported.map { it.url }.toSet()
        val kept = existing.filter { it.id !in importedIds && it.url !in importedUrls }
        val merged = (imported.sortedBy { it.order } + kept.sortedBy { it.order }).mapIndexed { i, s -> s.copy(order = i) }
        sources.replaceAll(merged)

        file.settings?.let { settings.replace(it) }
        myListDao.upsertAll(file.myList)
        val currentProgress = progressDao.all().associateBy { it.catalogKey to it.episodeKey }
        progressDao.upsertAll(file.progress.filter { p ->
            val c = currentProgress[p.catalogKey to p.episodeKey]
            c == null || c.updatedAt < p.updatedAt
        })
        val currentHistory = historyDao.all().associateBy { it.catalogKey }
        historyDao.upsertAll(file.history.filter { h -> (currentHistory[h.catalogKey]?.playedAt ?: Long.MIN_VALUE) < h.playedAt })
        offsetDao.upsertAll(file.subtitleOffsets)
        file.recentSearches.forEach { searchDao.upsert(it) }
        if (file.seenEpisodes.isNotEmpty()) seenDao.insertAll(file.seenEpisodes)
        return RestoreSummary(imported.size, file.myList.size, file.progress.size, file.history.size)
    }
}
