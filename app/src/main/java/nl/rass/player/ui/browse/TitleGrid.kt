package nl.rass.player.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.ui.components.MutedText
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.components.PosterCard
import nl.rass.player.ui.components.PosterWidth
import nl.rass.player.ui.components.SideSheet
import nl.rass.player.ui.theme.TextPrimary

/**
 * Virtualized poster grid shared by Browse and My List.
 *
 * - OK opens [onOpen]; the Menu key or a long press on OK opens [onMenu] (R3.1, R11.5).
 * - Focus tracking does not recompose the grid: [onFocused] is a plain callback and each card owns a
 *   [FocusRequester] looked up by key. Bump [focusRequestToken] to move focus back to [focusKey]
 *   (first composition after Back, after the actions sheet closes).
 */
@Composable
fun TitleGrid(
    items: List<TitleCardUi>,
    gridState: LazyGridState,
    focusKey: String?,
    focusRequestToken: Int,
    onFocused: (String) -> Unit,
    onOpen: (String) -> Unit,
    onMenu: (String) -> Unit,
    modifier: Modifier = Modifier,
    showSourceBadge: Boolean = true,
) {
    val requesters = remember { HashMap<String, FocusRequester>() }
    val density = LocalDensity.current
    val posterPx = remember(density) { with(density) { PosterWidth.roundToPx() } }
    val newEpisodeLabel = stringResource(R.string.badge_new_episode)

    LaunchedEffect(focusRequestToken) {
        val k = focusKey ?: return@LaunchedEffect
        val idx = items.indexOfFirst { it.key == k }
        if (idx < 0) return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) gridState.scrollToItem(idx)
        withFrameNanos { }
        runCatching { requesters[k]?.requestFocus() }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(PosterWidth + 12.dp),
        state = gridState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        items(items, key = { it.key }, contentType = { "poster" }) { c ->
            val fr = remember(c.key) { requesters.getOrPut(c.key) { FocusRequester() } }
            DisposableEffect(c.key) { onDispose { requesters.remove(c.key) } }
            val longPressed = remember { BooleanArray(1) }
            val badges = buildList {
                if (c.newEpisode) add(newEpisodeLabel)
                if (showSourceBadge && c.sourceName != null) add(c.sourceName)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterCard(
                    imageUrl = c.poster?.urlFor(posterPx),
                    title = c.title,
                    showTitle = false,
                    progress = c.progress,
                    badges = badges,
                    onClick = { if (longPressed[0]) longPressed[0] = false else onOpen(c.key) },
                    onFocusChange = { if (it) onFocused(c.key) },
                    modifier = Modifier
                        .contextMenuKeys(longPressed) { onMenu(c.key) }
                        .focusRequester(fr),
                )
                Text(
                    c.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                if (c.year != null) MutedText(c.year, Modifier.padding(horizontal = 2.dp))
            }
        }
    }
}

/**
 * Menu key, or holding OK (key repeat), opens the context menu. [longPressed] is set so the click that
 * the key-up would otherwise produce is ignored by the card's onClick.
 */
private fun Modifier.contextMenuKeys(longPressed: BooleanArray, onMenu: () -> Unit): Modifier =
    onPreviewKeyEvent { e ->
        when {
            e.key == Key.Menu && e.type == KeyEventType.KeyDown -> { onMenu(); true }
            (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) &&
                e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                if (!longPressed[0]) { longPressed[0] = true; onMenu() }
                true
            }
            else -> false
        }
    }

/** Context actions for the focused card (R3.1, R11.5). Place it last in a full-screen Box. */
@Composable
fun TitleActionsSheet(
    target: TitleCardUi?,
    onDismiss: () -> Unit,
    onOpenDetails: (String) -> Unit,
    onToggleMyList: (TitleCardUi) -> Unit,
    onMarkWatched: (String) -> Unit,
    onMarkUnwatched: (String) -> Unit,
) {
    // Keep the last target while the sheet animates out.
    var shown by remember { androidx.compose.runtime.mutableStateOf<TitleCardUi?>(null) }
    if (target != null) shown = target
    SideSheet(visible = target != null, onDismiss = onDismiss, width = 460.dp) {
        val t = shown ?: return@SideSheet
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            PillButton(stringResource(R.string.action_open_details), { onOpenDetails(t.key) }, style = PillStyle.Primary)
            PillButton(
                stringResource(if (t.inMyList) R.string.action_remove_from_list else R.string.action_add_to_list),
                { onToggleMyList(t) },
            )
            PillButton(stringResource(R.string.action_mark_watched), { onMarkWatched(t.key) })
            PillButton(stringResource(R.string.action_mark_unwatched), { onMarkUnwatched(t.key) })
        }
    }
}
