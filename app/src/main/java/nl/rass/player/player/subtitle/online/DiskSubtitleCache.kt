package nl.rass.player.player.subtitle.online

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Disk cache of downloaded (already unzipped) subtitle files, LRU by last-access time, capped at [maxBytes].
 * Exposes [sizeBytes] / [clear] so the "clear caches" page can list it (R9.2).
 * TODO(wire): implement `ClearableCache` (id = [ID], labelFa = [LABEL_FA]) + @IntoSet once that interface exists.
 */
@Singleton
class DiskSubtitleCache(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "online_subtitles"))

    val id: String get() = ID
    val labelFa: String get() = LABEL_FA

    private val lock = Any()

    suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val f = file(key)
            if (!f.isFile) return@synchronized null
            runCatching { f.readBytes().also { f.setLastModified(System.currentTimeMillis()) } }.getOrNull()
        }
    }

    suspend fun put(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (bytes.size > maxBytes) return@synchronized
            dir.mkdirs()
            runCatching { file(key).writeBytes(bytes) }
            trim()
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        synchronized(lock) { files().sumOf { it.length() } }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) { files().forEach { it.delete() } }
    }

    private fun trim() {
        var all = files().sortedBy { it.lastModified() }.toMutableList()
        var total = all.sumOf { it.length() }
        while (total > maxBytes && all.isNotEmpty()) {
            val f = all.removeAt(0)
            total -= f.length()
            f.delete()
        }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".sub") }?.toList().orEmpty()

    private fun file(key: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.sub")
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
        const val ID = "online_subtitles"
        const val LABEL_FA = "زیرنویس‌های آنلاین"
    }
}
