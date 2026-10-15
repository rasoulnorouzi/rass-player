package nl.rass.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.data.settings.ResumeBehavior
import nl.rass.player.data.settings.SubtitleLanguagePref
import nl.rass.player.data.settings.SubtitleStyleCodec
import nl.rass.player.player.subtitle.SubtitleColor
import nl.rass.player.player.subtitle.SubtitleEdge
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.toPersianDigits
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

@Composable
fun PlaybackPage(onBack: () -> Unit, vm: PrefsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val cur = s ?: return SettingsPage(stringResource(R.string.set_sec_playback), onBack) {}
    val qualities = listOf("auto", "2160p", "1080p", "720p", "480p", "360p").map {
        it to if (it == "auto") stringResource(R.string.set_quality_auto) else it.toPersianDigits()
    }
    val resume = listOf(
        ResumeBehavior.ASK to stringResource(R.string.set_resume_ask),
        ResumeBehavior.ALWAYS_RESUME to stringResource(R.string.set_resume_always),
        ResumeBehavior.ALWAYS_START_OVER to stringResource(R.string.set_resume_never),
    )
    SettingsPage(stringResource(R.string.set_sec_playback), onBack) {
        item { ChoiceRow(stringResource(R.string.set_default_quality), qualities, cur.defaultQuality, { q -> vm.update { it.copy(defaultQuality = q) } }, subtitle = stringResource(R.string.set_default_quality_hint)) }
        item { SwitchRow(stringResource(R.string.set_autoplay_next), cur.autoplayNext, { v -> vm.update { it.copy(autoplayNext = v) } }) }
        item { ChoiceRow(stringResource(R.string.set_resume), resume, cur.resumeBehavior, { r -> vm.update { it.copy(resumeBehavior = r) } }) }
    }
}

@Composable
fun SubtitlesPage(onBack: () -> Unit, vm: PrefsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val cur = s ?: return SettingsPage(stringResource(R.string.set_sec_subtitles), onBack) {}
    val style = SubtitleStyleCodec.decode(cur.subtitleStyleJson)
    val langs = listOf(SubtitleLanguagePref.FA to stringResource(R.string.set_lang_fa), SubtitleLanguagePref.EN to stringResource(R.string.set_lang_en))
    val colors = listOf(
        SubtitleColor.WHITE to stringResource(R.string.set_color_white), SubtitleColor.YELLOW to stringResource(R.string.set_color_yellow),
        SubtitleColor.CYAN to stringResource(R.string.set_color_cyan), SubtitleColor.GREEN to stringResource(R.string.set_color_green),
    )
    val sizes = SubtitleOptions.SIZES.map { it to "${(it * 100).toInt().fa()}٪" }
    val opacities = SubtitleOptions.OPACITIES.map { it to "${(it * 100).toInt().fa()}٪" }
    val positions = listOf(
        0f to stringResource(R.string.set_pos_top), 0.25f to stringResource(R.string.set_pos_upper),
        0.5f to stringResource(R.string.set_pos_middle), 0.75f to stringResource(R.string.set_pos_lower),
        1f to stringResource(R.string.set_pos_bottom),
    )
    val edges = listOf(
        SubtitleEdge.NONE to stringResource(R.string.set_edge_none), SubtitleEdge.OUTLINE to stringResource(R.string.set_edge_outline),
        SubtitleEdge.DROP_SHADOW to stringResource(R.string.set_edge_shadow),
    )
    val colorChoice = SubtitleColor.entries.firstOrNull { it.argb == style.textColor } ?: SubtitleColor.WHITE
    SettingsPage(stringResource(R.string.set_sec_subtitles), onBack, subtitle = stringResource(R.string.set_subtitles_only_fa_en)) {
        item { SwitchRow(stringResource(R.string.set_subs_on), cur.subtitlesAutoOn && cur.subtitleLanguage != SubtitleLanguagePref.OFF, { v -> vm.update { it.copy(subtitlesAutoOn = v, subtitleLanguage = if (v && it.subtitleLanguage == SubtitleLanguagePref.OFF) SubtitleLanguagePref.FA else it.subtitleLanguage) } }) }
        item { ChoiceRow(stringResource(R.string.set_subs_lang), langs, if (cur.subtitleLanguage == SubtitleLanguagePref.EN) SubtitleLanguagePref.EN else SubtitleLanguagePref.FA, { l -> vm.update { it.copy(subtitleLanguage = l) } }, subtitle = stringResource(R.string.set_subs_lang_hint)) }
        item { SubtitlePreview(style) }
        item { ChoiceRow(stringResource(R.string.set_sub_color), colors, colorChoice, { c -> vm.updateStyle { it.withColor(c) } }) }
        item { ChoiceRow(stringResource(R.string.set_sub_size), sizes, SubtitleOptions.nearest(SubtitleOptions.SIZES, style.sizeScale), { v -> vm.updateStyle { it.copy(sizeScale = v) } }) }
        item { SwitchRow(stringResource(R.string.set_sub_bg), style.backgroundEnabled, { v -> vm.updateStyle { it.copy(backgroundEnabled = v) } }) }
        item { ChoiceRow(stringResource(R.string.set_sub_bg_opacity), opacities, SubtitleOptions.nearest(SubtitleOptions.OPACITIES, style.backgroundOpacity), { v -> vm.updateStyle { it.copy(backgroundOpacity = v) } }) }
        item { ChoiceRow(stringResource(R.string.set_sub_edge), edges, style.edge, { v -> vm.updateStyle { it.copy(edge = v) } }) }
        item { ChoiceRow(stringResource(R.string.set_sub_position), positions, SubtitleOptions.nearest(positions.map { it.first }, style.verticalPosition), { v -> vm.updateStyle { it.copy(verticalPosition = v) } }) }
    }
}

