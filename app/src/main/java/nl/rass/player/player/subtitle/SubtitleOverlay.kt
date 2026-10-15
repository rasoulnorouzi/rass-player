package nl.rass.player.player.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.rass.player.ui.theme.Vazirmatn

private val SafeMargin = 48.dp
private val BaseFontSize = 30.sp

/**
 * Draws the active subtitle cue(s) over the video. Our own renderer replaces Media3's text renderer
 * (MEMORY.md, Decisions) for exact live sync, full style control and correct Persian RTL.
 *
 * Recomposes only when the visible cue set changes: [positionMsProvider] should read a Compose state that
 * the caller updates at about 10 Hz; the segment index is derived from it with [derivedStateOf].
 *
 * @param offsetMs sync offset; positive shows subtitles later (see [SubtitleTrack]).
 */
@Composable
fun SubtitleOverlay(
    track: SubtitleTrack?,
    positionMsProvider: () -> Long,
    offsetMs: Long,
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
) {
    if (track == null || track.isEmpty) return
    val provider by rememberUpdatedState(positionMsProvider)
    val offset by rememberUpdatedState(offsetMs)
    val segment by remember(track) { derivedStateOf { track.segmentIndexAt(provider(), offset) } }

    val cues = track.cuesInSegment(segment)
    if (cues.isEmpty()) return
    val lines = remember(cues) { cues.flatMap { it.lines } }

    Box(modifier.fillMaxSize().padding(SafeMargin)) {
        val bias = style.verticalPosition.coerceIn(SubtitleStyle.TOP, SubtitleStyle.BOTTOM) * 2f - 1f
        Column(
            modifier = Modifier.align(BiasAlignment(0f, bias)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (line in lines) SubtitleLine(line, style)
        }
    }
}

@Composable
private fun SubtitleLine(line: StyledLine, style: SubtitleStyle) {
    val fontSize = BaseFontSize * style.sizeScale
    val edgeColor = Color(style.edgeColor)
    val baseStyle = TextStyle(
        color = Color(style.textColor),
        fontFamily = Vazirmatn,
        fontWeight = FontWeight.Medium,
        fontSize = fontSize,
        lineHeight = fontSize * 1.3f,
        textAlign = TextAlign.Center,
        // Per-line bidi: Persian lines lay out RTL, English lines LTR.
        textDirection = TextDirection.Content,
        shadow = if (style.edge == SubtitleEdge.DROP_SHADOW) {
            Shadow(edgeColor, Offset(2f, 2f), blurRadius = 4f)
        } else {
            null
        },
    )
    val fill = remember(line) { line.toAnnotated(withColors = true) }

    val box = if (style.backgroundEnabled) {
        Modifier
            .background(Color.Black.copy(alpha = style.backgroundOpacity.coerceIn(0f, 1f)), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp)
    } else {
        Modifier.padding(horizontal = 4.dp)
    }

    Box(box) {
        if (style.edge == SubtitleEdge.OUTLINE) {
            // Outline = the same text drawn underneath with a stroke, so it has identical layout.
            val strokePx = with(LocalDensity.current) { fontSize.toPx() * 0.14f }
            val outline = remember(line) { line.toAnnotated(withColors = false) }
            BasicText(
                text = outline,
                style = baseStyle.copy(
                    color = edgeColor,
                    drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round),
                ),
            )
        }
        BasicText(text = fill, style = baseStyle)
    }
}

private fun StyledLine.toAnnotated(withColors: Boolean): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val spanStyle = SpanStyle(
            color = if (withColors && span.color != null) Color(span.color) else Color.Unspecified,
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = if (span.underline) TextDecoration.Underline else null,
        )
        withStyle(spanStyle) { append(span.text) }
    }
}
