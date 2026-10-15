package nl.rass.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.R
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.components.ScreenScaffold
import nl.rass.player.ui.components.SectionHeader
import nl.rass.player.ui.components.tvFocusable
import nl.rass.player.ui.theme.Accent
import nl.rass.player.ui.theme.Surface
import nl.rass.player.ui.theme.SurfaceRaised
import nl.rass.player.ui.theme.TextMuted
import nl.rass.player.ui.theme.TextPrimary

private val RowShape = RoundedCornerShape(12.dp)

/** One settings page: header + a D-pad list. Focus goes to the first row; Back calls [onBack]. */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(3) {
            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(100)
        }
    }
    ScreenScaffold(topBar = { SectionHeader(title, Modifier.padding(bottom = 20.dp), subtitle) }) {
        LazyColumn(
            Modifier.fillMaxWidth().focusRequester(fr).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            content = content,
        )
    }
}

/** A focusable row: title (+ optional subtitle) at the start, [value] and/or [trailing] at the end. */
@Composable
fun SettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .tvFocusable(shape = RowShape, scale = 1.02f, enabled = enabled, onClick = onClick)
            .clip(RowShape)
            .background(SurfaceRaised)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) TextPrimary else TextMuted)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = TextMuted, maxLines = 1, modifier = Modifier.widthIn(max = 460.dp))
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchVisual(checked: Boolean, showLabel: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showLabel) Text(
            stringResource(if (checked) R.string.set_state_on else R.string.set_state_off),
            style = MaterialTheme.typography.labelMedium,
            color = if (checked) TextPrimary else TextMuted,
        )
        Box(
            Modifier.size(width = 60.dp, height = 32.dp).clip(CircleShape)
                .background(if (checked) Accent else Color(0xFF2B2F38))
                .border(if (checked) 0.dp else 1.dp, Color(0x55FFFFFF), CircleShape)
                .padding(4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(24.dp).clip(CircleShape).background(if (checked) Color.White else Color(0xFF8B909B))) }
    }
}

/** Bar of primary action pills shown above a list; the first button should get initial focus (it is first in focus order). */
@Composable
fun ActionBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RowShape).background(Surface).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Focusable trash icon for the end of a row. */
@Composable
fun DeleteButton(onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        Modifier.size(44.dp).tvFocusable(shape = shape, onClick = onClick).clip(shape).background(Color(0xFF3A1D1D)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.tv.material3.Icon(
            androidx.compose.ui.res.painterResource(R.drawable.ic_delete), stringResource(R.string.set_delete),
            Modifier.size(24.dp), tint = Color(0xFFFF8A80),
        )
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    SettingRow(title, onClick = { onChange(!checked) }, subtitle = subtitle, enabled = enabled, trailing = { SwitchVisual(checked, showLabel = true) })
}

@Composable
fun CheckVisual(checked: Boolean) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(if (checked) Accent else Color(0xFF3A3E47)),
        contentAlignment = Alignment.Center,
    ) { if (checked) Text("✓", color = Color.White, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    SettingRow(title, onClick = { onChange(!checked) }, subtitle = subtitle, trailing = { CheckVisual(checked) })
}

/** A row that opens a [PickerDialog]; shows the current choice as its value. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit,
    subtitle: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, onClick = { open = true }, subtitle = subtitle, value = options.firstOrNull { it.first == selected }?.second)
    if (open) PickerDialog(title, options, selected, { onPick(it); open = false }, { open = false })
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit, title: String? = null, width: androidx.compose.ui.unit.Dp = 720.dp, content: @Composable () -> Unit) {
    // Dialogs get their own window; re-apply the app's scaled density and RTL so they match the rest
    // of the UI (otherwise they render ~2× larger and push the action buttons off screen).
    val appDensity = androidx.compose.ui.platform.LocalDensity.current
    val appDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
      androidx.compose.runtime.CompositionLocalProvider(
          androidx.compose.ui.platform.LocalDensity provides appDensity,
          androidx.compose.ui.platform.LocalLayoutDirection provides appDirection,
      ) {
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            repeat(3) {
                if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(100)
            }
        }
        Column(
            Modifier
                .widthIn(max = width)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .heightIn(max = 760.dp)
                .padding(28.dp)
                .focusRequester(fr)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            content()
        }
      }
    }
}

@Composable
fun <T> PickerDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    SettingsDialog(onDismiss, title, width = 560.dp) {
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(options.size) { i ->
                val (v, label) = options[i]
                SettingRow(label, onClick = { onPick(v) }, trailing = { if (v == selected) CheckVisual(true) })
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsDialog(onDismiss, title, width = 640.dp) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton(cancelText, onDismiss, style = PillStyle.Secondary)
            PillButton(confirmText, onConfirm, style = PillStyle.Accent)
        }
    }
}

/** Single-line field for the TV: OK on the focused field opens the on-screen keyboard. */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    ltr: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    /** Long text (links, keys): shows the hint that the Google TV phone app can type/paste here. */
    phoneHint: Boolean = false,
    /** Called when the keyboard's ✓/Done/Go key is pressed. */
    onDone: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val kb = LocalSoftwareKeyboardController.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        BasicTextField(
            value, onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content,
            ),
            cursorBrush = Brush.linearGradient(listOf(Color.White, Color.White)),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { kb?.hide(); onDone?.invoke() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceRaised)
                .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) kb?.show()
                    false
                },
        )
        if (phoneHint) Text(stringResource(R.string.phone_typing_hint), style = MaterialTheme.typography.labelMedium, color = TextMuted)
    }
}

/** Dialog with one text field (used for URLs, keys, names). [onClear] adds a "clear" button when non-null. */
@Composable
fun TextEntryDialog(
    title: String,
    label: String,
    initial: String,
    saveText: String,
    cancelText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    password: Boolean = false,
    ltr: Boolean = false,
    hint: String? = null,
    clearText: String? = null,
    onClear: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    SettingsDialog(onDismiss, title) {
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        TvTextField(text, { text = it }, label, password = password, ltr = ltr, phoneHint = password || ltr)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillButton(saveText, { onSave(text.trim()) }, style = PillStyle.Accent)
            if (onClear != null && clearText != null) PillButton(clearText, onClear)
            PillButton(cancelText, onDismiss)
        }
    }
}

/** Plain text, one focusable block per paragraph so the D-pad can scroll it. */
@Composable
fun TextViewerDialog(title: String, text: String, closeText: String, onDismiss: () -> Unit) {
    val paras = remember(text) { text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() } }
    SettingsDialog(onDismiss, title, width = 900.dp) {
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(paras.size) { i ->
                var f by remember { mutableStateOf(false) }
                Text(
                    paras[i], style = MaterialTheme.typography.labelMedium, color = TextPrimary,
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { f = it.isFocused }
                        .focusable()
                        .background(if (f) SurfaceRaised else Color.Transparent)
                        .padding(8.dp),
                )
            }
        }
        PillButton(closeText, onDismiss)
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

@Composable
fun StatusText(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = if (error) Color(0xFFFF8A80) else TextMuted, modifier = modifier.padding(horizontal = 24.dp))
}

@Composable
fun Gap(h: Int = 8) = Box(Modifier.height(h.dp))
