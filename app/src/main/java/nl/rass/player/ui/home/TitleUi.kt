package nl.rass.player.ui.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import java.util.Locale
import nl.rass.player.R
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.Image
import nl.rass.player.domain.TitleType
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.toPersianDigits

/*
 * Small helpers shared by Home and Detail: Persian number/label formatting, image URL picking and the
 * context-action key handling (Menu key or long-press OK) used on cards.
 */

/** 7.85 → «۷٫۹». */
fun formatRatingFa(rating: Double): String =
    String.format(Locale.US, "%.1f", rating).replace('.', '٫').toPersianDigits()

@Composable
fun runtimeLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> stringResource(R.string.home_runtime_m, m.fa())
        m == 0 -> stringResource(R.string.home_runtime_h, h.fa())
        else -> stringResource(R.string.home_runtime_hm, h.fa(), m.fa())
    }
}

@Composable
fun typeLabel(type: TitleType): String =
    stringResource(if (type == TitleType.MOVIE) R.string.home_type_movie else R.string.home_type_series)

@Composable
fun episodeShort(season: Int, episode: Int): String = stringResource(R.string.home_episode_short, season.fa(), episode.fa())

/** Genre ids → display names (feed order of the ids), at most [max]. */
fun genreNames(catalog: Catalog, ids: List<String>, max: Int = 3): List<String> {
    if (ids.isEmpty()) return emptyList()
    val byId = catalog.genres.associateBy { it.id.lowercase() }
    return ids.mapNotNull { byId[it.lowercase()]?.displayName }.distinct().take(max)
}

/** The URL of [image] for a slot [width] wide, picked from the feed's sizes (§2.6). */
@Composable
fun rememberImageUrl(image: Image?, width: Dp): String? {
    val px = with(LocalDensity.current) { width.roundToPx() }
    return remember(image, px) { image?.urlFor(px) }
}

/**
 * Context action on a focusable card: the Menu key, or holding OK (long press). The OK release after a long
 * press is swallowed so the card's click does not fire as well. Apply before the card's own focusable.
 */
fun Modifier.contextAction(onAction: () -> Unit): Modifier = composed {
    val state = remember { BooleanArray(1) } // [0] = a long press is in progress
    onPreviewKeyEvent { e ->
        val code = e.nativeKeyEvent.keyCode
        val isOk = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
            code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
        when {
            code == AndroidKeyEvent.KEYCODE_MENU -> {
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onAction()
                true
            }
            isOk && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                if (!state[0]) {
                    state[0] = true
                    onAction()
                }
                true
            }
            isOk && e.type == KeyEventType.KeyUp && state[0] -> {
                state[0] = false
                true
            }
            else -> false
        }
    }
}
