package nl.rass.player.ui.player

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.util.Locale
import nl.rass.player.R
import nl.rass.player.domain.CatalogEpisode
import nl.rass.player.domain.Quality
import nl.rass.player.domain.WatchProgress
import nl.rass.player.player.PlaybackSpeeds
import nl.rass.player.player.QualityOption
import nl.rass.player.player.SleepTimerOption
import nl.rass.player.player.subtitle.SubtitleColor
import nl.rass.player.player.subtitle.SubtitleOffset
import nl.rass.player.player.subtitle.SubtitleStyle
import nl.rass.player.ui.components.ChipItem
import nl.rass.player.ui.components.ChipRow
import nl.rass.player.ui.components.RassImage
import nl.rass.player.ui.components.SideSheet
import nl.rass.player.ui.components.tvFocusable
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.toPersianDigits
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

private val RowShape = RoundedCornerShape(10.dp)
private val RowBg = Color(0xFF242934)

@Composable
internal fun PlayerSheets(sheet: PlayerSheet, ui: PlayerUiState, onDismiss: () -> Unit, vm: PlayerViewModel) {
    // Keep the last content while the sheet slides out.
    var shown by remember { mutableStateOf(sheet) }
    if (sheet != PlayerSheet.None) shown = sheet
    SideSheet(
        visible = sheet != PlayerSheet.None,
        onDismiss = onDismiss,
        width = if (shown == PlayerSheet.Episodes || shown == PlayerSheet.Subtitles) 680.dp else 540.dp,
    ) {
        when (shown) {
            PlayerSheet.None -> Unit
            PlayerSheet.Episodes -> EpisodesSheet(ui) { vm.playEpisode(it); onDismiss() }
            PlayerSheet.Subtitles -> SubtitleSheet(ui.subtitle, vm)
            PlayerSheet.Quality -> OptionSheet(
                stringResource(R.string.player_quality),
                ui.quality.map { o -> MenuEntry(qualityOptionLabel(o), null, o.selected) { vm.selectQuality(o); onDismiss() } },
            )
            PlayerSheet.Audio -> OptionSheet(
                stringResource(R.string.player_audio),
                ui.audio.map { a -> MenuEntry(a.label, null, a.selected) { vm.selectAudio(a); onDismiss() } },
            )
            PlayerSheet.Source -> {
                val failed = stringResource(R.string.player_failed_mark)
                OptionSheet(
                    stringResource(R.string.player_source),
                    ui.sources.map { s ->
                        val parts = listOfNotNull(
                            s.sourceName,
                            qualityLabel(s.quality),
                            s.bitrateKbps?.let { "${it.fa()} kbps" },
                            failed.takeIf { s.failed },
                        )
                        MenuEntry("${(s.index + 1).fa()}. ${s.label}", parts.joinToString(" · "), s.current) {
                            vm.selectSource(s.index); onDismiss()
                        }
                    },
                )
            }
            PlayerSheet.Speed -> {
                val normal = stringResource(R.string.player_speed_normal)
                OptionSheet(
                    stringResource(R.string.player_speed),
                    PlaybackSpeeds.ALL.map { sp ->
                        MenuEntry(speedLabel(sp) + if (sp == 1f) " ($normal)" else "", null, ui.speed == sp) { vm.setSpeed(sp); onDismiss() }
                    },
                )
            }
            PlayerSheet.Sleep -> OptionSheet(
                stringResource(R.string.player_sleep),
                SleepTimerOption.ALL.map { o ->
                    MenuEntry(sleepLabel(o), null, ui.sleep == o) { vm.setSleep(o); onDismiss() }
                },
            )
        }
    }
}

@Composable
private fun qualityLabel(q: Quality): String? = when (q) {
    Quality.Auto -> stringResource(R.string.player_quality_auto)
    is Quality.Height -> "${q.px}p".toPersianDigits()
    Quality.Unknown -> null
}

