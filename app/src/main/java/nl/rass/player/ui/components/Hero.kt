package nl.rass.player.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.Bg
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

/** Plain data for the billboard (no domain types). */
data class HeroInfo(
    val id: String,
    val title: String,
    val backdropUrl: String?,
    val logoUrl: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
)

@Composable
fun HeroBillboard(
    info: HeroInfo,
    pageCount: Int,
    pageIndex: Int,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    modifier: Modifier = Modifier,
    playFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    Box(modifier.fillMaxWidth().height(380.dp)) {
        // Backdrop crossfades when the featured item changes.
        Crossfade(info, animationSpec = tween(500), label = "heroBackdrop", modifier = Modifier.fillMaxSize()) { i ->
            RassImage(
                i.backdropUrl, 1280.dp, 720.dp,
                Modifier.fillMaxSize().heroFades(),
                placeholderSeed = i.title, crossfade = false,
            )
        }
        Crossfade(info, animationSpec = tween(300), label = "heroText", modifier = Modifier.fillMaxSize()) { i ->
            Column(
                Modifier.fillMaxHeight().width(620.dp).padding(start = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (i.logoUrl != null) {
                    RassImage(i.logoUrl, 480.dp, 140.dp, Modifier.size(480.dp, 140.dp), placeholderSeed = i.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                } else {
                    Text(i.title, style = MaterialTheme.typography.displayMedium, color = TextPrimary, maxLines = 2)
                }
                HeroMeta(i)
                if (i.description != null) {
                    Text(
                        i.description, style = MaterialTheme.typography.bodyLarge, color = TextPrimary.copy(alpha = 0.9f),
                        maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(
                        stringResource(R.string.action_play), onPlay,
                        if (playFocusRequester != null) Modifier.focusRequester(playFocusRequester) else Modifier,
                        iconRes = R.drawable.ic_play, style = PillStyle.Primary,
                    )
                    PillButton(stringResource(R.string.action_more_info), onMoreInfo, iconRes = R.drawable.ic_info)
                }
            }
        }
        if (pageCount > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { repeat(pageCount) { Dot(it == pageIndex) } }
        }
    }
}

@Composable
private fun HeroMeta(i: HeroInfo) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (i.rating != null) {
            Icon(painterResource(R.drawable.ic_star), null, Modifier.size(22.dp), tint = Color(0xFFFACC15))
            Text(i.rating, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
        }
        val rest = listOfNotNull(i.year, i.type) + i.genres
        if (rest.isNotEmpty()) {
            Text(rest.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = TextMuted, maxLines = 1)
        }
    }
}

/** Gradient fades into the app background: from the bottom and from the start (text) side. */
private fun Modifier.heroFades(): Modifier = drawWithCache {
    val rtl = layoutDirection == LayoutDirection.Rtl
    val bottom = Brush.verticalGradient(listOf(Color.Transparent, Bg), startY = size.height * 0.45f, endY = size.height)
    val side = Brush.horizontalGradient(
        colors = listOf(Bg.copy(alpha = 0.95f), Color.Transparent),
        startX = if (rtl) size.width else 0f,
        endX = if (rtl) size.width * 0.35f else size.width * 0.65f,
    )
    onDrawWithContent {
        drawContent()
        drawRect(side)
        drawRect(bottom)
    }
}
