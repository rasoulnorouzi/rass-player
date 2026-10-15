package nl.rass.player.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One value stored as a single Preferences key, served from an in-memory [MutableStateFlow].
 *
 * Why: on a fresh install, DataStore's `data` flow sometimes handed collectors that started around the
 * first write a stale (empty) value and never the written one (seen on the emulator: Home said
 * "no sources" although the presets were saved). The DataStore is now read once; afterwards every
 * collector is fed from our own state, which [update] sets from the value it just wrote.
 * Only this cell may write [read]/[write]'s key.
 */
class DataStoreCell<T : Any>(
    private val store: DataStore<Preferences>,
    private val read: (Preferences) -> T,
    private val write: (androidx.datastore.preferences.core.MutablePreferences, T) -> Unit,
) {
    private val state = MutableStateFlow<T?>(null)
    private val mutex = Mutex()

    val data: Flow<T> = flow {
        if (state.value == null) load()
        emitAll(state.filterNotNull())
    }

    private suspend fun load() = mutex.withLock {
        if (state.value == null) state.value = read(store.data.first())
    }

    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        var out: T? = null
        store.edit { p ->
            val next = transform(read(p))
            write(p, next)
            out = next
        }
        val result = out!!
        state.value = result
        result
    }
}
