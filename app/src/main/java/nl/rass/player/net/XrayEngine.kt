package nl.rass.player.net

import android.content.Context
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * One in-process Xray instance. The real implementation wraps libv2ray's CoreController; tests use fakes,
 * so nothing outside [LibXrayEngine] touches native code.
 */
interface XrayEngine {
    /** Starts Xray with a full JSON config. Blocking; throws if Xray rejects the config. */
    fun start(configJson: String)

    /** Stops Xray. Safe to call when not running. */
    fun stop()

    val isRunning: Boolean
}

fun interface XrayEngineFactory {
    fun create(): XrayEngine
}

/** Picks free loopback ports. Separate so tests can use fixed ports. */
fun interface PortAllocator {
    fun freePort(): Int
}

object LoopbackPortAllocator : PortAllocator {
    override fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(ProxyEndpoint.LOOPBACK)).use { it.localPort }
}

/**
 * Xray via the prebuilt AAR (2dust/AndroidLibXrayLite, see docs/third-party.md). Runs inside the app
 * process: no VpnService, no TUN (tunFd = 0), no separate service.
 */
class LibXrayEngine private constructor(private val controller: libv2ray.CoreController) : XrayEngine {

    override fun start(configJson: String) {
        // Throws on config errors. Don't wrap with the config text: it contains secrets.
        controller.startLoop(configJson, 0)
    }

    override fun stop() {
        runCatching { controller.stopLoop() }
    }

    override val isRunning: Boolean get() = controller.isRunning

    private object SilentCallbacks : libv2ray.CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        // Status texts can include addresses; ignore them.
        override fun onEmitStatus(p0: Long, p1: String?): Long = 0
    }

    /** The native library (~37 MB) is loaded on the first [create], not when the app starts. */
    class Factory(context: Context) : XrayEngineFactory {
        private val appContext = context.applicationContext

        override fun create(): XrayEngine {
            ensureEnvironment(appContext)
            return LibXrayEngine(libv2ray.Libv2ray.newCoreController(SilentCallbacks))
        }
    }

    companion object {
        @Volatile private var initialised = false

        @Synchronized
        private fun ensureEnvironment(context: Context) {
            if (initialised) return
            go.Seq.setContext(context)
            // Asset dir for Xray. We ship no geo files (excluded in app/build.gradle.kts) and never use geo rules.
            val dir = File(context.noBackupFilesDir, "xray").apply { mkdirs() }
            libv2ray.Libv2ray.initCoreEnv(dir.absolutePath, "")
            initialised = true
        }

        /** "Lib vNN, Xray-core vX.Y.Z", for Settings → About. */
        fun versionText(): String = runCatching { libv2ray.Libv2ray.checkVersionX() }.getOrDefault("unknown")
    }
}
