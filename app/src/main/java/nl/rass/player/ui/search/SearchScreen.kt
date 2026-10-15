package nl.rass.player.ui.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.ui.components.ChipItem
import nl.rass.player.ui.components.ChipRow
import nl.rass.player.ui.components.EmptyState
import nl.rass.player.ui.components.PosterCard
import nl.rass.player.ui.components.PosterWidth
import nl.rass.player.ui.components.SectionHeader
import nl.rass.player.ui.components.tvFocusable
import nl.rass.player.ui.format.fa
import nl.rass.player.ui.navigation.Route
import nl.rass.player.ui.search.keyboard.KbKey
import nl.rass.player.ui.search.keyboard.OnScreenKeyboard
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

@Composable
fun SearchScreen(onNavigate: (Route) -> Unit, onBack: () -> Unit) {
    val vm: SearchViewModel = hiltViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
    }
    val micAvailable = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val voicePrompt = stringResource(R.string.search_voice_prompt)
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::submit)
        }
    }
    val startVoice = {
        runCatching { voiceLauncher.launch(Intent(voiceIntent).putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)) }
        Unit
    }

    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
    BackHandler(enabled = s.query.isNotEmpty()) { vm.setQuery("") }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // Start side: keyboard.
        OnScreenKeyboard(
            language = s.language,
            micAvailable = micAvailable,
            onKey = { if (it == KbKey.Mic) startVoice() else vm.onKey(it) },
            modifier = Modifier.width(560.dp),
            firstKeyFocus = firstKey,
        )
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            QueryField(s.query, vm::setQuery, onDone = vm::rememberCurrent)
            SuggestionStrip(s, onPick = vm::submit, onClearRecents = vm::clearRecents)
            Filters(s, vm::setSource, vm::setType)
            ResultsArea(s, onOpen = { key ->
                vm.rememberCurrent()
                onNavigate(Route.Detail(key))
            })
        }
    }
}

@Composable
private fun QueryField(query: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = TextPrimary),
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceRaised)
            .border(if (focused) 3.dp else 1.dp, if (focused) TextPrimary else TextMuted.copy(alpha = 0.4f), shape)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.titleMedium, color = TextMuted)
                }
                inner()
            }
        },
    )
}

/** Suggestions while typing; recent searches (with a clear action) when the query is empty. */
@Composable
private fun SuggestionStrip(s: SearchUiState, onPick: (String) -> Unit, onClearRecents: () -> Unit) {
    val showRecents = !s.hasQuery
    val items = if (showRecents) s.recents else s.suggestions
    if (items.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items, key = { (if (showRecents) "r:" else "s:") + it }) { text ->
            StripChip(text, onClick = { onPick(text) }, recent = showRecents)
        }
        if (showRecents) {
            item(key = "clear") {
                StripChip(stringResource(R.string.search_recent_clear), onClick = onClearRecents, recent = false, accent = true)
            }
        }
    }
}

@Composable
private fun StripChip(text: String, onClick: () -> Unit, recent: Boolean, accent: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (accent) Accent else TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .tvFocusable(shape = shape, onClick = onClick)
            .clip(shape)
            .background(if (recent) SurfaceRaised else SurfaceRaised)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun Filters(s: SearchUiState, onSource: (String?) -> Unit, onType: (TypeFilter) -> Unit) {
    val allId = "__all"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.sources.size > 1) {
            val chips = listOf(ChipItem(allId, stringResource(R.string.search_filter_all_sources))) +
                s.sources.map { ChipItem(it.id, it.name) }
            ChipRow(chips, s.sourceFilter ?: allId, { onSource(if (it == allId) null else it) })
        }
        val types = listOf(
            ChipItem(TypeFilter.All.name, stringResource(R.string.search_type_all)),
            ChipItem(TypeFilter.Movie.name, stringResource(R.string.search_type_movie)),
            ChipItem(TypeFilter.Series.name, stringResource(R.string.search_type_series)),
        )
        ChipRow(types, s.typeFilter.name, { onType(TypeFilter.valueOf(it)) })
    }
}

@Composable
private fun ResultsArea(s: SearchUiState, onOpen: (String) -> Unit) {
    when {
        !s.hasQuery -> EmptyState(
            title = stringResource(R.string.nav_search),
            message = stringResource(
                if (s.catalogLoading) R.string.search_loading_catalog else R.string.search_idle_message,
            ),
        )
        s.results.isEmpty() -> EmptyState(
            title = stringResource(R.string.search_empty_title),
            message = stringResource(R.string.search_empty_message),
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(
                stringResource(R.string.search_results_count, s.results.size.fa()),
            )
            val multi = s.sources.size > 1
            LazyVerticalGrid(
                columns = GridCells.Adaptive(PosterWidth + 16.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(s.results, key = { it.key }) { item ->
                    PosterCard(
                        imageUrl = item.posterUrl,
                        title = item.title,
                        onClick = { onOpen(item.key) },
                        badges = if (multi) badgesOf(item.sourceNames) else emptyList(),
                    )
                }
            }
        }
    }
}

private fun badgesOf(names: List<String>): List<String> =
    if (names.size <= 2) names else names.take(2) + "+${(names.size - 2).fa()}"