/** Choices offered on the Subtitles page (a subset of what the player menu allows). */
object SubtitleOptions {
    val SIZES = listOf(0.8f, 1f, 1.2f, 1.4f, 1.6f, 2f)
    val OPACITIES = listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)
    fun nearest(options: List<Float>, v: Float): Float = options.minByOrNull { kotlin.math.abs(it - v) } ?: v
}

@Composable
private fun SubtitlePreview(style: nl.rass.player.player.subtitle.SubtitleStyle) {
    Box(
        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2B3340)),
        contentAlignment = Alignment.TopCenter,
    ) {
        val align = when {
            style.verticalPosition < 0.2f -> Alignment.TopCenter
            style.verticalPosition < 0.6f -> Alignment.Center
            else -> Alignment.BottomCenter
        }
        Box(Modifier.fillMaxWidth().height(110.dp).padding(8.dp), contentAlignment = align) {
            Text(
                stringResource(R.string.set_sub_sample),
                style = TextStyle(
                    color = Color(style.textColor), fontSize = (22 * style.sizeScale).sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (style.backgroundEnabled) Color.Black.copy(alpha = style.backgroundOpacity) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun OnlineSubtitlesPage(onBack: () -> Unit, vm: OnlineSubtitlesViewModel = hiltViewModel()) {
    val c by vm.config.collectAsState()
    val cur = c ?: return SettingsPage(stringResource(R.string.set_sec_online), onBack) {}
    var editing by remember { mutableStateOf<String?>(null) }
    val set = stringResource(R.string.set_secret_set)
    val unset = stringResource(R.string.set_secret_unset)
    SettingsPage(stringResource(R.string.set_sec_online), onBack, subtitle = stringResource(R.string.set_online_attribution)) {
        item { SwitchRow(stringResource(R.string.set_subdl_enable), cur.subDlEnabled, { v -> vm.update { it.copy(subDlEnabled = v) } }) }
        item { SettingRow(stringResource(R.string.set_subdl_key), { editing = "subdl_key" }, value = if (cur.subDlApiKey.isNotEmpty()) set else unset) }
        item { SwitchRow(stringResource(R.string.set_os_enable), cur.openSubtitlesEnabled, { v -> vm.update { it.copy(openSubtitlesEnabled = v) } }) }
        item { SettingRow(stringResource(R.string.set_os_key), { editing = "os_key" }, value = if (cur.openSubtitlesApiKey.isNotEmpty()) set else unset) }
        item { SettingRow(stringResource(R.string.set_os_user), { editing = "os_user" }, value = cur.openSubtitlesUsername.ifEmpty { unset }) }
        item { SettingRow(stringResource(R.string.set_os_pass), { editing = "os_pass" }, subtitle = stringResource(R.string.set_os_pass_hint), value = if (cur.openSubtitlesPassword.isNotEmpty()) set else unset) }
        item { SwitchRow(stringResource(R.string.set_online_prefer_en), cur.preferEnglish, { v -> vm.update { it.copy(preferEnglish = v) } }, subtitle = stringResource(R.string.set_online_prefer_en_hint)) }
        item { StatusText(stringResource(R.string.set_online_note)) }
        item { StatusText(stringResource(R.string.set_online_attribution), modifier = Modifier.padding(top = 8.dp)) }
    }
    editing?.let { which ->
        val (titleRes, initial, secret) = when (which) {
            "subdl_key" -> Triple(R.string.set_subdl_key, "", true)
            "os_key" -> Triple(R.string.set_os_key, "", true)
            "os_user" -> Triple(R.string.set_os_user, cur.openSubtitlesUsername, false)
            else -> Triple(R.string.set_os_pass, "", true)
        }
        fun apply(v: String) {
            vm.update {
                when (which) {
                    "subdl_key" -> it.copy(subDlApiKey = v)
                    "os_key" -> it.copy(openSubtitlesApiKey = v)
                    "os_user" -> it.copy(openSubtitlesUsername = v)
                    else -> it.copy(openSubtitlesPassword = v)
                }
            }
            editing = null
        }
        TextEntryDialog(
            stringResource(titleRes), stringResource(titleRes), initial,
            stringResource(R.string.set_save), stringResource(R.string.set_cancel),
            onSave = { if (it.isNotEmpty() || !secret) apply(it) else editing = null },
            onDismiss = { editing = null },
            password = secret, ltr = true,
            hint = if (secret) stringResource(R.string.set_secret_hint) else null,
            clearText = stringResource(R.string.set_clear), onClear = { apply("") },
        )
    }
}
