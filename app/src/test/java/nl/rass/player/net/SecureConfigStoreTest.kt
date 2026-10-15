package nl.rass.player.net

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses a JVM AES key instead of the Android Keystore; the blob format and store logic are the same. */
class SecureConfigStoreTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmBlobCipher { key }
    private val dir: File = Files.createTempDirectory("store").toFile()

    @Test
    fun roundTripIsEncryptedOnDisk() = runTest {
        val file = File(dir, "s.bin")
        val io = StandardTestDispatcher(testScheduler)
        val store = SecureConfigStore(file, cipher, io)
        store.update {
            it.copy(
                settings = ProxySettings(enabled = true),
                subscriptions = listOf(Subscription("1", "https://panel.example.invalid/sub/SECRET-TOKEN")),
            )
        }
        val raw = file.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse("SECRET-TOKEN" in raw)
        assertFalse("panel.example.invalid" in raw)

        val reopened = SecureConfigStore(file, cipher, io).load()
        assertTrue(reopened.settings.enabled)
        assertEquals("https://panel.example.invalid/sub/SECRET-TOKEN", reopened.subscriptions.single().url)
        assertFalse(File(dir, "s.bin.tmp").exists())
    }

    @Test
    fun tamperedOrForeignKeyDataStartsEmpty() = runTest {
        val file = File(dir, "t.bin")
        val io = StandardTestDispatcher(testScheduler)
        SecureConfigStore(file, cipher, io).update { it.copy(settings = ProxySettings(enabled = true)) }
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(ProxyStoreData(), SecureConfigStore(file, AesGcmBlobCipher { otherKey }, io).load())

        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertEquals(ProxyStoreData(), SecureConfigStore(file, cipher, io).load())
    }

    @Test
    fun aadIsBound() {
        val blob = cipher.encrypt("x".encodeToByteArray(), "a".encodeToByteArray())
        assertEquals("x", cipher.decrypt(blob, "a".encodeToByteArray()).decodeToString())
        assertFailsWith<Exception> { cipher.decrypt(blob, "b".encodeToByteArray()) }
    }
}
