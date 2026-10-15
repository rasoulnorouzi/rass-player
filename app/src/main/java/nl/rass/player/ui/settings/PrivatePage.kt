package nl.rass.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.rass.player.R
import nl.rass.player.net.ConnectStep
import nl.rass.player.net.FailReason
import nl.rass.player.net.ProxyManager
import nl.rass.player.net.ProxyState
import nl.rass.player.net.ProxyStoreData
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.formatLatencyFa

@HiltViewModel
class PrivateConnectionViewModel @Inject constructor(private val proxy: ProxyManager) : ViewModel() {
    val state: StateFlow<ProxyState> = proxy.state
    val data: StateFlow<ProxyStoreData> = proxy.data
    val testing: StateFlow<Boolean> = proxy.testing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun setEnabled(on: Boolean) { viewModelScope.launch { proxy.setEnabled(on) } }
    fun setKillSwitch(on: Boolean) { viewModelScope.launch { proxy.setKillSwitch(on) } }
    fun setAuto(on: Boolean) { viewModelScope.launch { proxy.setAutoSelect(on) } }
    fun select(id: String) { viewModelScope.launch { proxy.selectConfig(id) } }
    fun setConcurrency(n: Int) { viewModelScope.launch { proxy.setTestConcurrency(n) } }
    fun remove(id: String) { viewModelScope.launch { proxy.removeSubscription(id) } }

    fun add(url: String, name: String) = run { r ->
        val res = proxy.addSubscription(url, name)
        if (res.configs.isEmpty()) "no" else "ok:${res.configs.size}:${res.skipped}"
    }

    fun refresh() = run { _ ->
        val res = proxy.refreshSubscriptions()
        "ok:${res.sumOf { it.configs.size }}:${res.sumOf { it.skipped }}"
    }

    fun testAll() = run { _ -> proxy.testAll(); "tested" }

    /** Result codes ("ok:n:skipped", "no", "tested", "fail") are mapped to Persian text by the page. */
    private fun run(block: suspend (Unit) -> String) {
        if (_busy.value) return
        _busy.value = true
        _message.value = null
        viewModelScope.launch {
            _message.value = try { block(Unit) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "fail" }
            _busy.value = false
        }
    }
}

