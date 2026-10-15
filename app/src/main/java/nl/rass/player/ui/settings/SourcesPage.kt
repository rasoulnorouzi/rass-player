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
import nl.rass.player.data.source.RefreshInterval
import nl.rass.player.data.source.Source
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
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

    SettingsPage(stringResource(R.string.set_sec_sources), onBack, subtitle = stringResource(R.string.set_sources_hint)) {
        item { SettingRow(stringResource(R.string.set_source_add), { vm.resetForm(); adding = true }, subtitle = stringResource(R.string.set_source_add_hint)) }
        item { SettingRow(stringResource(R.string.set_source_add_phone), { vm.startLan() }, subtitle = stringResource(R.string.set_source_add_phone_hint)) }
        val list = sources.orEmpty()
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
                    SwitchVisual(s.enabled)
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
        text, style = MaterialTheme.typography.labelMedium, color = TextPrimary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun LanDialog(lan: LanUi, onClose: () -> Unit) {
    SettingsDialog(onClose, stringResource(R.string.set_source_add_phone), width = 760.dp) {
        when (lan) {
            is LanUi.Waiting -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(lan.url, Modifier.size(300.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.set_lan_steps), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    Text(stringResource(R.string.set_lan_note), style = MaterialTheme.typography.labelMedium, color = TextMuted)
                }
            }
            is LanUi.Added -> Text(stringResource(R.string.set_lan_added, lan.name), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            LanUi.NoNetwork -> Text(stringResource(R.string.set_lan_no_network), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            LanUi.Off -> Unit
        }
        PillButton(stringResource(R.string.set_close), onClose)
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
                    ltr = true, placeholder = "https://mysite.nl", keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                )
            }
            item { TvTextField(f.name, { f = f.copy(name = it) }, stringResource(R.string.set_source_name_optional)) }
            item { ChoiceRow(stringResource(R.string.set_source_interval), intervals, f.refreshMin, { f = f.copy(refreshMin = it) }) }
            item { SwitchRow(stringResource(R.string.set_source_auth), showSecret, { showSecret = it }, subtitle = stringResource(R.string.set_source_auth_hint)) }
            if (showSecret) {
                item { TvTextField(f.token, { f = f.copy(token = it) }, stringResource(R.string.set_source_token), password = true, ltr = true) }
                item { TvTextField(f.headerName, { f = f.copy(headerName = it) }, stringResource(R.string.set_source_header_name), ltr = true, placeholder = "X-Api-Key") }
                item { TvTextField(f.headerValue, { f = f.copy(headerValue = it) }, stringResource(R.string.set_source_header_value), password = true, ltr = true) }
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
                val d = discover
                if (d is DiscoverUi.Found) PillButton(stringResource(R.string.set_save), { vm.add(f, d, onDismiss) }, style = PillStyle.Accent)
                PillButton(stringResource(R.string.set_source_discover), { vm.discover(f) }, style = if (d is DiscoverUi.Found) PillStyle.Secondary else PillStyle.Accent)
            }
            PillButton(stringResource(R.string.set_cancel), onDismiss)
        }
    }
}
