package nl.rass.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import nl.rass.player.ui.theme.Bg
import nl.rass.player.ui.theme.SurfaceRaised
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.data.source.PresetSources
import nl.rass.player.data.source.RefreshInterval
import nl.rass.player.data.source.Source
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.components.SectionHeader
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.formatIntervalFa
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

@Composable
fun SourcesPage(onBack: () -> Unit, vm: SourcesViewModel = hiltViewModel()) {
    val sources by vm.sources.collectAsState()
    val tests by vm.tests.collectAsState()
    val lan by vm.lan.collectAsState()
    var form by remember { mutableStateOf<Source?>(null) }
    var adding by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf<Source?>(null) }
    var deleting by remember { mutableStateOf<Source?>(null) }
    var confirmRemovePresets by remember { mutableStateOf(false) }
    var confirmRestorePresets by remember { mutableStateOf(false) }

    SettingsPage(stringResource(R.string.set_sec_sources), onBack, subtitle = stringResource(R.string.set_sources_hint)) {
        item {
            ActionBar {
                PillButton(stringResource(R.string.set_source_add), { vm.resetForm(); adding = true }, iconRes = R.drawable.ic_add, style = PillStyle.Accent)
                PillButton(stringResource(R.string.set_source_add_phone), { vm.startLan() }, iconRes = R.drawable.ic_phone)
                if (!sources.isNullOrEmpty()) PillButton(
                    stringResource(R.string.set_source_test_all),
                    { sources.orEmpty().forEach { vm.test(it) } }, iconRes = R.drawable.ic_check,
                )
            }
        }
        item { SectionHeader(stringResource(R.string.set_sources_list_header), Modifier.padding(top = 12.dp, start = 8.dp)) }
        val list = sources.orEmpty()
        val presetCount = list.count { it.isPreset }
        val missingPresets = sources != null && PresetSources.ALL.any { p -> list.none { it.url == p.url } }
        if (presetCount > 0) item {
            SettingRow(
                stringResource(R.string.set_presets_remove_all), { confirmRemovePresets = true },
                subtitle = stringResource(R.string.set_presets_remove_all_hint, presetCount.fa()),
            )
        }
        if (missingPresets) item {
            SettingRow(stringResource(R.string.set_presets_restore), { confirmRestorePresets = true }, subtitle = stringResource(R.string.set_presets_restore_hint))
        }
        if (sources != null && list.isEmpty()) item { StatusText(stringResource(R.string.set_sources_empty)) }
        items(list, key = { it.id }) { s ->
            val t = tests[s.id]
            SettingRow(
                s.name,
                onClick = { actions = s },
                subtitle = sourceSubtitle(s, t),
                trailing = {
                    if (s.isPreset) Badge(stringResource(R.string.set_preset_badge))
                    if (s.isInsecure) Badge(stringResource(R.string.set_insecure_badge))
                    SwitchVisual(s.enabled, showLabel = true)
                    DeleteButton { deleting = s }
                },
            )
        }
    }

    if (adding) SourceFormDialog(null, vm, onDismiss = { adding = false; vm.resetForm() })
    form?.let { s -> SourceFormDialog(s, vm, onDismiss = { form = null; vm.resetForm() }) }

    actions?.let { s ->
        val idx = list(sources).indexOfFirst { it.id == s.id }
        val cur = list(sources).firstOrNull { it.id == s.id } ?: s
        SettingsDialog({ actions = null }, cur.name) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow(stringResource(if (cur.enabled) R.string.set_source_disable else R.string.set_source_enable), { vm.setEnabled(cur.id, !cur.enabled); actions = null })
                SettingRow(stringResource(R.string.set_source_test), { vm.test(cur); actions = null })
                if (idx > 0) SettingRow(stringResource(R.string.set_source_up), { vm.move(cur.id, -1); actions = null })
                if (idx >= 0 && idx < list(sources).lastIndex) SettingRow(stringResource(R.string.set_source_down), { vm.move(cur.id, 1); actions = null })
                SettingRow(stringResource(R.string.set_source_edit), { form = cur; actions = null })
                SettingRow(stringResource(R.string.set_source_delete), { deleting = cur; actions = null })
            }
        }
    }

    deleting?.let { s ->
        ConfirmDialog(
            stringResource(R.string.set_source_delete_title, s.name),
            stringResource(R.string.set_source_delete_msg),
            stringResource(R.string.set_delete), stringResource(R.string.set_cancel),
            onConfirm = { vm.remove(s.id); deleting = null }, onDismiss = { deleting = null },
        )
    }

    if (confirmRemovePresets) ConfirmDialog(
        stringResource(R.string.set_presets_remove_title), stringResource(R.string.set_presets_remove_msg),
        stringResource(R.string.set_delete), stringResource(R.string.set_cancel),
        onConfirm = { vm.removeAllPresets(); confirmRemovePresets = false }, onDismiss = { confirmRemovePresets = false },
    )
    if (confirmRestorePresets) ConfirmDialog(
        stringResource(R.string.set_presets_restore_title), stringResource(R.string.set_presets_restore_msg),
        stringResource(R.string.set_presets_restore), stringResource(R.string.set_cancel),
        onConfirm = { vm.restorePresets(); confirmRestorePresets = false }, onDismiss = { confirmRestorePresets = false },
    )

    if (lan != LanUi.Off) LanDialog(lan, onClose = { vm.stopLan() })
}

