package nl.rass.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import nl.rass.player.ui.AppShell
import nl.rass.player.ui.theme.RassTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** The app's single Coil loader (di/ImageModule: shared OkHttpClient + per-source image headers). */
    @Inject lateinit var imageLoader: Lazy<ImageLoader>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first image request; Settings → Storage reads the same singleton.
        SingletonImageLoader.setSafe { imageLoader.get() }
        setContent {
            // Persian-only UI: always lay out right-to-left.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RassTheme {
                    // Debug builds: long-press Menu on the top nav bar opens the component gallery.
                    AppShell(debugTools = BuildConfig.DEBUG)
                }
            }
        }
    }
}
