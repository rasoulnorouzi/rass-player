package nl.rass.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.Bg
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

/** Screen-level scaffold: bg colour + 48dp title-safe margins, optional top bar above the content. */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().background(Bg).padding(SafeMargin)) {
        topBar?.invoke()
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

val SafeMargin = 48.dp

enum class PillStyle { Primary, Secondary, Accent }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    style: PillStyle = PillStyle.Secondary,
) {
    val (bg, fg, ring) = when (style) {
        PillStyle.Primary -> Triple(Color.White, Bg, Accent)
        PillStyle.Secondary -> Triple(Color(0x66FFFFFF).compositeOverBg(), TextPrimary, Color.White)
        PillStyle.Accent -> Triple(Accent, TextPrimary, Color.White)
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(56.dp)
            .tvFocusable(shape = shape, ringColor = ring, onClick = onClick)
            .clip(shape)
            .background(bg)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (iconRes != null) Icon(painterResource(iconRes), null, Modifier.size(26.dp), tint = fg)
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

private fun Color.compositeOverBg(): Color = Color(0xFF3A3E47)

data class ChipItem(val id: String, val label: String)

/** Single-select chip row (filter / sort). OK selects the focused chip. */
@Composable
fun ChipRow(
    chips: List<ChipItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyRow(modifier, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(chips, key = { it.id }) { chip ->
            val selected = chip.id == selectedId
            val shape = RoundedCornerShape(50)
            Text(
                chip.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Bg else TextPrimary,
                maxLines = 1,
                modifier = Modifier
                    .tvFocusable(shape = shape, onClick = { onSelect(chip.id) })
                    .clip(shape)
                    .background(if (selected) Color.White else SurfaceRaised)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}

/** Title with a thin red vertical bar at the start edge and an optional muted subtitle. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(5.dp).height(if (subtitle != null) 52.dp else 34.dp).clip(RoundedCornerShape(3.dp)).background(Accent))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        }
    }
}

@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.empty_title),
    message: String? = null,
) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = TextPrimary, textAlign = TextAlign.Center)
        if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge, color = TextMuted, textAlign = TextAlign.Center)
    }
}

/** Message + retry. The retry button takes initial focus so the D-pad is never stranded. */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.error_title),
) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = TextPrimary, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = TextMuted, textAlign = TextAlign.Center)
        PillButton(
            stringResource(R.string.action_retry), onRetry,
            Modifier.focusRequester(fr), iconRes = R.drawable.ic_refresh, style = PillStyle.Primary,
        )
    }
}

/** Cheap shimmer: one alpha pulse read in the graphicsLayer (no recomposition per frame). */
@Composable
fun Modifier.shimmer(): Modifier {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a = t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    return this.graphicsLayer { alpha = a.value }.background(SurfaceRaised)
}

@Composable
fun LoadingShimmer(
    modifier: Modifier = Modifier,
    cardWidth: androidx.compose.ui.unit.Dp = PosterWidth,
    cardHeight: androidx.compose.ui.unit.Dp = PosterHeight,
    count: Int = 8,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(count) { Box(Modifier.size(cardWidth, cardHeight).clip(CardShape).shimmer()) }
    }
}

@Composable
fun Dot(active: Boolean) {
    Box(
        Modifier
            .size(width = if (active) 28.dp else 10.dp, height = 10.dp)
            .clip(CircleShape)
            .background(if (active) Accent else Color(0x66FFFFFF)),
    )
}
