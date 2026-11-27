// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaType
import au.kidfin.tv.security.ParentGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeClock(var now: Long = 1_000 * Ticks.PER_SECOND) : MonotonicClock {
    override fun nowTicks(): Long = now
    fun advanceSeconds(s: Long) { now += Ticks.ofSeconds(s) }
    fun advanceTicks(t: Long) { now += t }
}

const val BOOT = "boot1"

fun item(id: String, minutes: Int?, type: MediaType = MediaType.EPISODE, lib: String = "kids") =
    MediaItem(id, "Title $id", type, minutes?.let { Ticks.ofMinutes(it) }, lib)

class SessionControllerTest {
    private val clock = FakeClock()
    private val store = InMemorySessionStore()
    private var ids = 0
    private val controller = SessionController(store, clock, BOOT) { "s${++ids}" }

    private fun grant() = ParentGrant(clock.nowTicks())

    private fun startTimed(allowance: Allowance = Allowance.MIN_15): SessionState.Timed {
        controller.restore()
        val r = controller.startTimed(grant(), allowance) as StartResult.Started
        return r.state as SessionState.Timed
    }

    private fun play(item: MediaItem): PlaybackToken {
        val g = controller.beginPlayback(item, accessConfirmed = true) as PlaybackStart.Granted
        assertEquals(Admission.Allowed, controller.revalidate(g.token, item, true))
        assertTrue(controller.onPlaying(g.token) is PlayingResult.Playing)
        return g.token
    }

    // ---- Accounting --------------------------------------------------------------------

    @Test
    fun onlyPlayingTimeIsCharged() {
        startTimed()
        val ep = item("a", 10)
        val g = controller.beginPlayback(ep, true) as PlaybackStart.Granted
        clock.advanceSeconds(30) // preparing: free
        assertEquals(Allowance.MIN_15.ticks, controller.remainingTicks())
        controller.onPlaying(g.token)
        clock.advanceSeconds(60)
        controller.onNotPlaying(g.token) // pause or buffering
        clock.advanceSeconds(600) // paused for 10 min: free
        assertEquals(Ticks.ofMinutes(14), controller.remainingTicks())
        controller.onPlaying(g.token)
        clock.advanceSeconds(60)
        assertEquals(Ticks.ofMinutes(13), controller.remainingTicks())
    }

    @Test
    fun seekingDoesNotChangeCharge() {
        startTimed()
        val token = play(item("a", 10))
        clock.advanceSeconds(120)
        // A seek is just a position change; the player does not report it to the controller.
        val r = controller.onPlaybackStopped(token, positionTicks = Ticks.ofMinutes(9))
        assertEquals(StopResult.Continue, r)
        assertEquals(Ticks.ofMinutes(13), controller.remainingTicks())
    }

    @Test
    fun homePreservesUnusedAllowance() {
        startTimed()
        play(item("a", 10))
        clock.advanceSeconds(90)
        assertNull(controller.onBackgrounded())
        clock.advanceSeconds(3_600)
        assertEquals(Ticks.ofMinutes(15) - Ticks.ofSeconds(90), controller.remainingTicks())
        val s = controller.state.value as SessionState.Timed
        assertNull(s.nowPlaying)
    }

    @Test
    fun clockGoingBackwardsChargesNothingAndRefundsNothing() {
        startTimed()
        val token = play(item("a", 10))
        clock.advanceSeconds(60)
        controller.onNotPlaying(token)
        controller.onPlaying(token)
        clock.now -= Ticks.ofSeconds(500)
        assertEquals(Ticks.ofMinutes(14), controller.remainingTicks())
    }

    // ---- Admission ---------------------------------------------------------------------

    @Test
    fun upToThreeMinutesOverIsEligibleAndOneTickMoreIsNot() {
        startTimed(Allowance.MIN_15)
        val exact = MediaItem("x", "x", MediaType.MOVIE, Ticks.ofMinutes(18), "kids")
        val over = exact.copy(id = "y", runtimeTicks = Ticks.ofMinutes(18) + 1)
        assertEquals(Admission.Allowed, controller.admit(exact, true))
        assertEquals(Admission.Denied(Rejection.DOES_NOT_FIT), controller.admit(over, true))
    }

