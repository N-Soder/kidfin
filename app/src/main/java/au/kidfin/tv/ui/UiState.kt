// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaLibrary
import au.kidfin.tv.media.ShelfRows
import au.kidfin.tv.security.PinFormat
import au.kidfin.tv.session.ApprovalOffer

internal sealed interface Screen {
    data object Loading : Screen
    data object SetupPin : Screen
    data object PinCorrupt : Screen
    /** Locked: the child browses everything permitted; nothing plays until a grown-up approves. */
    data object Shelf : Screen
    /** A show's episodes, next up highlighted. Picking one goes on to the PIN like any title. */
    data class Episodes(val series: MediaItem) : Screen
    data object Search : Screen
    data object EnterPin : Screen
    /** Back on a child home screen: the secret before Kidfin goes to the background. */
    data object LeaveKidfin : Screen
    /** One-time switch from an old 4-digit PIN to secret moves, straight after entering it. */
    data object ChangePin : Screen
    data object ParentMenu : Screen
    data class Approve(val item: MediaItem, val offer: ApprovalOffer) : Screen
    data object Browse : Screen
    data object Resume : Screen
    data object Player : Screen
    data object Goodbye : Screen
    data object TvPower : Screen
    data object ConfirmTvPowerTest : Screen
    data object ServerSetup : Screen
    data object PickLibraries : Screen
}

internal sealed interface BrowseState {
    data object Loading : BrowseState
    data class Offer(val items: List<MediaItem>, val nextCursor: Int?) : BrowseState
    data class Failed(val message: String) : BrowseState
}

internal sealed interface EpisodesState {
    data object Loading : EpisodesState
    data class Ready(val episodes: List<MediaItem>, val nextUpId: String?) : EpisodesState
    data class Failed(val message: String) : EpisodesState
}

internal sealed interface ShelfState {
    data object Loading : ShelfState
    data class Ready(val rows: ShelfRows) : ShelfState
    data class Failed(val message: String) : ShelfState
}

/**
 * What the PIN screen may show: how many presses so far, never which. [format] picks
 * hidden arrow moves or, for an old PIN not yet replaced, the digit keypad.
 */
internal data class PinEntryState(
    val entered: Int = 0,
    val format: PinFormat = PinFormat.MOVES,
    val message: String? = null,
    val busy: Boolean = false,
    /** Too many wrong tries: presses are ignored and [message] counts down. */
    val coolingDown: Boolean = false,
)

/**
 * Scroll position and last focused item of a poster grid. Held by the view model so going
 * to the PIN or approve screen and back lands on the same poster, not somewhere mid-shelf.
 */
internal class GridMemory {
    val state = LazyGridState()
    var focusedKey: Any? = null

    /** The list last shown, so a new list (another search) starts at the top. */
    var shownFor: Any? = null
}

/** A show's episode list: the chosen season and each season's own grid. */
internal class EpisodesMemory(val seriesId: String) {
    /** Null until chosen; the screen starts on next up's season. */
    var season: Int? by mutableStateOf(null)
    var seasonChosen by mutableStateOf(false)
    private val grids = HashMap<Int?, GridMemory>()

    fun grid(season: Int?): GridMemory = grids.getOrPut(season) { GridMemory() }
}

/** Parent sign-in form. The password is never kept here: it goes straight to sign-in. */
internal data class ServerSetupState(
    val address: String = "http://",
    val username: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)

internal sealed interface LibraryPickState {
    data object Loading : LibraryPickState
    data class Loaded(
        val libraries: List<MediaLibrary>,
        val selected: Set<String>,
        /** The child account can see every library on the server: worth restricting there. */
        val accountSeesEverything: Boolean,
    ) : LibraryPickState
    data class Failed(val message: String) : LibraryPickState
}
