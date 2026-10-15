package nl.rass.player.ui.settings

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.rass.player.R
import nl.rass.player.data.backup.BackupException
import nl.rass.player.data.backup.BackupManager
import nl.rass.player.ui.format.formatBytesFa
import nl.rass.player.ui.format.toPersianDigits

sealed interface BackupMsg {
    data class Exported(val path: String) : BackupMsg
    data class Imported(val sources: Int, val myList: Int, val progress: Int) : BackupMsg
    data class Error(val messageFa: String) : BackupMsg
}

data class BackupFileUi(val name: String, val bytes: Long, val modified: Long)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: BackupManager,
) : ViewModel() {
    /** App-specific external "Download" folder: no storage permission needed, reachable from a PC/file manager. */
    val dir: File = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    private val _msg = MutableStateFlow<BackupMsg?>(null)
    val msg: StateFlow<BackupMsg?> = _msg.asStateFlow()
    private val _files = MutableStateFlow<List<BackupFileUi>>(emptyList())
    val files: StateFlow<List<BackupFileUi>> = _files.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init { scan() }

    fun scan() {
        viewModelScope.launch {
            _files.value = withContext(Dispatchers.IO) {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name.startsWith(PREFIX) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { BackupFileUi(it.name, it.length(), it.lastModified()) }.orEmpty()
            }
        }
    }

    fun export(includeTokens: Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _msg.value = try {
                val text = manager.export(includeTokens)
                val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
                val file = File(dir, name)
                withContext(Dispatchers.IO) { dir.mkdirs(); file.writeText(text) }
                BackupMsg.Exported(file.absolutePath)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupMsg.Error(context.getString(R.string.set_backup_export_failed))
            }
            _busy.value = false
            scan()
        }
    }

    fun import(name: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _msg.value = try {
                val text = withContext(Dispatchers.IO) { File(dir, File(name).name).readText() }
                val r = manager.import(text)
                BackupMsg.Imported(r.sources, r.myList, r.progress)
            } catch (e: BackupException) {
                BackupMsg.Error(e.messageFa)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupMsg.Error(context.getString(R.string.set_backup_import_failed))
            }
            _busy.value = false
        }
    }

    companion object { const val PREFIX = "rass-player-backup-" }
}

@Composable
fun BackupPage(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val msg by vm.msg.collectAsState()
    val files by vm.files.collectAsState()
    val busy by vm.busy.collectAsState()
    var includeTokens by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<BackupFileUi?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }

    SettingsPage(stringResource(R.string.set_sec_backup), onBack, subtitle = stringResource(R.string.set_backup_hint)) {
        item { CheckRow(stringResource(R.string.set_backup_tokens), includeTokens, { includeTokens = it }, subtitle = stringResource(R.string.set_backup_tokens_hint)) }
        item { SettingRow(stringResource(R.string.set_backup_export), { vm.export(includeTokens) }, enabled = !busy, subtitle = stringResource(R.string.set_backup_export_hint)) }
        item { StatusText(stringResource(R.string.set_backup_folder, vm.dir.absolutePath.toPersianDigits())) }
        msg?.let { m ->
            item {
                when (m) {
                    is BackupMsg.Exported -> StatusText(stringResource(R.string.set_backup_exported, m.path.toPersianDigits()))
                    is BackupMsg.Imported -> StatusText(
                        stringResource(R.string.set_backup_imported, m.sources.toString().toPersianDigits(), m.myList.toString().toPersianDigits(), m.progress.toString().toPersianDigits()),
                    )
                    is BackupMsg.Error -> StatusText(m.messageFa, error = true)
                }
            }
        }
        item { StatusText(stringResource(R.string.set_backup_import_title)) }
        if (files.isEmpty()) item { StatusText(stringResource(R.string.set_backup_none)) }
        items(files, key = { it.name }) { f ->
            SettingRow(f.name, { importing = f }, value = formatBytesFa(f.bytes), subtitle = fmt.format(Date(f.modified)).toPersianDigits())
        }
    }

    importing?.let { f ->
        ConfirmDialog(
            stringResource(R.string.set_backup_import_confirm_title), stringResource(R.string.set_backup_import_confirm_msg, f.name),
            stringResource(R.string.set_backup_import), stringResource(R.string.set_cancel),
            onConfirm = { vm.import(f.name); importing = null }, onDismiss = { importing = null },
        )
    }
}
