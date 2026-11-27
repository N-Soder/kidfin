// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.jellyfin.ConnectionState
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaType
import au.kidfin.tv.media.SeriesEpisodes
import au.kidfin.tv.media.ShelfContent
import au.kidfin.tv.session.Allowance
import au.kidfin.tv.session.Extension
import au.kidfin.tv.session.SessionState
import au.kidfin.tv.session.Ticks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The view model's grown-up gating, end to end over the real session engine and secret guard:
 * every parent-only action runs only with a grant that still holds, and asks for the secret
 * (keeping what the grown-up was doing) when it doesn't.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var services: FakeServices
    private lateinit var vm: MainViewModel

    private val movie = MediaItem("m1", "Movie", MediaType.MOVIE, Ticks.ofMinutes(20), "kids")
    private val show = MediaItem("s1", "Show", MediaType.SERIES, null, "kids", seriesId = "s1")
    private val episode = MediaItem("e1", "Episode", MediaType.EPISODE, Ticks.ofMinutes(10), "kids", seriesId = "s1")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun start(secretSet: Boolean = true, account: FakeAccount = FakeAccount()) {
        services = FakeServices(dispatcher, listOf(movie, show, episode), tmp.root, account)
        services.source.content = ShelfContent(emptyList(), listOf(movie, show))
        services.source.episodes = SeriesEpisodes(listOf(episode), "e1")
        if (secretSet) services.pinGuard.setInitialPin(SECRET)
        vm = MainViewModel(services)
    }

    private fun enterSecret(secret: String = SECRET) = secret.forEach(vm::onPinKey)

    private fun pass(seconds: Long) = services.fakeClock.advanceSeconds(seconds)

    private fun lapse() = pass(GRANT_SECONDS + 1)

    private val screen get() = vm.screen.value
    private val session get() = vm.session.value

    /** Approve screen for [movie], reached the way a child and grown-up would. */
    private fun approveScreen() {
        vm.onShelfPick(movie)
        enterSecret()
        assertTrue(screen is Screen.Approve)
    }

    /** A timed session on Pick another, nothing playing. */
    private fun timedSession() {
        approveScreen()
        vm.onApproveTimed(movie, Allowance.MIN_30)
        assertEquals(Screen.Player, screen)
        vm.onStopPlayback()
        assertEquals(Screen.Browse, screen)
    }

    private fun assertAskedForSecretAgain() {
        assertEquals(Screen.EnterPin, screen)
        assertEquals("Once more, please", vm.pin.value.message)
    }

    // ---- Picking and approving -----------------------------------------------------------

    @Test
    fun aPickWaitsForTheSecretThenGoesToApprove() {
        start()
        assertEquals(Screen.Shelf, screen)
        vm.onShelfPick(movie)
        assertEquals(Screen.EnterPin, screen)
        assertEquals(movie, vm.pendingItem.value)
        enterSecret()
        assertEquals(Screen.Approve::class, screen::class)
        assertEquals(movie, (screen as Screen.Approve).item)
        assertNull(vm.pendingItem.value)
    }

    @Test
    fun approveTimedNeedsAGrantAndThePickIsKeptForTheSecret() {
        start()
        approveScreen()
        lapse()
        vm.onApproveTimed(movie, Allowance.MIN_30)
        assertAskedForSecretAgain()
        assertEquals(movie, vm.pendingItem.value)
        assertTrue(session is SessionState.Locked)
        assertTrue(services.fakePlayer.started.isEmpty())

        enterSecret()
        assertEquals(movie, (screen as Screen.Approve).item)
        vm.onApproveTimed(movie, Allowance.MIN_30)
        assertTrue(session is SessionState.Timed)
        assertEquals(Screen.Player, screen)
        assertEquals(listOf("m1"), services.fakePlayer.started)
    }

    @Test
    fun justThisOneNeedsAGrant() {
        start()
        approveScreen()
        lapse()
        vm.onApproveJustThis(movie)
        assertAskedForSecretAgain()
        assertTrue(session is SessionState.Locked)

        enterSecret()
        vm.onApproveJustThis(movie)
        assertTrue(session is SessionState.OneTitle)
    }

    @Test
    fun aGrantHoldsForTwoMinutesWithoutRenewalOnApprove() {
        start()
        approveScreen()
        // Presses on Approve never renew: every press there can start a session.
        pass(60)
        vm.onParentActivity()
        pass(GRANT_SECONDS - 60)
        vm.onParentActivity()
        pass(1)
        vm.onApproveTimed(movie, Allowance.MIN_30)
        assertAskedForSecretAgain()
    }

    @Test
    fun startingPlaybackUsesUpTheGrant() {
        start()
        approveScreen()
        vm.onApproveJustThis(movie)
        vm.onStopPlayback()
        assertEquals(Screen.Resume, screen)
        vm.onParentEnd()
        assertAskedForSecretAgain()
        assertTrue(session is SessionState.OneTitle)
    }

    @Test
    fun aFirstTitleThatWontStartWithdrawsTheSession() {
        start()
        approveScreen()
        vm.onApproveTimed(movie, Allowance.MIN_30)
        services.fakePlayer.snapshot.value = PlayerSnapshot(PlayerPhase.FAILED)
        assertTrue(session is SessionState.Locked)
        assertEquals(Screen.Shelf, screen)
        assertEquals("That video won't play right now. Ask a grown-up to pick again.", vm.notice.value)
    }

    // ---- Favourites ------------------------------------------------------------------------

    @Test
    fun heartOnApproveNeedsAGrant() {
        start()
        approveScreen()
        lapse()
        vm.onToggleFavourite(movie)
        assertAskedForSecretAgain()
        assertTrue(services.source.favourites.isEmpty())

        enterSecret()
        vm.onToggleFavourite(movie)
        assertEquals(listOf("m1" to true), services.source.favourites)
    }

    @Test
    fun heartOnAShowAsksForTheSecretThenHearts() {
        start()
        vm.onShelfPick(show)
        assertEquals(Screen.Episodes(show), screen)
        vm.onHeartShow(show)
        assertEquals(Screen.EnterPin, screen)
        assertTrue(services.source.favourites.isEmpty())
        enterSecret()
        assertEquals(Screen.Episodes(show), screen)
        assertEquals(listOf("s1" to true), services.source.favourites)
        assertTrue("s1" in vm.favouriteIds.value)
    }

    // ---- During a session ----------------------------------------------------------------

    @Test
    fun extendEndAndChooseSomethingElseNeedAGrant() {
        start()
        timedSession()
        val before = services.controller.remainingTicks()
        vm.onAskGrownUp()
        enterSecret()
        assertEquals(Screen.ParentMenu, screen)
        // The in-session menu never renews the grant.
        pass(60)
        vm.onParentActivity()
        pass(GRANT_SECONDS - 59)

        vm.onExtend(Extension.MIN_10)
        assertAskedForSecretAgain()
        assertEquals(before, services.controller.remainingTicks())
        vm.onParentEnd()
        assertTrue(session is SessionState.Timed)
        vm.onChooseSomethingElse()
        assertTrue(session is SessionState.Timed)

        enterSecret()
        vm.onParentEnd()
        assertTrue(session is SessionState.Ending || session is SessionState.Locked)
    }

    @Test
    fun oneExtensionPerSecretEntry() {
        start()
        timedSession()
        val before = services.controller.remainingTicks()!!
        vm.onAskGrownUp()
        enterSecret()
        vm.onExtend(Extension.MIN_5)
        assertEquals(before + Extension.MIN_5.ticks, services.controller.remainingTicks())
        assertEquals(Screen.Browse, screen)
        assertTrue(vm.notice.value!!.startsWith("Added 5 min."))

        // Straight away, the same grant is gone: another extension needs the secret again.
        vm.onExtend(Extension.MIN_5)
        assertAskedForSecretAgain()
        assertEquals(before + Extension.MIN_5.ticks, services.controller.remainingTicks())
    }

    @Test
    fun chooseSomethingElseKeepsTheGrantForTheNextPick() {
        start()
        timedSession()
        vm.onAskGrownUp()
        enterSecret()
        vm.onChooseSomethingElse()
        assertTrue(session is SessionState.Locked)
        assertEquals(Screen.Shelf, screen)
        vm.onShelfPick(movie)
        assertTrue(screen is Screen.Approve)
    }

    // ---- Settings --------------------------------------------------------------------------

    @Test
    fun settingsActivityRenewsTheGrantButNeverPastFifteenMinutes() {
        start()
        vm.onAskGrownUp()
        enterSecret()
        vm.onServerSetup()
        assertEquals(Screen.ServerSetup, screen)
        repeat(14) {
            pass(60)
            vm.onParentActivity()
        }
        // Fourteen minutes in, well past two: still granted thanks to the presses.
        vm.onPickLibraries()
        assertEquals(Screen.PickLibraries, screen)
        pass(60)
        vm.onParentActivity()
        pass(60)
        vm.onParentActivity()
        vm.onSaveLibraries()
        assertAskedForSecretAgain()
        assertTrue(services.account.saved.isEmpty())

        // The secret again leads straight back to the step they were on.
        enterSecret()
        assertEquals(Screen.PickLibraries, screen)
        vm.onSaveLibraries()
        assertEquals(1, services.account.saved.size)
        assertEquals(Screen.Shelf, screen)
    }

    @Test
    fun activityAfterTheGrantLapsedDoesNotReviveIt() {
        start()
        vm.onAskGrownUp()
        enterSecret()
        vm.onTvPower()
        lapse()
        vm.onParentActivity()
        vm.onTvPowerTest()
        assertAskedForSecretAgain()
    }

    @Test
    fun signInNeedsAGrantAndKeepsWhatWasTyped() {
        start()
        vm.onAskGrownUp()
        enterSecret()
        vm.onServerSetup()
        lapse()
        vm.onSignIn("http://jellyfin.test:8096", "child", "password")
        assertAskedForSecretAgain()
        assertTrue(services.account.signIns.isEmpty())

        enterSecret()
        assertEquals(Screen.ServerSetup, screen)
        assertEquals("child", vm.setup.value.username)
        vm.onSignIn("http://jellyfin.test:8096", "child", "password")
        assertEquals(listOf("child"), services.account.signIns)
        assertEquals(Screen.PickLibraries, screen)
    }

    @Test
    fun serverSetupAndLibrariesNeedAGrant() {
        start()
        vm.onAskGrownUp()
        enterSecret()
        lapse()
        vm.onServerSetup()
        assertAskedForSecretAgain()
        enterSecret()
        lapse()
        vm.onPickLibraries()
        assertAskedForSecretAgain()
    }

    @Test
    fun tvPowerTestNeedsAGrant() {
        start()
        vm.onAskGrownUp()
        enterSecret()
        vm.onTvPower()
        vm.onTvPowerTest()
        assertEquals(Screen.ConfirmTvPowerTest, screen)
        lapse()
        vm.onConfirmTvPowerTest()
        assertAskedForSecretAgain()
        assertEquals(0, services.standbyRequests)
    }

    @Test
    fun backFromSettingsReturnsToTheMenuOnlyWhileGranted() {
        start()
        vm.onAskGrownUp()
        enterSecret()
        vm.onTvPower()
        vm.onBackToParentMenu()
        assertEquals(Screen.ParentMenu, screen)
        vm.onTvPower()
        lapse()
        vm.onBackToParentMenu()
        assertEquals(Screen.Shelf, screen)
    }

    // ---- First run -------------------------------------------------------------------------

    @Test
    fun firstRunGrantCarriesIntoSetupWithoutASecondEntry() {
        start(secretSet = false, account = FakeAccount(ConnectionState.NotSetUp))
        assertEquals(Screen.SetupPin, screen)
        enterSecret()
        assertEquals("Same again", vm.pin.value.message)
        enterSecret()
        assertEquals(Screen.ServerSetup, screen)

        vm.onSignIn("http://jellyfin.test:8096", "child", "password")
        assertEquals(listOf("child"), services.account.signIns)
        assertEquals(Screen.PickLibraries, screen)
        vm.onToggleLibrary("kids")
        vm.onSaveLibraries()
        assertEquals(1, services.account.saved.size)
        // Setup started from first run finishes on the shelf, and the grant is gone.
        assertEquals(Screen.Shelf, screen)
        vm.onTvPower()
        assertAskedForSecretAgain()
    }

    @Test
    fun firstRunSecretMustMatchTwice() {
        start(secretSet = false, account = FakeAccount(ConnectionState.NotSetUp))
        enterSecret()
        enterSecret("DDDDDD")
        assertEquals(Screen.SetupPin, screen)
        assertEquals("Didn't match. Choose 6 arrow presses", vm.pin.value.message)
    }

    // ---- Leaving Kidfin --------------------------------------------------------------------

    @Test
    fun backOnTheShelfAsksForTheSecretToLeave() {
        start()
        val leaves = mutableListOf<Unit>()
        CoroutineScope(dispatcher).launch { vm.leaveRequests.collect { leaves += it } }

        vm.onAskLeave()
        assertEquals(Screen.LeaveKidfin, screen)
        vm.onBackFromLeave()
        assertEquals(Screen.Shelf, screen)
        assertTrue(leaves.isEmpty())

        vm.onAskLeave()
        enterSecret("DDDDDD")
        assertEquals(Screen.LeaveKidfin, screen)
        assertEquals("Try again", vm.pin.value.message)
        assertTrue(leaves.isEmpty())

        enterSecret()
        assertEquals(1, leaves.size)
        assertEquals(Screen.Shelf, screen)
        // Leaving never keeps a grant for anything else.
        vm.onTvPower()
        assertEquals(Screen.EnterPin, screen)
    }

    @Test
    fun backOnPickAnotherAndResumeAlsoAskToLeave() {
        start()
        timedSession()
        vm.onAskLeave()
        assertEquals(Screen.LeaveKidfin, screen)
        vm.onBackFromLeave()
        assertEquals(Screen.Browse, screen)

        vm.onAskGrownUp()
        enterSecret()
        vm.onChooseSomethingElse()
        vm.onShelfPick(movie)
        vm.onApproveJustThis(movie)
        vm.onStopPlayback()
        assertEquals(Screen.Resume, screen)
        vm.onAskLeave()
        assertEquals(Screen.LeaveKidfin, screen)
        vm.onBackFromLeave()
        assertEquals(Screen.Resume, screen)
    }

    @Test
    fun goingToTheBackgroundDropsTheGrant() {
        start()
        vm.onAskGrownUp()
        enterSecret()
        vm.onAppStopped()
        vm.onAppStarted()
        assertEquals(Screen.Shelf, screen)
        vm.onTvPower()
        assertEquals(Screen.EnterPin, screen)
    }

    private companion object {
        const val SECRET = "UUDDLR"
        const val GRANT_SECONDS = 120L
    }
}
