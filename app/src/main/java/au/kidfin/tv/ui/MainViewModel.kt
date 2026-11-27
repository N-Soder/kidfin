// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.kidfin.tv.jellyfin.ConnectionState
import au.kidfin.tv.jellyfin.SignInResult
import au.kidfin.tv.media.CatalogueResult
import au.kidfin.tv.media.DurationLabels
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaType
import au.kidfin.tv.media.RuntimeRules
import au.kidfin.tv.power.StandbyDecision
import au.kidfin.tv.power.StandbyRequestResult
import au.kidfin.tv.security.ParentGrant
import au.kidfin.tv.security.PinFormat
import au.kidfin.tv.security.PinResult
import au.kidfin.tv.security.PinStatus
import au.kidfin.tv.session.Allowance
import au.kidfin.tv.session.Approval
import au.kidfin.tv.session.EndCommand
import au.kidfin.tv.session.ExtendResult
import au.kidfin.tv.session.Extension
import au.kidfin.tv.session.Rejection
import au.kidfin.tv.session.SessionState
import au.kidfin.tv.session.StartResult
import au.kidfin.tv.session.StopResult
import au.kidfin.tv.session.Ticks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Screen flow and wiring between the session engine, catalogue, player and power policy.
 * Time and approval decisions belong to the SessionController, the grant to [ParentGate],
 * the shelf to [ShelfLoader] and the player to [PlaybackCoordinator].
 */
internal class MainViewModel(private val c: AppServices) : ViewModel() {
    private val mutableScreen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = mutableScreen.asStateFlow()

    private val mutableBrowse = MutableStateFlow<BrowseState>(BrowseState.Loading)
    val browse: StateFlow<BrowseState> = mutableBrowse.asStateFlow()

    private val gate = ParentGate(c.clock)
    private val secret = SecretPad(viewModelScope) { c.clock.nowTicks() / Ticks.PER_MILLISECOND }
    val pin: StateFlow<PinEntryState> = secret.state

    private val shelfLoader = ShelfLoader(
        viewModelScope, c.shelfSource, c.shelfCache, c.clock, { c.account.allowedLibraryIds },
        c.io, c.compute, c::log, c::today,
    )
    val shelf: StateFlow<ShelfState> = shelfLoader.shelf

    /** Titles (or whole series) hearted for the child account, by id. */
    val favouriteIds: StateFlow<Set<String>> = shelfLoader.favouriteIds

    private val mutableEpisodes = MutableStateFlow<EpisodesState>(EpisodesState.Loading)
    val episodes: StateFlow<EpisodesState> = mutableEpisodes.asStateFlow()
    private var episodesJob: Job? = null

    private val mutableSearchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = mutableSearchQuery.asStateFlow()
    private val mutableSearchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = mutableSearchResults.asStateFlow()
    private var searchJob: Job? = null

    /** What the child picked on the locked shelf, waiting for the PIN. Never starts anything alone. */
    val pendingItem: StateFlow<MediaItem?> = gate.pending

    private val mutableRemaining = MutableStateFlow<String?>(null)
    val remainingLabel: StateFlow<String?> = mutableRemaining.asStateFlow()

    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    /** An approve button was pressed and the title is being checked and started. */
    private val mutableApproving = MutableStateFlow(false)
    val approving: StateFlow<Boolean> = mutableApproving.asStateFlow()

    private val leaveChannel = Channel<Unit>(Channel.CONFLATED)
    /** The grown-up confirmed leaving: the activity moves to the background. */
    val leaveRequests: Flow<Unit> = leaveChannel.receiveAsFlow()

    val shelfGrid = GridMemory()
    val browseGrid = GridMemory()
    var searchGrid = GridMemory()
        private set
    var episodesMemory = EpisodesMemory("")
        private set

    val session: StateFlow<SessionState> = c.controller.state
    val connection: StateFlow<ConnectionState> = c.account.state

    private val playback = PlaybackCoordinator(
        viewModelScope, c.controller, c.newPlayer(viewModelScope), ::checkAccess, PlaybackHost(), c::log,
    )
    val player: VideoPlayer = playback.player
    val nowPlaying: StateFlow<MediaItem?> = playback.nowPlaying

