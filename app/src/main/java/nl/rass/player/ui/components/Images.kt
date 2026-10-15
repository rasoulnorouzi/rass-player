package nl.rass.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size

/** Deterministic dark-ish placeholder gradient from a seed (used when there is no image URL). */
fun placeholderBrush(seed: String): Brush {
    val h = (seed.hashCode().toUInt().toInt() and 0x7fffffff)
    val hue = (h % 360).toFloat()
    val a = Color.hsv(hue, 0.45f, 0.38f)
    val b = Color.hsv((hue + 40f) % 360f, 0.55f, 0.18f)
    return Brush.linearGradient(listOf(a, b))
}

/**
 * Image with a size-limited Coil request (decoded at display size, never full resolution).
 * [widthDp]/[heightDp] are the intended display size in dp. With a null [url] a colored box is drawn.
 */
@Composable
fun RassImage(
    url: String?,
    widthDp: Dp,
    heightDp: Dp,
    modifier: Modifier = Modifier,
    placeholderSeed: String = "",
    contentScale: ContentScale = ContentScale.Crop,
    crossfade: Boolean = true,
) {
    Box(modifier.background(placeholderBrush(placeholderSeed)).clipToBounds()) {
        if (url != null) {
            val ctx = LocalContext.current
            val density = androidx.compose.ui.platform.LocalDensity.current
            val request = remember(url, widthDp, heightDp) {
                val w = with(density) { widthDp.roundToPx() }
                val h = with(density) { heightDp.roundToPx() }
                ImageRequest.Builder(ctx).data(url).size(Size(w, h)).crossfade(crossfade).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
