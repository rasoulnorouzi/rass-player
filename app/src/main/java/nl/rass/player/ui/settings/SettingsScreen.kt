package nl.rass.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.rass.player.R
import nl.rass.player.data.settings.PinHasher
import nl.rass.player.data.settings.PinLock
import nl.rass.player.data.update.UpdateFlag
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.components.ScreenScaffold
import nl.rass.player.ui.components.tvFocusable
import nl.rass.player.ui.format.toPersianDigits
import nl.rass.player.ui.navigation.Route
import nl.rass.player.ui.navigation.SettingsSection
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import android.view.KeyEvent as AKeyEvent

/** The PIN gate (R11.9) shared by every settings page. */
@HiltViewModel
class PinViewModel @Inject constructor(private val lock: PinLock) : ViewModel() {
    val hasPin: StateFlow<Boolean?> = lock.hasPin.let { f ->
        kotlinx.coroutines.flow.flow<Boolean?> { f.collect { emit(it) } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }
    /** null = still checking. */
    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked

    init { recheck() }

    fun recheck() { viewModelScope.launch { _locked.value = lock.isLocked() } }

    fun unlock(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = lock.tryUnlock(pin)
            if (ok) _locked.value = false
            onResult(ok)
        }
    }

    fun touch() = lock.touch()
    fun setPin(pin: String) { viewModelScope.launch { lock.setPin(pin); _locked.value = false } }
    fun clearPin() { viewModelScope.launch { lock.clearPin(); _locked.value = false } }
}

@Composable
fun SettingsScreen(section: SettingsSection, onNavigate: (Route) -> Unit, onBack: () -> Unit) {
    val pin: PinViewModel = hiltViewModel()
    val locked by pin.locked.collectAsState()
    when (locked) {
        null -> Box(Modifier.fillMaxSize().background(nl.rass.player.ui.theme.Bg))
        true -> PinEntryScreen(title = stringResource(R.string.set_pin_enter), onSubmit = { p, res -> pin.unlock(p, res) }, onBack = onBack)
        false -> {
            LaunchedEffect(section) { pin.touch() }
            when (section) {
                SettingsSection.Root -> SettingsRoot(pin, onNavigate, onBack)
                SettingsSection.Sources -> SourcesPage(onBack)
                SettingsSection.Playback -> PlaybackPage(onBack)
                SettingsSection.Subtitles -> SubtitlesPage(onBack)
                SettingsSection.OnlineSubtitles -> OnlineSubtitlesPage(onBack)
                SettingsSection.ContentFilter -> ContentFilterPage(onBack)
                SettingsSection.PrivateConnection -> PrivateConnectionPage(onBack)
                SettingsSection.Updates -> UpdatesPage(onBack)
                SettingsSection.Storage -> StoragePage(onBack)
                SettingsSection.Backup -> BackupPage(onBack)
                SettingsSection.About -> AboutPage(onBack)
            }
        }
    }
}

