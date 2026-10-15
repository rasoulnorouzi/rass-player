package nl.rass.player.data.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ZERO = "0000000000000000000000000000000000000000000000000000000000000000"

private fun manifestJson(
    code: Int = 12,
    minSdk: Int = 24,
    apks: String = """{"abi":"universal","url":"a.apk","sha256":"$ZERO","size_bytes":10}""",
    certs: String = "\"$ZERO\"",
    pkg: String = "nl.rass.player",
) = """{"rass_update_version":1,"package":"$pkg","version_code":$code,"version_name":"0.4.0","min_sdk":$minSdk,
"apks":[$apks],"signing_cert_sha256":[$certs],"release_notes_fa":"نکته","x_extra":1}"""

class ManifestParsingTest {
    @Test fun valid() {
        val m = UpdateManifestParser.parse(manifestJson())
        assertEquals(12, m.versionCode)
        assertEquals("نکته", m.releaseNotesFa)
        assertFalse(m.mandatory)
    }

    @Test fun validExampleWithColons() {
        val c = (1..32).joinToString(":") { "ab" }
        UpdateManifestParser.parse(manifestJson(certs = "\"$c\""))
    }

    @Test fun invalid() {
        listOf(
            "not json",
            manifestJson(pkg = "other.app"),
            manifestJson(minSdk = 5),
            manifestJson(code = 0),
            manifestJson(apks = ""),
            manifestJson(apks = """{"abi":"mips","url":"a","sha256":"$ZERO"}"""),
            manifestJson(apks = """{"abi":"universal","url":"a","sha256":"abc"}"""),
            manifestJson(certs = "\"zz\""),
        ).forEach { assertFailsWith<UpdateException>(it) { UpdateManifestParser.parse(it) } }
    }
}

class UpdateLogicTest {
    private fun e(abi: String) = ApkEntry(abi, "$abi.apk", ZERO)

    @Test fun versionCompare() {
        assertTrue(UpdateLogic.isNewer(2, 1))
        assertFalse(UpdateLogic.isNewer(1, 1))
        assertFalse(UpdateLogic.isNewer(1, 2))
    }

    @Test fun abiChoice() {
        val apks = listOf(e("universal"), e("armeabi-v7a"), e("arm64-v8a"))
        assertEquals("arm64-v8a", UpdateLogic.chooseApk(apks, listOf("arm64-v8a", "armeabi-v7a"))?.abi)
        assertEquals("armeabi-v7a", UpdateLogic.chooseApk(apks, listOf("x86", "armeabi-v7a"))?.abi)
        assertEquals("universal", UpdateLogic.chooseApk(apks, listOf("x86"))?.abi)
        assertNull(UpdateLogic.chooseApk(listOf(e("arm64-v8a")), listOf("x86")))
    }
}

class ApkVerifierTest {
    private class FakeInspector(val archive: Set<String>?, val installed: Set<String>) : ApkInspector {
        override fun archiveCerts(file: File) = archive
        override fun installedCerts() = installed
    }

    private fun tmp(content: String) = File.createTempFile("apk", ".apk").apply { writeText(content); deleteOnExit() }

    @Test fun sha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.ofFile(tmp("abc")))
    }

    @Test fun verifyOkAndFailures() {
        val f = tmp("abc")
        val h = Sha256.ofFile(f)
        val cert = "ab".repeat(32)
        val entry = ApkEntry("universal", "u", h.uppercase(), 3)
        ApkVerifier.verify(f, entry, listOf(cert.chunked(2).joinToString(":")), FakeInspector(setOf(cert), setOf(cert)))
        assertFailsWith<UpdateException> { ApkVerifier.verify(f, entry.copy(sha256 = ZERO), listOf(cert), FakeInspector(setOf(cert), setOf(cert))) }
        assertFailsWith<UpdateException> { ApkVerifier.verify(f, entry.copy(sizeBytes = 4), listOf(cert), FakeInspector(setOf(cert), setOf(cert))) }
        assertFailsWith<UpdateException> { ApkVerifier.verify(f, entry, listOf(ZERO), FakeInspector(setOf(cert), setOf(cert))) }
        assertFailsWith<UpdateException> { ApkVerifier.verify(f, entry, listOf(cert), FakeInspector(setOf(cert), setOf("cd".repeat(32)))) }
        assertFailsWith<UpdateException> { ApkVerifier.verify(f, entry, listOf(cert), FakeInspector(null, setOf(cert))) }
    }
}