    @Test
    fun missingZeroAndSeriesRuntimesAreRejected() {
        startTimed()
        assertEquals(Admission.Denied(Rejection.NOT_PLAYABLE), controller.admit(item("a", null), true))
        assertEquals(Admission.Denied(Rejection.NOT_PLAYABLE), controller.admit(item("b", 0), true))
        assertEquals(Admission.Denied(Rejection.NOT_PLAYABLE), controller.admit(item("c", 5, MediaType.SERIES), true))
    }

    @Test
    fun deniedAccessIsRejectedAtPlay() {
        startTimed()
        assertEquals(PlaybackStart.Denied(Rejection.ACCESS_DENIED), controller.beginPlayback(item("a", 5), false))
    }

    @Test
    fun revalidationBeforeStartUsesCurrentAllowance() {
        startTimed(Allowance.MIN_15)
        val first = play(item("a", 10))
        clock.advanceSeconds(8 * 60)
        controller.onPlaybackStopped(first, 0)
        // 7 min left (10 with the grace); an 11 min title must not start.
        val long = item("b", 11)
        assertEquals(PlaybackStart.Denied(Rejection.DOES_NOT_FIT), controller.beginPlayback(long, true))
    }

    @Test
    fun cannotAdmitWhileLockedOrEnding() {
        controller.restore()
        assertEquals(Admission.Denied(Rejection.NOT_ACTIVE), controller.admit(item("a", 1), true))
    }

    // ---- Endings -----------------------------------------------------------------------

    @Test
    fun deadlineEndsExactlyAtTheEndOfTheGraceOnce() {
        startTimed(Allowance.MIN_15)
        play(item("a", 18))
        clock.advanceTicks(Ticks.ofMinutes(18) - 1)
        assertNull(controller.checkDeadline())
        clock.advanceTicks(1)
        val end = controller.checkDeadline()!!
        assertEquals(EndReason.ALLOWANCE_USED, end.reason)
        assertTrue(end.showSignOff)
        assertNull(controller.checkDeadline())
        assertTrue(controller.state.value is SessionState.Ending)
    }

    @Test
    fun titleRunningIntoTheGraceKeepsPlayingThenSignsOffOnce() {
        startTimed(Allowance.MIN_15)
        val token = play(item("a", 18))
        clock.advanceSeconds(16 * 60)
        assertNull(controller.checkDeadline())
        assertEquals(0L, controller.remainingTicks())
        clock.advanceSeconds(2 * 60)
        val timer = controller.checkDeadline()
        val completed = controller.onPlaybackCompleted(token)
        assertEquals(EndReason.ALLOWANCE_USED, timer!!.reason)
        assertEquals(StopResult.Stale, completed)
    }

    @Test
    fun completionBeforeTimerAtZeroAlsoEndsOnce() {
        startTimed(Allowance.MIN_15)
        val token = play(item("a", 15))
        clock.advanceSeconds(15 * 60)
        val completed = controller.onPlaybackCompleted(token) as StopResult.Ended
        assertEquals(EndReason.ALLOWANCE_USED, completed.command.reason)
        assertNull(controller.checkDeadline())
    }

    @Test
    fun earlyFinishReturnsToBrowsingWithUnusedTime() {
        startTimed(Allowance.MIN_30)
        val token = play(item("a", 10))
        clock.advanceSeconds(10 * 60)
        assertEquals(StopResult.Continue, controller.onPlaybackCompleted(token))
        assertEquals(Ticks.ofMinutes(20), controller.remainingTicks())
    }

    @Test
    fun noEligibleTitleEndsOnlyForCurrentUncheckedState() {
        val s = startTimed()
        val shown = controller.remainingTicks()!!
        assertNull(controller.reportNoEligibleTitles("other", shown))
        assertNull(controller.reportNoEligibleTitles(s.sessionId, shown - 1))
        val end = controller.reportNoEligibleTitles(s.sessionId, shown)!!
        assertEquals(EndReason.NO_ELIGIBLE_TITLE, end.reason)
    }

