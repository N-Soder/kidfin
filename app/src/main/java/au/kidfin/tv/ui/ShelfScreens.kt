// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import au.kidfin.tv.jellyfin.ConnectionState
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.ShelfOrdering
import au.kidfin.tv.media.ShelfRows

// ---- Shelf (locked) -------------------------------------------------------------------------

@Composable
internal fun ShelfScreen(vm: MainViewModel) {
    BackHandler { vm.onAskLeave() }
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    // Focus already on search or the cog: the shelf arriving mustn't take it away.
    var barFocused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        val connection by vm.connection.collectAsStateWithLifecycle()
        // The highlighted title and its details take the heading's place: no strip at the bottom.
        val shown = focused.takeIf { connection is ConnectionState.Ready && shelf is ShelfState.Ready }
        TopBar(shown?.title ?: "What shall we watch?", shown?.let(::detailsLabel)) {
            if (connection is ConnectionState.Ready) {
                Row(Modifier.onFocusChanged { barFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SearchButton(vm::onOpenSearch)
                    CogButton(vm::onAskGrownUp)
                }
            }
        }
        Notice(vm)
        if (connection !is ConnectionState.Ready) {
            SetupCard(connection, vm::onStartSetup)
            return@Column
        }
        when (val b = shelf) {
            ShelfState.Loading -> Text("Loading…", color = Sb.Muted, modifier = Modifier.padding(top = 24.dp))
            is ShelfState.Failed -> {
                val focus = rememberInitialFocus(b)
                Spacer(Modifier.height(16.dp))
                Text(b.message, color = Tones.Sun.ink, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                PillButton("Try again", vm::onShelfRetry, Modifier.focusRequester(focus))
            }
            is ShelfState.Ready -> {
                ShelfGrid(
                    b.rows,
                    vm.shelfGrid,
                    Modifier.weight(1f).onFocusChanged { if (!it.hasFocus) focused = null },
                    { !barFocused },
                    { focused = it },
                    vm::onShelfPick,
                )
            }
        }
    }
}

/**
 * Keep watching, Favourites, then everything, in one lazily-built grid: it scrolls on as far
 * as the catalogue goes and posters load as they come into view.
 */
@Composable
private fun ShelfGrid(
    rows: ShelfRows,
    memory: GridMemory,
    modifier: Modifier,
    allowFocus: () -> Boolean,
    onFocused: (MediaItem) -> Unit,
    onPick: (MediaItem) -> Unit,
) {
    val sections = remember(rows) {
        listOf("Keep watching" to rows.keepWatching, "Favourites" to rows.favourites, "Everything" to rows.everything)
            .filter { it.second.isNotEmpty() }
    }
    val keys = remember(sections) { sections.flatMap { (title, items) -> listOf<Any>("h-$title") + items.map { "$title-${it.id}" } } }
    val first = sections.firstOrNull()?.let { (title, items) -> "$title-${items.first().id}" }
    // Keyed by the memory only: a background refresh must not yank focus back to the first poster.
    val requester = rememberGridFocus(memory, keys, first, allowed = allowFocus)
    LazyVerticalGrid(
        columns = GridCells.Fixed(ShelfOrdering.ROW),
        state = memory.state,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 40.dp),
        modifier = modifier,
    ) {
        sections.forEach { (title, items) ->
                item(key = "h-$title", span = { GridItemSpan(maxLineSpan) }) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Sb.Muted, modifier = Modifier.padding(top = 6.dp))
                }
                val section = title
                itemsIndexed(items, key = { _, it -> "$section-${it.id}" }) { i, item ->
                    val key = "$section-${item.id}"
                    StoryCard(
                        item,
                        Tones.at(i),
                        Modifier.focusRequester(requester(key)),
                        onFocused = {
                            memory.focusedKey = key
                            onFocused(item)
                        },
                    ) { onPick(item) }
                }
            }
    }
}

// ---- Episodes (locked) ----------------------------------------------------------------------

@Composable
internal fun EpisodesScreen(vm: MainViewModel, series: MediaItem) {
    BackHandler { vm.onBackToShelf() }
    val state by vm.episodes.collectAsStateWithLifecycle()
    val favourites by vm.favouriteIds.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopBar(series.title) {
            HeartButton((series.seriesId ?: series.id) in favourites, { vm.onHeartShow(series) })
        }
        Notice(vm)
        when (val e = state) {
            EpisodesState.Loading -> Text("Loading…", color = Sb.Muted, modifier = Modifier.padding(top = 24.dp))
            is EpisodesState.Failed -> {
                val focus = rememberInitialFocus(e)
                Spacer(Modifier.height(16.dp))
                Text(e.message, color = Tones.Sun.ink, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                ButtonRow {
                    PillButton("Try again", vm::onEpisodesRetry, Modifier.focusRequester(focus))
                    PillButton("Back", vm::onBackToShelf, kind = PillKind.QUIET)
                }
            }
            is EpisodesState.Ready -> EpisodeList(e, series, vm.episodesMemory, vm::onShelfPick)
        }
    }
}

