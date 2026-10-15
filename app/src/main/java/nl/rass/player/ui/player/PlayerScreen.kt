package nl.rass.player.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import nl.rass.player.player.subtitle.SubtitleOverlay
import nl.rass.player.ui.navigation.Route

/** Which side sheet is open over the video. */
enum class PlayerSheet { None, Episodes, Subtitles, Quality, Audio, Source, Speed, Sleep }

/**
 * Full-screen player (R4, R5, R6, R11). D-pad, handled here in one place:
 *  - controls hidden: OK = play/pause (or skip intro / play next when that prompt shows) + show controls;
 *    Left/Right = seek ±10 s (accelerating while held) with a time preview; Up/Down = show controls;
 *  - controls visible: normal focus navigation; Left/Right on the progress bar seek;
 *  - Back: closes a sheet, then the controls, then cancels auto-next, then leaves the player.
 */
@Composable
fun PlayerScreen(route: Route.Player, onNavigate: (Route) -> Unit, onBack: () -> Unit) {
    val vm: PlayerViewModel = hiltViewModel()
    LaunchedEffect(vm, route) { vm.start(route) }
    DisposableEffect(vm) { onDispose { vm.exit() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onStop() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(vm) { vm.exitRequests.collect { onBack() } }

    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val seekPreview by vm.seekPreview.collectAsStateWithLifecycle()
    // Kept as a State (not delegated) so only the readers (subtitles, progress bar) recompose at 10 Hz.
    val positionState = vm.position.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(PlayerSheet.None) }
    var lastInteraction by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }

    val blocking = ui.resumePromptMs != null || ui.phase != PlayerPhase.PLAYING
    fun showControls() {
        controlsVisible = true
        lastInteraction = System.currentTimeMillis()
    }

    // Auto-hide the controls after 5 s without input while playing.
    LaunchedEffect(controlsVisible, lastInteraction, ui.isPlaying, sheet, ui.ended) {
        if (controlsVisible && ui.isPlaying && sheet == PlayerSheet.None && !ui.ended) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(ui.ended) { if (ui.ended) showControls() }
    LaunchedEffect(controlsVisible, sheet, blocking) {
        if (!controlsVisible && sheet == PlayerSheet.None && !blocking) runCatching { rootFocus.requestFocus() }
    }

    BackHandler(enabled = sheet == PlayerSheet.None) {
        when {
            blocking -> onBack()
            controlsVisible -> controlsVisible = false
            ui.autoNextMs != null -> vm.cancelAutoNext()
            else -> onBack()
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        lastInteraction = System.currentTimeMillis()
        if (sheet != PlayerSheet.None || blocking) return false
        val code = e.key.nativeKeyCode
        val down = e.type == KeyEventType.KeyDown
        val repeat = e.nativeKeyEvent.repeatCount > 0
        // Media keys work in every state.
        when (code) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down && !repeat) vm.togglePlayPause(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) vm.setPlaying(true); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) vm.setPlaying(false); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) vm.seekStep(true, repeat) else vm.commitSeek(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) vm.seekStep(false, repeat) else vm.commitSeek(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { if (down && !repeat) vm.playNext(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (down && !repeat) vm.playPrev(); return true }
        }
        if (controlsVisible) return false
        return when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && !repeat) {
                    when {
                        ui.skip != null -> vm.skip()
                        ui.autoNextMs != null -> vm.playNext()
                        else -> { vm.togglePlayPause(); showControls() }
                    }
                }
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                // The timeline is drawn left-to-right, so Right always means forward.
                if (down) vm.seekStep(forward = code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeat = repeat) else vm.commitSeek()
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) showControls()
                true
            }
            else -> false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent(::onKey)
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        VideoSurface(player, keepScreenOn = ui.isPlaying)

        val sub = ui.subtitle
        if (sub.enabled) {
            SubtitleOverlay(
                track = sub.track,
                positionMsProvider = { positionState.value },
                offsetMs = sub.offsetMs,
                style = sub.style,
            )
        }

        if (ui.buffering && ui.phase == PlayerPhase.PLAYING && ui.resumePromptMs == null) BufferingSpinner()
        if (ui.phase == PlayerPhase.LOADING) BufferingSpinner()

        if (!controlsVisible && seekPreview != null) {
            SeekHud(positionMs = seekPreview ?: 0L, durationMs = ui.durationMs)
        }

        if (!controlsVisible && sheet == PlayerSheet.None) {
            PromptsHud(ui)
        }

        AnimatedVisibility(
            visible = controlsVisible && !blocking,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ControlsOverlay(
                ui = ui,
                positionProvider = { seekPreview ?: positionState.value },
                sheetOpen = sheet != PlayerSheet.None,
                onPlayPause = vm::togglePlayPause,
                onPrev = vm::playPrev,
                onNext = vm::playNext,
                onSkip = vm::skip,
                onCancelAutoNext = vm::cancelAutoNext,
                onSeekStep = vm::seekStep,
                onSeekCommit = vm::commitSeek,
                onOpenSheet = { sheet = it },
            )
        }

        NoticeHud(ui.notice, offline = ui.offline)

        when {
            ui.phase == PlayerPhase.NOT_FOUND -> MessagePanel(if (ui.hiddenByFilter) MessageKind.HIDDEN else MessageKind.NOT_FOUND, onRetry = null, onSource = null, onBack = onBack)
            ui.phase == PlayerPhase.EXHAUSTED -> MessagePanel(MessageKind.EXHAUSTED, onRetry = vm::retry, onSource = { sheet = PlayerSheet.Source }, onBack = onBack, sheetOpen = sheet != PlayerSheet.None)
            ui.phase == PlayerPhase.PRIVATE_CONNECTION -> MessagePanel(MessageKind.PRIVATE_CONNECTION, onRetry = vm::retry, onSource = null, onBack = onBack)
            ui.resumePromptMs != null -> ResumeDialog(ui.resumePromptMs ?: 0L, onChoice = vm::onResumeChoice)
        }

        PlayerSheets(
            sheet = sheet,
            ui = ui,
            onDismiss = { sheet = PlayerSheet.None; showControls() },
            vm = vm,
        )
    }
}

private const val CONTROLS_TIMEOUT_MS = 5_000L

/** Media3's PlayerView for the video surface only: no controller, no Media3 subtitles, never focusable. */
@Composable
private fun VideoSurface(player: Player?, keepScreenOn: Boolean) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
                subtitleView?.visibility = android.view.View.GONE
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.keepScreenOn = keepScreenOn
        },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
