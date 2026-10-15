package nl.rass.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import nl.rass.player.net.AppVisibility
import nl.rass.player.net.ProxyManager
import javax.inject.Inject

@HiltAndroidApp
class RassApp : Application() {

    // Private connection (R10): load settings and connect early, before the first feed request.
    @Inject lateinit var proxyManager: ProxyManager

    override fun onCreate() {
        super.onCreate()
        // Pause proxy health checks/retries while the app is in the background.
        registerActivityLifecycleCallbacks(AppVisibility(proxyManager::setAppForeground))
        proxyManager.start()
    }
}
