// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCodecTest {
    private fun roundTrip(s: SessionState) = SessionCodec.decode(SessionCodec.encode(s))

    @Test
    fun roundTripsAllStates() {
        val states = listOf(
            SessionState.Locked(0),
            SessionState.Locked(7, EndReason.ALLOWANCE_USED),
            SessionState.Timed("s", 3, Ticks.ofMinutes(15) + 1, NowPlaying("i", 2), "req", "b1", 777),
            SessionState.Timed("s", 3, 5, NowPlaying("i", 2), bootId = "b1", idleSinceTicks = null),
            SessionState.OneTitle("s", 4, "m", "A title\nwith = and \\ odd chars", 99, bootId = "b2", idleSinceTicks = 0),
            SessionState.Ending("s", 9, EndReason.NO_ELIGIBLE_TITLE),
        )
        states.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun openIntervalSurvivesOnlyAsAMarker() {
        val s = SessionState.Timed("s", 3, 10, NowPlaying("i", 2, intervalStartTicks = 123456))
        val decoded = roundTrip(s) as SessionState.Timed
        assertEquals(SessionCodec.OPEN_INTERVAL_UNKNOWN_START, decoded.nowPlaying!!.intervalStartTicks)
    }

    @Test
    fun noNowPlayingRoundTrips() {
        assertNull((roundTrip(SessionState.Timed("s", 1, 5)) as SessionState.Timed).nowPlaying)
    }

    @Test
    fun rejectsDamage() {
        val good = SessionCodec.encode(SessionState.Timed("s", 3, 600))
        val cases = listOf(
            "",
            "garbage",
            good.replace("remaining=600", "remaining=900"), // checksum mismatch
            good.substring(0, good.length / 2), // truncated
            good.replace("v=1", "v=2"),
        )
        for (c in cases) {
            try {
                SessionCodec.decode(c)
                throw AssertionError("expected corrupt: $c")
            } catch (expected: CorruptStateException) {
                // ok
            }
        }
    }

    @Test(expected = CorruptStateException::class)
    fun rejectsNegativeRemainingEvenWithValidChecksum() {
        SessionCodec.decode(ChecksummedFields.encode(mapOf("v" to "1", "rev" to "1", "kind" to "timed", "sid" to "s", "remaining" to "-5")))
    }

    @Test
    fun activeSessionWithoutBootOrIdleIsCorrupt() {
        val base = mapOf("v" to "1", "rev" to "1", "kind" to "timed", "sid" to "s", "remaining" to "5")
        for (fields in listOf(base, base + ("boot" to "b"), base + ("idle" to "5"), base + ("boot" to "b") + ("idle" to "x"))) {
            try {
                SessionCodec.decode(ChecksummedFields.encode(fields))
                throw AssertionError("expected corrupt: $fields")
            } catch (expected: CorruptStateException) {
                // ok
            }
        }
        val ok = SessionCodec.decode(ChecksummedFields.encode(base + ("boot" to "b") + ("idle" to "5"))) as SessionState.Timed
        assertEquals(5L, ok.idleSinceTicks)
    }

    @Test(expected = CorruptStateException::class)
    fun rejectsUnknownKind() {
        SessionCodec.decode(ChecksummedFields.encode(mapOf("v" to "1", "rev" to "1", "kind" to "party")))
    }
}
