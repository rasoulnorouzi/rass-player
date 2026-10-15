package nl.rass.player.ui.search.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.ui.components.tvFocusable
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

/**
 * A real editable field (accepts the system IME, so the Google TV phone keyboard can type/paste into it).
 * D-pad arrows always move focus out of the field instead of the cursor; OK opens the soft keyboard.
 */
@Composable
fun RassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    ltr: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = TextPrimary,
            textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content,
        ),
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(
            imeAction = imeAction,
            keyboardType = if (ltr) KeyboardType.Uri else KeyboardType.Text,
        ),
        keyboardActions = KeyboardActions(onSearch = { onImeAction() }, onDone = { onImeAction() }),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceRaised)
            .border(if (focused) 3.dp else 1.dp, if (focused) TextPrimary else TextMuted.copy(alpha = 0.4f), shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val dir = when (e.key) {
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    Key.DirectionLeft -> FocusDirection.Left
                    Key.DirectionRight -> FocusDirection.Right
                    Key.DirectionCenter, Key.Enter -> {
                        if (e.type == KeyEventType.KeyUp) kb?.show()
                        return@onPreviewKeyEvent false
                    }
                    else -> return@onPreviewKeyEvent false
                }
                if (e.type == KeyEventType.KeyDown) focusManager.moveFocus(dir)
                true
            },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.titleMedium, color = TextMuted)
                inner()
            }
        },
    )
}

/** Up to [KeyboardPredictor.MAX] completion chips; empty rows keep their height so the layout doesn't jump. */
@Composable
fun PredictorRow(
    predictions: List<Prediction>,
    onPick: (Prediction) -> Unit,
    entry: FocusRequester,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier.fillMaxWidth().height(52.dp).focusRequester(entry).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(predictions, key = { _, p -> p.result }) { _, p ->
            val shape = RoundedCornerShape(50)
            Text(
                p.label,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                maxLines = 1,
                modifier = Modifier
                    .tvFocusable(shape = shape, onClick = { onPick(p) })
                    .clip(shape)
                    .background(SurfaceRaised)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Predictor row + fa/en on-screen keyboard bound to [text]. Up from the first keyboard row reaches the
 * suggestions. [vocab] is the word list (frequency ordered), [recents] earlier inputs, [urlMode] offers URL pieces.
 */
@Composable
fun RassKeyboard(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    vocab: List<String> = emptyList(),
    recents: List<String> = emptyList(),
    urlMode: Boolean = false,
    domains: List<String> = emptyList(),
    micAvailable: Boolean = false,
    onMic: () -> Unit = {},
    firstKeyFocus: FocusRequester? = null,
) {
    var language by rememberSaveable(urlMode) { mutableStateOf(if (urlMode) KeyboardLanguage.EN else KeyboardLanguage.FA) }
    val predictions = remember(text, vocab, recents, urlMode, domains) {
        KeyboardPredictor.predict(text, vocab, recents, urlMode, domains)
    }
    val entry = remember { FocusRequester() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PredictorRow(predictions, onPick = { onTextChange(it.result) }, entry = entry)
        OnScreenKeyboard(
            language = language,
            micAvailable = micAvailable,
            onKey = {
                when (it) {
                    KbKey.Language -> language = language.toggled()
                    KbKey.Mic -> onMic()
                    else -> onTextChange(KeyboardEdit.apply(text, it))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            firstKeyFocus = firstKeyFocus,
            onUpFromTop = {
                if (predictions.isEmpty()) false else runCatching { entry.requestFocus() }.isSuccess
            },
        )
    }
}

/** Reusable text input for any screen: field (system IME works too) + [RassKeyboard]. */
@Composable
fun RassKeyboardField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    vocab: List<String> = emptyList(),
    recents: List<String> = emptyList(),
    urlMode: Boolean = false,
    domains: List<String> = emptyList(),
    firstKeyFocus: FocusRequester? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RassTextField(text, onTextChange, placeholder, ltr = urlMode)
        RassKeyboard(text, onTextChange, vocab = vocab, recents = recents, urlMode = urlMode, domains = domains, firstKeyFocus = firstKeyFocus)
    }
}