@Composable
private fun SettingsRoot(pin: PinViewModel, onNavigate: (Route) -> Unit, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hasPin by pin.hasPin.collectAsState()
    var pinDialog by remember { mutableStateOf(false) }
    val updateFlag = remember { UpdateFlag.get(ctx) }
    fun go(s: SettingsSection) = onNavigate(Route.Settings(s))

    SettingsPage(stringResource(R.string.set_title), onBack) {
        item { SettingRow(stringResource(R.string.set_sec_sources), { go(SettingsSection.Sources) }, subtitle = stringResource(R.string.set_root_sources)) }
        item { SettingRow(stringResource(R.string.set_sec_playback), { go(SettingsSection.Playback) }, subtitle = stringResource(R.string.set_root_playback)) }
        item { SettingRow(stringResource(R.string.set_sec_subtitles), { go(SettingsSection.Subtitles) }, subtitle = stringResource(R.string.set_root_subtitles)) }
        item { SettingRow(stringResource(R.string.set_sec_online), { go(SettingsSection.OnlineSubtitles) }, subtitle = stringResource(R.string.set_root_online)) }
        item { SettingRow(stringResource(R.string.set_sec_content_filter), { go(SettingsSection.ContentFilter) }, subtitle = stringResource(R.string.set_root_content_filter)) }
        item { SettingRow(stringResource(R.string.set_sec_private), { go(SettingsSection.PrivateConnection) }, subtitle = stringResource(R.string.set_root_private)) }
        item {
            SettingRow(
                stringResource(R.string.set_sec_updates), { go(SettingsSection.Updates) }, subtitle = stringResource(R.string.set_root_updates),
                trailing = {
                    if (updateFlag != null) Text(
                        stringResource(R.string.set_updates_badge), style = MaterialTheme.typography.labelMedium, color = TextPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                },
            )
        }
        item { SettingRow(stringResource(R.string.set_sec_storage), { go(SettingsSection.Storage) }, subtitle = stringResource(R.string.set_root_storage)) }
        item { SettingRow(stringResource(R.string.set_sec_backup), { go(SettingsSection.Backup) }, subtitle = stringResource(R.string.set_root_backup)) }
        item { SettingRow(stringResource(R.string.set_sec_about), { go(SettingsSection.About) }, subtitle = stringResource(R.string.set_root_about)) }
        item {
            SettingRow(
                stringResource(R.string.set_sec_pin), { pinDialog = true },
                subtitle = stringResource(R.string.set_root_pin),
                value = stringResource(if (hasPin == true) R.string.set_on else R.string.set_off),
            )
        }
    }

    if (pinDialog) PinSetupDialog(hasPin == true, pin, onDismiss = { pinDialog = false })
}

@Composable
private fun PinSetupDialog(hasPin: Boolean, vm: PinViewModel, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(if (hasPin) 0 else 1) } // 0 menu, 1 enter new, 2 confirm
    var first by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    SettingsDialog(onDismiss, stringResource(R.string.set_sec_pin), width = 560.dp) {
        when (step) {
            0 -> {
                SettingRow(stringResource(R.string.set_pin_change), { step = 1 })
                SettingRow(stringResource(R.string.set_pin_remove), { vm.clearPin(); onDismiss() })
            }
            1 -> PinPad(stringResource(R.string.set_pin_new), error = null) { p -> first = p; mismatch = false; step = 2 }
            else -> PinPad(
                stringResource(R.string.set_pin_confirm),
                error = if (mismatch) stringResource(R.string.set_pin_mismatch) else null,
            ) { p -> if (p == first) { vm.setPin(p); onDismiss() } else { mismatch = true; step = 1 } }
        }
    }
}

@Composable
private fun PinEntryScreen(title: String, onSubmit: (String, (Boolean) -> Unit) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var wrong by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    ScreenScaffold {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.runtime.key(attempt) {
                PinPad(title, error = if (wrong) stringResource(R.string.set_pin_wrong) else null) { p ->
                    onSubmit(p) { ok -> if (!ok) { wrong = true; attempt++ } }
                }
            }
        }
    }
}

/** 4-digit keypad: D-pad grid, plus the remote's number keys. Calls [onDone] when 4 digits are entered. */
@Composable
fun PinPad(title: String, error: String?, onDone: (String) -> Unit) {
    var digits by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    fun push(d: Char) {
        if (digits.length >= PinHasher.LENGTH) return
        digits += d
        if (digits.length == PinHasher.LENGTH) { val p = digits; digits = ""; onDone(p) }
    }
    Column(
        Modifier.onPreviewKeyEvent { e ->
            val code = e.key.nativeKeyCode
            if (e.type == KeyEventType.KeyDown && code in AKeyEvent.KEYCODE_0..AKeyEvent.KEYCODE_9) {
                push('0' + (code - AKeyEvent.KEYCODE_0)); true
            } else false
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(PinHasher.LENGTH) { i ->
                Box(Modifier.size(20.dp).clip(CircleShape).background(if (i < digits.length) Accent else Color(0xFF3A3E47)))
            }
        }
        if (error != null) Text(error, style = MaterialTheme.typography.labelMedium, color = Color(0xFFFF8A80))
        val rows = listOf("123", "456", "789")
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                r.forEachIndexed { idx, c -> Key(c.toString(), if (r == "123" && idx == 0) Modifier.focusRequester(fr) else Modifier) { push(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Key("⌫", Modifier) { digits = digits.dropLast(1) }
            Key("0", Modifier) { push('0') }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.size(width = 96.dp, height = 64.dp).tvFocusable(shape = shape, onClick = onClick).clip(shape).background(SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) { Text(label.toPersianDigits(), style = MaterialTheme.typography.titleMedium, color = TextPrimary) }
}
