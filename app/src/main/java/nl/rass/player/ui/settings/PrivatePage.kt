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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.rass.player.data.source.LanEntryServer
import nl.rass.player.data.source.LanMode
import nl.rass.player.R
import nl.rass.player.net.AddLinkResult
import nl.rass.player.net.CheckOutcome
import nl.rass.player.net.ConnectStep
import nl.rass.player.net.ConnectionChecker
import nl.rass.player.net.RouteDecision
import nl.rass.player.net.FailReason
import nl.rass.player.net.ProxyManager
import nl.rass.player.net.ProxyState
import nl.rass.player.net.ProxyStoreData
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.components.SectionHeader
import androidx.compose.foundation.layout.padding
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.formatLatencyFa

@HiltViewModel
class PrivateConnectionViewModel @Inject constructor(
    private val proxy: ProxyManager,
    private val checker: ConnectionChecker,
) : ViewModel() {
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
    fun removeConfig(id: String) { viewModelScope.launch { proxy.removeConfig(id) } }
    fun setConfigDisabled(id: String, disabled: Boolean) { viewModelScope.launch { proxy.setConfigDisabled(id, disabled) } }
    fun removeAll() { viewModelScope.launch { proxy.removeAll() } }
    fun restoreRemoved(subId: String) = run { _ -> proxy.restoreRemoved(subId); "restored" }
    fun addLink(link: String, name: String) = run { _ ->
        when (proxy.addConfigLink(link, name)) {
            AddLinkResult.ADDED -> "linkok"
            AddLinkResult.DUPLICATE -> "linkdup"
            AddLinkResult.INVALID -> "linkbad"
        }
    }

    // ---- R1.7: subscription link typed on the phone. The link goes straight into the encrypted store; never logged. ----
    private val _lan = MutableStateFlow<LanUi>(LanUi.Off)
    val lan: StateFlow<LanUi> = _lan.asStateFlow()
    private var server: LanEntryServer? = null

    fun startLan(configMode: Boolean = false) {
        stopLan()
        val s = if (configMode) {
            LanEntryServer(LanMode.CONFIG) { name, link -> addLinkFromPhone(name, link) }
        } else {
            LanEntryServer(LanMode.SUBSCRIPTION) { name, link -> addFromPhone(name, link) }
        }
        val url = runCatching { s.startAndGetUrl() }.getOrNull()
        if (url == null) { _lan.value = LanUi.NoNetwork; return }
        server = s
        _lan.value = LanUi.Waiting(url)
    }

    private fun addFromPhone(name: String, link: String): String? = runBlocking(Dispatchers.IO) {
        try {
            val res = proxy.addSubscription(link, name)
            if (res.configs.isEmpty()) "در این پیوند پیکربندی قابل استفاده‌ای پیدا نشد."
            else { _lan.value = LanUi.Added(name.ifBlank { "اتصال خصوصی" }); null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "افزودن ناموفق بود."
        }
    }

    private fun addLinkFromPhone(name: String, link: String): String? = runBlocking(Dispatchers.IO) {
        try {
            when (proxy.addConfigLink(link, name)) {
                AddLinkResult.ADDED -> { _lan.value = LanUi.Added(name.ifBlank { "سرور" }); null }
                AddLinkResult.DUPLICATE -> "این سرور قبلاً اضافه شده است."
                AddLinkResult.INVALID -> "این لینک پشتیبانی نمی‌شود."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "افزودن ناموفق بود."
        }
    }

    fun stopLan() {
        server?.stop(); server = null
        _lan.value = LanUi.Off
    }

    override fun onCleared() { server?.stop(); server = null }

    fun add(url: String, name: String) = run { r ->
        val res = proxy.addSubscription(url, name)
        if (res.configs.isEmpty()) "no" else "ok:${res.configs.size}:${res.skipped}"
    }

    fun refresh() = run { _ ->
        val res = proxy.refreshSubscriptions()
        "ok:${res.sumOf { it.configs.size }}:${res.sumOf { it.skipped }}"
    }

    fun testAll() = run { _ -> proxy.testAll(); "tested" }

    // ---- connection check (exit address); kept in memory only, never logged ----
    private val _check = MutableStateFlow(CheckUi())
    val check: StateFlow<CheckUi> = _check.asStateFlow()

    fun runCheck() {
        if (_check.value.running) return
        _check.value = CheckUi(running = true)
        viewModelScope.launch {
            val proxied = proxy.currentRoute() is RouteDecision.Proxied
            _check.value = CheckUi(app = checker.viaApp(), viaPrivate = proxied)
        }
    }

    fun compareDirect() {
        val cur = _check.value
        if (cur.running || cur.app !is CheckOutcome.Ok) return
        _check.value = cur.copy(running = true)
        viewModelScope.launch { _check.value = cur.copy(direct = checker.direct(), running = false) }
    }

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

data class CheckUi(
    val running: Boolean = false,
    val app: CheckOutcome? = null,
    val viaPrivate: Boolean = false,
    val direct: CheckOutcome? = null,
)

@Composable
fun PrivateConnectionPage(onBack: () -> Unit, vm: PrivateConnectionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val data by vm.data.collectAsState()
    val testing by vm.testing.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val check by vm.check.collectAsState()
    val lan by vm.lan.collectAsState()
    val s = data.settings
    var addDialog by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var subActions by remember { mutableStateOf<String?>(null) }
    var configActions by remember { mutableStateOf<String?>(null) }
    var removingConfig by remember { mutableStateOf<String?>(null) }
    var addLinkDialog by remember { mutableStateOf(false) }
    var lanConfig by remember { mutableStateOf(false) }
    var removeAllDialog by remember { mutableStateOf(false) }
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
        item {
            ActionBar {
                PillButton(stringResource(R.string.set_pc_add_sub), { addDialog = true }, iconRes = R.drawable.ic_add, style = PillStyle.Accent)
                PillButton(stringResource(R.string.set_pc_add_link), { addLinkDialog = true }, iconRes = R.drawable.ic_add)
                PillButton(stringResource(R.string.set_pc_add_phone), { lanConfig = false; vm.startLan() }, iconRes = R.drawable.ic_phone)
                PillButton(stringResource(R.string.set_pc_check), { vm.runCheck() }, iconRes = R.drawable.ic_check)
            }
        }
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
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (check.app is CheckOutcome.Ok) PillButton(stringResource(R.string.set_pc_check_direct), { vm.compareDirect() })
            }
        }
        if (check.running || check.app != null) item { CheckStatus(check) }
        if (working || message != null) item {
            StatusText(
                when {
                    working -> stringResource(R.string.set_pc_working)
                    message == "no" -> stringResource(R.string.set_pc_add_none)
                    message == "tested" -> stringResource(R.string.set_pc_tested)
                    message == "fail" -> stringResource(R.string.set_pc_add_fail)
                    message == "linkok" -> stringResource(R.string.set_pc_link_ok)
                    message == "linkdup" -> stringResource(R.string.set_pc_link_dup)
                    message == "linkbad" -> stringResource(R.string.set_pc_link_bad)
                    message == "restored" -> stringResource(R.string.set_pc_restored)
                    message?.startsWith("ok:") == true -> message!!.split(":").let {
                        stringResource(R.string.set_pc_add_ok, (it.getOrNull(1)?.toIntOrNull() ?: 0).fa(), (it.getOrNull(2)?.toIntOrNull() ?: 0).fa())
                    }
                    else -> ""
                },
                error = message == "no" || message == "fail" || message == "linkbad" || message == "linkdup",
            )
        }

        item { SettingRow(stringResource(R.string.set_pc_add_link_phone), { lanConfig = true; vm.startLan(configMode = true) }, subtitle = stringResource(R.string.set_pc_add_link_phone_hint)) }
        if (data.subscriptions.isNotEmpty() || data.configs.isNotEmpty()) item {
            SettingRow(stringResource(R.string.set_pc_remove_all), { removeAllDialog = true }, subtitle = stringResource(R.string.set_pc_remove_all_hint))
        }
        item { SectionHeader(stringResource(R.string.set_pc_list_header), androidx.compose.ui.Modifier.padding(top = 12.dp, start = 8.dp)) }
        items(data.subscriptions, key = { it.id }) { sub ->
            val n = data.configs.count { it.subscriptionId == sub.id }
            SettingRow(
                sub.name.ifBlank { stringResource(R.string.set_pc_sub_unnamed) },
                onClick = { subActions = sub.id },
                subtitle = (if (sub.lastError != null) stringResource(R.string.set_pc_sub_error) else stringResource(R.string.set_pc_sub_count, n.fa())) +
                    (data.removed[sub.id].orEmpty().size.takeIf { it > 0 }?.let { " • " + stringResource(R.string.set_pc_sub_removed_count, it.fa()) } ?: ""),
                value = stringResource(R.string.set_pc_sub_manage_hint),
                trailing = { DeleteButton { removing = sub.id } },
            )
        }

        item { StatusText(stringResource(R.string.set_pc_configs, data.configs.size.fa()), modifier = androidx.compose.ui.Modifier) }
        val manual = data.configs.filter { it.isManual }
        val fromSubs = data.configs.filter { !it.isManual }
        if (manual.isNotEmpty()) item { StatusText(stringResource(R.string.set_pc_manual_group, manual.size.fa()), modifier = androidx.compose.ui.Modifier) }
        items(manual, key = { it.id }) { c -> ConfigRow(c, data, results[c.id], state, s, { configActions = c.id }, { removingConfig = c.id }) }
        if (manual.isNotEmpty() && fromSubs.isNotEmpty()) item { StatusText(stringResource(R.string.set_pc_sub_group, fromSubs.size.fa()), modifier = androidx.compose.ui.Modifier) }
        items(fromSubs, key = { it.id }) { c -> ConfigRow(c, data, results[c.id], state, s, { configActions = c.id }, { removingConfig = c.id }) }
    }

    if (lan != LanUi.Off) LanDialog(
        lan, onClose = { vm.stopLan() },
        title = stringResource(if (lanConfig) R.string.set_pc_add_link_phone else R.string.set_pc_add_phone),
    )
    if (addLinkDialog) AddLinkDialog(
        onAdd = { l, n -> vm.addLink(l, n); addLinkDialog = false }, onDismiss = { addLinkDialog = false },
    )
    subActions?.let { id ->
        val sub = data.subscriptions.firstOrNull { it.id == id }
        if (sub == null) subActions = null else {
            val removedN = data.removed[id].orEmpty().size
            SettingsDialog({ subActions = null }, sub.name.ifBlank { stringResource(R.string.set_pc_sub_unnamed) }) {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (removedN > 0) SettingRow(
                        stringResource(R.string.set_pc_restore_removed), { vm.restoreRemoved(id); subActions = null },
                        subtitle = stringResource(R.string.set_pc_restore_removed_hint, removedN.fa()),
                    )
                    SettingRow(stringResource(R.string.set_pc_sub_remove_title), { removing = id; subActions = null })
                }
            }
        }
    }
    configActions?.let { id ->
        val c = data.configs.firstOrNull { it.id == id }
        if (c == null) configActions = null else {
            val off = data.isDisabled(c)
            SettingsDialog({ configActions = null }, c.name.ifBlank { c.protocol }) {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingRow(stringResource(R.string.set_pc_use_server), { vm.select(id); configActions = null }, subtitle = stringResource(R.string.set_pc_use_server_hint))
                    SettingRow(
                        stringResource(if (off) R.string.set_pc_enable_server else R.string.set_pc_disable_server),
                        { vm.setConfigDisabled(id, !off); configActions = null },
                        subtitle = stringResource(if (off) R.string.set_pc_enable_server_hint else R.string.set_pc_disable_server_hint),
                    )
                    SettingRow(stringResource(R.string.set_pc_remove_server), { removingConfig = id; configActions = null })
                }
            }
        }
    }
    removingConfig?.let { id ->
        val c = data.configs.firstOrNull { it.id == id }
        ConfirmDialog(
            stringResource(R.string.set_pc_remove_server_title, c?.name.orEmpty().ifBlank { c?.protocol.orEmpty() }),
            stringResource(if (c?.isManual == false) R.string.set_pc_remove_server_msg_sub else R.string.set_pc_remove_server_msg),
            stringResource(R.string.set_delete), stringResource(R.string.set_cancel),
            onConfirm = { vm.removeConfig(id); removingConfig = null }, onDismiss = { removingConfig = null },
        )
    }
    if (removeAllDialog) ConfirmDialog(
        stringResource(R.string.set_pc_remove_all_title), stringResource(R.string.set_pc_remove_all_msg),
        stringResource(R.string.set_pc_remove_all), stringResource(R.string.set_cancel),
        onConfirm = { vm.removeAll(); removeAllDialog = false }, onDismiss = { removeAllDialog = false },
    )
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
private fun ConfigRow(
    c: nl.rass.player.net.ProxyConfig,
    data: ProxyStoreData,
    r: nl.rass.player.net.LatencyResult?,
    state: ProxyState,
    s: nl.rass.player.net.ProxySettings,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val off = data.isDisabled(c)
    val selected = !s.autoSelect && s.selectedConfigId == c.id
    val active = (state as? ProxyState.Connected)?.configName == c.name
    SettingRow(
        c.name.ifBlank { c.protocol },
        onClick = onClick,
        subtitle = c.protocol.uppercase() + if (active) " • " + stringResource(R.string.set_pc_active) else "",
        value = when {
            off -> stringResource(R.string.set_pc_disabled)
            r == null -> stringResource(R.string.set_pc_untested)
            r.latencyMs != null -> formatLatencyFa(r.latencyMs)
            else -> stringResource(R.string.set_pc_failed)
        },
        trailing = { if (selected) CheckVisual(true); DeleteButton(onDelete) },
    )
}

