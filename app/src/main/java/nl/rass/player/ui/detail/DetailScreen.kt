package nl.rass.player.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import nl.rass.player.R
import nl.rass.player.domain.CatalogEpisode
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.Person
import nl.rass.player.domain.TitleType
import nl.rass.player.ui.components.CardBadge
import nl.rass.player.ui.components.ChipItem
import nl.rass.player.ui.components.ChipRow
import nl.rass.player.ui.components.EmptyState
import nl.rass.player.ui.components.LandscapeCard
import nl.rass.player.ui.components.LandscapeWidth
import nl.rass.player.ui.components.LoadingShimmer
import nl.rass.player.ui.components.MutedText
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
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
import nl.rass.player.ui.format.formatDurationFa
import nl.rass.player.ui.home.contextAction
import nl.rass.player.ui.home.formatRatingFa
import nl.rass.player.ui.home.rememberImageUrl
import nl.rass.player.ui.home.runtimeLabel
import nl.rass.player.ui.home.typeLabel
import nl.rass.player.ui.navigation.Route
import nl.rass.player.ui.theme.Bg
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

private const val FOCUS_HEADER = "header"
private const val FOCUS_EPISODES = "episodes"
private const val FOCUS_CAST = "cast"

/** Detail page (R2.3, R4.2, R4.4, R11.3, R11.5, R11.7, R11.8). */
@Composable
fun DetailScreen(catalogKey: String, onNavigate: (Route) -> Unit, onBack: () -> Unit) {
    val vm = hiltViewModel<DetailViewModel, DetailViewModel.Factory>(key = "detail:$catalogKey") { it.create(catalogKey) }
    val s by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Bg)) {
        val t = s.title
        when {
            t != null -> DetailContent(s, t, vm, onNavigate)
            s.notFound -> EmptyState(message = stringResource(R.string.detail_not_found))
            else -> DetailLoading()
        }
    }
}

