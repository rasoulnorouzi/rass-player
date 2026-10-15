package nl.rass.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import nl.rass.player.ui.theme.SurfaceRaised

/**
 * Overlay panel that slides in from the start edge (right in RTL) over a scrim. Back closes it.
 * Focus moves into the sheet when it opens and is kept inside while open.
 * Place it last in a full-screen Box so it draws above the screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SideSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 520.dp,
    content: @Composable () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)))
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.CenterStart),
            // Slide direction is in absolute pixels: start edge = right when RTL.
            enter = slideInHorizontally { if (rtl) it else -it },
            exit = slideOutHorizontally { if (rtl) it else -it },
        ) {
            val fr = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(width)
                    .background(SurfaceRaised)
                    .padding(32.dp)
                    .focusRequester(fr)
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup(),
            ) { content() }
        }
    }
}