    @Test
    fun parentEndSignsOffAndSwitchModeDoesNot() {
        startTimed()
        assertEquals(EndReason.PARENT_ENDED, controller.parentEnd(grant())!!.reason)
        controller.completeSignOff((controller.state.value as SessionState.Ending).sessionId)
        controller.startTimed(grant(), Allowance.MIN_15)
        assertTrue(controller.parentSwitchMode(grant()))
        assertEquals(SessionState.Locked(controller.state.value.revision, EndReason.PARENT_SWITCHED_MODE), controller.state.value)
    }

    @Test
    fun completeSignOffLocks() {
        startTimed()
        val end = controller.parentEnd(grant())!!
        assertTrue(!controller.completeSignOff("wrong"))
        assertTrue(controller.completeSignOff(end.sessionId))
        assertEquals(EndReason.PARENT_ENDED, (controller.state.value as SessionState.Locked).lastEnd)
    }

    // ---- Extensions --------------------------------------------------------------------

    @Test
    fun extensionSettlesPlayingIntervalAndReopensIt() {
        val s = startTimed(Allowance.MIN_15)
        play(item("a", 15))
        clock.advanceSeconds(5 * 60)
        val r = controller.extend(grant(), Extension.MIN_5, s.sessionId, "req1") as ExtendResult.Extended
        assertEquals(Ticks.ofMinutes(15), r.remainingTicks)
        // The cutoff includes the 3 min grace for the title playing.
        assertEquals(clock.now + Ticks.ofMinutes(18), r.cutoffAtTicks)
        clock.advanceSeconds(60)
        assertEquals(Ticks.ofMinutes(14), controller.remainingTicks())
    }

    @Test
    fun extensionAppliesOncePerRequest() {
        val s = startTimed(Allowance.MIN_15)
        controller.extend(grant(), Extension.MIN_10, s.sessionId, "req1")
        assertEquals(ExtendResult.AlreadyApplied, controller.extend(grant(), Extension.MIN_10, s.sessionId, "req1"))
        assertEquals(Ticks.ofMinutes(25), controller.remainingTicks())
    }

    @Test
    fun extensionRejectedAfterEndingWrongSessionOrExpiredGrant() {
        val s = startTimed(Allowance.MIN_15)
        val old = grant()
        clock.advanceTicks(ParentGrant.VALIDITY_TICKS + 1)
        assertEquals(ExtendResult.Rejected(Rejection.PARENT_NOT_AUTHORISED), controller.extend(old, Extension.MIN_5, s.sessionId, "r"))
        assertEquals(ExtendResult.Rejected(Rejection.WRONG_SESSION), controller.extend(grant(), Extension.MIN_5, "nope", "r"))
        play(item("a", 15))
        clock.advanceSeconds(18 * 60)
        controller.checkDeadline()
        assertEquals(ExtendResult.Rejected(Rejection.NOT_ACTIVE), controller.extend(grant(), Extension.MIN_5, s.sessionId, "r2"))
    }

    @Test
    fun extensionAtTheEndOfTheGraceBeforeTimerFiresIsRejected() {
        val s = startTimed(Allowance.MIN_15)
        play(item("a", 15))
        clock.advanceSeconds(18 * 60)
        assertEquals(ExtendResult.Rejected(Rejection.NO_TIME_LEFT), controller.extend(grant(), Extension.MIN_5, s.sessionId, "r"))
        assertEquals(EndReason.ALLOWANCE_USED, controller.checkDeadline()!!.reason)
    }

    // ---- Stale callbacks ---------------------------------------------------------------

    @Test
    fun callbacksFromOldPlaybackOrSessionAreIgnored() {
        startTimed()
        val first = play(item("a", 5))
        controller.onPlaybackStopped(first, 0)
        val second = play(item("b", 5))
        assertEquals(PlayingResult.Stale, controller.onPlaying(first))
        assertEquals(StopResult.Stale, controller.onPlaybackCompleted(first))
        clock.advanceSeconds(60)
        val end = controller.parentEnd(grant())!!
        controller.completeSignOff(end.sessionId)
        controller.startTimed(grant(), Allowance.MIN_15)
        assertEquals(StopResult.Stale, controller.onNotPlaying(second))
        assertEquals(Ticks.ofMinutes(15), controller.remainingTicks())
    }

    // ---- Parent authorisation ----------------------------------------------------------