@Composable
private fun DetailContent(s: DetailUiState, t: CatalogTitle, vm: DetailViewModel, onNavigate: (Route) -> Unit) {
    val listState = rememberLazyListState()
    val playRequester = remember { FocusRequester() }
    var lastFocus by rememberSaveable { mutableStateOf(FOCUS_HEADER) }
    val isSeries = t.type == TitleType.SERIES
    val seasons = t.seasons
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedSeason = seasons.firstOrNull { it.number == season }
        ?: seasons.firstOrNull { it.number == s.next?.episode?.seasonNumber }
        ?: seasons.firstOrNull()
    val epRow = rememberRowFocusState()
    val castRow = rememberRowFocusState()
    var menuEpisode by remember { mutableStateOf<CatalogEpisode?>(null) }
    var lastMenuEpisode by remember { mutableStateOf<CatalogEpisode?>(null) }
    var restoreToken by remember { mutableIntStateOf(0) }

    fun focusRow(fs: RowFocusState, size: Int): Boolean {
        if (size == 0) return false
        fs.lastIndex = fs.lastIndex.coerceIn(0, size - 1)
        return runCatching { fs.requester.requestFocus() }.isSuccess
    }

    fun restoreFocus() {
        val ok = when (lastFocus) {
            FOCUS_EPISODES -> focusRow(epRow, selectedSeason?.episodes?.size ?: 0)
            FOCUS_CAST -> focusRow(castRow, t.meta.cast.size)
            else -> false
        }
        if (!ok) runCatching { playRequester.requestFocus() }
    }

    // Initial focus = Play; after Back from the player = the episode (or row) that was focused.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        restoreFocus()
    }
    LaunchedEffect(restoreToken) {
        if (restoreToken == 0) return@LaunchedEffect
        delay(320) // the side sheet has slid out
        restoreFocus()
    }

    Box(Modifier.fillMaxSize()) {
        RassImage(
            rememberImageUrl(t.meta.backdrop ?: t.meta.poster, 1280.dp), 1280.dp, 720.dp,
            Modifier.fillMaxWidth().fillMaxHeight(0.82f).detailFades(),
            placeholderSeed = t.meta.displayTitle, crossfade = true,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = SafeMargin, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Header(
                    s, t, playRequester, onNavigate, vm,
                    Modifier.onFocusChanged { if (it.hasFocus) lastFocus = FOCUS_HEADER },
                )
            }
            if (isSeries && selectedSeason != null) {
                item(key = "episodes") {
                    Column(Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) lastFocus = FOCUS_EPISODES }) {
                        SectionHeader(stringResource(R.string.detail_episodes), Modifier.padding(horizontal = SafeMargin, vertical = 8.dp))
                        if (seasons.size > 1) {
                            ChipRow(
                                chips = seasons.map { ChipItem(it.number.toString(), seasonLabel(it.number, it.name)) },
                                selectedId = selectedSeason.number.toString(),
                                onSelect = { id -> season = id.toIntOrNull(); epRow.lastIndex = 0 },
                                contentPadding = PaddingValues(horizontal = SafeMargin, vertical = 8.dp),
                            )
                        }
                        EpisodeRow(
                            t, selectedSeason.episodes, s, epRow,
                            onPlay = { ep -> onNavigate(Route.Player(t.key, ep.seasonNumber, ep.number)) },
                            onMenu = { menuEpisode = it },
                        )
                    }
                }
            }
            if (t.meta.cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) lastFocus = FOCUS_CAST }) {
                        SectionHeader(stringResource(R.string.detail_cast), Modifier.padding(horizontal = SafeMargin, vertical = 8.dp))
                        CastRow(t.meta.cast, castRow)
                    }
                }
            }
        }

        if (menuEpisode != null) lastMenuEpisode = menuEpisode
        fun closeMenu() {
            menuEpisode = null
            restoreToken++
        }
        SideSheet(visible = menuEpisode != null, onDismiss = ::closeMenu) {
            lastMenuEpisode?.let { ep ->
                val watched = s.progress[ep.key]?.completed == true
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeader(episodeTitle(ep), Modifier.padding(bottom = 8.dp))
                    val wide = Modifier.fillMaxWidth()
                    PillButton(stringResource(R.string.detail_play), {
                        closeMenu(); onNavigate(Route.Player(t.key, ep.seasonNumber, ep.number))
                    }, wide, iconRes = R.drawable.ic_play, style = PillStyle.Primary)
                    PillButton(stringResource(R.string.detail_episode_start_over), {
                        closeMenu(); onNavigate(Route.Player(t.key, ep.seasonNumber, ep.number, startFromBeginning = true))
                    }, wide)
                    PillButton(
                        stringResource(if (watched) R.string.detail_mark_unwatched else R.string.detail_mark_watched),
                        { vm.setEpisodeWatched(ep, !watched); closeMenu() }, wide,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    s: DetailUiState,
    t: CatalogTitle,
    playRequester: FocusRequester,
    onNavigate: (Route) -> Unit,
    vm: DetailViewModel,
    modifier: Modifier,
) {
    val m = t.meta
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth(0.62f).padding(horizontal = SafeMargin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val logo = rememberImageUrl(m.logo, 420.dp)
            if (logo != null) {
                RassImage(logo, 420.dp, 120.dp, Modifier.size(420.dp, 120.dp), placeholderSeed = "", contentScale = ContentScale.Fit)
            } else {
                Text(
                    m.displayTitle, style = MaterialTheme.typography.displayMedium, color = TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val secondary = listOfNotNull(m.titleEn, m.originalTitle).firstOrNull { it != m.displayTitle }
            if (secondary != null) {
                Text(secondary, style = MaterialTheme.typography.titleMedium, color = TextMuted, maxLines = 1)
            }
            MetaLine(s, t)
            m.description?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyLarge, color = TextPrimary.copy(alpha = 0.9f),
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Buttons(s, t, playRequester, onNavigate, vm)
        if (s.sourceNames.isNotEmpty()) {
            Row(
                Modifier.padding(horizontal = SafeMargin),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MutedText(stringResource(R.string.detail_sources))
                s.sourceNames.take(6).forEach { CardBadge(it, background = SurfaceRaised) }
            }
        }
    }
}

@Composable
private fun MetaLine(s: DetailUiState, t: CatalogTitle) {
    val m = t.meta
    val parts = buildList {
        m.year?.let { add(it.fa()) }
        if (t.type == TitleType.SERIES) {
            if (t.seasons.isNotEmpty()) add(stringResource(R.string.home_seasons_count, t.seasons.size.fa()))
        } else {
            m.runtimeMin?.takeIf { it > 0 }?.let { add(runtimeLabel(it)) }
        }
        add(typeLabel(t.type))
        addAll(s.genres)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        m.rating?.let {
            Icon(painterResource(R.drawable.ic_star), null, Modifier.size(22.dp), tint = Color(0xFFFACC15))
            Text(formatRatingFa(it), style = MaterialTheme.typography.labelLarge, color = TextPrimary)
            Text("·", style = MaterialTheme.typography.labelLarge, color = TextMuted)
        }
        Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = TextMuted, maxLines = 1)
    }
}

private data class ButtonSpec(val id: String, val text: String, val icon: Int?, val style: PillStyle, val onClick: () -> Unit)

@Composable
private fun Buttons(s: DetailUiState, t: CatalogTitle, playRequester: FocusRequester, onNavigate: (Route) -> Unit, vm: DetailViewModel) {
    val specs = buildList {
        if (t.type == TitleType.MOVIE) {
            val resume = s.movieResumeMs
            if (resume != null) {
                add(ButtonSpec("play", stringResource(R.string.detail_resume_at, formatDurationFa(resume)), R.drawable.ic_play, PillStyle.Primary) {
                    onNavigate(Route.Player(t.key))
                })
                add(ButtonSpec("restart", stringResource(R.string.detail_start_over), null, PillStyle.Secondary) {
                    onNavigate(Route.Player(t.key, startFromBeginning = true))
                })
            } else {
                add(ButtonSpec("play", stringResource(R.string.detail_play), R.drawable.ic_play, PillStyle.Primary) {
                    onNavigate(Route.Player(t.key))
                })
            }
        } else {
            val next = s.next
            if (next != null) {
                val e = next.episode
                val resume = next.resumeMs
                val label = if (resume != null) {
                    stringResource(R.string.detail_resume_episode, e.seasonNumber.fa(), e.number.fa(), formatDurationFa(resume))
                } else stringResource(R.string.detail_play_episode, e.seasonNumber.fa(), e.number.fa())
                add(ButtonSpec("play", label, R.drawable.ic_play, PillStyle.Primary) {
                    onNavigate(Route.Player(t.key, e.seasonNumber, e.number))
                })
                if (resume != null) {
                    add(ButtonSpec("restart", stringResource(R.string.detail_start_over), null, PillStyle.Secondary) {
                        onNavigate(Route.Player(t.key, e.seasonNumber, e.number, startFromBeginning = true))
                    })
                }
            }
        }
        add(ButtonSpec(
            "mylist",
            stringResource(if (s.inMyList) R.string.detail_remove_my_list else R.string.detail_add_my_list),
            null, if (s.inMyList) PillStyle.Secondary else PillStyle.Accent, vm::toggleMyList,
        ))
        add(ButtonSpec(
            "watched",
            stringResource(if (s.watched) R.string.detail_mark_unwatched else R.string.detail_mark_watched),
            null, PillStyle.Secondary, vm::toggleWatched,
        ))
        if (s.trailerPlayable) {
            add(ButtonSpec("trailer", stringResource(R.string.detail_trailer), R.drawable.ic_play_triangle, PillStyle.Secondary) {
                onNavigate(Route.Trailer(t.key))
            })
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = SafeMargin, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(specs, key = { _, b -> b.id }) { _, b ->
            PillButton(
                b.text, b.onClick,
                if (b.id == "play" || (b.id == "mylist" && specs.none { it.id == "play" })) Modifier.focusRequester(playRequester) else Modifier,
                iconRes = b.icon, style = b.style,
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    t: CatalogTitle,
    episodes: List<CatalogEpisode>,
    s: DetailUiState,
    fs: RowFocusState,
    onPlay: (CatalogEpisode) -> Unit,
    onMenu: (CatalogEpisode) -> Unit,
) {
    LazyRow(
        state = fs.listState,
        modifier = Modifier.fillMaxWidth().rowFocusEntry(fs),
        contentPadding = PaddingValues(horizontal = SafeMargin, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(episodes, key = { _, e -> e.key }) { i, ep ->
            val p = s.progress[ep.key]
            val playable = ep.streams.isNotEmpty()
            val badges = buildList {
                if (ep.key in s.newEpisodeKeys) add(stringResource(R.string.detail_badge_new))
                if (p?.completed == true) add(stringResource(R.string.detail_badge_watched))
                if (!playable) add(stringResource(R.string.detail_not_playable))
            }
            Column(Modifier.width(LandscapeWidth), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LandscapeCard(
                    imageUrl = rememberImageUrl(ep.still ?: t.meta.backdrop, LandscapeWidth),
                    title = episodeTitle(ep),
                    onClick = { if (playable) onPlay(ep) },
                    modifier = Modifier.rowItemFocus(fs, i).contextAction { onMenu(ep) },
                    progress = p?.takeIf { !it.completed && it.positionMs > 0 }?.fraction,
                    badges = badges,
                )
                ep.runtimeMin?.takeIf { it > 0 }?.let { MutedText(runtimeLabel(it), Modifier.padding(horizontal = 2.dp)) }
            }
        }
    }
}

@Composable
private fun CastRow(cast: List<Person>, fs: RowFocusState) {
    LazyRow(
        state = fs.listState,
        modifier = Modifier.fillMaxWidth().rowFocusEntry(fs),
        contentPadding = PaddingValues(horizontal = SafeMargin, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(cast, key = { i, p -> "$i:${p.name}" }) { i, person ->
            Column(
                Modifier.width(140.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RassImage(
                    rememberImageUrl(person.photo, 120.dp), 120.dp, 120.dp,
                    Modifier
                        .rowItemFocus(fs, i)
                        .size(120.dp)
                        .tvFocusable(shape = CircleShape)
                        .clip(CircleShape),
                    placeholderSeed = person.name,
                )
                Text(
                    person.name, style = MaterialTheme.typography.labelMedium, color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                person.role?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun episodeTitle(ep: CatalogEpisode): String =
    stringResource(R.string.detail_episode_title, ep.number.fa(), ep.titleFa ?: ep.titleEn ?: stringResource(R.string.home_episode_short, ep.seasonNumber.fa(), ep.number.fa()))

@Composable
private fun seasonLabel(number: Int, name: String?): String = when {
    name != null -> name
    number == 0 -> stringResource(R.string.detail_season_special)
    else -> stringResource(R.string.detail_season_n, number.fa())
}

@Composable
private fun DetailLoading() {
    Column(
        Modifier.fillMaxSize().padding(SafeMargin),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.size(520.dp, 56.dp).clip(CircleShape).shimmer())
        Box(Modifier.size(360.dp, 28.dp).clip(CircleShape).shimmer())
        Box(Modifier.fillMaxWidth(0.6f).height(120.dp).clip(RoundedCornerShape(8.dp)).shimmer())
        LoadingShimmer(count = 4)
    }
}

/** Fades the backdrop into the background: from the bottom and from the text (start) side. */
private fun Modifier.detailFades(): Modifier = drawWithCache {
    val rtl = layoutDirection == LayoutDirection.Rtl
    val bottom = Brush.verticalGradient(listOf(Color.Transparent, Bg), startY = size.height * 0.35f, endY = size.height)
    val side = Brush.horizontalGradient(
        colors = listOf(Bg.copy(alpha = 0.97f), Bg.copy(alpha = 0.6f), Color.Transparent),
        startX = if (rtl) size.width else 0f,
        endX = if (rtl) size.width * 0.25f else size.width * 0.75f,
    )
    onDrawWithContent {
        drawContent()
        drawRect(side)
        drawRect(bottom)
    }
}