    private val mutableSetup = MutableStateFlow(ServerSetupState())
    val setup: StateFlow<ServerSetupState> = mutableSetup.asStateFlow()

    private val mutableLibraries = MutableStateFlow<LibraryPickState>(LibraryPickState.Loading)
    val libraries: StateFlow<LibraryPickState> = mutableLibraries.asStateFlow()

    /** Screen to go back to if the grown-up doesn't confirm leaving. */
    private var leaveReturn: Screen? = null
    val standbyConnected: StateFlow<Boolean> = c.standbyConnected

    private val mutableLastPowerResult = MutableStateFlow<String?>(null)
    /** Plain-language outcome of the last standby request, for the parent TV power screen. */
    val lastPowerResult: StateFlow<String?> = mutableLastPowerResult.asStateFlow()

    private var setupFirstEntry: String? = null
    /** Format of the stored secret, read at start and after it changes. */
    private var pinFormat = PinFormat.MOVES
    private var foreground = true
    private var signOffJob: Job? = null

    private val locked: Boolean get() = session.value is SessionState.Locked

    init {
        viewModelScope.launch {
            val status = try {
                withContext(c.io) { c.pinGuard.status() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PinStatus.CORRUPT
            }
            pinFormat = runCatching { withContext(c.io) { c.pinGuard.format() } }.getOrNull() ?: PinFormat.MOVES
            mutableScreen.value = when (status) {
                PinStatus.NOT_SET -> Screen.SetupPin
                PinStatus.CORRUPT -> Screen.PinCorrupt
                PinStatus.READY -> baseScreen()
            }
            enterScreen(mutableScreen.value)
        }
        playback.attach()
        // Nothing playing for 30 minutes (paused, Pick another, Resume) ends the session.
        viewModelScope.launch {
            while (isActive) {
                delay(IDLE_CHECK_MS)
                if (foreground && signOffJob?.isActive != true) {
                    c.controller.checkIdle()?.let { end ->
                        playback.stopQuietly()
                        beginEnding(end)
                    }
                }
            }
        }
    }

    // ---- Navigation ----------------------------------------------------------------------

    private fun baseScreen(): Screen = when (session.value) {
        is SessionState.Locked -> Screen.Shelf
        is SessionState.Timed -> Screen.Browse
        is SessionState.OneTitle -> Screen.Resume
        is SessionState.Ending -> Screen.Goodbye
    }

    /** Notices belong to the screen they were shown on; set one after calling this. */
    private fun go(screen: Screen) {
        if (screen != mutableScreen.value) mutableNotice.value = null
        mutableScreen.value = screen
        enterScreen(screen)
    }

    private fun enterScreen(screen: Screen) {
        when (screen) {
            Screen.Browse -> loadCatalogue(0)
            Screen.Shelf -> {
                gate.setPending(null)
                if (shelfLoader.needsLoad()) loadShelf()
            }
            Screen.EnterPin, Screen.LeaveKidfin -> {
                secret.reset(null, pinFormat)
                refreshCooldown()
            }
            Screen.SetupPin, Screen.ChangePin -> {
                setupFirstEntry = null
                secret.reset(CHOOSE_MOVES, PinFormat.MOVES)
            }
            else -> Unit
        }
        updateRemaining()
    }

    private fun leaveParent() {
        // Backing out of the PIN or approval returns to where the pick (or heart) was made.
        val origin = gate.pickOrigin?.takeIf { locked }
        gate.clearAll()
        go(origin ?: baseScreen())
    }

    /** Back from parent screens returns to the child view and forgets the grant. */
    fun onBackFromParent() = leaveParent()

    // ---- PIN -----------------------------------------------------------------------------

    /** "Grown-ups" button: PIN, then the parent menu. */
    fun onAskGrownUp() {
        gate.clearAll()
        go(Screen.EnterPin)
    }

    /** Back on the shelf, Pick another or Resume: leaving Kidfin needs the secret. */
    fun onAskLeave() {
        if (mutableScreen.value == Screen.LeaveKidfin) return
        leaveReturn = mutableScreen.value
        go(Screen.LeaveKidfin)
    }

    /** Back on the leave screen: carry on where they were. */
    fun onBackFromLeave() {
        val back = leaveReturn ?: baseScreen()
        leaveReturn = null
        go(back)
    }

    private fun leaveKidfin() {
        secret.idle(pinFormat)
        onBackFromLeave()
        leaveChannel.trySend(Unit)
    }

    /** A key press on a parent screen; [ParentGate.onActivity] decides whether it renews the grant. */
    fun onParentActivity() = gate.onActivity(mutableScreen.value, locked)

    /** On arriving at the PIN screen: show a cooldown that's already running. */
    private fun refreshCooldown() {
        viewModelScope.launch {
            val left = try {
                withContext(c.io) { c.pinGuard.cooldownRemainingMs() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val screen = mutableScreen.value
            if (left != null && left > 0 && (screen == Screen.EnterPin || screen == Screen.LeaveKidfin)) secret.showCooldown(left)
        }
    }

    /** One press: an arrow ('U', 'D', 'L', 'R') for moves, or a digit on the old keypad. */
    fun onPinKey(key: Char) {
        secret.press(key)?.let(::submitPin)
    }

    fun onPinDelete() = secret.delete()

    /** OK while entering moves: start again (there's no visible key to delete with). */
    fun onPinClear() = secret.clear()

    private fun submitPin(entered: String) {
        viewModelScope.launch {
            val screen = mutableScreen.value
            try {
                if (screen == Screen.SetupPin || screen == Screen.ChangePin) {
                    handleNewMoves(entered, screen)
                    return@launch
                }
                val result = withContext(c.io) { c.pinGuard.verify(entered) }
                // Home (or anything else) moved on while checking: the result is for a screen that's gone.
                if (mutableScreen.value != screen) return@launch
                when (result) {
                    is PinResult.Granted -> when {
                        // Leaving never keeps a grant around.
                        screen == Screen.LeaveKidfin -> leaveKidfin()
                        else -> {
                            gate.accept(result.grant)
                            if (pinFormat == PinFormat.DIGITS) go(Screen.ChangePin) else continueAfterPin()
                        }
                    }
                    is PinResult.Wrong -> secret.reset("Try again")
                    is PinResult.CoolingDown -> secret.showCooldown(result.remainingMs)
                    PinResult.Corrupt -> go(Screen.PinCorrupt)
                    else -> secret.reset("Could not check. Try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage trouble reading or writing the secret: say so, never crash.
                c.log("secret check failed: ${e.javaClass.simpleName}")
                secret.reset("Could not check. Try again.")
            }
        }
    }

    /** After the PIN: back to the setup step, on to the picked title, or the parent menu. */
    private fun continueAfterPin() {
        secret.idle(pinFormat)
        when (val next = gate.afterSecret(locked)) {
            is ParentGate.AfterSecret.Heart -> {
                toggleFavourite(next.show)
                go(Screen.Episodes(next.show))
            }
            is ParentGate.AfterSecret.Return -> openParent(next.screen)
            is ParentGate.AfterSecret.Approve -> approve(next.item)
            ParentGate.AfterSecret.Menu -> go(Screen.ParentMenu)
        }
    }

    /** Choosing secret moves: entered twice to confirm, at first run or replacing a 4-digit PIN. */
    private suspend fun handleNewMoves(entered: String, screen: Screen) {
        val first = setupFirstEntry
        if (first == null) {
            setupFirstEntry = entered
            secret.reset("Same again")
            return
        }
        setupFirstEntry = null
        if (first != entered) {
            secret.reset("Didn't match. $CHOOSE_MOVES")
            return
        }
        if (screen == Screen.SetupPin) {
            val result = withContext(c.io) { c.pinGuard.setInitialPin(entered) }
            if (result is PinResult.Granted) {
                pinFormat = PinFormat.MOVES
                secret.idle(PinFormat.MOVES)
                // Straight on to connecting Jellyfin with the grant just earned: no second entry.
                gate.accept(result.grant)
                when (connection.value) {
                    is ConnectionState.Ready -> leaveParent()
                    is ConnectionState.NeedsLibraries -> {
                        gate.setupFlow = true
                        openParent(Screen.PickLibraries)
                    }
                    else -> {
                        gate.setupFlow = true
                        openParent(Screen.ServerSetup)
                    }
                }
            } else {
                secret.reset("Could not save. $CHOOSE_MOVES")
            }
            return
        }
        val result = gate.valid()?.let { g -> withContext(c.io) { c.pinGuard.changePin(g, entered) } }
        when (result) {
            is PinResult.Granted -> {
                pinFormat = PinFormat.MOVES
                continueAfterPin()
            }
            // Took too long: the old PIN once more, then choose again (where to go next is kept).
            null, PinResult.Expired -> {
                gate.forget()
                go(Screen.EnterPin)
                secret.reset("Once more, please")
            }
            else -> secret.reset("Could not save. $CHOOSE_MOVES")
        }
    }

    // ---- Parent actions ------------------------------------------------------------------

    /** The grant for a parent-only action, or null after sending the grown-up to the secret. */
    private fun requireGrant(): ParentGrant? = gate.require(mutableScreen.value, locked) ?: run {
        go(Screen.EnterPin)
        secret.reset("Once more, please")
        null
    }

    // ---- Shelf and approval (content first) ------------------------------------------------

    fun onShelfRetry() = loadShelf()

    fun onBackToShelf() = go(Screen.Shelf)

    private fun loadShelf() = shelfLoader.load((connection.value as? ConnectionState.Ready)?.config)

    /** Heart on a show's episode list: grown-ups only, so the PIN first unless just entered. */
    fun onHeartShow(series: MediaItem) {
        if (!locked) return
        if (gate.valid() != null) {
            toggleFavourite(series)
            return
        }
        gate.setPending(null)
        gate.pendingHeart = series
        gate.pickOrigin = mutableScreen.value.takeIf { it is Screen.Episodes }
        go(Screen.EnterPin)
    }

    /** Grown-up heart on the approve screen. Applies to the whole series for an episode. */
    fun onToggleFavourite(item: MediaItem) {
        requireGrant() ?: return
        toggleFavourite(item)
    }

    /** Callers have checked the grant. */
    private fun toggleFavourite(item: MediaItem) =
        shelfLoader.toggleFavourite(item) { mutableNotice.value = "Couldn't update favourites." }

    /** Child picked a title on the locked shelf. A grown-up must approve it. */
    fun onShelfPick(item: MediaItem) {
        if (!locked) return
        gate.pendingHeart = null
        if (item.type == MediaType.SERIES) {
            openSeries(item)
            return
        }
        gate.pickOrigin = mutableScreen.value.takeIf { it == Screen.Shelf || it == Screen.Search || it is Screen.Episodes }
        if (gate.valid() != null) {
            approve(item)
        } else {
            gate.setPending(item)
            go(Screen.EnterPin)
        }
    }

    private fun approve(item: MediaItem) {
        gate.setPending(null)
        mutableNotice.value = null
        go(Screen.Approve(item, Approval.offerFor(item)))
    }

    /** "Watch for N min": start a timed session and play the picked title straight away. */
    fun onApproveTimed(item: MediaItem, allowance: Allowance) = approveAndPlay(
        item,
        problem = { fresh ->
            if (RuntimeRules.fits(fresh, allowance.ticks)) null else "That one is too long for ${allowanceLabel(allowance)} now."
        },
        start = { g, _ -> c.controller.startTimed(g, allowance).also { c.log("timed session: ${allowance.minutes} min") } },
    )

    /** "Just this one": approve the picked title to its natural end and play it now. */
    fun onApproveJustThis(item: MediaItem) = approveAndPlay(
        item,
        problem = { null },
        start = { g, fresh -> c.controller.startOneTitle(g, fresh, accessConfirmed = true).also { c.log("one title approved") } },
    )

    /**
     * Checks the title afresh (still there, still in an approved library, still fits), then
     * starts the session and the player together. Nothing is left approved but unplayed: if
     * the title can't start, the session is withdrawn and the grown-up stays here with a
     * note. Repeat presses while this runs are ignored.
     */
    private fun approveAndPlay(
        item: MediaItem,
        problem: (MediaItem) -> String?,
        start: (ParentGrant, MediaItem) -> StartResult,
    ) {
        if (mutableApproving.value || playback.busy) return
        requireGrant() ?: return
        mutableApproving.value = true
        mutableNotice.value = null
        playback.launchStart {
            var started: String? = null
            var playing = false
            try {
                val fresh = checkAccess(item.id)
                if (!foreground) return@launchStart
                if (fresh == null) {
                    mutableNotice.value = UNAVAILABLE
                    return@launchStart
                }
                problem(fresh)?.let {
                    mutableNotice.value = it
                    return@launchStart
                }
                // The server check took a moment: the grant must still hold (if not, the
                // secret again brings them back to this title).
                val g = requireGrant() ?: return@launchStart
                when (val r = start(g, fresh)) {
                    is StartResult.Rejected -> {
                        mutableNotice.value = describe(r.reason)
                        return@launchStart
                    }
                    is StartResult.Started -> started = r.state.sessionId
                }
                playing = playback.startChecked(fresh.id, prechecked = fresh)
                if (playing) {
                    gate.forget()
                    gate.pickOrigin = null
                    playback.watchFirstStart(started)
                }
            } finally {
                // Not started after all (refused, or Home pressed): withdraw the approval.
                if (!playing) started?.let { c.controller.cancelStart(it) }
                mutableApproving.value = false
            }
        }
    }

    fun onExtend(extension: Extension) {
        val g = requireGrant() ?: return
        val s = session.value as? SessionState.Timed ?: return
        when (val r = c.controller.extend(g, extension, s.sessionId, UUID.randomUUID().toString())) {
            is ExtendResult.Extended -> {
                c.log("extended by ${extension.minutes} min")
                // One extension per secret entry: more needs the secret again.
                leaveParent()
                mutableNotice.value = "Added ${extension.minutes} min. ${DurationLabels.remaining(r.remainingTicks)}."
                updateRemaining()
            }
            ExtendResult.AlreadyApplied -> Unit
            is ExtendResult.Rejected -> mutableNotice.value =
                if (r.reason == Rejection.NO_TIME_LEFT) "Time has just run out." else describe(r.reason)
        }
    }

    fun onParentEnd() {
        val g = requireGrant() ?: return
        playback.stopQuietly()
        c.controller.parentEnd(g)?.let(::beginEnding)
    }

    /**
     * "Choose something else": end the current session quietly (no goodbye, no standby; the
     * grown-up is here) and return to the shelf. The grant stays for its remaining lifetime so
     * the next pick goes straight to approval.
     */
    fun onChooseSomethingElse() {
        val g = requireGrant() ?: return
        playback.stopQuietly()
        if (c.controller.parentSwitchMode(g)) {
            shelfLoader.markStale()
            go(Screen.Shelf)
        }
    }

    // ---- Episodes (child) -------------------------------------------------------------------

    private fun openSeries(series: MediaItem) {
        episodesMemory = EpisodesMemory(series.id)
        go(Screen.Episodes(series))
        loadEpisodes(series)
    }

    fun onEpisodesRetry() {
        (mutableScreen.value as? Screen.Episodes)?.let { loadEpisodes(it.series) }
    }

    private fun loadEpisodes(series: MediaItem) {
        episodesJob?.cancel()
        mutableEpisodes.value = EpisodesState.Loading
        episodesJob = viewModelScope.launch {
            mutableEpisodes.value = try {
                val r = c.shelfSource.episodes(series)
                val playable = r.episodes.filter { RuntimeRules.isApprovableForOneTitle(it) }
                if (playable.isEmpty()) EpisodesState.Failed("No episodes yet.") else EpisodesState.Ready(playable, r.nextUpId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EpisodesState.Failed("Can't reach the videos right now.")
            }
        }
    }

    // ---- Search (child) ---------------------------------------------------------------------

    /** Opening search from the shelf starts afresh; coming back from the PIN keeps it. */
    fun onOpenSearch() {
        if (!locked) return
        searchJob?.cancel()
        mutableSearchQuery.value = ""
        mutableSearchResults.value = emptyList()
        searchGrid = GridMemory()
        go(Screen.Search)
    }

    fun onSearchKey(key: Char) {
        if (mutableSearchQuery.value.length >= 30) return
        mutableSearchQuery.value += key
        runSearch()
    }

    fun onSearchDelete() {
        mutableSearchQuery.value = mutableSearchQuery.value.dropLast(1)
        runSearch()
    }

    fun onSearchClear() {
        mutableSearchQuery.value = ""
        runSearch()
    }

    private fun runSearch() {
        // New results: don't pull focus back to a poster from the previous ones.
        searchGrid.focusedKey = null
        searchJob?.cancel()
        val query = mutableSearchQuery.value
        if (query.isBlank()) {
            mutableSearchResults.value = emptyList()
            return
        }
        val titles = shelfLoader.titles()
        searchJob = viewModelScope.launch {
            mutableSearchResults.value = shelfLoader.search(titles, query)
        }
    }

    // ---- Child browsing -------------------------------------------------------------------

    fun onRefresh() = loadCatalogue(0)

    fun onMore() {
        val offer = browse.value as? BrowseState.Offer ?: return
        loadCatalogue(offer.nextCursor ?: 0)
    }

    private fun loadCatalogue(cursor: Int) {
        val s = session.value as? SessionState.Timed ?: return
        updateRemaining()
        if (connection.value !is ConnectionState.Ready) {
            // Never conclude "nothing fits" (which ends the session) without a working server.
            mutableBrowse.value = BrowseState.Failed("Can't reach the videos right now.")
            return
        }
        mutableBrowse.value = BrowseState.Loading
        viewModelScope.launch {
            val remaining = s.settledRemainingTicks - RuntimeRules.GRACE_TICKS
            when (val r = c.catalogue.find(c.account.allowedLibraryIds, remaining, cursor)) {
                is CatalogueResult.Offer -> mutableBrowse.value = BrowseState.Offer(r.items, r.nextCursor)
                is CatalogueResult.Failed -> mutableBrowse.value = BrowseState.Failed("Can't reach the videos right now.")
                CatalogueResult.NoneFit -> {
                    val end = c.controller.reportNoEligibleTitles(s.sessionId, remaining)
                    if (end != null) beginEnding(end) else loadCatalogue(0)
                }
            }
        }
    }

    fun onPlay(item: MediaItem) = playback.play(item.id)

    fun onResumeOneTitle() {
        val s = session.value as? SessionState.OneTitle ?: return
        playback.play(s.approvedItemId)
    }

    // ---- Player --------------------------------------------------------------------------

    fun onTogglePause() = playback.togglePause()

    /** Back during playback: stop, keep unused time or the One title position. */
    fun onStopPlayback() = playback.stop()

    private inner class PlaybackHost : PlaybackCoordinator.Host {
        override val foreground: Boolean get() = this@MainViewModel.foreground

        override fun notice(text: String) {
            mutableNotice.value = text
        }

        override fun playerStarted() {
            mutableNotice.value = null
            go(Screen.Player)
        }

        override fun playDenied() {
            if (mutableScreen.value == Screen.Browse) loadCatalogue(0)
        }

        override fun playbackStopped(problem: String?) {
            go(baseScreen())
            if (problem == null) return
            mutableNotice.value = when (session.value) {
                is SessionState.Timed -> "$problem Your time is saved."
                is SessionState.OneTitle -> "$problem You can carry on later."
                else -> problem
            }
        }

        override fun ending(end: EndCommand) = beginEnding(end)

        override fun approvalWithdrawn() {
            go(Screen.Shelf)
            mutableNotice.value = "That video won't play right now. Ask a grown-up to pick again."
        }

        override fun tick() = updateRemaining()
    }

    // ---- Ending --------------------------------------------------------------------------

    /** Playback is already stopped and the session persisted as Ending. */
    private fun beginEnding(end: EndCommand) {
        if (signOffJob?.isActive == true) return
        c.log("session ending: ${end.reason}")
        gate.forget()
        val animate = foreground && end.showSignOff
        if (animate) go(Screen.Goodbye)
        signOffJob = viewModelScope.launch {
            if (animate) delay(GOODBYE_MS)
            // Locked is persisted before any power request, so a failed or ignored request
            // (or the device sleeping mid-call) still leaves the app locked.
            c.controller.completeSignOff(end.sessionId)
            // Standby only after a foreground sign-off, and only if still on screen now.
            if (animate) requestStandby()
            // Play counts and Keep watching changed during the session.
            shelfLoader.markStale()
            go(Screen.Shelf)
        }
    }

    private suspend fun requestStandby() {
        val decision = c.standby.afterSignOff { foreground }
        c.log("standby decision: $decision")
        mutableLastPowerResult.value = describe(decision)
    }

    // ---- TV power (parent) ---------------------------------------------------------------

    fun onTvPower() {
        requireGrant() ?: return
        go(Screen.TvPower)
    }

    fun onTvPowerTest() {
        requireGrant() ?: return
        go(Screen.ConfirmTvPowerTest)
    }

    fun onCancelTvPowerTest() = go(Screen.TvPower)

    /** Explicitly confirmed by the parent on the warning screen. Only offered while locked. */
    fun onConfirmTvPowerTest() {
        requireGrant() ?: return
        if (!locked) return
        viewModelScope.launch {
            requestStandby()
            go(Screen.TvPower)
        }
    }

    fun onSettingsUnreachable() {
        mutableNotice.value = "Open Settings › System › Accessibility on the TV."
    }

    // ---- Jellyfin setup (parent) ---------------------------------------------------------

    /**
     * "Set up" on the shelf when Kidfin isn't ready: PIN, then straight to the step that's
     * missing (sign in, or choose videos), then back to the shelf.
     */
    fun onStartSetup() {
        gate.forget()
        gate.pendingHeart = null
        mutableNotice.value = null
        gate.setPending(null)
        gate.setupFlow = true
        gate.returnAfterSecret = if (connection.value is ConnectionState.NeedsLibraries) Screen.PickLibraries else Screen.ServerSetup
        go(Screen.EnterPin)
    }

    private fun openParent(screen: Screen) = when (screen) {
        // Back after the secret again: keep what was typed.
        Screen.ServerSetup -> openServerSetup(keepTyped = true)
        Screen.PickLibraries -> openLibraries()
        else -> go(screen)
    }

    fun onServerSetup() {
        requireGrant() ?: return
        openServerSetup()
    }

    private fun openServerSetup(keepTyped: Boolean = false) {
        val config = c.account.config
        val typed = mutableSetup.value
        mutableSetup.value = if (keepTyped && typed.username.isNotBlank()) {
            ServerSetupState(address = typed.address, username = typed.username)
        } else {
            ServerSetupState(address = config?.baseUrl ?: typed.address, username = config?.userName ?: typed.username)
        }
        go(Screen.ServerSetup)
    }

    fun onSignIn(address: String, username: String, password: String) {
        mutableSetup.value = mutableSetup.value.copy(address = address, username = username, message = null)
        requireGrant() ?: return
        if (mutableSetup.value.busy) return
        mutableSetup.value = mutableSetup.value.copy(busy = true)
        viewModelScope.launch {
            when (val r = c.account.signIn(address, username, password)) {
                SignInResult.Done -> {
                    c.log("jellyfin signed in")
                    mutableSetup.value = mutableSetup.value.copy(busy = false, message = null)
                    shelfLoader.reset()
                    openLibraries()
                }
                is SignInResult.Refused -> mutableSetup.value = mutableSetup.value.copy(busy = false, message = r.message)
            }
        }
    }

    fun onPickLibraries() {
        requireGrant() ?: return
        openLibraries()
    }

    private fun openLibraries() {
        mutableLibraries.value = LibraryPickState.Loading
        go(Screen.PickLibraries)
        viewModelScope.launch {
            mutableLibraries.value = try {
                val libs = c.repository.libraries()
                val everything = runCatching { c.account.canSeeAllLibraries() }.getOrDefault(false)
                val saved = c.account.config?.libraryIds.orEmpty()
                LibraryPickState.Loaded(libs, saved.intersect(libs.map { it.id }.toSet()), everything)
            } catch (e: Exception) {
                LibraryPickState.Failed("Couldn't load libraries.")
            }
        }
    }

    fun onToggleLibrary(id: String) {
        val l = mutableLibraries.value as? LibraryPickState.Loaded ?: return
        mutableLibraries.value = l.copy(selected = if (id in l.selected) l.selected - id else l.selected + id)
    }

    fun onSaveLibraries() {
        val l = mutableLibraries.value as? LibraryPickState.Loaded ?: return
        requireGrant() ?: return
        if (l.selected.isEmpty()) return
        if (!c.account.saveLibraries(l.libraries.filter { it.id in l.selected })) {
            mutableNotice.value = "Couldn't save on this TV. Try again."
            return
        }
        c.log("libraries saved: ${l.selected.size}")
        shelfLoader.reset()
        // Done: straight to the shelf, the next step is picking something to watch.
        leaveParent()
    }

    /** Back from setup screens returns to the parent menu while the grant lasts. */
    fun onBackToParentMenu() {
        if (!gate.setupFlow && gate.valid() != null) go(Screen.ParentMenu) else leaveParent()
    }

    private suspend fun checkAccess(itemId: String): MediaItem? = try {
        c.access.check(itemId, c.account.allowedLibraryIds)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ---- Lifecycle -----------------------------------------------------------------------

    fun onAppStopped() {
        foreground = false
        // A title still being checked must not start behind another app.
        (playback.onAppStopped() as? StopResult.Ended)?.let { beginEnding(it.command) }
        c.controller.onBackgrounded()?.let(::beginEnding)
        gate.forget()
        if (mutableScreen.value !is Screen.Goodbye && signOffJob?.isActive != true) {
            mutableScreen.value = if (mutableScreen.value == Screen.SetupPin || mutableScreen.value == Screen.PinCorrupt) {
                mutableScreen.value
            } else {
                baseScreen()
            }
        }
    }

    fun onAppStarted() {
        foreground = true
        val current = mutableScreen.value
        if (current == Screen.Loading || current == Screen.SetupPin || current == Screen.PinCorrupt) return
        if (signOffJob?.isActive == true) return
        // Away 30 minutes or more: the leftover time is gone (quietly; they're just arriving).
        c.controller.checkIdle(quiet = true)?.let(::beginEnding)
        if (signOffJob?.isActive == true) return
        go(baseScreen())
    }

    override fun onCleared() {
        playback.release()
    }

    fun clearNotice() {
        mutableNotice.value = null
    }

    private fun updateRemaining() {
        mutableRemaining.value = c.controller.remainingTicks()?.let(DurationLabels::remaining)
    }

    companion object {
        const val UNAVAILABLE = "That video isn't available right now."

        fun allowanceLabel(a: Allowance): String = if (a.minutes == 60) "1 hour" else "${a.minutes} min"

        /** Plain words for a refusal; never an enum name on screen. */
        fun describe(reason: Rejection): String = when (reason) {
            Rejection.STORAGE_FAILED -> "Couldn't save on this TV. Try again."
            Rejection.DOES_NOT_FIT, Rejection.NO_TIME_LEFT -> "There isn't enough time left for that."
            Rejection.NOT_PLAYABLE -> "That one can't be played."
            Rejection.ACCESS_DENIED, Rejection.NOT_APPROVED_ITEM -> UNAVAILABLE
            Rejection.PARENT_NOT_AUTHORISED -> "Grown-up time ran out. Try again."
            Rejection.NOT_LOCKED, Rejection.NOT_ACTIVE, Rejection.WRONG_SESSION, Rejection.ALREADY_PLAYING ->
                "Something else is already on. Try again."
        }

        private fun describe(decision: StandbyDecision): String = when (decision) {
            StandbyDecision.SkippedAppOnly -> "App only: no standby requested."
            StandbyDecision.SkippedNotOnScreen -> "Skipped: the app was no longer on screen."
            is StandbyDecision.Attempted -> when (val r = decision.result) {
                StandbyRequestResult.Requested -> "Standby requested."
                is StandbyRequestResult.Unavailable -> "Not sent: ${r.reason}."
                is StandbyRequestResult.Failed -> "Failed: ${r.reason}."
            }
        }

        /** Long enough to wind down; the TV goes to standby after it. */
        const val GOODBYE_MS = 30_000L
        private const val IDLE_CHECK_MS = 15_000L
        private const val CHOOSE_MOVES = "Choose 6 arrow presses"
    }
}
