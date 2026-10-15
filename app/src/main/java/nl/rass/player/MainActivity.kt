package nl.rass.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dagger.hilt.android.AndroidEntryPoint
import nl.rass.player.ui.theme.RassTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Persian-only UI: always lay out right-to-left.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RassTheme {
                    // Temporary: component gallery until real screens exist.
                    nl.rass.player.ui.gallery.GalleryScreen()
                }
            }
        }
    }
}
