package nl.rass.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.rass.player.data.source.AuthHeaders
import nl.rass.player.data.source.DiscoveryResult
import nl.rass.player.data.source.LanEntryServer
import nl.rass.player.data.source.Source
import nl.rass.player.data.source.SourceAuth
import nl.rass.player.data.source.SourceRepository
import nl.rass.player.data.source.SourceValidationException
import nl.rass.player.data.source.TestConnectionResult

/** Result of "test connection" shown under a source. */
sealed interface SourceTestUi {
    data object Testing : SourceTestUi
    data class Ok(val titles: Int) : SourceTestUi
    data class Failed(val messageFa: String) : SourceTestUi
}

/** State of the add-source dialog (R1.5). */
sealed interface DiscoverUi {
    data object Idle : DiscoverUi
    data object Working : DiscoverUi
    data class Found(val feedUrl: String, val feedName: String?, val titles: Int, val insecure: Boolean) : DiscoverUi
    data class Error(val messageFa: String) : DiscoverUi
}

/** The LAN/QR add page (R1.7). */
sealed interface LanUi {
    data object Off : LanUi
    data class Waiting(val url: String) : LanUi
    data class Added(val name: String) : LanUi
    data object NoNetwork : LanUi
}

/** The fields of the add/edit dialog. */
data class SourceForm(
    val name: String = "",
    val address: String = "",
    val token: String = "",
    val headerName: String = "",
    val headerValue: String = "",
    val sendToMediaHosts: Boolean = false,
    val allowOverHttp: Boolean = false,
    val refreshMin: Int = nl.rass.player.data.source.RefreshInterval.DEFAULT,
) {
    fun auth() = SourceAuth(
        bearerToken = token.trim().ifEmpty { null },
        headerName = headerName.trim().ifEmpty { null },
        headerValue = headerValue.ifEmpty { null },
        sendToMediaHosts = sendToMediaHosts,
        allowOverHttp = allowOverHttp,
    )

    companion object {
        fun of(s: Source) = SourceForm(
            s.name, s.url, s.token.orEmpty(), s.customHeaderName.orEmpty(), s.customHeaderValue.orEmpty(),
            s.allowTokenToMediaHosts, s.allowTokenOverHttp, s.refreshIntervalMin,
        )
    }
}

@HiltViewModel
class SourcesViewModel @Inject constructor(private val repo: SourceRepository) : ViewModel() {

    val sources: StateFlow<List<Source>?> =
        repo.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _tests = MutableStateFlow<Map<String, SourceTestUi>>(emptyMap())
    val tests: StateFlow<Map<String, SourceTestUi>> = _tests.asStateFlow()

    private val _discover = MutableStateFlow<DiscoverUi>(DiscoverUi.Idle)
    val discover: StateFlow<DiscoverUi> = _discover.asStateFlow()

    private val _formError = MutableStateFlow<String?>(null)
    val formError: StateFlow<String?> = _formError.asStateFlow()

    private val _lan = MutableStateFlow<LanUi>(LanUi.Off)
    val lan: StateFlow<LanUi> = _lan.asStateFlow()
    private var server: LanEntryServer? = null

    fun setEnabled(id: String, on: Boolean) = viewModelScope.launch { repo.setEnabled(id, on) }
    fun remove(id: String) = viewModelScope.launch { repo.remove(id); _tests.update { it - id } }

    fun removeAllPresets() = viewModelScope.launch { repo.removeAllPresets(); _tests.update { it.filterKeys { k -> k !in presetIds } } }
    fun restorePresets() = viewModelScope.launch { repo.restorePresets() }
    private val presetIds = nl.rass.player.data.source.PresetSources.ALL.map { it.id }.toSet()

    fun move(id: String, delta: Int) = viewModelScope.launch {
        val list = repo.current()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) repo.move(id, (i + delta).coerceIn(0, list.lastIndex))
    }

    fun test(s: Source) {
        _tests.update { it + (s.id to SourceTestUi.Testing) }
        viewModelScope.launch {
            val r = repo.testConnection(s.url, s.auth)
            _tests.update {
                it + (s.id to when (r) {
                    is TestConnectionResult.Ok -> SourceTestUi.Ok(r.report.titles)
                    is TestConnectionResult.Failed -> SourceTestUi.Failed(r.messageFa)
                })
            }
        }
    }

    fun resetForm() { _discover.value = DiscoverUi.Idle; _formError.value = null }

    /** Autodiscovery (R1.5): a site address or a feed URL. */
    fun discover(form: SourceForm) {
        _formError.value = null
        val hn = form.headerName.trim()
        if (hn.isNotEmpty()) AuthHeaders.validateHeaderName(hn)?.let { _discover.value = DiscoverUi.Error(it.messageFa); return }
        _discover.value = DiscoverUi.Working
        viewModelScope.launch {
            _discover.value = when (val d = repo.discover(form.address, form.auth())) {
                is DiscoveryResult.Found -> DiscoverUi.Found(d.feedUrl, d.feedName, d.titleCount, d.insecure)
                is DiscoveryResult.Failed -> DiscoverUi.Error(d.messageFa)
            }
        }
    }

    /** Saves the discovered feed as a new source. Calls [done] on success. */
    fun add(form: SourceForm, found: DiscoverUi.Found, done: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.add(
                    name = form.name.ifBlank { found.feedName.orEmpty() },
                    url = found.feedUrl, auth = form.auth(), refreshIntervalMin = form.refreshMin,
                )
                resetForm(); done()
            } catch (e: SourceValidationException) {
                _formError.value = e.messageFa
            }
        }
    }

    fun saveEdit(old: Source, form: SourceForm, done: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.update(
                    old.copy(
                        name = form.name.ifBlank { old.name },
                        url = form.address.trim(),
                        token = form.token.trim().ifEmpty { null },
                        customHeaderName = form.headerName.trim().ifEmpty { null },
                        customHeaderValue = form.headerValue.ifEmpty { null },
                        allowTokenToMediaHosts = form.sendToMediaHosts,
                        allowTokenOverHttp = form.allowOverHttp,
                        refreshIntervalMin = form.refreshMin,
                    )
                )
                resetForm(); done()
            } catch (e: SourceValidationException) {
                _formError.value = e.messageFa
            }
        }
    }

    // ---- R1.7: QR + phone page ----

    fun startLan() {
        stopLan()
        val s = LanEntryServer { name, address -> addFromPhone(name, address) }
        val url = withBlockingStart(s)
        if (url == null) { _lan.value = LanUi.NoNetwork; return }
        server = s
        _lan.value = LanUi.Waiting(url)
    }

    private fun withBlockingStart(s: LanEntryServer): String? = runCatching { s.startAndGetUrl() }.getOrNull()

    private fun addFromPhone(name: String, address: String): String? = runBlocking(Dispatchers.IO) {
        when (val d = repo.discover(address)) {
            is DiscoveryResult.Failed -> d.messageFa
            is DiscoveryResult.Found -> try {
                val src = repo.add(name.ifBlank { d.feedName.orEmpty() }, d.feedUrl)
                _lan.value = LanUi.Added(src.name)
                null
            } catch (e: SourceValidationException) {
                e.messageFa
            }
        }
    }

    fun stopLan() {
        server?.stop(); server = null
        _lan.value = LanUi.Off
    }

    override fun onCleared() { server?.stop(); server = null }
}
