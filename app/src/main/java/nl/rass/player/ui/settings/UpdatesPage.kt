package nl.rass.player.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.rass.player.R
import nl.rass.player.data.settings.AppSettings
import nl.rass.player.data.settings.DEFAULT_UPDATE_URL
import nl.rass.player.data.settings.SettingsRepository
import nl.rass.player.data.update.UpdateFlag
import nl.rass.player.data.update.UpdateRepository
import nl.rass.player.data.update.UpdateScheduler
import nl.rass.player.data.update.UpdateState
import nl.rass.player.ui.format.formatBytesFa
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.toPersianDigits

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val updates: UpdateRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val state: StateFlow<UpdateState> = updates.state

    private val _flag = MutableStateFlow(UpdateFlag.get(context))
    /** Version name of an update found by the daily check (the badge), or null. */
    val flag: StateFlow<String?> = _flag.asStateFlow()

    private val _canInstall = MutableStateFlow(updates.canRequestInstalls())
    val canInstall: StateFlow<Boolean> = _canInstall.asStateFlow()

    private val _urlError = MutableStateFlow(false)
    val urlError: StateFlow<Boolean> = _urlError.asStateFlow()

    fun installPermissionIntent() = updates.installPermissionIntent()
    fun refreshPermission() { _canInstall.value = updates.canRequestInstalls(); _flag.value = UpdateFlag.get(context) }

    fun setUrl(raw: String) {
        val url = raw.trim()
        if (url.isNotEmpty() && url.toHttpUrlOrNull() == null) { _urlError.value = true; return }
        _urlError.value = false
        viewModelScope.launch {
            val s = settingsRepo.update { it.copy(updateUrl = url.ifEmpty { null }) }
            applySchedule(s)
        }
    }

    fun resetUrl() = setUrl(DEFAULT_UPDATE_URL)

    fun setAuto(on: Boolean) {
        viewModelScope.launch { applySchedule(settingsRepo.update { it.copy(autoCheckUpdates = on) }) }
    }

    private fun applySchedule(s: AppSettings) {
        val url = s.updateUrl
        if (s.autoCheckUpdates && !url.isNullOrBlank()) UpdateScheduler.schedule(context, url) else UpdateScheduler.cancel(context)
    }

    fun check() {
        viewModelScope.launch {
            val url = settingsRepo.current().updateUrl ?: return@launch
            updates.check(url)
            when (val st = updates.state.value) {
                is UpdateState.Available -> UpdateFlag.set(context, st.versionName)
                is UpdateState.UpToDate -> UpdateFlag.set(context, null)
                else -> Unit
            }
            _flag.value = UpdateFlag.get(context)
        }
    }

    fun download() { viewModelScope.launch { updates.downloadAndVerify() } }
    fun install() { viewModelScope.launch { updates.install() } }
}

@Composable
fun UpdatesPage(onBack: () -> Unit, vm: UpdatesViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val state by vm.state.collectAsState()
    val flag by vm.flag.collectAsState()
    val canInstall by vm.canInstall.collectAsState()
    val urlError by vm.urlError.collectAsState()
    val ctx = LocalContext.current
    var editUrl by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refreshPermission() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    val cur = s ?: return SettingsPage(stringResource(R.string.set_sec_updates), onBack) {}
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    SettingsPage(
        stringResource(R.string.set_sec_updates), onBack,
        subtitle = stringResource(R.string.set_updates_current, version.toPersianDigitsSafe()),
    ) {
        if (flag != null) item { StatusText(stringResource(R.string.set_updates_flag, flag!!.toPersianDigitsSafe())) }
        item { SettingRow(stringResource(R.string.set_updates_url), { editUrl = true }, value = cur.updateUrl ?: stringResource(R.string.set_secret_unset)) }
        item { SwitchRow(stringResource(R.string.set_updates_auto), cur.autoCheckUpdates, { vm.setAuto(it) }, subtitle = stringResource(R.string.set_updates_auto_hint)) }
        item {
            when (val st = state) {
                UpdateState.Idle, UpdateState.UpToDate, is UpdateState.Failed ->
                    SettingRow(stringResource(R.string.set_updates_check), { vm.check() }, enabled = !cur.updateUrl.isNullOrBlank())
                UpdateState.Checking -> StatusText(stringResource(R.string.set_updates_checking))
                is UpdateState.Available -> SettingRow(
                    stringResource(R.string.set_updates_download, st.versionName.toPersianDigitsSafe()), { vm.download() },
                    subtitle = listOfNotNull(st.sizeBytes?.let { formatBytesFa(it) }, st.notesFa.takeIf { it.isNotBlank() }).joinToString(" • "),
                )
                is UpdateState.Downloading -> StatusText(
                    if (st.progress < 0f) stringResource(R.string.set_updates_downloading_unknown)
                    else stringResource(R.string.set_updates_downloading, (st.progress * 100).toInt().fa()),
                )
                UpdateState.Verifying -> StatusText(stringResource(R.string.set_updates_verifying))
                UpdateState.ReadyToInstall -> SettingRow(
                    stringResource(R.string.set_updates_install), { if (canInstall) vm.install() else ctx.startActivity(vm.installPermissionIntent()) },
                    subtitle = stringResource(R.string.set_updates_verified),
                )
                UpdateState.Installing -> StatusText(stringResource(R.string.set_updates_installing))
            }
        }
        item {
            val st = state
            when {
                st is UpdateState.UpToDate -> StatusText(stringResource(R.string.set_updates_uptodate))
                st is UpdateState.Failed -> StatusText(st.reasonFa, error = true)
            }
        }
        if (!canInstall) item {
            SettingRow(
                stringResource(R.string.set_updates_allow), { ctx.startActivity(vm.installPermissionIntent()) },
                subtitle = stringResource(R.string.set_updates_allow_hint),
            )
        }
    }

    if (editUrl) TextEntryDialog(
        stringResource(R.string.set_updates_url), stringResource(R.string.set_updates_url), cur.updateUrl.orEmpty(),
        stringResource(R.string.set_save), stringResource(R.string.set_cancel),
        onSave = { vm.setUrl(it); editUrl = false }, onDismiss = { editUrl = false },
        ltr = true, hint = if (urlError) stringResource(R.string.set_updates_url_invalid) else stringResource(R.string.set_updates_url_hint),
        clearText = stringResource(R.string.set_reset_default), onClear = { vm.resetUrl(); editUrl = false },
    )
}

private fun String.toPersianDigitsSafe() = toPersianDigits()
