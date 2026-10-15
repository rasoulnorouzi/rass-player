package nl.rass.player.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import nl.rass.player.R
import nl.rass.player.domain.Image
import nl.rass.player.domain.TitleType
import nl.rass.player.ui.LocalShell
import nl.rass.player.ui.components.ErrorState
import nl.rass.player.ui.components.HeroBillboard
import nl.rass.player.ui.components.HeroInfo
import nl.rass.player.ui.components.LandscapeCard
import nl.rass.player.ui.components.LandscapeHeight
import nl.rass.player.ui.components.LandscapeWidth
import nl.rass.player.ui.components.LoadingShimmer
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.components.PosterCard
import nl.rass.player.ui.components.PosterHeight
import nl.rass.player.ui.components.PosterWidth
import nl.rass.player.ui.components.RassImage
import nl.rass.player.ui.components.RowFocusState
import nl.rass.player.ui.components.SafeMargin
import nl.rass.player.ui.components.SectionHeader
import nl.rass.player.ui.components.SideSheet
import nl.rass.player.ui.components.rememberRowFocusState
import nl.rass.player.ui.components.rowFocusEntry
import nl.rass.player.ui.components.rowItemFocus
import nl.rass.player.ui.components.shimmer
import nl.rass.player.ui.components.tvFocusable
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.navigation.Route
import nl.rass.player.ui.navigation.SettingsSection
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.Bg
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

private const val HERO_KEY = "hero"
private const val HERO_ROTATE_MS = 9_000L

/** Home dashboard (R2.1, R2.4). */
@Composable
fun HomeScreen(onNavigate: (Route) -> Unit, onBack: () -> Unit) {
    val vm: HomeViewModel = hiltViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Bg)) {
        when (val p = s.phase) {
            HomePhase.Loading -> HomeLoading()
            is HomePhase.Empty -> EmptyCatalog(p.noSources) { onNavigate(Route.Settings(SettingsSection.Sources)) }
            HomePhase.AllFailed -> ErrorState(stringResource(R.string.home_error_all), onRetry = vm::retry)
            HomePhase.Content -> HomeContent(s, vm, onNavigate)
        }
    }
}

