package nl.rass.player.ui.mylist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.rass.player.R
import nl.rass.player.domain.TitleType
import nl.rass.player.ui.browse.BrowseLogic
import nl.rass.player.ui.browse.TitleActionsSheet
import nl.rass.player.ui.browse.TitleGrid
import nl.rass.player.ui.components.ChipItem
import nl.rass.player.ui.components.ChipRow
import nl.rass.player.ui.components.EmptyState
import nl.rass.player.ui.components.ScreenScaffold
import nl.rass.player.ui.components.SectionHeader
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.navigation.Route

/** My List (R3): Movies / Series tabs, newest added first, "new episode" badges, remove via the context menu. */
@Composable
fun MyListScreen(onNavigate: (Route) -> Unit, onBack: () -> Unit) {
    val vm: MyListViewModel = hiltViewModel()
    val ui by vm.state.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState(vm.savedIndex, vm.savedOffset)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> vm.savedIndex = i; vm.savedOffset = o }
    }
    LaunchedEffect(ui.tab) { gridState.scrollToItem(vm.savedIndex, vm.savedOffset) }

    var menuKey by remember { mutableStateOf<String?>(null) }
    var focusToken by remember { mutableIntStateOf(if (vm.lastFocusedKey != null) 1 else 0) }

    val tabs = listOf(
        ChipItem(TitleType.MOVIE.wire, stringResource(R.string.mylist_tab_movies) + " (" + ui.movieCount.fa() + ")"),
        ChipItem(TitleType.SERIES.wire, stringResource(R.string.mylist_tab_series) + " (" + ui.seriesCount.fa() + ")"),
    )

    Box(Modifier.fillMaxSize()) {
        ScreenScaffold {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionHeader(stringResource(R.string.mylist_title))
                ChipRow(
                    tabs, ui.tab.wire,
                    { vm.selectTab(TitleType.fromWire(it) ?: TitleType.MOVIE) },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                )
                if (ui.cards.isEmpty()) {
                    EmptyState(
                        title = stringResource(
                            if (ui.tab == TitleType.MOVIE) R.string.mylist_empty_movies_title else R.string.mylist_empty_series_title,
                        ),
                        message = stringResource(R.string.mylist_empty_message),
                    )
                } else {
                    TitleGrid(
                        items = ui.cards,
                        gridState = gridState,
                        focusKey = vm.lastFocusedKey,
                        focusRequestToken = focusToken,
                        onFocused = { vm.lastFocusedKey = it },
                        onOpen = { onNavigate(Route.Detail(it)) },
                        onMenu = { menuKey = it },
                        showSourceBadge = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        TitleActionsSheet(
            target = menuKey?.let { k -> ui.cards.firstOrNull { it.key == k } },
            onDismiss = { menuKey = null; focusToken++ },
            onOpenDetails = { menuKey = null; onNavigate(Route.Detail(it)) },
            onToggleMyList = {
                // The card disappears: move focus to its neighbour first.
                vm.lastFocusedKey = BrowseLogic.neighborKey(ui.cards.map { c -> c.key }, it.key)
                vm.toggleMyList(it.key); menuKey = null; focusToken++
            },
            onMarkWatched = { vm.markWatched(it); menuKey = null; focusToken++ },
            onMarkUnwatched = { vm.markUnwatched(it); menuKey = null; focusToken++ },
        )
    }
}