@Composable
fun PrivateConnectionPage(onBack: () -> Unit, vm: PrivateConnectionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val data by vm.data.collectAsState()
    val testing by vm.testing.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val s = data.settings
    var addDialog by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val results = data.results.associateBy { it.configId }
    val working = busy || testing

    val stateText = when (val st = state) {
        ProxyState.Off -> stringResource(R.string.set_pc_state_off)
        is ProxyState.Connecting -> stringResource(
            when (st.step) {
                ConnectStep.TESTING, ConnectStep.RETESTING -> R.string.set_pc_state_testing
                ConnectStep.STARTING -> R.string.set_pc_state_starting
            },
        )
        is ProxyState.Connected -> stringResource(R.string.set_pc_state_connected, st.configName) +
            (st.latencyMs?.let { " • " + formatLatencyFa(it) } ?: "")
        is ProxyState.Failed -> stringResource(
            when (st.reason) {
                FailReason.NO_CONFIGS -> R.string.set_pc_fail_no_configs
                FailReason.NO_WORKING_CONFIG -> R.string.set_pc_fail_none_work
                FailReason.SELECTED_CONFIG_MISSING -> R.string.set_pc_fail_selected_missing
                FailReason.ENGINE_ERROR -> R.string.set_pc_fail_engine
            },
        )
    }

    SettingsPage(stringResource(R.string.set_sec_private), onBack, subtitle = stateText) {
        item { SwitchRow(stringResource(R.string.set_pc_enable), s.enabled, { vm.setEnabled(it) }, subtitle = stringResource(R.string.set_pc_enable_hint)) }
        item { SwitchRow(stringResource(R.string.set_pc_kill), s.killSwitch, { vm.setKillSwitch(it) }, subtitle = stringResource(R.string.set_pc_kill_hint)) }
        item { SwitchRow(stringResource(R.string.set_pc_auto), s.autoSelect, { vm.setAuto(it) }, subtitle = stringResource(R.string.set_pc_auto_hint)) }
        item {
            ChoiceRow(
                stringResource(R.string.set_pc_concurrency), (1..4).map { it to it.fa() }, s.testConcurrency.coerceIn(1, 4),
                { vm.setConcurrency(it) }, subtitle = stringResource(R.string.set_pc_concurrency_hint),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(stringResource(R.string.set_pc_test_all), { vm.testAll() }, style = PillStyle.Accent)
                PillButton(stringResource(R.string.set_pc_reconnect), { vm.setEnabled(s.enabled) })
                PillButton(stringResource(R.string.set_pc_refresh), { vm.refresh() })
            }
        }
        if (working || message != null) item {
            StatusText(
                when {
                    working -> stringResource(R.string.set_pc_working)
                    message == "no" -> stringResource(R.string.set_pc_add_none)
                    message == "tested" -> stringResource(R.string.set_pc_tested)
                    message == "fail" -> stringResource(R.string.set_pc_add_fail)
                    message?.startsWith("ok:") == true -> message!!.split(":").let {
                        stringResource(R.string.set_pc_add_ok, (it.getOrNull(1)?.toIntOrNull() ?: 0).fa(), (it.getOrNull(2)?.toIntOrNull() ?: 0).fa())
                    }
                    else -> ""
                },
                error = message == "no" || message == "fail",
            )
        }

        item { SettingRow(stringResource(R.string.set_pc_add_sub), { addDialog = true }, subtitle = stringResource(R.string.set_pc_add_sub_hint)) }
        items(data.subscriptions, key = { it.id }) { sub ->
            val n = data.configs.count { it.subscriptionId == sub.id }
            SettingRow(
                sub.name.ifBlank { stringResource(R.string.set_pc_sub_unnamed) },
                onClick = { removing = sub.id },
                subtitle = if (sub.lastError != null) stringResource(R.string.set_pc_sub_error) else stringResource(R.string.set_pc_sub_count, n.fa()),
                value = stringResource(R.string.set_pc_sub_remove_hint),
            )
        }

        item { StatusText(stringResource(R.string.set_pc_configs, data.configs.size.fa()), modifier = androidx.compose.ui.Modifier) }
        items(data.configs, key = { it.id }) { c ->
            val r = results[c.id]
            val selected = !s.autoSelect && s.selectedConfigId == c.id
            val active = (state as? ProxyState.Connected)?.configName == c.name
            SettingRow(
                c.name.ifBlank { c.protocol },
                onClick = { vm.select(c.id) },
                subtitle = c.protocol.uppercase() + if (active) " • " + stringResource(R.string.set_pc_active) else "",
                value = when {
                    r == null -> stringResource(R.string.set_pc_untested)
                    r.latencyMs != null -> formatLatencyFa(r.latencyMs)
                    else -> stringResource(R.string.set_pc_failed)
                },
                trailing = { if (selected) CheckVisual(true) },
            )
        }
    }

    if (addDialog) AddSubscriptionDialog(
        onAdd = { u, n -> vm.add(u, n); addDialog = false }, onDismiss = { addDialog = false },
    )
    removing?.let { id ->
        ConfirmDialog(
            stringResource(R.string.set_pc_sub_remove_title), stringResource(R.string.set_pc_sub_remove_msg),
            stringResource(R.string.set_delete), stringResource(R.string.set_cancel),
            onConfirm = { vm.remove(id); removing = null }, onDismiss = { removing = null },
        )
    }
}

@Composable
private fun AddSubscriptionDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    SettingsDialog(onDismiss, stringResource(R.string.set_pc_add_sub)) {
        TvTextField(url, { url = it }, stringResource(R.string.set_pc_sub_url), ltr = true)
        TvTextField(name, { name = it }, stringResource(R.string.set_pc_sub_name))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton(stringResource(R.string.set_save), { if (url.isNotBlank()) onAdd(url.trim(), name.trim()) }, style = PillStyle.Accent)
            PillButton(stringResource(R.string.set_cancel), onDismiss)
        }
    }
}