@Composable
private fun HomeContent(s: HomeUiState, vm: HomeViewModel, onNavigate: (Route) -> Unit) {
    val shell = LocalShell.current
    val listState = rememberLazyListState()
    // Row (or hero) that had focus last; restored when coming back from Detail/Player.
    var focusedRow by rememberSaveable { mutableStateOf<String?>(null) }
    var heroIndex by rememberSaveable { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    val heroPlay = remember { FocusRequester() }
    val rowStates = remember { HashMap<String, RowFocusState>() }
    val focusedCard = remember { mutableStateOf<HomeCard?>(null) }
    var rowsFocused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Pair<String, HomeCard>?>(null) }
    var restoreRow by remember { mutableStateOf<String?>(null) }
    var restoreToken by remember { mutableIntStateOf(0) }

    fun focusRow(key: String?): Boolean {
        val fs = key?.let { rowStates[it] } ?: return false
        val size = s.rows.firstOrNull { it.key == key }?.let { it.cards.size + if (it.seeAll != null) 1 else 0 } ?: return false
        if (size == 0) return false
        fs.lastIndex = fs.lastIndex.coerceIn(0, size - 1)
        return runCatching { fs.requester.requestFocus() }.isSuccess
    }

    fun focusFallback() {
        if (s.hero.isNotEmpty() && runCatching { heroPlay.requestFocus() }.isSuccess) return
        s.rows.firstOrNull()?.let { focusRow(it.key) }
    }

    // Initial focus / restore after Back. Skipped when the user is in the top nav bar (tab switch).
    LaunchedEffect(Unit) {
        if (shell.isNavFocused) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val key = focusedRow
        if (key == null || key == HERO_KEY || !focusRow(key)) focusFallback()
    }
    // The billboard/rows are rebuilt while sources finish loading; if that drops focus, Compose hands it to
    // the nav bar although the user never went there. Take it back (only while the user hasn't used the bar).
    LaunchedEffect(shell.navHasFocus, shell.navUserActive) {
        if (shell.navHasFocus && !shell.navUserActive) {
            withFrameNanos { }
            if (shell.navHasFocus && !shell.navUserActive) focusFallback()
        }
    }
    // Focus back to the card after its context menu closed (the sheet has slid out by then).
    LaunchedEffect(restoreToken) {
        val key = restoreRow ?: return@LaunchedEffect
        delay(320)
        if (!focusRow(key)) focusFallback()
        restoreRow = null
    }
    LaunchedEffect(rowsFocused) { shell.reportScrolled(Route.Home, rowsFocused) }

    // Featured rotation, paused while the billboard has focus (its buttons would be recreated).
    val heroCount = s.hero.size
    LaunchedEffect(heroFocused, heroCount, heroIndex) {
        if (!heroFocused && heroCount > 1) {
            delay(HERO_ROTATE_MS)
            heroIndex = (heroIndex + 1) % heroCount
        }
    }

    Box(Modifier.fillMaxSize()) {
        FocusBackdrop(focusedCard, visible = rowsFocused)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (s.failedSources.isNotEmpty()) {
                item(key = "banner") { SourcesBanner(s.failedSources, vm::retry) }
            }
            if (s.hero.isNotEmpty()) {
                item(key = HERO_KEY) {
                    val h = s.hero[heroIndex.coerceIn(0, s.hero.lastIndex)]
                    HeroBillboard(
                        info = heroInfo(h),
                        pageCount = s.hero.size,
                        pageIndex = heroIndex.coerceIn(0, s.hero.lastIndex),
                        onPlay = { onNavigate(Route.Player(h.catalogKey)) },
                        onMoreInfo = { onNavigate(Route.Detail(h.catalogKey)) },
                        modifier = Modifier
                            .padding(horizontal = SafeMargin)
                            .onFocusChanged {
                                heroFocused = it.hasFocus
                                if (it.hasFocus) {
                                    focusedRow = HERO_KEY
                                    rowsFocused = false
                                }
                            },
                        playFocusRequester = heroPlay,
                    )
                }
            }
            items(s.rows, key = { it.key }) { row ->
                val fs = rememberRowFocusState()
                DisposableEffect(row.key, fs) {
                    rowStates[row.key] = fs
                    onDispose { if (rowStates[row.key] === fs) rowStates.remove(row.key) }
                }
                HomeRowView(
                    row = row,
                    focusState = fs,
                    modifier = Modifier.onFocusChanged {
                        if (it.hasFocus) {
                            focusedRow = row.key
                            rowsFocused = true
                        }
                    },
                    onFocused = { focusedCard.value = it },
                    onClick = { onNavigate(it.onClick) },
                    onMenu = { menu = row.key to it },
                    onSeeAll = onNavigate,
                )
            }
        }
        // Keep the last card while the sheet slides out.
        var lastMenu by remember { mutableStateOf<Pair<String, HomeCard>?>(null) }
        if (menu != null) lastMenu = menu
        fun closeMenu() {
            restoreRow = menu?.first
            restoreToken++
            menu = null
        }
        SideSheet(visible = menu != null, onDismiss = ::closeMenu) {
            lastMenu?.let { (_, card) ->
                CardMenuContent(
                    card = card,
                    inMyList = card.catalogKey in s.myListKeys,
                    onPlay = { closeMenu(); onNavigate(card.onClick) },
                    onDetails = { closeMenu(); onNavigate(Route.Detail(card.catalogKey)) },
                    onRemoveContinue = { vm.removeFromContinueWatching(card); closeMenu() },
                    onMarkWatched = { vm.markWatched(card); closeMenu() },
                    onRemoveHistory = { vm.removeFromHistory(card); closeMenu() },
                    onToggleMyList = { vm.toggleMyList(card); closeMenu() },
                )
            }
        }
    }
}

