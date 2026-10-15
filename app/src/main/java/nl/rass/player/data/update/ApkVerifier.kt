package nl.rass.player.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

object Sha256 {
    fun ofFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun ofBytes(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/** Reads signing certificates. Returns lowercase hex SHA-256 of each DER certificate. */
interface ApkInspector {
    fun archiveCerts(file: File): Set<String>?
    fun installedCerts(): Set<String>
}

class AndroidApkInspector(private val context: Context) : ApkInspector {
    @Suppress("DEPRECATION")
    override fun archiveCerts(file: File): Set<String>? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        } ?: return null
        return certsOf(info)
    }

    @Suppress("DEPRECATION")
    override fun installedCerts(): Set<String> {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        return certsOf(info) ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun certsOf(info: android.content.pm.PackageInfo): Set<String>? {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return null
        return sigs.map { Sha256.ofBytes(it.toByteArray()) }.toSet()
    }
}

object ApkVerifier {
    /** Throws [UpdateException] (Persian message) on any mismatch. Does not delete the file. */
    fun verify(file: File, entry: ApkEntry, manifestCerts: List<String>, inspector: ApkInspector) {
        entry.sizeBytes?.let {
            if (file.length() != it) throw UpdateException("اندازهٔ فایل دانلودشده با اعلام‌شده برابر نیست.")
        }
        if (Sha256.ofFile(file) != UpdateLogic.normalizeHex(entry.sha256)) {
            throw UpdateException("اعتبار فایل تأیید نشد (SHA-256 نادرست).")
        }
        val archive = inspector.archiveCerts(file)
        if (archive.isNullOrEmpty()) throw UpdateException("امضای برنامهٔ دانلودشده خوانده نشد.")
        val allowed = manifestCerts.map(UpdateLogic::normalizeHex).toSet()
        if (!allowed.containsAll(archive)) throw UpdateException("گواهی امضای فایل با گواهی اعلام‌شده برابر نیست.")
        if (archive != inspector.installedCerts()) {
            throw UpdateException("گواهی امضای نسخهٔ جدید با نسخهٔ نصب‌شده یکی نیست؛ نصب انجام نمی‌شود.")
        }
    }
}