private fun list(s: List<Source>?) = s.orEmpty()

@Composable
private fun sourceSubtitle(s: Source, t: SourceTestUi?): String {
    val host = if (s.isAsset) stringResource(R.string.set_source_builtin) else s.url.toHttpUrlOrNull()?.host.orEmpty()
    val status = when (t) {
        null -> null
        SourceTestUi.Testing -> stringResource(R.string.set_source_testing)
        is SourceTestUi.Ok -> stringResource(R.string.set_source_test_ok, t.titles.fa())
        is SourceTestUi.Failed -> t.messageFa
    }
    return listOfNotNull(host.takeIf { it.isNotEmpty() }, status).joinToString(" • ")
}

@Composable
private fun Badge(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = TextMuted,
        modifier = Modifier.border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

@Composable
fun LanDialog(lan: LanUi, onClose: () -> Unit, title: String = stringResource(R.string.set_source_add_phone)) {
    SettingsDialog(onClose, title, width = 560.dp) {
        when (lan) {
            is LanUi.Waiting -> {
                val code = remember(lan.url) { lan.url.substringAfter("c=", "").substringBefore('&') }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LanStep("۱", stringResource(R.string.set_lan_step1))
                        LanStep("۲", stringResource(R.string.set_lan_step2))
                        LanStep("۳", stringResource(R.string.set_lan_step3))
                    }
                    QrCode(lan.url, Modifier.size(200.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.set_lan_url_label), style = MaterialTheme.typography.bodyMedium, color = LanText)
                    Text(
                        lan.url.substringBefore("?c="),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                        color = TextPrimary,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bg).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                if (code.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.set_lan_code), style = MaterialTheme.typography.bodyMedium, color = LanText)
                    Text(
                        code,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr, letterSpacing = 3.sp,
                        ),
                        color = TextPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Bg).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Text(stringResource(R.string.set_lan_note_short), style = MaterialTheme.typography.bodyMedium, color = LanText)
            }
            is LanUi.Added -> Text(stringResource(R.string.set_lan_added, lan.name), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            LanUi.NoNetwork -> Text(stringResource(R.string.set_lan_no_network), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            LanUi.Off -> Unit
        }
        PillButton(stringResource(R.string.set_close), onClose, style = PillStyle.Accent)
    }
}

private val LanText = Color(0xFFD4D4D8)