@Composable
private fun AddLinkDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    SettingsDialog(onDismiss, stringResource(R.string.set_pc_add_link)) {
        TvTextField(link, { link = it }, stringResource(R.string.set_pc_link_field), ltr = true, phoneHint = true)
        TvTextField(name, { name = it }, stringResource(R.string.set_pc_sub_name))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton(stringResource(R.string.set_save), { if (link.isNotBlank()) onAdd(link.trim(), name.trim()) }, style = PillStyle.Accent)
            PillButton(stringResource(R.string.set_cancel), onDismiss)
        }
    }
}

@Composable
private fun AddSubscriptionDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    SettingsDialog(onDismiss, stringResource(R.string.set_pc_add_sub)) {
        TvTextField(url, { url = it }, stringResource(R.string.set_pc_sub_url), ltr = true, phoneHint = true)
        TvTextField(name, { name = it }, stringResource(R.string.set_pc_sub_name))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton(stringResource(R.string.set_save), { if (url.isNotBlank()) onAdd(url.trim(), name.trim()) }, style = PillStyle.Accent)
            PillButton(stringResource(R.string.set_cancel), onDismiss)
        }
    }
}

@Composable
private fun CheckStatus(c: CheckUi) {
    val app = c.app
    val lines = buildList {
        when (app) {
            is CheckOutcome.Ok -> {
                add(stringResource(R.string.set_pc_check_exit, app.info.ip, app.info.country ?: "—") to false)
                add(stringResource(if (c.viaPrivate) R.string.set_pc_check_via_private else R.string.set_pc_check_via_direct) to false)
            }
            CheckOutcome.Blocked -> add(stringResource(R.string.set_pc_check_blocked) to true)
            CheckOutcome.Failed -> add(stringResource(R.string.set_pc_check_failed) to true)
            null -> Unit
        }
        when (val d = c.direct) {
            is CheckOutcome.Ok -> if (app is CheckOutcome.Ok) {
                if (d.info.ip != app.info.ip) add(stringResource(R.string.set_pc_check_changed) to false)
                else add(stringResource(R.string.set_pc_check_same) to true)
            }
            CheckOutcome.Blocked, CheckOutcome.Failed -> add(stringResource(R.string.set_pc_check_direct_failed) to true)
            null -> Unit
        }
        if (c.running) add(stringResource(R.string.set_pc_working) to false)
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { (text, error) -> StatusText(text, error = error) }
    }
}
