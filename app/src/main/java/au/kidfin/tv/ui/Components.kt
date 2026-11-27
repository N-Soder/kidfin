// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaType
import kotlinx.coroutines.flow.first

@Composable
internal fun Heading(text: String, sub: String? = null) {
    Text(text, style = MaterialTheme.typography.displaySmall)
    if (sub != null) {
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = Sb.Muted)
    }
}

@Composable
internal fun Notice(vm: MainViewModel) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    notice?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.titleMedium, color = Tones.Sun.ink)
    }
}

/** Requests initial focus on the attached element once it is composed (again when [key] changes). */
@Composable
internal fun rememberInitialFocus(key: Any? = Unit): FocusRequester {
    val requester = remember(key) { FocusRequester() }
    LaunchedEffect(requester) {
        runCatching { requester.requestFocus() }
    }
    return requester
}

/**
 * Puts focus back on the remembered poster (else [fallbackKey]) when a grid is shown. A lazy
 * item that isn't composed can't take focus, so this scrolls it into view and waits until it
 * is laid out first. Runs once per [memory] and [restartKey]; a later data refresh doesn't
 * move focus. [allowed] is checked at the last moment, so focus the user has already moved
 * elsewhere (the cog, say) is left alone. Returns the focus requester for each item key.
 */
@Composable
internal fun rememberGridFocus(
    memory: GridMemory,
    keys: List<Any>,
    fallbackKey: Any?,
    restartKey: Any? = Unit,
    allowed: () -> Boolean = { true },
): (Any) -> FocusRequester {
    val requesters = remember(memory) { HashMap<Any, FocusRequester>() }
    val currentKeys by rememberUpdatedState(keys)
    val currentFallback by rememberUpdatedState(fallbackKey)
    val currentAllowed by rememberUpdatedState(allowed)
    LaunchedEffect(memory, restartKey) {
        val keysNow = currentKeys
        val target = memory.focusedKey?.takeIf { it in keysNow } ?: currentFallback ?: return@LaunchedEffect
        val index = keysNow.indexOf(target)
        if (index < 0) return@LaunchedEffect
        val state = memory.state
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        if (state.layoutInfo.visibleItemsInfo.none { it.key == target }) state.requestScrollToItem(index)
        snapshotFlow { state.layoutInfo.visibleItemsInfo.any { it.key == target } }.first { it }
        // One more frame so the item's focus target is attached.
        withFrameNanos { }
        if (currentAllowed()) runCatching { requesters.getOrPut(target) { FocusRequester() }.requestFocus() }
    }
    return { key -> requesters.getOrPut(key) { FocusRequester() } }
}

private val TopBarSize = 28.sp

@Composable
internal fun TopBar(title: String? = null, details: String? = null, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Brand in the serif, content in the sans, the same size, split by a thin rule.
        Wordmark(TopBarSize)
        Row(Modifier.weight(1f).padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            title?.let {
                Box(Modifier.padding(horizontal = 22.dp).size(width = 2.dp, height = 28.dp).background(Sb.LineStrong, RoundedCornerShape(1.dp)))
                Row(Modifier.weight(1f, fill = false)) {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = TopBarSize, lineHeight = 34.sp, letterSpacing = (-0.2).sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                    )
                    details?.takeIf { it.isNotEmpty() }?.let {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight(500)),
                            color = Sb.Muted,
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            }
        }
        right()
    }
}

/** "Bluey · TV show", "1992 · 1 h 31 min": what the poster alone doesn't say. */
internal fun detailsLabel(item: MediaItem): String = listOfNotNull(
    item.seriesName?.takeUnless { item.title.startsWith(it) },
    if (item.type == MediaType.SERIES) "TV show" else null,
    item.year?.toString(),
    runtimeLabel(item),
).joinToString(" · ")