@Composable
private fun LanStep(n: String, text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SurfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
            Text(n, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SourceFormDialog(editing: Source?, vm: SourcesViewModel, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(if (editing != null) SourceForm.of(editing) else SourceForm()) }
    val discover by vm.discover.collectAsState()
    val err by vm.formError.collectAsState()
    var showSecret by remember { mutableStateOf(editing?.let { it.token != null || it.customHeaderName != null } ?: false) }
    val intervals = RefreshInterval.CHOICES.map { it to formatIntervalFa(it) }

    SettingsDialog(onDismiss, stringResource(if (editing == null) R.string.set_source_add else R.string.set_source_edit), width = 860.dp) {
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.heightIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TvTextField(
                    f.address, { f = f.copy(address = it); if (editing == null) vm.resetForm() },
                    stringResource(if (editing == null) R.string.set_source_address else R.string.set_source_url),
                    ltr = true, placeholder = "https://mysite.nl", phoneHint = true, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    onDone = { if (editing == null) vm.discoverAndAdd(f, onDismiss) else vm.saveEdit(editing, f, onDismiss) },
                )
            }
            // Primary action right under the address, so the on-screen keyboard can't hide it.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (editing != null) {
                        PillButton(stringResource(R.string.set_save), { vm.saveEdit(editing, f, onDismiss) }, style = PillStyle.Accent)
                    } else {
                        PillButton(stringResource(R.string.set_source_add_confirm), { vm.discoverAndAdd(f, onDismiss) }, style = PillStyle.Accent)
                        PillButton(stringResource(R.string.set_source_discover), { vm.discover(f) })
                    }
                }
            }
            item { TvTextField(f.name, { f = f.copy(name = it) }, stringResource(R.string.set_source_name_optional)) }
            item { ChoiceRow(stringResource(R.string.set_source_interval), intervals, f.refreshMin, { f = f.copy(refreshMin = it) }) }
            item { SwitchRow(stringResource(R.string.set_source_auth), showSecret, { showSecret = it }, subtitle = stringResource(R.string.set_source_auth_hint)) }
            if (showSecret) {
                item { TvTextField(f.token, { f = f.copy(token = it) }, stringResource(R.string.set_source_token), password = true, ltr = true, phoneHint = true) }
                item { TvTextField(f.headerName, { f = f.copy(headerName = it) }, stringResource(R.string.set_source_header_name), ltr = true, placeholder = "X-Api-Key") }
                item { TvTextField(f.headerValue, { f = f.copy(headerValue = it) }, stringResource(R.string.set_source_header_value), password = true, ltr = true, phoneHint = true) }
                item { SwitchRow(stringResource(R.string.set_source_media_hosts), f.sendToMediaHosts, { f = f.copy(sendToMediaHosts = it) }) }
                item { SwitchRow(stringResource(R.string.set_source_over_http), f.allowOverHttp, { f = f.copy(allowOverHttp = it) }, subtitle = stringResource(R.string.set_source_over_http_hint)) }
            }
            item {
                val d = discover
                val text = when {
                    err != null -> err
                    editing == null && d is DiscoverUi.Working -> stringResource(R.string.set_source_discovering)
                    editing == null && d is DiscoverUi.Found -> stringResource(
                        R.string.set_source_found, d.feedName ?: stringResource(R.string.set_source_unnamed), d.titles.fa(),
                    ) + if (d.insecure) " " + stringResource(R.string.set_source_found_insecure) else ""
                    editing == null && d is DiscoverUi.Error -> d.messageFa
                    else -> null
                }
                if (text != null) StatusText(text, error = err != null || d is DiscoverUi.Error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (editing != null) {
                PillButton(stringResource(R.string.set_save), { vm.saveEdit(editing, f, onDismiss) }, style = PillStyle.Accent)
            } else {
                PillButton(stringResource(R.string.set_source_add_confirm), { vm.discoverAndAdd(f, onDismiss) }, style = PillStyle.Accent)
            }
            PillButton(stringResource(R.string.set_cancel), onDismiss)
        }
    }
}
