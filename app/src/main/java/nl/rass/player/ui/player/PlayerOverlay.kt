package nl.rass.player.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.player.SkipKind
import nl.rass.player.player.SleepTimerOption
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.components.SafeMargin
import nl.rass.player.ui.components.tvFocusable
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.formatDurationFa
import nl.rass.player.ui.format.toPersianDigits
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

private val Scrim = Color(0xD9000000)

@Composable
internal fun ControlsOverlay(
    ui: PlayerUiState,
    positionProvider: () -> Long,
    sheetOpen: Boolean,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onCancelAutoNext: () -> Unit,
    onSeekStep: (forward: Boolean, repeat: Boolean) -> Unit,
    onSeekCommit: () -> Unit,
    onOpenSheet: (PlayerSheet) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    // On show, and when a side sheet closes: focus goes back to play/pause.
    LaunchedEffect(sheetOpen) { if (!sheetOpen) runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Scrim, Color.Transparent)))
                .padding(start = SafeMargin, end = SafeMargin, top = 36.dp, bottom = 48.dp),
        ) {
            Text(ui.titleText, style = MaterialTheme.typography.headlineMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ui.episodeLabel?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                .padding(start = SafeMargin, end = SafeMargin, top = 48.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.skip != null || ui.autoNextMs != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ui.skip?.let { s ->
                        PillButton(skipLabel(s.kind), onSkip, style = PillStyle.Primary)
                    }
                    ui.autoNextMs?.let { ms ->
                        PillButton(stringResource(R.string.player_next_in, secondsFa(ms)), onNext, style = PillStyle.Accent)
                        PillButton(stringResource(R.string.player_cancel), onCancelAutoNext)
                    }
                }
            }

            SeekBar(positionProvider, ui.durationMs, onSeekStep, onSeekCommit)

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui.isSeries) RoundButton(R.drawable.ic_player_prev, onPrev, enabled = ui.hasPrev)
                RoundButton(
                    if (ui.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_play,
                    onPlayPause,
                    modifier = Modifier.focusRequester(playFocus),
                    primary = true,
                )
                if (ui.isSeries) RoundButton(R.drawable.ic_player_next, onNext, enabled = ui.hasNext)
                Spacer(Modifier.width(12.dp))
                if (ui.isSeries) ControlChip(stringResource(R.string.player_episodes)) { onOpenSheet(PlayerSheet.Episodes) }
                ControlChip(subtitleChipLabel(ui.subtitle)) { onOpenSheet(PlayerSheet.Subtitles) }
                if (ui.quality.size > 1) ControlChip(stringResource(R.string.player_quality)) { onOpenSheet(PlayerSheet.Quality) }
                if (ui.audio.isNotEmpty()) ControlChip(stringResource(R.string.player_audio)) { onOpenSheet(PlayerSheet.Audio) }
                if (ui.sources.isNotEmpty()) ControlChip(stringResource(R.string.player_source)) { onOpenSheet(PlayerSheet.Source) }
                ControlChip(stringResource(R.string.player_speed) + " " + speedLabel(ui.speed)) { onOpenSheet(PlayerSheet.Speed) }
                ControlChip(sleepChipLabel(ui)) { onOpenSheet(PlayerSheet.Sleep) }
            }
        }
    }
}

@Composable
private fun subtitleChipLabel(s: SubtitleUi): String {
    val base = stringResource(R.string.player_subtitles)
    if (!s.enabled) return "$base: ${stringResource(R.string.player_sub_bg_off)}"
    val lang = stringResource(if (s.lang == "en") R.string.player_sub_lang_en else R.string.player_sub_lang_fa)
    return "$base: $lang"
}

@Composable
private fun sleepChipLabel(ui: PlayerUiState): String {
    val base = stringResource(R.string.player_sleep)
    val rem = ui.sleepRemainingMs
    return when {
        rem != null -> "$base: " + stringResource(R.string.player_sleep_remaining, formatDurationFa(rem))
        ui.sleep == SleepTimerOption.EndOfItem -> "$base ✓"
        else -> base
    }
}

@Composable
internal fun skipLabel(kind: SkipKind) =
    stringResource(if (kind == SkipKind.INTRO) R.string.player_skip_intro else R.string.player_skip_recap)

internal fun secondsFa(ms: Long): String = ((ms + 999) / 1000).fa()

/** 1.25 → «۱٫۲۵×», 1 → «۱×». */
internal fun speedLabel(speed: Float): String {
    val s = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')
    return s.replace('.', '٫').toPersianDigits() + "×"
}

