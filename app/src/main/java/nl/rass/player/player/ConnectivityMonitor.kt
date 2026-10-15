package nl.rass.player.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android connectivity status for the player's failover rule (feed-spec §7.3: no failover while offline).
 * "Online" = the default network has the INTERNET capability (not "validated": a network behind the private
 * connection or a captive setup may never validate). Registered once for the app's lifetime.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = cm.getNetworkCapabilities(network)?.hasInternet() ?: true
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasInternet()
                }

                // Default-network callback: a replacement network reports onAvailable right after.
                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        }
    }

    private fun currentlyOnline(): Boolean = runCatching {
        val n = cm?.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(n)?.hasInternet() ?: false
    }.getOrDefault(true)

    private fun NetworkCapabilities.hasInternet() = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
