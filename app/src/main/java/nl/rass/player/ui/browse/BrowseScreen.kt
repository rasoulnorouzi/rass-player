package nl.rass.player.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.ui.components.ChipItem
import nl.rass.player.ui.components.ChipRow
import nl.rass.player.ui.components.ErrorState
import nl.rass.player.ui.components.EmptyState
import nl.rass.player.ui.components.LoadingShimmer
import nl.rass.player.ui.components.ScreenScaffold
import nl.rass.player.ui.components.SectionHeader
import nl.rass.player.ui.components.tvFocusable
import nl.rass.player.ui.navigation.MediaType
import nl.rass.player.ui.navigation.Route
import nl.rass.player.ui.theme.Bg
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextPrimary

/** Movies, Series and Category routes (R2.2): genre chips, sort/filter chips (R11.4) and a poster grid. */
@Composable
fun BrowseScreen(type: MediaType, genreId: String?, onNavigate: (Route) -> Unit, onBack: () -> Unit) {
    val vm: BrowseViewModel = hiltViewModel(key = "browse:$type:$genreId")
    remember(vm) { vm.init(type, genreId) }
    val ui by vm.state.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState(vm.savedIndex, vm.savedOffset)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> vm.savedIndex = i; vm.savedOffset = o }
    }

    var menuKey by remember { mutableStateOf<String?>(null) }
    // Start at 1 when there is a remembered focus (coming back from Detail) so it is restored on entry.
    var focusToken by remember { mutableIntStateOf(if (vm.lastFocusedKey != null) 1 else 0) }

    // Sort/filter changes start at the top (but not the first composition, which restores scroll).
    var firstFilter by remember { mutableStateOf(true) }
    LaunchedEffect(ui.selectedGenre, ui.sort, ui.selectedSource) {
        if (firstFilter) firstFilter = false else gridState.scrollToItem(0)
    }

    val title = when {
        ui.genreTitle != null && genreId != null -> ui.genreTitle!!
        type == MediaType.Movie -> stringResource(R.string.nav_movies)
        else -> stringResource(R.string.nav_series)
    }

    Box(Modifier.fillMaxSize()) {
        ScreenScaffold {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionHeader(title)
                when (ui.phase) {
                    BrowsePhase.Loading -> LoadingShimmer(Modifier.padding(top = 24.dp))
                    BrowsePhase.Error -> ErrorState(stringResource(R.string.browse_error_message), vm::retry)
                    BrowsePhase.Ready -> {
                        val allLabel = stringResource(R.string.browse_genre_all)
                        val genreChips = remember(ui.genres, allLabel) {
                            listOf(ChipItem("", allLabel)) + ui.genres.map { ChipItem(it.id, it.name) }
                        }
                        ChipRow(genreChips, ui.selectedGenre, vm::selectGenre, contentPadding = PaddingValues(horizontal = 16.dp))

                        val sortLabels = listOf(
                            BrowseSort.Newest to stringResource(R.string.browse_sort_newest),
                            BrowseSort.Year to stringResource(R.string.browse_sort_year),
                            BrowseSort.Rating to stringResource(R.string.browse_sort_rating),
                            BrowseSort.Alpha to stringResource(R.string.browse_sort_alpha),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChipRow(
                                sortLabels.map { ChipItem(it.first.name, it.second) },
                                ui.sort.name,
                                { n -> vm.selectSort(BrowseSort.valueOf(n)) },
                                modifier = Modifier.weight(1f, fill = false),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                            )
                            if (ui.sources.isNotEmpty()) {
                                val srcName = ui.sources.firstOrNull { it.id == ui.selectedSource }?.name
                                    ?: stringResource(R.string.browse_source_all)
                                CycleChip(stringResource(R.string.browse_source_label, srcName), vm::cycleSource)
                            }
                        }

                        if (ui.cards.isEmpty()) {
                            EmptyState(message = stringResource(R.string.browse_empty_message))
                        } else {
                            TitleGrid(
                                items = ui.cards,
                                gridState = gridState,
                                focusKey = vm.lastFocusedKey,
                                focusRequestToken = focusToken,
                                onFocused = { vm.lastFocusedKey = it },
                                onOpen = { onNavigate(Route.Detail(it)) },
                                onMenu = { menuKey = it },
                                showSourceBadge = ui.showSourceBadge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        TitleActionsSheet(
            target = menuKey?.let { k -> ui.cards.firstOrNull { it.key == k } },
            onDismiss = { menuKey = null; focusToken++ },
            onOpenDetails = { menuKey = null; onNavigate(Route.Detail(it)) },
            onToggleMyList = { vm.toggleMyList(it.key); menuKey = null; focusToken++ },
            onMarkWatched = { vm.markWatched(it); menuKey = null; focusToken++ },
            onMarkUnwatched = { vm.markUnwatched(it); menuKey = null; focusToken++ },
        )
    }
}

@Composable
private fun CycleChip(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .tvFocusable(shape = shape, onClick = onClick)
            .clip(shape)
            .background(SurfaceRaised)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    )
}
