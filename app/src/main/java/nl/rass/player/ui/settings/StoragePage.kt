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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.rass.player.R
import nl.rass.player.data.cache.CacheCap
import nl.rass.player.data.local.MyListDao
import nl.rass.player.data.local.WatchProgressDao
import nl.rass.player.data.settings.AppSettings
import nl.rass.player.data.settings.SettingsRepository
import nl.rass.player.data.source.SourceRepository
import nl.rass.player.domain.ClearableCache
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.format.formatBytesFa

data class CacheRowUi(val id: String, val label: String, val bytes: Long, val inClearAll: Boolean)

/** Which user data "clear all" also removes (R9.2). Everything is off by default. */
data class ClearAlsoOptions(
    val myList: Boolean = false,
    val progress: Boolean = false,
    val sources: Boolean = false,
    val settings: Boolean = false,
) {
    val any get() = myList || progress || sources || settings
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val caches: Set<@JvmSuppressWildcards ClearableCache>,
    private val settingsRepo: SettingsRepository,
    private val myListDao: MyListDao,
    private val progressDao: WatchProgressDao,
    private val sourceRepo: SourceRepository,
) : ViewModel() {
    private val sorted = caches.sortedBy { it.order }

    private val _rows = MutableStateFlow<List<CacheRowUi>?>(null)
    val rows: StateFlow<List<CacheRowUi>?> = _rows.asStateFlow()

    private val _capMb = MutableStateFlow(0)
    val capMb: StateFlow<Int> = _capMb.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init { refresh(enforceCap = true) }

    fun refresh(enforceCap: Boolean = false) {
        viewModelScope.launch {
            val cap = settingsRepo.current().totalCacheCapMb
            _capMb.value = cap
            var list = load()
            if (enforceCap && cap > 0) {
                val ids = CacheCap.idsToClear(list.filter { it.inClearAll }.map { CacheCap.Entry(it.id, it.bytes) }, cap * 1024L * 1024L)
                if (ids.isNotEmpty()) {
                    sorted.filter { it.id in ids }.forEach { runCatching { it.clear() } }
                    list = load()
                }
            }
            _rows.value = list
        }
    }

    private suspend fun load() = sorted.map { c ->
        CacheRowUi(c.id, c.labelFa, runCatching { c.sizeBytes() }.getOrDefault(0L), c.includedInClearAll)
    }

    fun clear(id: String) {
        viewModelScope.launch {
            _busy.value = true
            sorted.firstOrNull { it.id == id }?.let { runCatching { it.clear() } }
            _rows.value = load()
            _busy.value = false
        }
    }

    fun clearAll(also: ClearAlsoOptions) {
        viewModelScope.launch {
            _busy.value = true
            sorted.filter { it.includedInClearAll }.forEach { runCatching { it.clear() } }
            if (also.myList) myListDao.clear()
            if (also.progress) progressDao.clear()
            if (also.sources) sourceRepo.current().forEach { sourceRepo.remove(it.id) }
            if (also.settings) settingsRepo.replace(AppSettings())
            _rows.value = load()
            _busy.value = false
        }
    }

    fun setCap(mb: Int) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(totalCacheCapMb = mb) }
            refresh(enforceCap = true)
        }
    }
}

@Composable
fun StoragePage(onBack: () -> Unit, vm: StorageViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsState()
    val cap by vm.capMb.collectAsState()
    val busy by vm.busy.collectAsState()
    var also by remember { mutableStateOf(ClearAlsoOptions()) }
    var confirmAll by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<CacheRowUi?>(null) }
    val total = rows.orEmpty().sumOf { it.bytes }
    val caps = listOf(0, 100, 200, 300, 500, 1000).map { it to if (it == 0) stringResource(R.string.set_storage_cap_off) else "${it.fa()} ${stringResource(R.string.set_mb)}" }

    SettingsPage(
        stringResource(R.string.set_sec_storage), onBack,
        subtitle = stringResource(R.string.set_storage_total, formatBytesFa(total)),
    ) {
        if (rows == null) item { StatusText(stringResource(R.string.set_loading)) }
        items(rows.orEmpty(), key = { it.id }) { r ->
            SettingRow(r.label, { confirmOne = r }, value = formatBytesFa(r.bytes), subtitle = if (!r.inClearAll) stringResource(R.string.set_storage_not_in_all) else null)
        }
        item { ChoiceRow(stringResource(R.string.set_storage_cap), caps, cap.takeIf { caps.any { c -> c.first == it } } ?: 0, { vm.setCap(it) }, subtitle = stringResource(R.string.set_storage_cap_hint)) }
        item { StatusText(stringResource(R.string.set_storage_also_hint)) }
        item { CheckRow(stringResource(R.string.set_storage_also_mylist), also.myList, { also = also.copy(myList = it) }) }
        item { CheckRow(stringResource(R.string.set_storage_also_progress), also.progress, { also = also.copy(progress = it) }) }
        item { CheckRow(stringResource(R.string.set_storage_also_sources), also.sources, { also = also.copy(sources = it) }) }
        item { CheckRow(stringResource(R.string.set_storage_also_settings), also.settings, { also = also.copy(settings = it) }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(stringResource(R.string.set_storage_clear_all), { confirmAll = true }, style = PillStyle.Accent)
                PillButton(stringResource(R.string.set_storage_refresh), { vm.refresh() })
            }
        }
        if (busy) item { StatusText(stringResource(R.string.set_working)) }
    }

    confirmOne?.let { r ->
        ConfirmDialog(
            stringResource(R.string.set_storage_clear_one_title, r.label), stringResource(R.string.set_storage_clear_one_msg, formatBytesFa(r.bytes)),
            stringResource(R.string.set_storage_clear), stringResource(R.string.set_cancel),
            onConfirm = { vm.clear(r.id); confirmOne = null }, onDismiss = { confirmOne = null },
        )
    }
    if (confirmAll) {
        ConfirmDialog(
            stringResource(R.string.set_storage_clear_all),
            stringResource(if (also.any) R.string.set_storage_clear_all_user_msg else R.string.set_storage_clear_all_msg),
            stringResource(R.string.set_storage_clear), stringResource(R.string.set_cancel),
            onConfirm = { vm.clearAll(also); also = ClearAlsoOptions(); confirmAll = false }, onDismiss = { confirmAll = false },
        )
    }
}