@Composable
private fun heroInfo(h: HeroItem): HeroInfo {
    val typeOrSeasons = if (h.type == TitleType.SERIES && h.seasonCount > 0) {
        stringResource(R.string.home_seasons_count, h.seasonCount.fa())
    } else typeLabel(h.type)
    return HeroInfo(
        id = h.catalogKey,
        title = h.title,
        backdropUrl = rememberImageUrl(h.backdrop, 1280.dp),
        logoUrl = rememberImageUrl(h.logo, 480.dp),
        rating = h.rating?.let(::formatRatingFa),
        year = h.year?.fa(),
        type = typeOrSeasons,
        genres = h.genres,
        description = h.description,
    )
}

@Composable
private fun rowTitle(row: HomeRow): Pair<String, String?> = when (row.kind) {
    RowKind.ContinueWatching -> stringResource(R.string.home_row_continue) to stringResource(R.string.home_row_continue_sub)
    RowKind.RecentlyPlayed -> stringResource(R.string.home_row_recent_played) to null
    RowKind.RecentlyAdded -> stringResource(R.string.home_row_recent_added) to stringResource(R.string.home_row_recent_added_sub)
    RowKind.NewEpisodes -> stringResource(R.string.home_row_new_episodes) to stringResource(R.string.home_row_new_episodes_sub)
    RowKind.MyList -> stringResource(R.string.home_row_my_list) to null
    RowKind.Top10 -> stringResource(R.string.home_row_top10) to null
    RowKind.GenreMovies -> stringResource(R.string.home_row_genre_movies, row.genreName.orEmpty()) to null
    RowKind.GenreSeries -> stringResource(R.string.home_row_genre_series, row.genreName.orEmpty()) to null
}

@Composable
private fun HomeRowView(
    row: HomeRow,
    focusState: RowFocusState,
    modifier: Modifier,
    onFocused: (HomeCard) -> Unit,
    onClick: (HomeCard) -> Unit,
    onMenu: (HomeCard) -> Unit,
    onSeeAll: (Route) -> Unit,
) {
    val (title, subtitle) = rowTitle(row)
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        SectionHeader(title, Modifier.padding(horizontal = SafeMargin), subtitle = subtitle)
        LazyRow(
            state = focusState.listState,
            modifier = Modifier.fillMaxWidth().rowFocusEntry(focusState),
            // Vertical padding leaves room for the focus scale so titles and rings are never clipped.
            contentPadding = PaddingValues(horizontal = SafeMargin, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.cards, key = { _, c -> c.id }) { i, card ->
                HomeCardView(
                    card, row.style,
                    Modifier.rowItemFocus(focusState, i).contextAction { onMenu(card) },
                    onFocused = { onFocused(card) },
                    onClick = { onClick(card) },
                )
            }
            val all = row.seeAll
            if (all != null) {
                item(key = "see-all") {
                    SeeAllTile(row.style, Modifier.rowItemFocus(focusState, row.cards.size)) { onSeeAll(all) }
                }
            }
        }
    }
}

@Composable
private fun HomeCardView(card: HomeCard, style: CardStyle, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    val title = card.episode?.let { (se, ep) ->
        stringResource(R.string.home_title_with_episode, card.title, episodeShort(se, ep))
    } ?: card.title
    val badges = if (card.newEpisodes > 0) listOf(stringResource(R.string.home_badge_new_episodes, card.newEpisodes.fa())) else emptyList()
    val onFocus: (Boolean) -> Unit = { if (it) onFocused() }
    when (style) {
        CardStyle.Poster -> PosterCard(
            rememberImageUrl(card.image, PosterWidth), title, onClick, modifier,
            progress = card.progress, badges = badges, rank = card.rank, onFocusChange = onFocus,
        )
        CardStyle.Landscape -> LandscapeCard(
            rememberImageUrl(card.image, LandscapeWidth), title, onClick, modifier,
            showTitle = card.rank == null, progress = card.progress, badges = badges, rank = card.rank, onFocusChange = onFocus,
        )
    }
}

@Composable
private fun SeeAllTile(style: CardStyle, modifier: Modifier, onClick: () -> Unit) {
    val (w, h) = if (style == CardStyle.Poster) PosterWidth to PosterHeight else LandscapeWidth to LandscapeHeight
    Box(
        modifier.size(w, h).tvFocusable(onClick = onClick).clip(RoundedCornerShape(10.dp)).background(SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.home_see_all), style = MaterialTheme.typography.labelLarge, color = TextPrimary)
    }
}

