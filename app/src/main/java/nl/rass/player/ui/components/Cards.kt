package nl.rass.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

val PosterWidth = 180.dp
val PosterHeight = 270.dp // 2:3
val LandscapeWidth = 320.dp
val LandscapeHeight = 180.dp // 16:9

/** A small label on a card (source name, "new episode", ...). */
@Composable
fun CardBadge(text: String, modifier: Modifier = Modifier, background: Color = Color(0xCC000000)) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 16.sp),
        color = TextPrimary,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Red numbered tag for Top 10 rows. */
@Composable
fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    Text(
        text = rank.toString(),
        style = MaterialTheme.typography.titleMedium,
        color = TextPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(bottomEnd = 10.dp, topStart = 10.dp))
            .background(Accent)
            .padding(horizontal = 14.dp, vertical = 2.dp),
    )
}

@Composable
private fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(5.dp).background(Color(0x99000000))) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(5.dp)
                .background(Accent),
        )
    }
}

@Composable
private fun MediaCard(
    imageUrl: String?,
    title: String,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier,
    showTitle: Boolean,
    progress: Float?,
    badges: List<String>,
    rank: Int?,
    onFocusChange: ((Boolean) -> Unit)?,
) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier
                .size(width, height)
                .tvFocusable(onFocusChange = onFocusChange, onClick = onClick)
                .clip(CardShape),
        ) {
            RassImage(imageUrl, width, height, Modifier.matchParentSize(), placeholderSeed = title)
            if (badges.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) { badges.forEach { CardBadge(it) } }
            }
            if (rank != null) RankBadge(rank, Modifier.align(Alignment.TopStart))
            if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomCenter))
        }
        if (showTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** Portrait 2:3 card. */
@Composable
fun PosterCard(
    imageUrl: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    progress: Float? = null,
    badges: List<String> = emptyList(),
    rank: Int? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
) = MediaCard(
    imageUrl, title, PosterWidth, PosterHeight, onClick, modifier, showTitle, progress, badges, rank, onFocusChange,
)

/** Landscape 16:9 card (Continue Watching, Top 10, episodes). */
@Composable
fun LandscapeCard(
    imageUrl: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    progress: Float? = null,
    badges: List<String> = emptyList(),
    rank: Int? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
) = MediaCard(
    imageUrl, title, LandscapeWidth, LandscapeHeight, onClick, modifier, showTitle, progress, badges, rank, onFocusChange,
)

/** Muted caption text helper used by cards/rows. */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = modifier)
