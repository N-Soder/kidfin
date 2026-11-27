// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaType
import au.kidfin.tv.security.ParentGrant
import au.kidfin.tv.session.Approval
import au.kidfin.tv.session.FakeClock
import au.kidfin.tv.session.Ticks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentGateTest {
    private val clock = FakeClock()
    private val gate = ParentGate(clock)
    private val movie = MediaItem("m1", "Movie", MediaType.MOVIE, Ticks.ofMinutes(20), "kids")
    private val show = MediaItem("s1", "Show", MediaType.SERIES, null, "kids")
    private val approve = Screen.Approve(movie, Approval.offerFor(movie))

    private fun grant() = gate.accept(ParentGrant(clock.nowTicks()))

    @Test
    fun noGrantUntilOneIsAccepted() {
        assertNull(gate.valid())
        assertNull(gate.require(Screen.ParentMenu, locked = true))
        grant()
        assertNotNull(gate.require(Screen.ParentMenu, locked = true))
    }

    @Test
    fun grantLastsExactlyTwoMinutes() {
        grant()
        clock.advanceTicks(ParentGrant.VALIDITY_TICKS)
        assertNotNull(gate.valid())
        clock.advanceTicks(1)
        assertNull(gate.valid())
        // Dropped for good: going back in time can't revive it.
        clock.advanceTicks(-ParentGrant.VALIDITY_TICKS)
        assertNull(gate.valid())
    }

    @Test
    fun settingsActivityRenewsUpToFifteenMinutes() {
        grant()
        repeat(15) {
            clock.advanceSeconds(60)
            gate.onActivity(Screen.ServerSetup, locked = true)
        }
        assertNotNull(gate.valid())
        clock.advanceTicks(1)
        gate.onActivity(Screen.ServerSetup, locked = true)
        assertNull(gate.valid())
    }

    @Test
    fun lapsedGrantIsNotRenewed() {
        grant()
        clock.advanceTicks(ParentGrant.VALIDITY_TICKS + 1)
        gate.onActivity(Screen.PickLibraries, locked = true)
        assertNull(gate.valid())
    }

    @Test
    fun onlySettingsScreensRenew() {
        for (s in listOf(Screen.ServerSetup, Screen.PickLibraries, Screen.TvPower, Screen.ConfirmTvPowerTest, Screen.ChangePin)) {
            assertTrue(s.toString(), ParentGate.renewsOn(s, locked = true))
        }
        assertTrue(ParentGate.renewsOn(Screen.ParentMenu, locked = true))
        // The in-session menu adds or ends time; Approve starts it.
        assertFalse(ParentGate.renewsOn(Screen.ParentMenu, locked = false))
        assertFalse(ParentGate.renewsOn(approve, locked = true))
        for (s in listOf(Screen.Shelf, Screen.EnterPin, Screen.LeaveKidfin, Screen.Browse, Screen.Player)) {
            assertFalse(s.toString(), ParentGate.renewsOn(s, locked = true))
        }
    }

    @Test
    fun noRenewalOnApprove() {
        grant()
        clock.advanceSeconds(60)
        gate.onActivity(approve, locked = true)
        clock.advanceSeconds(61)
        assertNull(gate.valid())
    }

    @Test
    fun lapseOnApproveKeepsThePickForTheSecret() {
        assertNull(gate.require(approve, locked = true))
        assertEquals(movie, gate.pending.value)
        assertEquals(ParentGate.AfterSecret.Approve(movie), gate.afterSecret(locked = true))
    }

    @Test
    fun lapseOnApproveDuringASessionKeepsNothing() {
        assertNull(gate.require(approve, locked = false))
        assertNull(gate.pending.value)
    }

    @Test
    fun lapseOnASetupStepReturnsThereAfterTheSecret() {
        assertNull(gate.require(Screen.PickLibraries, locked = true))
        assertEquals(ParentGate.AfterSecret.Return(Screen.PickLibraries), gate.afterSecret(locked = true))
        // Used once.
        assertEquals(ParentGate.AfterSecret.Menu, gate.afterSecret(locked = true))
        assertNull(gate.require(Screen.TvPower, locked = true))
        assertEquals(ParentGate.AfterSecret.Menu, gate.afterSecret(locked = true))
    }

    @Test
    fun afterSecretPrefersHeartThenSetupThenPick() {
        gate.setPending(movie)
        gate.returnAfterSecret = Screen.ServerSetup
        gate.pendingHeart = show
        gate.pickOrigin = Screen.Shelf
        assertEquals(ParentGate.AfterSecret.Heart(show), gate.afterSecret(locked = true))
        assertNull(gate.pickOrigin)
        assertEquals(ParentGate.AfterSecret.Return(Screen.ServerSetup), gate.afterSecret(locked = true))
        assertEquals(ParentGate.AfterSecret.Approve(movie), gate.afterSecret(locked = true))
    }

    @Test
    fun aPickIsApprovedOnlyWhileLocked() {
        gate.setPending(movie)
        assertEquals(ParentGate.AfterSecret.Menu, gate.afterSecret(locked = false))
    }

    @Test
    fun clearAllForgetsEverything() {
        grant()
        gate.setPending(movie)
        gate.pendingHeart = show
        gate.returnAfterSecret = Screen.ServerSetup
        gate.setupFlow = true
        gate.pickOrigin = Screen.Search
        gate.clearAll()
        assertNull(gate.valid())
        assertNull(gate.pending.value)
        assertNull(gate.pendingHeart)
        assertNull(gate.returnAfterSecret)
        assertFalse(gate.setupFlow)
        assertNull(gate.pickOrigin)
    }
}