/** Dim, low-resolution backdrop of the focused card behind the rows ("backdrop follows focus"). */
@Composable
private fun FocusBackdrop(focused: MutableState<HomeCard?>, visible: Boolean) {
    var shown by remember { mutableStateOf<Image?>(null) }
    val target = focused.value?.backdrop
    LaunchedEffect(target, visible) {
        if (!visible) { shown = null; return@LaunchedEffect }
        delay(350) // debounce fast scrolling
        shown = target
    }
    Crossfade(shown, animationSpec = tween(400), label = "homeBackdrop", modifier = Modifier.fillMaxSize()) { img ->
        if (img != null) {
            val url = rememberImageUrl(img, 640.dp)
            RassImage(
                url, 640.dp, 360.dp,
                Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f }.backdropScrim(),
                placeholderSeed = "", crossfade = false,
            )
        }
    }
}

private fun Modifier.backdropScrim(): Modifier = drawWithCache {
    val bottom = Brush.verticalGradient(listOf(Bg.copy(alpha = 0.3f), Bg), startY = 0f, endY = size.height * 0.85f)
    onDrawWithContent {
        drawContent()
        drawRect(bottom)
    }
}

@Composable
private fun SourcesBanner(failed: List<String>, onRetry: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = SafeMargin, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceRaised)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Accent))
        Text(
            stringResource(R.string.home_sources_unavailable_named, failed.size.fa(), failed.joinToString("، ")),
            style = MaterialTheme.typography.labelLarge, color = TextPrimary, maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        PillButton(stringResource(R.string.action_retry), onRetry, iconRes = R.drawable.ic_refresh)
    }
}

@Composable
private fun HomeLoading() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = SafeMargin, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)).shimmer())
        repeat(2) {
            Box(Modifier.size(220.dp, 30.dp).clip(RoundedCornerShape(6.dp)).shimmer())
            LoadingShimmer(count = 8)
        }
    }
}

@Composable
private fun EmptyCatalog(noSources: Boolean, onOpenSources: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(
        Modifier.fillMaxSize().padding(SafeMargin),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Text(
            stringResource(if (noSources) R.string.home_empty_no_sources else R.string.home_empty_no_titles),
            style = MaterialTheme.typography.bodyLarge, color = TextMuted, textAlign = TextAlign.Center,
        )
        PillButton(
            stringResource(R.string.home_open_sources), onOpenSources, Modifier.focusRequester(fr),
            iconRes = R.drawable.ic_nav_settings, style = PillStyle.Primary,
        )
    }
}

@Composable
private fun CardMenuContent(
    card: HomeCard,
    inMyList: Boolean,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onRemoveContinue: () -> Unit,
    onMarkWatched: () -> Unit,
    onRemoveHistory: () -> Unit,
    onToggleMyList: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val title = card.episode?.let { (se, ep) ->
            stringResource(R.string.home_title_with_episode, card.title, episodeShort(se, ep))
        } ?: card.title
        SectionHeader(title, Modifier.padding(bottom = 8.dp))
        val wide = Modifier.fillMaxWidth()
        when (card.menu) {
            CardMenu.ContinueWatching -> {
                PillButton(stringResource(R.string.home_menu_play), onPlay, wide, iconRes = R.drawable.ic_play, style = PillStyle.Primary)
                PillButton(stringResource(R.string.home_menu_remove_continue), onRemoveContinue, wide)
                PillButton(stringResource(R.string.home_menu_mark_watched), onMarkWatched, wide)
            }
            CardMenu.History -> PillButton(stringResource(R.string.home_menu_remove_history), onRemoveHistory, wide)
            CardMenu.MyList, CardMenu.Default -> PillButton(
                stringResource(if (inMyList) R.string.home_menu_remove_my_list else R.string.home_menu_add_my_list),
                onToggleMyList, wide,
            )
        }
        PillButton(stringResource(R.string.home_menu_details), onDetails, wide, iconRes = R.drawable.ic_info)
    }
}