    @Test
    fun startRequiresFreshGrantAndLockedState() {
        controller.restore()
        val old = grant()
        clock.advanceTicks(ParentGrant.VALIDITY_TICKS + 1)
        assertEquals(StartResult.Rejected(Rejection.PARENT_NOT_AUTHORISED), controller.startTimed(old, Allowance.MIN_15))
        controller.startTimed(grant(), Allowance.MIN_15)
        assertEquals(StartResult.Rejected(Rejection.NOT_LOCKED), controller.startTimed(grant(), Allowance.MIN_30))
        assertEquals(StartResult.Rejected(Rejection.NOT_LOCKED), controller.startOneTitle(grant(), item("m", 120, MediaType.MOVIE), true))
    }

    @Test
    fun startFailsClosedWhenStorageFails() {
        controller.restore()
        store.failWrites = true
        assertEquals(StartResult.Rejected(Rejection.STORAGE_FAILED), controller.startTimed(grant(), Allowance.MIN_15))
        assertTrue(controller.state.value is SessionState.Locked)
    }

    @Test
    fun playingRefusedWhenOpenIntervalCannotBePersisted() {
        startTimed()
        val g = controller.beginPlayback(item("a", 5), true) as PlaybackStart.Granted
        store.failWrites = true
        assertEquals(PlayingResult.StorageFailed, controller.onPlaying(g.token))
        assertEquals(Ticks.ofMinutes(15), controller.remainingTicks())
    }

    // ---- Grace and resume ---------------------------------------------------------------

    @Test
    fun stoppingAfterTheAllowanceIsUsedEndsEvenWithGraceLeft() {
        startTimed(Allowance.MIN_15)
        val token = play(item("a", 18))
        clock.advanceSeconds(16 * 60)
        val stopped = controller.onPlaybackStopped(token, 0) as StopResult.Ended
        assertEquals(EndReason.ALLOWANCE_USED, stopped.command.reason)
    }

    @Test
    fun pausingInTheGraceDoesNotEndTheTitle() {
        startTimed(Allowance.MIN_15)
        val token = play(item("a", 18))
        clock.advanceSeconds(16 * 60)
        assertEquals(StopResult.Continue, controller.onNotPlaying(token))
        assertTrue(controller.onPlaying(token) is PlayingResult.Playing)
    }

    @Test
    fun partlyWatchedTitleResumesAndCountsOnlyWhatsLeft() {
        startTimed(Allowance.MIN_15)
        val film = item("m", 40, MediaType.MOVIE).copy(resumeTicks = Ticks.ofMinutes(25))
        val g = controller.beginPlayback(film, true) as PlaybackStart.Granted
        assertEquals(Ticks.ofMinutes(25), g.resumeFromTicks)
    }

    // ---- Persistence and recovery -------------------------------------------------------

    @Test
    fun cleanRestoreKeepsBalance() {
        startTimed()
        val token = play(item("a", 10))
        clock.advanceSeconds(120)
        controller.onPlaybackStopped(token, 0)
        clock.advanceSeconds(60)
        val restarted = SessionController(store, clock, BOOT)
        val s = restarted.restore() as SessionState.Timed
        assertEquals(Ticks.ofMinutes(13 + 3), s.settledRemainingTicks)
    }

    @Test
    fun leftoverTimeExpiresAfterAReboot() {
        startTimed()
        val restarted = SessionController(store, FakeClock(5), "boot2")
        assertEquals(EndReason.EXPIRED, (restarted.restore() as SessionState.Locked).lastEnd)
        assertTrue(store.stored is SessionState.Locked)
    }

    @Test
    fun leftoverTimeExpiresAfterThirtyIdleMinutesAway() {
        startTimed()
        val token = play(item("a", 10))
        clock.advanceSeconds(60)
        controller.onPlaybackStopped(token, 0)
        clock.advanceTicks(SessionState.IDLE_LIMIT_TICKS - 1)
        assertTrue(SessionController(store, clock, BOOT).restore() is SessionState.Timed)
        clock.advanceTicks(1)
        assertEquals(EndReason.EXPIRED, (SessionController(store, clock, BOOT).restore() as SessionState.Locked).lastEnd)
    }

