package nl.rass.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.Bg
import nl.rass.player.ui.theme.Surface
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

data class NavItem(val id: String, val label: String, val iconRes: Int)

object NavIds {
    const val Home = "home"
    const val Movies = "movies"
    const val Series = "series"
    const val Search = "search"
    const val MyList = "my_list"
    const val Settings = "settings"
}

@Composable
fun defaultNavItems(): List<NavItem> = listOf(
    NavItem(NavIds.Home, stringResource(R.string.nav_home), R.drawable.ic_nav_home),
    NavItem(NavIds.Movies, stringResource(R.string.nav_movies), R.drawable.ic_nav_movies),
    NavItem(NavIds.Series, stringResource(R.string.nav_series), R.drawable.ic_nav_series),
    NavItem(NavIds.Search, stringResource(R.string.nav_search), R.drawable.ic_nav_search),
    NavItem(NavIds.MyList, stringResource(R.string.nav_my_list), R.drawable.ic_nav_list),
    NavItem(NavIds.Settings, stringResource(R.string.nav_settings), R.drawable.ic_nav_settings),
)

/**
 * Pill-shaped top bar. [visible]=false slides it out (e.g. when the content scrolls down); the caller
 * must move focus into the content before hiding it and may pass [selectedFocusRequester] so Back from
 * content can return focus to the selected item.
 */
@Composable
fun TopNavBar(
    items: List<NavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    selectedFocusRequester: FocusRequester? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Surface.copy(alpha = 0.92f))
                    .padding(6.dp)
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items.forEach { item ->
                    val selected = item.id == selectedId
                    val shape = RoundedCornerShape(50)
                    Row(
                        Modifier
                            .height(48.dp)
                            .then(if (selected && selectedFocusRequester != null) Modifier.focusRequester(selectedFocusRequester) else Modifier)
                            .tvFocusable(shape = shape, scale = 1.04f, onClick = { onSelect(item.id) })
                            .clip(shape)
                            .background(if (selected) Color(0xFF2A2F39) else Color.Transparent)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(painterResource(item.iconRes), null, Modifier.size(24.dp), tint = if (selected) Accent else TextMuted)
                        Text(
                            item.label, style = MaterialTheme.typography.labelLarge,
                            color = if (selected) TextPrimary else TextMuted, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
