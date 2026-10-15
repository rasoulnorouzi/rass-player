package nl.rass.player.data.source

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import nl.rass.player.data.DataStoreCell

/** Persistence of [SourcesFile]. */
interface SourceStore {
    val data: Flow<SourcesFile>
    suspend fun update(transform: (SourcesFile) -> SourcesFile): SourcesFile
}

/** Preferences DataStore holding one JSON string (decision: Preferences + JSON, see MEMORY.md). */
class DataStoreSourceStore(store: DataStore<Preferences>) : SourceStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("sources_v1")

    private fun decode(s: String?): SourcesFile =
        s?.let { runCatching { json.decodeFromString(SourcesFile.serializer(), it) }.getOrNull() } ?: SourcesFile()

    private val cell = DataStoreCell(
        store,
        read = { decode(it[key]) },
        write = { p, v -> p[key] = json.encodeToString(SourcesFile.serializer(), v) },
    )

    override val data: Flow<SourcesFile> = cell.data

    override suspend fun update(transform: (SourcesFile) -> SourcesFile): SourcesFile = cell.update(transform)
}

class InMemorySourceStore(initial: SourcesFile = SourcesFile()) : SourceStore {
    private val state = MutableStateFlow(initial)
    override val data: Flow<SourcesFile> = state
    override suspend fun update(transform: (SourcesFile) -> SourcesFile): SourcesFile {
        state.update(transform)
        return state.value
    }
}