class UpdateRepositoryTest {
    private class FakeInspector(val certs: Set<String>) : ApkInspector {
        override fun archiveCerts(file: File) = certs
        override fun installedCerts() = certs
    }

    private class FakeInstaller : ApkInstaller {
        var installed: File? = null
        override fun canRequestInstalls() = true
        override fun installPermissionIntent() = throw UnsupportedOperationException()
        override fun install(file: File) { installed = file }
    }

    private val apkBytes = "fake-apk-content"
    private val cert = "ab".repeat(32)

    private fun repo(server: MockWebServer, code: Long = 1, installer: ApkInstaller = FakeInstaller(), certs: Set<String> = setOf(cert)) =
        UpdateRepository(
            OkHttpClient(),
            DeviceInfo(code, 30, listOf("arm64-v8a")),
            FakeInspector(certs),
            installer,
            UpdateDir(File.createTempFile("upd", "").let { it.delete(); it.apply { mkdirs(); deleteOnExit() } }),
        )

    private fun enqueueManifest(server: MockWebServer, size: Int = apkBytes.length) {
        val h = Sha256.ofBytes(apkBytes.toByteArray())
        server.enqueue(MockResponse().setBody(manifestJson(apks = """{"abi":"universal","url":"a.apk","sha256":"$h","size_bytes":$size}""", certs = "\"$cert\"")))
    }

    @Test fun upToDate(): Unit = runBlocking {
        MockWebServer().use { s ->
            enqueueManifest(s)
            val r = repo(s, code = 12)
            r.check(s.url("/update.json").toString())
            assertEquals(UpdateState.UpToDate, r.state.value)
        }
    }

    @Test fun fullFlow(): Unit = runBlocking {
        MockWebServer().use { s ->
            enqueueManifest(s)
            s.enqueue(MockResponse().setBody(apkBytes))
            val inst = FakeInstaller()
            val r = repo(s, installer = inst)
            r.check(s.url("/update.json").toString())
            val a = assertIs<UpdateState.Available>(r.state.value)
            assertEquals("0.4.0", a.versionName)
            r.downloadAndVerify()
            assertEquals(UpdateState.ReadyToInstall, r.state.value)
            r.install()
            assertEquals(UpdateState.Installing, r.state.value)
            assertTrue(inst.installed!!.exists())
            r.onInstallResult(android.content.pm.PackageInstaller.STATUS_SUCCESS)
            assertEquals(UpdateState.Idle, r.state.value)
        }
    }

    @Test fun tamperedDownloadFails(): Unit = runBlocking {
        MockWebServer().use { s ->
            enqueueManifest(s)
            s.enqueue(MockResponse().setBody("other-content!!!"))
            val r = repo(s)
            r.check(s.url("/update.json").toString())
            r.downloadAndVerify()
            assertIs<UpdateState.Failed>(r.state.value)
        }
    }

    @Test fun certMismatchFails(): Unit = runBlocking {
        MockWebServer().use { s ->
            enqueueManifest(s)
            s.enqueue(MockResponse().setBody(apkBytes))
            val r = repo(s, certs = setOf("cd".repeat(32)))
            r.check(s.url("/update.json").toString())
            r.downloadAndVerify()
            assertIs<UpdateState.Failed>(r.state.value)
        }
    }

    @Test fun badManifestAndHttpError(): Unit = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("{}"))
            val r = repo(s)
            r.check(s.url("/update.json").toString())
            assertIs<UpdateState.Failed>(r.state.value)
            s.enqueue(MockResponse().setResponseCode(404))
            r.check(s.url("/update.json").toString())
            assertIs<UpdateState.Failed>(r.state.value)
        }
    }
}
