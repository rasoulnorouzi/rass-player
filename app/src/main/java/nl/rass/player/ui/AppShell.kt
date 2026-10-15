package nl.rass.player.ui

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlin.math.roundToInt
import nl.rass.player.R
import nl.rass.player.ui.browse.BrowseScreen
import nl.rass.player.ui.components.NavIds
import nl.rass.player.ui.components.SafeMargin
import nl.rass.player.ui.components.TopNavBar
import nl.rass.player.ui.components.defaultNavItems
import nl.rass.player.ui.detail.DetailScreen
import nl.rass.player.ui.detail.TrailerScreen
import nl.rass.player.ui.gallery.GalleryScreen
import nl.rass.player.ui.home.HomeScreen
import nl.rass.player.ui.mylist.MyListScreen
import nl.rass.player.ui.navigation.MediaType
import nl.rass.player.ui.navigation.Route
import nl.rass.player.ui.navigation.SettingsSection
import nl.rass.player.ui.player.PlayerScreen
import nl.rass.player.ui.search.SearchScreen
import nl.rass.player.ui.settings.SettingsScreen
import nl.rass.player.ui.theme.Bg

/**
 * Services the shell offers to screens (via [LocalShell]).
 *  - [isNavFocused]: the top nav bar holds focus (a screen should not grab focus on entry then, e.g. tab switch).
 *  - [reportScrolled]: a screen tells whether its content is scrolled down; the nav bar collapses while true.
 *    Screens that never report get a fallback: the bar collapses after a D-pad Down inside the content.
 */
@Stable
class ShellController {
    internal var navHasFocus by mutableStateOf(false)
    internal val scrolled = mutableStateMapOf<Route, Boolean>()

    val isNavFocused: Boolean get() = navHasFocus

    fun reportScrolled(route: Route, scrolled: Boolean) {
        if (this.scrolled[route] != scrolled) this.scrolled[route] = scrolled
    }
}

val LocalShell = staticCompositionLocalOf { ShellController() }

/**
 * App shell: owns the back stack of [Route]s (Navigation 3 `NavDisplay`, per-entry saved state and
 * ViewModels), the top nav bar and the Back rules:
 *  - on a tab screen (Home/Movies/Series/Search/My List/Settings) Back from the content focuses the nav bar;
 *  - Back with the nav bar focused goes to Home, and on Home exits after a "press back again" toast;
 *  - on any other screen Back pops it.
 * Detail, Player and Trailer are full screen (no nav bar).
 *
 * Debug builds only ([debugTools]): long-press Menu while the nav bar has focus opens the component gallery.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppShell(debugTools: Boolean = false) {
    val stack = rememberSaveable(saver = RouteStackSaver) { mutableStateListOf<Route>(Route.Home) }
    var gallery by rememberSaveable { mutableStateOf(false) }
    val shell = remember { ShellController() }
    val navRequester = remember { FocusRequester() }
    var keyScrolled by remember { mutableStateOf(false) }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val backAgain = stringResource(R.string.shell_press_back_again)

    val top = stack.last()
    val navId = top.navId()
    val navShown = navId != null

    fun focusNav() {
        keyScrolled = false
        runCatching { navRequester.requestFocus() }
    }

    fun setStack(routes: List<Route>) {
        if (stack.toList() == routes) return
        // Replace in place (never empty in between: NavDisplay needs a non-empty stack).
        routes.forEachIndexed { i, r -> if (i < stack.size) stack[i] = r else stack.add(r) }
        while (stack.size > routes.size) stack.removeAt(stack.lastIndex)
    }

    fun navigate(route: Route) {
        when {
            route.isTabRoot() -> setStack(if (route == Route.Home) listOf(Route.Home) else listOf(Route.Home, route))
            route in stack -> while (stack.last() != route) stack.removeAt(stack.lastIndex)
            else -> stack.add(route)
        }
    }

    fun handleBack() {
        if (gallery) {
            gallery = false
            return
        }
        val current = stack.last()
        when {
            current.navId() != null && current.isTabRoot() && !shell.navHasFocus -> focusNav()
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            else -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackAt < EXIT_WINDOW_MS) {
                    activity?.finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, backAgain, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(top) { keyScrolled = false }

    // Registered before NavDisplay and the screens, so screen-level handlers (side sheets, search) win.
    BackHandler(enabled = true) { handleBack() }

    if (gallery) {
        BackHandler(enabled = true) { gallery = false }
        GalleryScreen()
        return
    }

    val collapsed = navShown && !shell.navHasFocus && (shell.scrolled[top] ?: keyScrolled)
    val navFraction by animateFloatAsState(if (collapsed) 0f else 1f, tween(220), label = "navCollapse")

    CompositionLocalProvider(LocalShell provides shell) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            if (navShown) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .collapsible { navFraction }
                        .onFocusChanged {
                            shell.navHasFocus = it.hasFocus
                            if (it.hasFocus) keyScrolled = false
                        }
                        .onPreviewKeyEvent { e ->
                            val n = e.nativeKeyEvent
                            if (debugTools && n.keyCode == AndroidKeyEvent.KEYCODE_MENU && e.type == KeyEventType.KeyDown &&
                                (n.repeatCount > 0 || n.isLongPress)
                            ) {
                                gallery = true
                                true
                            } else false
                        },
                ) {
                    TopNavBar(
                        items = defaultNavItems(),
                        selectedId = navId!!,
                        onSelect = { id -> navigate(tabRoute(id)) },
                        modifier = Modifier.padding(top = 20.dp, start = SafeMargin, end = SafeMargin),
                        selectedFocusRequester = navRequester,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN &&
                            !shell.navHasFocus
                        ) keyScrolled = true
                        false
                    }
                    .focusProperties {
                        exit = { dir -> if (dir == FocusDirection.Up && navShown) navRequester else FocusRequester.Default }
                    }
                    .focusGroup(),
            ) {
                NavDisplay(
                    backStack = stack,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { handleBack() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    // Cheap fades only (low-end TV).
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    popTransitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    predictivePopTransitionSpec = { _ -> fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    entryProvider = { route ->
                        NavEntry(route, contentKey = route.encode()) { r -> RouteContent(r, ::navigate, ::handleBack) }
                    },
                )
            }
        }
    }
}

@Composable
private fun RouteContent(route: Route, navigate: (Route) -> Unit, back: () -> Unit) {
    when (route) {
        Route.Home -> HomeScreen(navigate, back)
        Route.Movies -> BrowseScreen(MediaType.Movie, null, navigate, back)
        Route.Series -> BrowseScreen(MediaType.Series, null, navigate, back)
        is Route.Category -> BrowseScreen(route.type, route.genreId, navigate, back)
        Route.Search -> SearchScreen(navigate, back)
        Route.MyList -> MyListScreen(navigate, back)
        is Route.Detail -> DetailScreen(route.catalogKey, navigate, back)
        is Route.Player -> PlayerScreen(route, navigate, back)
        is Route.Settings -> SettingsScreen(route.section, navigate, back)
        is Route.Trailer -> TrailerScreen(route.catalogKey, back)
    }
}

private const val EXIT_WINDOW_MS = 2_000L

/** Collapses vertically to `fraction × height`, sliding up; children keep their size (stay focusable). */
private fun Modifier.collapsible(fraction: () -> Float): Modifier = clipToBounds().layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val h = (p.height * fraction().coerceIn(0f, 1f)).roundToInt()
    layout(p.width, h) { p.placeRelative(0, h - p.height) }
}

