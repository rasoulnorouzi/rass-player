package nl.rass.player.data.catalog

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CacheMeta(
    val url: String,
    val finalUrl: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val fetchedAt: Long,
    val maxAgeSec: Long? = null,
)

class CachedDocument(val body: ByteArray, val meta: CacheMeta)

/**
 * Last good copy of every feed document (main + parts) per source (feed-spec §15). Kept until the source
 * is deleted or its URL changes. Writes are atomic (temp file + rename). Thread-safe per file.
 */
class FeedCache(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(sourceId: String) = File(root, sourceId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun name(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun read(sourceId: String, url: String): CachedDocument? {
        val d = dir(sourceId); val n = name(url)
        val body = File(d, "$n.json"); val meta = File(d, "$n.meta")
        if (!body.isFile || !meta.isFile) return null
        return runCatching {
            val m = json.decodeFromString(CacheMeta.serializer(), meta.readText())
            if (m.url != url) null else CachedDocument(body.readBytes(), m)
        }.getOrNull()
    }

    @Synchronized
    fun write(sourceId: String, url: String, body: ByteArray, meta: CacheMeta) {
        val d = dir(sourceId).apply { mkdirs() }
        val n = name(url)
        atomicWrite(File(d, "$n.json"), body)
        atomicWrite(File(d, "$n.meta"), json.encodeToString(CacheMeta.serializer(), meta).toByteArray())
    }

    /** A 304: the copy is current again. */
    @Synchronized
    fun touch(sourceId: String, url: String, fetchedAt: Long, maxAgeSec: Long?) {
        val d = dir(sourceId); val f = File(d, "${name(url)}.meta")
        val m = runCatching { json.decodeFromString(CacheMeta.serializer(), f.readText()) }.getOrNull() ?: return
        atomicWrite(f, json.encodeToString(CacheMeta.serializer(), m.copy(fetchedAt = fetchedAt, maxAgeSec = maxAgeSec)).toByteArray())
    }

    @Synchronized
    fun clear(sourceId: String) { dir(sourceId).deleteRecursively() }

    @Synchronized
    fun clearAll() { root.listFiles()?.forEach { it.deleteRecursively() } }

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}