@Composable
private fun qualityOptionLabel(o: QualityOption): String = when (o) {
    is QualityOption.Auto -> stringResource(R.string.player_quality_auto)
    is QualityOption.Variant -> stringResource(R.string.player_quality_variant, "${o.info.height}p".toPersianDigits())
    is QualityOption.Fixed -> stringResource(R.string.player_quality_fixed, "${o.height}p".toPersianDigits())
}

@Composable
private fun sleepLabel(o: SleepTimerOption): String = when (o) {
    SleepTimerOption.Off -> stringResource(R.string.player_sleep_off)
    is SleepTimerOption.Minutes -> stringResource(R.string.player_sleep_minutes, o.minutes.fa())
    SleepTimerOption.EndOfItem -> stringResource(R.string.player_sleep_end)
}

// ---- generic rows ------------------------------------------------------------------------------------------

private data class MenuEntry(val text: String, val secondary: String?, val selected: Boolean, val onClick: () -> Unit)

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = TextPrimary, modifier = Modifier.padding(bottom = 16.dp))
}

@Composable
private fun OptionSheet(title: String, entries: List<MenuEntry>) {
    val fr = remember { FocusRequester() }
    val selected = entries.indexOfFirst { it.selected }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (entries.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(selected)
        withFrameNanos { }
        runCatching { fr.requestFocus() }
    }
    Column {
        SheetTitle(title)
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(entries) { i, e ->
                MenuRow(e.text, e.secondary, e.selected, e.onClick, if (i == selected) Modifier.focusRequester(fr) else Modifier)
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, secondary: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .tvFocusable(shape = RowShape, scale = 1.02f, onClick = onClick)
            .clip(RowShape)
            .background(if (selected) Color(0xFF353B48) else RowBg)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (secondary != null) Text(secondary, style = MaterialTheme.typography.labelMedium, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Text("✓", style = MaterialTheme.typography.titleMedium, color = Accent)
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(
        modifier
            .height(48.dp)
            .tvFocusable(shape = RowShape, scale = 1.04f, onClick = onClick)
            .clip(RowShape)
            .background(if (selected) Color.White else RowBg)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.Black else TextPrimary, maxLines = 1)
    }
}

/** OK = one small step; holding OK repeats big steps (R6.3: 100 ms, hold = 1 s), throttled to 5/s. */
@Composable
private fun RepeatStepButton(text: String, onStep: (fast: Boolean) -> Unit) {
    var lastFast by remember { mutableLongStateOf(0L) }
    SmallButton(
        text,
        onClick = { onStep(false) },
        modifier = Modifier.onPreviewKeyEvent { e ->
            val c = e.key.nativeKeyCode
            if (c != AndroidKeyEvent.KEYCODE_DPAD_CENTER && c != AndroidKeyEvent.KEYCODE_ENTER && c != AndroidKeyEvent.KEYCODE_NUMPAD_ENTER) {
                return@onPreviewKeyEvent false
            }
            if (e.type == KeyEventType.KeyDown) {
                if (e.nativeKeyEvent.repeatCount == 0) {
                    onStep(false)
                } else {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFast >= 200) {
                        lastFast = now
                        onStep(true)
                    }
                }
            }
            true
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = TextMuted, modifier = Modifier.padding(top = 10.dp))
}

// ---- episodes (R5.8) ---------------------------------------------------------------------------------------

@Composable
private fun EpisodesSheet(ui: PlayerUiState, onPick: (CatalogEpisode) -> Unit) {
    val currentSeason = ui.seasons.firstOrNull { s -> s.episodes.any { it.key == ui.currentEpisodeKey } }?.number
        ?: ui.seasons.firstOrNull()?.number
    var season by remember { mutableStateOf(currentSeason) }
    val eps = ui.seasons.firstOrNull { it.number == season }?.episodes.orEmpty()
    val listState = rememberLazyListState()
    val currentIdx = eps.indexOfFirst { it.key == ui.currentEpisodeKey }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (currentIdx < 0) return@LaunchedEffect
        listState.scrollToItem(currentIdx)
        withFrameNanos { }
        runCatching { fr.requestFocus() }
    }
    Column {
        SheetTitle(stringResource(R.string.player_episodes))
        if (ui.seasons.size > 1) {
            ChipRow(
                chips = ui.seasons.map { ChipItem(it.number.toString(), it.displayName.toPersianDigits()) },
                selectedId = season.toString(),
                onSelect = { season = it.toIntOrNull() },
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(eps, key = { _, e -> e.key }) { i, e ->
                EpisodeRow(
                    e,
                    ui.episodeProgress[e.key],
                    current = e.key == ui.currentEpisodeKey,
                    onClick = { onPick(e) },
                    modifier = if (i == currentIdx) Modifier.focusRequester(fr) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: CatalogEpisode, progress: WatchProgress?, current: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .tvFocusable(shape = RowShape, scale = 1.02f, onClick = onClick)
            .clip(RowShape)
            .background(if (current) Color(0xFF353B48) else RowBg)
            .then(if (current) Modifier.border(2.dp, Accent, RowShape) else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RassImage(
            url = e.still?.urlFor(320),
            widthDp = 160.dp,
            heightDp = 90.dp,
            placeholderSeed = e.key,
            modifier = Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val head = stringResource(R.string.player_episode_number, e.number.fa())
            val name = e.titleFa ?: e.titleEn
            Text(
                if (name != null) "$head: $name" else head,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                progress?.completed == true -> Text(stringResource(R.string.player_watched), style = MaterialTheme.typography.labelMedium, color = TextMuted)
                progress != null && progress.fraction > 0f -> Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0x44FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress.fraction).height(4.dp).background(Accent))
                }
            }
        }
    }
}

// ---- subtitles (R6.3, R6.5, R6.6, R6.7) ----------------------------------------------------------------------

@Composable
private fun SubtitleSheet(sub: SubtitleUi, vm: PlayerViewModel) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    Column {
        SheetTitle(stringResource(R.string.player_subtitles))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                MenuRow(
                    stringResource(if (sub.enabled) R.string.player_sub_on else R.string.player_sub_off),
                    null,
                    sub.enabled,
                    { vm.setSubtitlesEnabled(!sub.enabled) },
                    Modifier.focusRequester(firstFocus),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton(stringResource(R.string.player_sub_lang_fa), { vm.setSubtitleLanguage("fa") }, selected = sub.enabled && sub.lang == "fa")
                    SmallButton(stringResource(R.string.player_sub_lang_en), { vm.setSubtitleLanguage("en") }, selected = sub.enabled && sub.lang == "en")
                }
            }
            item { SectionLabel(stringResource(R.string.player_sub_tracks)) }
            if (sub.choices.isEmpty()) {
                item { Text(stringResource(R.string.player_sub_none), style = MaterialTheme.typography.labelLarge, color = TextMuted) }
            }
            choiceItems(sub, vm)
            item {
                MenuRow(
                    stringResource(if (sub.searching) R.string.player_sub_searching else R.string.player_sub_search_online),
                    null, false, { if (!sub.searching) vm.searchOnline() },
                )
            }
            if (sub.loading) {
                item { Text(stringResource(R.string.player_sub_loading), style = MaterialTheme.typography.labelLarge, color = TextMuted) }
            }

            item { SectionLabel(stringResource(R.string.player_sub_sync) + " · " + stringResource(R.string.player_sub_sync_hint)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RepeatStepButton(stringResource(R.string.player_sub_earlier)) { fast ->
                        vm.stepOffset(-(if (fast) SubtitleOffset.FAST_STEP_MS else SubtitleOffset.STEP_MS))
                    }
                    Text(
                        stringResource(R.string.player_sub_offset, offsetText(sub.offsetMs)),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.width(150.dp),
                    )
                    RepeatStepButton(stringResource(R.string.player_sub_later)) { fast ->
                        vm.stepOffset(if (fast) SubtitleOffset.FAST_STEP_MS else SubtitleOffset.STEP_MS)
                    }
                    SmallButton(stringResource(R.string.player_sub_reset), vm::resetOffset)
                }
            }

            item { SectionLabel(stringResource(R.string.player_sub_color)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SubtitleColor.entries.forEach { c ->
                        SmallButton(colorName(c), { vm.updateStyle { it.withColor(c) } }, selected = sub.style.textColor == c.argb)
                    }
                }
            }

            item { SectionLabel(stringResource(R.string.player_sub_background)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton(
                        stringResource(if (sub.style.backgroundEnabled) R.string.player_sub_bg_on else R.string.player_sub_bg_off),
                        { vm.updateStyle { it.copy(backgroundEnabled = !it.backgroundEnabled) } },
                        selected = sub.style.backgroundEnabled,
                    )
                    SmallButton(stringResource(R.string.player_minus), { vm.updateStyle { it.copy(backgroundEnabled = true, backgroundOpacity = (it.backgroundOpacity - 0.1f).coerceIn(0.2f, 1f)) } })
                    Text(
                        stringResource(R.string.player_sub_opacity, (sub.style.backgroundOpacity * 100).toInt().fa()),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                    )
                    SmallButton(stringResource(R.string.player_plus), { vm.updateStyle { it.copy(backgroundEnabled = true, backgroundOpacity = (it.backgroundOpacity + 0.1f).coerceIn(0.2f, 1f)) } })
                }
            }

            item { SectionLabel(stringResource(R.string.player_sub_size)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton(stringResource(R.string.player_minus), { vm.updateStyle { it.scaledBy(-SubtitleStyle.SCALE_STEP) } })
                    Text(
                        stringResource(R.string.player_sub_size_value, Math.round(sub.style.sizeScale * 100).fa()),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                    )
                    SmallButton(stringResource(R.string.player_plus), { vm.updateStyle { it.scaledBy(SubtitleStyle.SCALE_STEP) } })
                }
            }

            item { SectionLabel(stringResource(R.string.player_sub_position)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton(stringResource(R.string.player_sub_up), { vm.updateStyle { it.moveBy(-SubtitleStyle.POSITION_STEP) } })
                    SmallButton(stringResource(R.string.player_sub_down), { vm.updateStyle { it.moveBy(SubtitleStyle.POSITION_STEP) } })
                    SmallButton(stringResource(R.string.player_sub_top), { vm.updateStyle { it.atTop() } }, selected = sub.style.verticalPosition == SubtitleStyle.TOP)
                    SmallButton(stringResource(R.string.player_sub_bottom), { vm.updateStyle { it.atBottom() } }, selected = sub.style.verticalPosition == SubtitleStyle.BOTTOM)
                }
            }
            item {
                Box(Modifier.padding(top = 12.dp)) {
                    MenuRow(stringResource(R.string.player_sub_save_default), null, false, vm::saveSubtitleDefaults)
                }
            }
        }
    }
}

private fun LazyListScope.choiceItems(sub: SubtitleUi, vm: PlayerViewModel) {
    itemsIndexed(sub.choices, key = { _, c -> c.id }) { _, c ->
        val lang = stringResource(if (c.lang == "en") R.string.player_sub_lang_en else R.string.player_sub_lang_fa)
        MenuRow(c.label, "$lang · ${c.origin}", sub.enabled && c.id == sub.selectedId, { vm.selectSubtitle(c.id) })
    }
}

/** +1.2 → «+۱٫۲» (left-to-right mark keeps the sign in front in RTL). */
internal fun offsetText(ms: Long): String =
    "\u200E" + String.format(Locale.US, "%+.1f", ms / 1000.0).replace('.', '٫').toPersianDigits()

@Composable
private fun colorName(c: SubtitleColor) = stringResource(
    when (c) {
        SubtitleColor.WHITE -> R.string.player_color_white
        SubtitleColor.YELLOW -> R.string.player_color_yellow
        SubtitleColor.CYAN -> R.string.player_color_cyan
        SubtitleColor.GREEN -> R.string.player_color_green
    },
)
