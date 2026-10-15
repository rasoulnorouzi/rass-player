package nl.rass.player.ui.search.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.ui.theme.Bg
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextPrimary

private enum class Dir { Up, Down, Left, Right }

/**
 * Fa/en on-screen keyboard. D-pad: Up/Down wrap vertically; inside a row the edge on the side away from the
 * results wraps around, the edge facing the results lets focus leave (so the user can reach them).
 * Holding OK on Backspace repeats. [firstKeyFocus] gets focus on the first (digits) key.
 */
@Composable
fun OnScreenKeyboard(
    language: KeyboardLanguage,
    micAvailable: Boolean,
    onKey: (KbKey) -> Unit,
    modifier: Modifier = Modifier,
    firstKeyFocus: FocusRequester? = null,
    /** Up from the first row: return true when focus moved elsewhere (the predictor row) instead of wrapping. */
    onUpFromTop: () -> Boolean = { false },
) {
    val rows = remember(language, micAvailable) { KeyboardLayouts.rows(language, micAvailable) }
    val requesters = remember(rows) { rows.map { r -> r.map { FocusRequester() } } }
    // The screen is RTL (results panel on the left). The English layout is drawn left-to-right like a real
    // QWERTY keyboard; the Persian layout right-to-left.
    val screenRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val keysRtl = screenRtl && language != KeyboardLanguage.EN

    fun move(r: Int, c: Int, d: Dir): Boolean {
        val n = rows.size
        fun mapCol(newRow: Int): Int {
            val a = rows[r].size
            val b = rows[newRow].size
            return if (a <= 1) 0 else Math.round(c * (b - 1).toFloat() / (a - 1)).coerceIn(0, b - 1)
        }
        when (d) {
            Dir.Up -> { if (r == 0 && onUpFromTop()) return true; val nr = (r - 1 + n) % n; requesters[nr][mapCol(nr)].requestFocus() }
            Dir.Down -> { val nr = (r + 1) % n; requesters[nr][mapCol(nr)].requestFocus() }
            else -> {
                val size = rows[r].size
                // Physical direction → index step for how this layout is drawn.
                val next = if ((d == Dir.Left) == keysRtl) c + 1 else c - 1
                // The results panel is on the start side of the screen (left in RTL).
                val towardExit = (d == Dir.Left) == screenRtl
                val exitEdge = if ((screenRtl == keysRtl)) size - 1 else 0
                when {
                    next in 0 until size -> requesters[r][next].requestFocus()
                    towardExit -> return false // leave toward the results
                    else -> requesters[r][exitEdge].requestFocus() // wrap to the exit edge
                }
            }
        }
        return true
    }

    CompositionLocalProvider(LocalLayoutDirection provides if (keysRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, key ->
                        val fr = requesters[r][c]
                        KeyButton(
                            key = key,
                            language = language,
                            requester = if (r == 0 && c == 0 && firstKeyFocus != null) firstKeyFocus else fr,
                            extraRequester = if (r == 0 && c == 0 && firstKeyFocus != null) fr else null,
                            onKey = onKey,
                            onMove = { d -> move(r, c, d) },
                            modifier = Modifier.weight(weightOf(key)),
                        )
                    }
                }
            }
        }
    }
}

private fun weightOf(key: KbKey): Float = when (key) {
    KbKey.Space -> 3f
    KbKey.Zwnj, KbKey.Backspace, KbKey.Clear, KbKey.Language, KbKey.Mic -> 1.4f
    else -> 1f
}

@Composable
private fun KeyButton(
    key: KbKey,
    language: KeyboardLanguage,
    requester: FocusRequester,
    extraRequester: FocusRequester?,
    onKey: (KbKey) -> Unit,
    onMove: (Dir) -> Boolean,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "keyScale")
    val label = when (key) {
        is KbKey.Char -> key.text
        KbKey.Space -> stringResource(R.string.kb_space)
        KbKey.Zwnj -> stringResource(R.string.kb_zwnj)
        KbKey.Backspace -> stringResource(R.string.kb_backspace)
        KbKey.Clear -> stringResource(R.string.kb_clear)
        KbKey.Mic -> stringResource(R.string.kb_mic)
        // Shows the layout you will switch to.
        KbKey.Language -> stringResource(if (language == KeyboardLanguage.FA) R.string.kb_lang_en else R.string.kb_lang_fa)
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .height(54.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(requester)
            .let { if (extraRequester != null) it.focusRequester(extraRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val ne = e.nativeKeyEvent
                when (e.key) {
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val d = when (e.key) {
                            Key.DirectionUp -> Dir.Up
                            Key.DirectionDown -> Dir.Down
                            Key.DirectionLeft -> Dir.Left
                            else -> Dir.Right
                        }
                        onMove(d)
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyDown) {
                            // Held key: only Backspace auto-repeats.
                            if (ne.repeatCount == 0 || key == KbKey.Backspace) onKey(key)
                        }
                        true
                    }
                    else -> false
                }
            }
            .clip(shape)
            .background(if (focused) TextPrimary else SurfaceRaised)
            .pointerInput(key) { detectTapGestures { onKey(key) } }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = if (key is KbKey.Char) 26.sp else 20.sp),
            color = if (focused) Bg else TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