    @Test
    fun restoreFailsClosedWithoutIdleTimeOrWithClockBeforeIt() {
        store.stored = SessionState.Timed("s", 3, Ticks.ofMinutes(10), bootId = BOOT, idleSinceTicks = null)
        assertEquals(EndReason.EXPIRED, (SessionController(store, clock, BOOT).restore() as SessionState.Locked).lastEnd)
        store.stored = SessionState.Timed("s", 3, Ticks.ofMinutes(10), bootId = BOOT, idleSinceTicks = clock.now + 1)
        assertEquals(EndReason.EXPIRED, (SessionController(store, clock, BOOT).restore() as SessionState.Locked).lastEnd)
    }

    @Test
    fun idleWhileOpenEndsWithSignOffAndPlayingNeverIdles() {
        startTimed(Allowance.MIN_60)
        val token = play(item("a", 50))
        clock.advanceTicks(SessionState.IDLE_LIMIT_TICKS + Ticks.ofMinutes(10))
        assertNull(controller.checkIdle())
        controller.onNotPlaying(token) // paused
        clock.advanceTicks(SessionState.IDLE_LIMIT_TICKS - 1)
        assertNull(controller.checkIdle())
        clock.advanceTicks(1)
        val end = controller.checkIdle()!!
        assertEquals(EndReason.IDLE, end.reason)
        assertTrue(end.showSignOff)
        assertNull(controller.checkIdle())
    }

    @Test
    fun idleAfterReturningLocksQuietly() {
        startTimed()
        clock.advanceTicks(SessionState.IDLE_LIMIT_TICKS)
        val end = controller.checkIdle(quiet = true)!!
        assertEquals(EndReason.EXPIRED, end.reason)
        assertTrue(!end.showSignOff)
    }

    @Test
    fun extensionRestartsTheIdleClock() {
        val s = startTimed()
        clock.advanceTicks(SessionState.IDLE_LIMIT_TICKS - 10)
        controller.extend(grant(), Extension.MIN_5, s.sessionId, "r")
        clock.advanceTicks(20)
        assertNull(controller.checkIdle())
    }

    @Test
    fun cancelStartWithdrawsAnUnplayedSessionOnly() {
        val s = startTimed()
        assertTrue(!controller.cancelStart("other"))
        val g = controller.beginPlayback(item("a", 5), true) as PlaybackStart.Granted
        assertTrue(controller.cancelStart(s.sessionId))
        assertEquals(EndReason.START_FAILED, (controller.state.value as SessionState.Locked).lastEnd)
        assertEquals(PlayingResult.Stale, controller.onPlaying(g.token))
        val t = startTimed()
        play(item("b", 5))
        assertTrue(!controller.cancelStart(t.sessionId))
    }

    @Test
    fun crashWithOpenIntervalLocksAndNeedsParent() {
        startTimed()
        play(item("a", 10))
        clock.advanceSeconds(120)
        // Process dies here.
        val restarted = SessionController(store, clock, BOOT)
        assertEquals(EndReason.RECOVERED_FAIL_CLOSED, (restarted.restore() as SessionState.Locked).lastEnd)
    }

    @Test
    fun crashDuringSignOffRestoresLockedWithoutStandbyRetry() {
        startTimed()
        controller.parentEnd(grant())
        val restarted = SessionController(store, FakeClock(5), "boot2")
        assertEquals(EndReason.PARENT_ENDED, (restarted.restore() as SessionState.Locked).lastEnd)
    }

    @Test
    fun corruptStateLocks() {
        val bad = object : SessionStore {
            override fun load(): SessionState? = throw CorruptStateException("x")
            override fun save(state: SessionState) = Unit
        }
        val c = SessionController(bad, clock, BOOT)
        assertEquals(SessionState.Locked(0, EndReason.CORRUPT_STATE), c.restore())
    }

    @Test
    fun revisionsNeverRepeatAcrossSessions() {
        startTimed()
        val r1 = controller.state.value.revision
        controller.completeSignOff(controller.parentEnd(grant())!!.sessionId)
        controller.startTimed(grant(), Allowance.MIN_15)
        assertTrue(controller.state.value.revision > r1 + 1)
    }
}

