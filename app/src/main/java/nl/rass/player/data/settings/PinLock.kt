package nl.rass.player.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nl.rass.player.di.SettingsDataStore

/** Pure PIN rules (R11.9): 4 digits, stored as salted, iterated SHA-256. */
object PinHasher {
    const val LENGTH = 4
    private const val ROUNDS = 5_000

    fun isValid(pin: String) = pin.length == LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): String = ByteArray(12).also { SecureRandom().nextBytes(it) }.toHex()

    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var h = md.digest("$salt:$pin".toByteArray())
        repeat(ROUNDS) { h = md.digest(h + salt.toByteArray()) }
        return h.toHex()
    }

    /** Stored form: `salt$hash`. */
    fun encode(pin: String, salt: String = newSalt()) = "$salt$${hash(pin, salt)}"

    fun verify(pin: String, stored: String?): Boolean {
        if (stored == null || !isValid(pin)) return false
        val i = stored.indexOf('$')
        if (i <= 0) return false
        return MessageDigest.isEqual(hash(pin, stored.substring(0, i)).toByteArray(), stored.substring(i + 1).toByteArray())
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/** After a correct PIN, Settings stays open for this long (so moving between its pages doesn't re-ask). */
const val PIN_UNLOCK_WINDOW_MS = 5 * 60_000L

/** Optional PIN for Settings (R11.9). The hash lives in the settings DataStore and is not part of backups. */
@Singleton
class PinLock @Inject constructor(@SettingsDataStore private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("settings_pin_v1")
    @Volatile private var unlockedAt = Long.MIN_VALUE / 2

    val hasPin: Flow<Boolean> = store.data.map { it[key] != null }

    suspend fun isLocked(now: Long = System.currentTimeMillis()): Boolean =
        store.data.first()[key] != null && now - unlockedAt > PIN_UNLOCK_WINDOW_MS

    suspend fun tryUnlock(pin: String, now: Long = System.currentTimeMillis()): Boolean {
        val ok = PinHasher.verify(pin, store.data.first()[key])
        if (ok) unlockedAt = now
        return ok
    }

    /** Keeps the window open while the user is active in Settings. */
    fun touch(now: Long = System.currentTimeMillis()) { if (now - unlockedAt <= PIN_UNLOCK_WINDOW_MS) unlockedAt = now }

    suspend fun setPin(pin: String) {
        require(PinHasher.isValid(pin))
        store.edit { it[key] = PinHasher.encode(pin) }
        unlockedAt = System.currentTimeMillis()
    }

    suspend fun clearPin() { store.edit { it.remove(key) } }
}
