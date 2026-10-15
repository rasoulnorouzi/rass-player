package nl.rass.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Section header + virtualized horizontal row. In an RTL layout the row starts at the right edge.
 * [itemContent] receives a modifier that must be passed to the card so focus restoration works.
 */
@Composable
fun <T> ContentRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focusState: RowFocusState = rememberRowFocusState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    itemContent: @Composable (item: T, itemModifier: Modifier) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(title, subtitle = subtitle, modifier = Modifier.padding(bottom = 4.dp))
        LazyRow(
            state = focusState.listState,
            modifier = Modifier.fillMaxWidth().rowFocusEntry(focusState),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, it -> key(it) }) { index, item ->
                itemContent(item, Modifier.rowItemFocus(focusState, index))
            }
        }
    }
}