class OneTitleTest {
    private val clock = FakeClock()
    private val store = InMemorySessionStore()
    private val controller = SessionController(store, clock, BOOT) { "one" }
    private val movie = MediaItem("movie", "Long Movie", MediaType.MOVIE, Ticks.ofMinutes(130), "kids")

    private fun grant() = ParentGrant(clock.nowTicks())

    private fun approve() {
        controller.restore()
        assertTrue(controller.startOneTitle(grant(), movie, accessConfirmed = true) is StartResult.Started)
    }

    private fun playMovie(): PlaybackStart.Granted {
        val g = controller.beginPlayback(movie, true) as PlaybackStart.Granted
        assertEquals(PlayingResult.Playing(null), controller.onPlaying(g.token))
        return g
    }

    @Test
    fun longerThanLargestPresetPlaysToTheEnd() {
        approve()
        val g = playMovie()
        clock.advanceSeconds(130 * 60)
        assertNull(controller.checkDeadline())
        val end = controller.onPlaybackCompleted(g.token) as StopResult.Ended
        assertEquals(EndReason.ONE_TITLE_COMPLETED, end.command.reason)
        assertTrue(end.command.showSignOff)
    }

    @Test
    fun differentItemAndRevokedAccessAreRejected() {
        approve()
        assertEquals(PlaybackStart.Denied(Rejection.NOT_APPROVED_ITEM), controller.beginPlayback(movie.copy(id = "other"), true))
        assertEquals(PlaybackStart.Denied(Rejection.ACCESS_DENIED), controller.beginPlayback(movie, false))
    }

    @Test
    fun approvalNeedsAccessAndPlayableType() {
        controller.restore()
        assertEquals(StartResult.Rejected(Rejection.ACCESS_DENIED), controller.startOneTitle(grant(), movie, false))
        assertEquals(
            StartResult.Rejected(Rejection.NOT_PLAYABLE),
            controller.startOneTitle(grant(), movie.copy(type = MediaType.SERIES), true),
        )
    }

    @Test
    fun backMidwaySavesPositionAndResumesWithoutPin() {
        approve()
        val g = playMovie()
        clock.advanceSeconds(40 * 60)
        assertEquals(StopResult.Continue, controller.onPlaybackStopped(g.token, Ticks.ofMinutes(40)))
        val s = controller.state.value as SessionState.OneTitle
        assertEquals(Ticks.ofMinutes(40), s.resumePositionTicks)
        val resumed = controller.beginPlayback(movie, true) as PlaybackStart.Granted
        assertEquals(Ticks.ofMinutes(40), resumed.resumeFromTicks)
    }

    @Test
    fun homeMidwayKeepsApprovalAcrossRelaunch() {
        approve()
        val g = playMovie()
        controller.onPlaybackStopped(g.token, Ticks.ofMinutes(12))
        assertNull(controller.onBackgrounded())
        val relaunched = SessionController(store, clock, BOOT)
        val s = relaunched.restore() as SessionState.OneTitle
        assertEquals("movie", s.approvedItemId)
        assertEquals(Ticks.ofMinutes(12), s.resumePositionTicks)
    }

    @Test
    fun stopWithoutAPositionKeepsTheSavedStopPoint() {
        approve()
        val g = playMovie()
        controller.onPlaybackStopped(g.token, Ticks.ofMinutes(12))
        // The next try fails before playing: position 0 must not wipe the stop point.
        val again = controller.beginPlayback(movie, true) as PlaybackStart.Granted
        controller.onPlaybackStopped(again.token, 0)
        assertEquals(Ticks.ofMinutes(12), (controller.state.value as SessionState.OneTitle).resumePositionTicks)
    }

    @Test
    fun completionConsumesApprovalOnce() {
        approve()
        val g = playMovie()
        controller.onPlaybackCompleted(g.token)
        assertEquals(StopResult.Stale, controller.onPlaybackCompleted(g.token))
        controller.completeSignOff("one")
        assertEquals(PlaybackStart.Denied(Rejection.NOT_ACTIVE), controller.beginPlayback(movie, true))
    }

    @Test
    fun extensionsDoNotApplyToOneTitle() {
        approve()
        assertEquals(ExtendResult.Rejected(Rejection.NOT_ACTIVE), controller.extend(grant(), Extension.MIN_5, "one", "r"))
    }
}
