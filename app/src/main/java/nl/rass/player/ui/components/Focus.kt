package nl.rass.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import nl.rass.player.ui.theme.Focus

const val FocusScale = 1.06f
val FocusRingWidth: Dp = 3.dp
val CardShape: Shape = RoundedCornerShape(10.dp)

/**
 * Clickable + focusable with a cheap focus effect: scale (graphicsLayer only) and a white ring drawn
 * in the draw phase. No recomposition happens on focus change beyond this modifier's own state.
 */
fun Modifier.tvFocusable(
    shape: Shape = CardShape,
    scale: Float = FocusScale,
    ringColor: Color = Focus,
    ringWidth: Dp = FocusRingWidth,
    enabled: Boolean = true,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit = {},
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, tween(120), label = "focusScale")
    this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        .drawWithContent {
            drawContent()
            if (focused) {
                val w = ringWidth.toPx()
                drawOutline(shape.createOutline(size, layoutDirection, this), ringColor, style = Stroke(w * 2))
            }
        }
        .onFocusChanged {
            if (focused != it.isFocused) {
                focused = it.isFocused
                onFocusChange?.invoke(it.isFocused)
            }
        }
        .clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
}

/**
 * Remembers which item of a row was focused last, so focus returns to it when the user comes back
 * to the row (e.g. after Back from a detail screen or moving up/down between rows).
 */
class RowFocusState(val listState: LazyListState, initialIndex: Int) {
    var lastIndex by mutableStateOf(initialIndex)
    internal val requester = FocusRequester()

    internal fun isLastVisible(): Boolean =
        listState.layoutInfo.visibleItemsInfo.any { it.index == lastIndex }
}

@Composable
fun rememberRowFocusState(): RowFocusState {
    val listState = rememberLazyListState()
    val saved = rememberSaveable { mutableStateOf(0) }
    return remember(listState) { RowFocusState(listState, saved.value) }
        .also { saved.value = it.lastIndex }
}

/** Apply to the LazyRow: entering the row from outside focuses the last focused item. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rowFocusEntry(state: RowFocusState): Modifier =
    focusProperties {
        enter = { if (state.isLastVisible()) state.requester else FocusRequester.Default }
    }

/** Apply to each item (by index): records focus and exposes the item as the restore target. */
fun Modifier.rowItemFocus(state: RowFocusState, index: Int): Modifier =
    (if (index == state.lastIndex) Modifier.focusRequester(state.requester) else Modifier)
        .onFocusChanged { if (it.hasFocus) state.lastIndex = index }
