package nl.rass.player.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import nl.rass.player.BuildConfig
import nl.rass.player.R
import nl.rass.player.data.source.SourceRepository
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.formatBytesFa
import nl.rass.player.ui.format.toPersianDigits

@HiltViewModel
class AboutViewModel @Inject constructor(sources: SourceRepository) : ViewModel() {
    /** Names of enabled sources, Settings order. */
    val activeSources: StateFlow<List<String>?> =
        sources.enabledSources.map { l -> l.map { it.name } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun AboutPage(onBack: () -> Unit, vm: AboutViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val active by vm.activeSources.collectAsState()
    var license by remember { mutableStateOf<String?>(null) }
    val files = remember { runCatching { ctx.assets.list("licenses")?.sorted().orEmpty() }.getOrDefault(emptyList()) }
    val ram = remember {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.totalMem
    }

    SettingsPage(stringResource(R.string.set_sec_about), onBack) {
        item { InfoLine(stringResource(R.string.set_about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})".toPersianDigits()) }
        item { InfoLine(stringResource(R.string.set_about_abis), Build.SUPPORTED_ABIS.joinToString(", ")) }
        item { InfoLine(stringResource(R.string.set_about_ram), formatBytesFa(ram)) }
        item { InfoLine(stringResource(R.string.set_about_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})".toPersianDigits()) }
        item { InfoLine(stringResource(R.string.set_about_device), "${Build.MANUFACTURER} ${Build.MODEL}") }
        item { InfoLine(stringResource(R.string.set_about_sources), (active?.size ?: 0).fa()) }
        if (!active.isNullOrEmpty()) item { StatusText(active!!.joinToString("، ")) }
        item { StatusText(stringResource(R.string.set_online_attribution)) }
        item { StatusText(stringResource(R.string.set_about_licenses)) }
        files.forEach { f ->
            item(key = f) { SettingRow(f.substringBeforeLast('.'), { license = f }) }
        }
    }

    license?.let { f ->
        val text = remember(f) { runCatching { ctx.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrDefault("") }
        TextViewerDialog(f.substringBeforeLast('.'), text, stringResource(R.string.set_close)) { license = null }
    }
}
