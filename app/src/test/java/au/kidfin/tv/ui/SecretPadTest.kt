// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.security.PinFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecretPadTest {
    private fun TestScope.pad() = SecretPad(backgroundScope) { testScheduler.currentTime }

    @Test
    fun countsPressesAndHandsOverTheWholeEntry() = runTest {
        val pad = pad()
        "UUDDL".forEach { assertNull(pad.press(it)) }
        assertEquals(5, pad.state.value.entered)
        assertEquals("UUDDLR", pad.press('R'))
        assertTrue(pad.state.value.busy)
        // Busy until reset: further presses are ignored.
        assertNull(pad.press('U'))
        pad.reset("Try again")
        assertEquals(PinEntryState(message = "Try again"), pad.state.value)
    }

    @Test
    fun ignoresKeysTheFormatDoesNotUse() = runTest {
        val pad = pad()
        assertNull(pad.press('7'))
        assertEquals(0, pad.state.value.entered)
        pad.reset(null, PinFormat.DIGITS)
        assertNull(pad.press('U'))
        "123".forEach { pad.press(it) }
        assertEquals("1234", pad.press('4'))
    }

    @Test
    fun deleteAndClear() = runTest {
        val pad = pad()
        "UUD".forEach { pad.press(it) }
        pad.delete()
        assertEquals(2, pad.state.value.entered)
        pad.clear()
        assertEquals(0, pad.state.value.entered)
        "LLLLL".forEach { pad.press(it) }
        assertEquals("LLLLLL", pad.press('L'))
    }

    @Test
    fun cooldownIgnoresPressesAndCountsDown() = runTest {
        val pad = pad()
        pad.press('U')
        pad.showCooldown(30_000)
        runCurrent()
        assertTrue(pad.state.value.coolingDown)
        assertEquals(0, pad.state.value.entered)
        assertEquals("Too many tries. Try again in 30 s.", pad.state.value.message)
        assertNull(pad.press('U'))
        pad.delete()
        pad.clear()
        assertEquals(0, pad.state.value.entered)

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("Too many tries. Try again in 10 s.", pad.state.value.message)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(pad.state.value.coolingDown)
        assertEquals("Try again", pad.state.value.message)
        assertNull(pad.press('U'))
        assertEquals(1, pad.state.value.entered)
    }

    @Test
    fun resetCancelsACooldown() = runTest {
        val pad = pad()
        pad.showCooldown(30_000)
        runCurrent()
        pad.reset(null)
        advanceTimeBy(31_000)
        runCurrent()
        assertNull(pad.state.value.message)
    }
}
