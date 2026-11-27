// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import au.kidfin.tv.media.DurationLabels
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.session.SessionState

// ---- Watching (timed and one-title sessions) --------------------------------------------------------------------------

@Composable
internal fun BrowseScreen(vm: MainViewModel) {
    BackHandler { vm.onAskLeave() }
    val browse by vm.browse.collectAsStateWithLifecycle()
    val remaining by vm.remainingLabel.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopBar {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                remaining?.let { TimePill(it) }
                CogButton(vm::onAskGrownUp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Pick another", style = MaterialTheme.typography.displaySmall)
        Notice(vm)
        when (val b = browse) {
            BrowseState.Loading -> Text("Loading…", color = Sb.Muted, modifier = Modifier.padding(top = 24.dp))
            is BrowseState.Failed -> {
                val focus = rememberInitialFocus(b)
                Spacer(Modifier.height(16.dp))
                Text(b.message, color = Tones.Sun.ink, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                PillButton("Try again", vm::onRefresh, Modifier.focusRequester(focus))
            }
            is BrowseState.Offer -> {
                var focused by remember(b) { mutableStateOf(b.items.firstOrNull()) }
                CardGrid(b.items, vm.browseGrid, Modifier.weight(1f), b, { focused = it }) { vm.onPlay(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FocusedTitle(focused, Modifier.weight(1f))
                    PageButtons(b.nextCursor, vm::onMore, vm::onRefresh)
                }
            }
        }
    }
}

@Composable
internal fun ResumeScreen(vm: MainViewModel) {
    BackHandler { vm.onAskLeave() }
    val session by vm.session.collectAsStateWithLifecycle()
    val s = session as? SessionState.OneTitle ?: return
    val focus = rememberInitialFocus()
    Box(Modifier.fillMaxSize()) {
        TopBar { CogButton(vm::onAskGrownUp) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Heading(
            s.approvedItemTitle,
            if (s.resumePositionTicks > 0) "Paused at ${DurationLabels.clock(s.resumePositionTicks)}" else null,
        )
        Spacer(Modifier.height(28.dp))
        ButtonRow {
            PillButton(
                if (s.resumePositionTicks > 0) "Carry on watching" else "Play",
                vm::onResumeOneTitle,
                Modifier.focusRequester(focus),
                large = true,
            )
        }
        Notice(vm)
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun PlayerScreen(vm: MainViewModel) {
    BackHandler { vm.onStopPlayback() }
    val item by vm.nowPlaying.collectAsStateWithLifecycle()
    val snap by vm.player.snapshot.collectAsStateWithLifecycle()
    val exo by vm.player.surface.collectAsStateWithLifecycle()
    val remaining by vm.remainingLabel.collectAsStateWithLifecycle()
    KeepScreenOn()
    val focus = rememberInitialFocus()
    // Controls show while not playing, and for a few seconds after any key press.
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            now = System.currentTimeMillis()
        }
    }
    val showControls = snap.phase != PlayerPhase.PLAYING || now - lastKeyAt < CONTROLS_MS
    val controlsAlpha by animateFloatAsState(if (showControls) 1f else 0f, label = "controls")
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) {
                    val wasHidden = !showControls
                    lastKeyAt = System.currentTimeMillis()
                    now = lastKeyAt
                    // First press only reveals the controls.
                    wasHidden && it.key != Key.MediaPlayPause && it.key != Key.Back
                } else {
                    false
                }
            },
    ) {
        exo?.let { ContentFrame(player = it, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (snap.phase == PlayerPhase.PREPARING) {
            Text(
                "Loading…",
                style = MaterialTheme.typography.titleLarge,
                color = Sb.Muted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .alpha(controlsAlpha)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 48.dp, vertical = 24.dp),
        ) {
            Text(item?.title ?: "", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            val fraction = if (snap.durationTicks > 0) snap.positionTicks.toFloat() / snap.durationTicks else 0f
            Box(Modifier.fillMaxWidth().height(8.dp).background(Sb.LineStrong, RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).background(Tones.Sun.strong, RoundedCornerShape(4.dp)))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ButtonRow {
                    PillButton(if (snap.phase == PlayerPhase.PAUSED) "Play" else "Pause", vm::onTogglePause, Modifier.focusRequester(focus))
                    PillButton("Stop", vm::onStopPlayback, kind = PillKind.QUIET)
                }
                Spacer(Modifier.width(24.dp))
                Text(
                    "${DurationLabels.clock(snap.positionTicks)} / ${DurationLabels.clock(snap.durationTicks)}" +
                        if (snap.phase == PlayerPhase.PAUSED) "  · paused" else "",
                    color = Sb.Muted,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                remaining?.let { TimePill(it) }
            }
        }
    }
}

private const val CONTROLS_MS = 4_000L

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun CardGrid(
    items: List<MediaItem>,
    memory: GridMemory,
    modifier: Modifier,
    focusKey: Any,
    onFocused: (MediaItem) -> Unit,
    onPick: (MediaItem) -> Unit,
) {
    val keys = remember(items) { items.map { it.id } }
    val requester = rememberGridFocus(memory, keys, items.firstOrNull()?.id, restartKey = focusKey)
    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        state = memory.state,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
        modifier = modifier,
    ) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
            StoryCard(
                item,
                Tones.at(i),
                Modifier.focusRequester(requester(item.id)),
                onFocused = {
                    memory.focusedKey = item.id
                    onFocused(item)
                },
            ) { onPick(item) }
        }
    }
}

/** Title and runtime of the focused poster, in the footer. */
@Composable
private fun FocusedTitle(item: MediaItem?, modifier: Modifier) {
    Column(modifier) {
        if (item != null) {
            Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detailsLabel(item), style = MaterialTheme.typography.bodyMedium, color = Sb.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun PageButtons(nextCursor: Int?, onMore: () -> Unit, onStart: () -> Unit) {
    ButtonRow {
        if (nextCursor != null) PillButton("More", onMore, kind = PillKind.QUIET)
        PillButton("Start of list", onStart, kind = PillKind.QUIET)
    }
}
