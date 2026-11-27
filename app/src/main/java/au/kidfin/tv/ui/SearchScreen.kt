// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import au.kidfin.tv.media.MediaItem

/** QWERTY, with numbers on top like a phone keyboard. */
private val SEARCH_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm⌫", "␣C")

@Composable
internal fun SearchScreen(vm: MainViewModel) {
    BackHandler { vm.onBackToShelf() }
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val memory = vm.searchGrid
    // Coming back from the PIN puts focus on the result that was picked, not the keyboard.
    val returning = remember { memory.focusedKey != null }
    val keys = remember(results) { results.map { it.id } }
    val requester = rememberGridFocus(memory, keys, fallbackKey = null)
    LaunchedEffect(results) {
        // A new search starts at the top of its results.
        if (memory.shownFor != null && memory.shownFor !== results) memory.state.requestScrollToItem(0)
        memory.shownFor = results
    }
    // The highlighted result's title and details, as on the shelf.
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar(focused?.title ?: "Search", focused?.let(::detailsLabel)) {}
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(KEYBOARD_WIDTH)) {
                Box(
                    Modifier.fillMaxWidth().background(Sb.Card, RoundedCornerShape(14.dp))
                        .border(2.dp, Sb.LineStrong, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(
                        query.ifEmpty { "Type a name" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (query.isEmpty()) Sb.Muted else Sb.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                SearchKeyboard(vm, results.firstOrNull()?.takeIf { query.isNotBlank() }?.let { requester(it.id) }, initialFocus = !returning)
            }
            Spacer(Modifier.width(24.dp))
            when {
                query.isBlank() -> Unit
                results.isEmpty() -> Text("Nothing found", color = Sb.Muted, style = MaterialTheme.typography.titleMedium)
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = memory.state,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    // Room for the focused poster to grow without its top edge being cut off.
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 40.dp),
                    modifier = Modifier.weight(1f).onFocusChanged { if (!it.hasFocus) focused = null },
                ) {
                    itemsIndexed(results, key = { _, it -> it.id }) { i, item ->
                        StoryCard(
                            item,
                            Tones.at(i),
                            Modifier.focusRequester(requester(item.id)),
                            onFocused = {
                                memory.focusedKey = item.id
                                focused = item
                            },
                        ) { vm.onShelfPick(item) }
                    }
                }
            }
        }
    }
}

private val KEY = 44.dp
private val KEY_GAP = 6.dp
private val KEYBOARD_WIDTH = KEY * 10 + KEY_GAP * 9

/**
 * Big on-screen keys: no system keyboard, works from the remote alone. Left and right wrap
 * within a row; right off the end of a row goes to the results when there are some.
 */
@Composable
private fun SearchKeyboard(vm: MainViewModel, results: FocusRequester?, initialFocus: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (initialFocus) runCatching { focus.requestFocus() }
    }
    val ends = remember { SEARCH_ROWS.map { FocusRequester() to FocusRequester() } }
    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        SEARCH_ROWS.forEachIndexed { r, row ->
            val (first, last) = ends[r]
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEachIndexed { i, key ->
                    val (label, width, action) = when (key) {
                        '␣' -> Triple("Space", KEY * 6 + KEY_GAP * 5, { vm.onSearchKey(' ') })
                        'C' -> Triple("Clear", KEY * 4 + KEY_GAP * 3, vm::onSearchClear)
                        '⌫' -> Triple("⌫", KEY * 3 + KEY_GAP * 2, vm::onSearchDelete)
                        else -> Triple(key.uppercase(), KEY, { vm.onSearchKey(key) })
                    }
                    var mod: Modifier = Modifier
                    if (key == 'q') mod = mod.focusRequester(focus)
                    if (i == 0) mod = mod.focusRequester(first).focusProperties { left = last }
                    if (i == row.lastIndex) mod = mod.focusRequester(last).focusProperties { right = results ?: first }
                    KeyButton(label, action, mod, width)
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit, modifier: Modifier, width: Dp) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.width(width).height(KEY),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Sb.Card, contentColor = Sb.Ink,
            focusedContainerColor = Sb.Ink, focusedContentColor = Sb.Bg,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