/** Nav bar item for a route, or null when the route is full screen (no nav bar). */
private fun Route.navId(): String? = when (this) {
    Route.Home -> NavIds.Home
    Route.Movies -> NavIds.Movies
    Route.Series -> NavIds.Series
    is Route.Category -> if (type == MediaType.Movie) NavIds.Movies else NavIds.Series
    Route.Search -> NavIds.Search
    Route.MyList -> NavIds.MyList
    is Route.Settings -> NavIds.Settings
    is Route.Detail, is Route.Player, is Route.Trailer -> null
}

private fun Route.isTabRoot(): Boolean = when (this) {
    Route.Home, Route.Movies, Route.Series, Route.Search, Route.MyList -> true
    is Route.Settings -> section == SettingsSection.Root
    else -> false
}

private fun tabRoute(navId: String): Route = when (navId) {
    NavIds.Movies -> Route.Movies
    NavIds.Series -> Route.Series
    NavIds.Search -> Route.Search
    NavIds.MyList -> Route.MyList
    NavIds.Settings -> Route.Settings()
    else -> Route.Home
}

// ---- back stack persistence (process death / config change) ----

private const val SEP = '\u001F'

internal fun Route.encode(): String = when (this) {
    Route.Home -> "home"
    Route.Movies -> "movies"
    Route.Series -> "series"
    Route.Search -> "search"
    Route.MyList -> "mylist"
    is Route.Category -> listOf("category", type.name, genreId.orEmpty()).joinToString(SEP.toString())
    is Route.Detail -> "detail$SEP$catalogKey"
    is Route.Player -> listOf(
        "player", catalogKey, seasonNumber?.toString().orEmpty(), episodeNumber?.toString().orEmpty(), startFromBeginning.toString(),
    ).joinToString(SEP.toString())
    is Route.Settings -> "settings$SEP${section.name}"
    is Route.Trailer -> "trailer$SEP$catalogKey"
}

internal fun decodeRoute(s: String): Route? {
    val p = s.split(SEP)
    return runCatching {
        when (p[0]) {
            "home" -> Route.Home
            "movies" -> Route.Movies
            "series" -> Route.Series
            "search" -> Route.Search
            "mylist" -> Route.MyList
            "category" -> Route.Category(MediaType.valueOf(p[1]), p[2].ifEmpty { null })
            "detail" -> Route.Detail(p[1])
            "player" -> Route.Player(p[1], p[2].toIntOrNull(), p[3].toIntOrNull(), p[4].toBoolean())
            "settings" -> Route.Settings(SettingsSection.valueOf(p[1]))
            "trailer" -> Route.Trailer(p[1])
            else -> null
        }
    }.getOrNull()
}

private val RouteStackSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved ->
        mutableStateListOf<Route>().apply {
            addAll(saved.mapNotNull(::decodeRoute))
            if (isEmpty() || first() != Route.Home) add(0, Route.Home)
        }
    },
)