@Composable
private fun RoundButton(
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val bg = when {
        primary -> Color.White
        else -> Color(0xFF3A3E47)
    }
    val fg = if (primary) Color.Black else TextPrimary
    Box(
        modifier
            .size(60.dp)
            .tvFocusable(shape = CircleShape, enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(bg.copy(alpha = if (enabled) 1f else 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(iconRes), null, Modifier.size(30.dp), tint = fg.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

@Composable
private fun ControlChip(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(52.dp)
            .tvFocusable(shape = shape, onClick = onClick)
            .clip(shape)
            .background(Color(0xCC2A2E37))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = TextPrimary, maxLines = 1)
    }
}

/** Focusable progress bar; Left/Right seek while it has focus. Drawn left-to-right like every video timeline. */
@Composable
private fun SeekBar(
    positionProvider: () -> Long,
    durationMs: Long,
    onSeekStep: (forward: Boolean, repeat: Boolean) -> Unit,
    onSeekCommit: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    val code = e.key.nativeKeyCode
                    if (code != AndroidKeyEvent.KEYCODE_DPAD_LEFT && code != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) {
                        onSeekStep(code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT, e.nativeKeyEvent.repeatCount > 0)
                    } else if (e.type == KeyEventType.KeyUp) {
                        onSeekCommit()
                    }
                    true
                }
                .focusable()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TimeText(positionProvider)
            ProgressTrack(positionProvider, durationMs, focused, Modifier.weight(1f))
            Text(
                if (durationMs > 0) formatDurationFa(durationMs) else "--:--",
                style = MaterialTheme.typography.labelLarge,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun TimeText(positionProvider: () -> Long) {
    Text(formatDurationFa(positionProvider()), style = MaterialTheme.typography.labelLarge, color = TextPrimary)
}

@Composable
private fun ProgressTrack(positionProvider: () -> Long, durationMs: Long, focused: Boolean, modifier: Modifier) {
    val h = if (focused) 10.dp else 6.dp
    Canvas(modifier.height(24.dp)) {
        val y = size.height / 2
        val stroke = h.toPx()
        drawLine(Color(0x55FFFFFF), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        val frac = if (durationMs > 0) (positionProvider().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val x = size.width * frac
        drawLine(Accent, Offset(0f, y), Offset(x, y), stroke, StrokeCap.Round)
        if (focused) drawCircle(Color.White, radius = stroke * 1.2f, center = Offset(x, y))
    }
}

/** Time preview while seeking with the controls hidden (no thumbnails). */
@Composable
internal fun SeekHud(positionMs: Long, durationMs: Long) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                .padding(start = SafeMargin, end = SafeMargin, top = 32.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                formatDurationFa(positionMs) + if (durationMs > 0) "  /  " + formatDurationFa(durationMs) else "",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ProgressTrack({ positionMs }, durationMs, focused = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Skip intro / auto-next prompts while the controls are hidden (OK acts on them, Back cancels auto-next). */
@Composable
internal fun PromptsHud(ui: PlayerUiState) {
    if (ui.skip == null && ui.autoNextMs == null) return
    Box(Modifier.fillMaxSize().padding(SafeMargin)) {
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.skip?.let { s ->
                PromptCard(skipLabel(s.kind), null)
            }
            ui.autoNextMs?.let { ms ->
                PromptCard(stringResource(R.string.player_next_in, secondsFa(ms)), stringResource(R.string.player_next_hint))
            }
        }
    }
}

@Composable
private fun PromptCard(title: String, hint: String?) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Color(0xE6FFFFFF))
            .border(3.dp, Color.White, shape)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.Black)
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = Color(0xFF333333))
    }
}

/** Short notices (failover, subtitle auto-pick) at the top start + the offline banner. */
@Composable
internal fun NoticeHud(notice: Notice?, offline: Boolean) {
    Box(Modifier.fillMaxSize().padding(SafeMargin)) {
        Column(Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AnimatedVisibility(visible = offline, enter = fadeIn(), exit = fadeOut()) {
                Banner(stringResource(R.string.player_offline), Accent)
            }
            AnimatedVisibility(visible = notice != null, enter = fadeIn(), exit = fadeOut()) {
                var last by remember { mutableStateOf("") }
                if (notice != null) last = notice.text
                Banner(last, SurfaceRaised.copy(alpha = 0.92f))
            }
        }
    }
}

@Composable
private fun Banner(text: String, bg: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = TextPrimary,
        modifier = Modifier
            .widthIn(max = 760.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    )
}

/** Cheap spinner: one arc rotated in the graphics layer (no recomposition per frame). */
@Composable
internal fun BufferingSpinner() {
    val t = rememberInfiniteTransition(label = "spin")
    val angle = t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(64.dp).graphicsLayer { rotationZ = angle.value }) {
            drawArc(Color.White, 0f, 270f, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

enum class MessageKind { NOT_FOUND, EXHAUSTED, PRIVATE_CONNECTION }

@Composable
internal fun MessagePanel(
    kind: MessageKind,
    onRetry: (() -> Unit)?,
    onSource: (() -> Unit)?,
    onBack: () -> Unit,
    sheetOpen: Boolean = false,
) {
    val fr = remember { FocusRequester() }
    // Also when a side sheet opened from here closes, so focus is never lost.
    LaunchedEffect(kind, sheetOpen) { if (!sheetOpen) runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                stringResource(
                    when (kind) {
                        MessageKind.NOT_FOUND -> R.string.player_not_found
                        MessageKind.EXHAUSTED -> R.string.player_all_failed
                        MessageKind.PRIVATE_CONNECTION -> R.string.player_private_connection
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 720.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (onRetry != null) {
                    PillButton(stringResource(R.string.player_try_again), onRetry, Modifier.focusRequester(fr), iconRes = R.drawable.ic_refresh, style = PillStyle.Primary)
                }
                if (onSource != null) PillButton(stringResource(R.string.player_source), onSource)
                PillButton(
                    stringResource(R.string.player_back), onBack,
                    if (onRetry == null) Modifier.focusRequester(fr) else Modifier,
                    style = if (onRetry == null) PillStyle.Primary else PillStyle.Secondary,
                )
            }
        }
    }
}

/** R4.2: «ادامه از mm:ss» / «از ابتدا». */
@Composable
internal fun ResumeDialog(positionMs: Long, onChoice: (resume: Boolean) -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(SurfaceRaised).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(stringResource(R.string.player_resume_title), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(
                    stringResource(R.string.player_resume_from, formatDurationFa(positionMs)),
                    { onChoice(true) },
                    Modifier.focusRequester(fr),
                    iconRes = R.drawable.ic_play,
                    style = PillStyle.Primary,
                )
                PillButton(stringResource(R.string.player_start_over), { onChoice(false) })
            }
        }
    }
}