@Composable
private fun EpisodeList(state: EpisodesState.Ready, series: MediaItem, memory: EpisodesMemory, onPick: (MediaItem) -> Unit) {
    val seasons = remember(state) { state.episodes.map { it.season }.distinct() }
    val nextUp = state.episodes.firstOrNull { it.id == state.nextUpId } ?: state.episodes.first()
    // Kept in the view model: back from the PIN is the same season, scrolled to the same place.
    val season = if (memory.seasonChosen) memory.season else nextUp.season
    val shown = remember(state, season) { state.episodes.filter { it.season == season } }
    if (seasons.size > 1) {
        Spacer(Modifier.height(4.dp))
        // Many seasons scroll sideways rather than running off the screen.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            items(seasons) { s ->
                PillButton(
                    s?.let { if (it == 0) "Specials" else "Season $it" } ?: "Other",
                    {
                        memory.season = s
                        memory.seasonChosen = true
                    },
                    kind = if (s == season) PillKind.PRIMARY else PillKind.QUIET,
                )
            }
        }
    }
    // Focus starts on next up (or the first in the chosen season), never on the season pills.
    // Each season has its own grid state, so switching never keeps another season's scroll.
    val grid = memory.grid(season)
    val target = shown.firstOrNull { it.id == nextUp.id } ?: shown.firstOrNull()
    val keys = remember(shown) { shown.map { it.id } }
    val requester = rememberGridFocus(grid, keys, target?.id)
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = grid.state,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 40.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(shown, key = { _, it -> it.id }) { i, ep ->
            EpisodeCard(
                ep,
                Tones.at(i),
                isNext = ep.id == nextUp.id,
                modifier = Modifier.focusRequester(requester(ep.id)),
                onFocused = { grid.focusedKey = ep.id },
            ) { onPick(ep.copy(seriesName = series.title)) }
        }
    }
}

@Composable
private fun EpisodeCard(ep: MediaItem, fallback: Tone, isNext: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var tone by remember(ep.id) { mutableStateOf(ArtworkTones.cached(ep.id) ?: fallback) }
    Box(modifier) {
        Box(Modifier.matchParentSize().offset(y = 5.dp).background(tone.strong, CardShape))
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(shape = CardShape),
            colors = CardDefaults.colors(containerColor = Sb.Card, focusedContainerColor = Sb.Card),
            border = CardDefaults.border(
                border = Border(BorderStroke(3.dp, tone.strong), shape = CardShape),
                focusedBorder = Border(BorderStroke(4.dp, Sb.Ink), shape = CardShape),
                pressedBorder = Border(BorderStroke(4.dp, Sb.Ink), shape = CardShape),
            ),
            scale = CardDefaults.scale(focusedScale = 1.05f),
        ) {
            Box {
                Artwork(ep, tone, Modifier.fillMaxWidth().aspectRatio(16f / 9f), initialSize = 36) { tone = it }
                if (isNext) {
                    Text(
                        "Next",
                        style = MaterialTheme.typography.labelMedium,
                        color = Sb.Bg,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                            .background(tone.strong, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                } else if (ep.watched) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = Sb.Ink,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                            .background(Sb.Bg.copy(alpha = 0.75f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                ep.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(Sb.Bg.copy(alpha = 0.7f)))
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p).height(6.dp).background(tone.strong))
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(
                    listOfNotNull(ep.episode?.toString(), ep.title).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(runtimeLabel(ep).orEmpty(), maxLines = 1, style = MaterialTheme.typography.labelMedium, color = Sb.Muted)
            }
        }
    }
}

// ---- First run ------------------------------------------------------------------------------

/** What a grown-up needs to do before anything can be watched, with one obvious button. */
@Composable
private fun SetupCard(connection: ConnectionState, onSetUp: () -> Unit) {
    val focus = rememberInitialFocus(connection)
    val signedIn = connection is ConnectionState.NeedsLibraries
    val (title, button) = when (connection) {
        is ConnectionState.SignedOut -> "Signed out" to "Sign in"
        is ConnectionState.NeedsLibraries -> "Almost ready" to "Choose videos"
        else -> "Set up Kidfin" to "Start"
    }
    Spacer(Modifier.height(28.dp))
    Column(
        Modifier
            .width(820.dp)
            .background(Sb.Card, CardShape)
            .border(3.dp, Sb.Brand, CardShape)
            .padding(horizontal = 36.dp, vertical = 30.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(22.dp))
        SetupStep(1, "Sign in to Jellyfin", done = signedIn)
        Spacer(Modifier.height(12.dp))
        SetupStep(2, "Choose videos", done = false)
        Spacer(Modifier.height(26.dp))
        PillButton(button, onSetUp, Modifier.focusRequester(focus), large = true)
    }
}

@Composable
private fun SetupStep(number: Int, text: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).background(if (done) Tones.Teal.strong else Sb.LineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (done) "✓" else number.toString(), style = MaterialTheme.typography.titleMedium, color = if (done) Sb.Bg else Sb.Ink)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            color = if (done) Sb.Muted else Sb.Ink,
        )
    }
}
