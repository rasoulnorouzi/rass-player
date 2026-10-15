package nl.rass.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import nl.rass.player.ui.AppShell
import nl.rass.player.ui.theme.RassTheme

/** Global UI scale applied to the Compose density (see onCreate). */
private const val UI_SCALE = 0.62f

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** The app's single Coil loader (di/ImageModule: shared OkHttpClient + per-source image headers). */
    @Inject lateinit var imageLoader: Lazy<ImageLoader>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first image request; Settings → Storage reads the same singleton.
        SingletonImageLoader.setSafe { imageLoader.get() }
        setContent {
            // Design sizes were authored at roughly 2× what a 1080p TV needs; scale the whole UI
            // (dp and sp) down once here instead of touching every component.
            val base = LocalDensity.current
            val scaled = Density(base.density * UI_SCALE, base.fontScale)
            // Persian-only UI: always lay out right-to-left.
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides scaled,
            ) {
                RassTheme {
                    // Debug builds: long-press Menu on the top nav bar opens the component gallery.
                    AppShell(debugTools = BuildConfig.DEBUG)
                }
            }
        }
    }
}
