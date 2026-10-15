package nl.rass.player.data.update

import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What the repository needs to know about this device/build; injectable for tests. */
data class DeviceInfo(
    val installedVersionCode: Long,
    val sdkInt: Int,
    val supportedAbis: List<String>,
)

@Singleton
class UpdateRepository @Inject constructor(
    private val client: OkHttpClient,
    private val device: DeviceInfo,
    private val inspector: ApkInspector,
    private val installer: ApkInstaller,
    private val updateDir: UpdateDir,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var pending: Pending? = null
    private var downloaded: File? = null

    private class Pending(val manifest: UpdateManifest, val apk: ApkEntry, val apkUrl: String)

    /** Fetches and evaluates update.json; moves state to UpToDate, Available or Failed. */
    suspend fun check(updateUrl: String) {
        _state.value = UpdateState.Checking
        pending = null
        try {
            val base = updateUrl.trim().toHttpUrlOrNull()
                ?: throw UpdateException("نشانی به‌روزرسانی معتبر نیست.")
            val text = withContext(Dispatchers.IO) { fetchText(base.toString()) }
            val m = UpdateManifestParser.parse(text)
            if (!UpdateLogic.isNewer(m.versionCode, device.installedVersionCode)) {
                _state.value = UpdateState.UpToDate
                return
            }
            if (device.sdkInt < m.minSdk) {
                throw UpdateException("این نسخه برای نسخهٔ اندروید این دستگاه ساخته نشده است.")
            }
            val apk = UpdateLogic.chooseApk(m.apks, device.supportedAbis)
                ?: throw UpdateException("برای این دستگاه به‌روزرسانی موجود نیست.")
            val url = base.resolve(apk.url)?.toString()
                ?: throw UpdateException("نشانی فایل نصب معتبر نیست.")
            pending = Pending(m, apk, url)
            _state.value = UpdateState.Available(m.versionName, m.releaseNotesFa, apk.sizeBytes)
        } catch (e: UpdateException) {
            _state.value = UpdateState.Failed(e.reasonFa)
        } catch (e: IOException) {
            _state.value = UpdateState.Failed("اتصال به سرور به‌روزرسانی برقرار نشد.")
        }
    }

    /** Downloads and verifies the APK found by [check]. Ends in ReadyToInstall or Failed. */
    suspend fun downloadAndVerify() {
        val p = pending ?: run { _state.value = UpdateState.Failed("ابتدا به‌روزرسانی را بررسی کنید."); return }
        val file = File(updateDir.dir, "update.apk")
        downloaded = null
        try {
            _state.value = UpdateState.Downloading(0f)
            withContext(Dispatchers.IO) { download(p, file) }
            _state.value = UpdateState.Verifying
            withContext(Dispatchers.IO) { ApkVerifier.verify(file, p.apk, p.manifest.signingCertSha256, inspector) }
            downloaded = file
            _state.value = UpdateState.ReadyToInstall
        } catch (e: UpdateException) {
            file.delete()
            _state.value = UpdateState.Failed(e.reasonFa)
        } catch (e: IOException) {
            file.delete()
            _state.value = UpdateState.Failed("دانلود فایل به‌روزرسانی ناموفق بود.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            file.delete()
            throw e
        }
    }

    /** Size of cached update files (for the clear-cache page). */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        updateDir.dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Deletes cached update files. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        updateDir.dir.listFiles()?.forEach { it.delete() }
        downloaded = null
        if (_state.value is UpdateState.ReadyToInstall) _state.value = UpdateState.Idle
    }

    fun canRequestInstalls() = installer.canRequestInstalls()
    fun installPermissionIntent(): Intent = installer.installPermissionIntent()

    /** Starts the install session. Caller should check [canRequestInstalls] first. */
    suspend fun install() {
        val file = downloaded
        if (file == null || !file.exists()) {
            _state.value = UpdateState.Failed("فایل به‌روزرسانی در دسترس نیست.")
            return
        }
        _state.value = UpdateState.Installing
        try {
            withContext(Dispatchers.IO) { installer.install(file) }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("شروع نصب ناموفق بود.")
        }
    }

    /** Called by [InstallResultReceiver] for every final session status. */
    fun onInstallResult(status: Int) {
        if (status == PackageInstaller.STATUS_SUCCESS) {
            downloaded?.delete()
            downloaded = null
            _state.value = UpdateState.Idle
        } else {
            _state.value = UpdateState.Failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "نصب لغو شد."
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "نصب به دلیل ناسازگاری (مثلاً امضای متفاوت) انجام نشد."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "فضای دستگاه برای نصب کافی نیست."
                    else -> "نصب ناموفق بود."
                },
            )
        }
    }

    private fun fetchText(url: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw UpdateException("سرور به‌روزرسانی پاسخ نامعتبر داد (${r.code}).")
            val body = r.body ?: throw UpdateException("پاسخ سرور خالی است.")
            if (body.contentLength() > MAX_MANIFEST_BYTES) throw UpdateException("فایل به‌روزرسانی بیش از حد بزرگ است.")
            return body.string()
        }
    }

    private suspend fun download(p: Pending, file: File) {
        file.parentFile?.mkdirs()
        file.delete()
        client.newCall(Request.Builder().url(p.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw UpdateException("دانلود فایل ناموفق بود (${r.code}).")
            val body = r.body ?: throw UpdateException("پاسخ سرور خالی است.")
            val total = body.contentLength().takeIf { it > 0 } ?: p.apk.sizeBytes ?: -1L
            if (total > MAX_APK_BYTES) throw UpdateException("اندازهٔ فایل بیش از حد مجاز است.")
            var read = 0L
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastEmit = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (read > MAX_APK_BYTES) throw UpdateException("اندازهٔ فایل بیش از حد مجاز است.")
                        if (read - lastEmit >= 256 * 1024) {
                            lastEmit = read
                            _state.value = UpdateState.Downloading(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 256 * 1024L
        const val MAX_APK_BYTES = 500L * 1024 * 1024
    }
}

/** Directory (app cache) where downloaded APKs live. */
class UpdateDir(val dir: File)
