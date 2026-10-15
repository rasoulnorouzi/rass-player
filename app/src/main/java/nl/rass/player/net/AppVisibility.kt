package nl.rass.player.net

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tells [ProxyManager] whether any activity is started (app visible). No extra dependency
 * (lifecycle-process) needed for a single-activity app. Registered in RassApp.
 */
class AppVisibility(private val onChange: (foreground: Boolean) -> Unit) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) onChange(true)
    }

    override fun onActivityStopped(activity: Activity) {
        if (--started == 0) onChange(false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
