// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

import au.kidfin.tv.session.Ticks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRulesTest {
    private fun ep(ticks: Long?, type: MediaType = MediaType.EPISODE) = MediaItem("e", "e", type, ticks, "kids")

    @Test
    fun tickConversions() {
        assertEquals(10_000L, Ticks.PER_MILLISECOND)
        assertEquals(9_000_000_000L, Ticks.ofMinutes(15))
        assertEquals(1L, Ticks.toMillisCeil(1))
        assertEquals(0L, Ticks.toMillisFloor(9_999))
        assertEquals(2L, Ticks.toMillisCeil(10_001))
    }

    @Test
    fun mayRunUpToThreeMinutesOver() {
        val remaining = Ticks.ofMinutes(15)
        val limit = remaining + Ticks.ofMinutes(3)
        assertTrue(RuntimeRules.fits(ep(limit), remaining))
        assertFalse(RuntimeRules.fits(ep(limit + 1), remaining))
        // Fractional milliseconds are preserved: 0.5 ms over must not round into eligibility.
        assertFalse(RuntimeRules.fits(ep(limit + 5_000), remaining))
    }

    @Test
    fun partlyWatchedCountsOnlyWhatsLeft() {
        val film = ep(Ticks.ofMinutes(40)).copy(resumeTicks = Ticks.ofMinutes(25))
        assertEquals(Ticks.ofMinutes(15), RuntimeRules.ticksToWatch(film))
        assertTrue(RuntimeRules.fits(film, Ticks.ofMinutes(12)))
        assertFalse(RuntimeRules.fits(film, Ticks.ofMinutes(11)))
        // A stop point at or past the end is ignored: the whole runtime counts.
        assertEquals(Ticks.ofMinutes(40), RuntimeRules.ticksToWatch(film.copy(resumeTicks = Ticks.ofMinutes(40))))
    }

    @Test
    fun invalidRuntimesAndNonLeafTypesAreNotPlayable() {
        assertFalse(RuntimeRules.isPlayable(ep(null)))
        assertFalse(RuntimeRules.isPlayable(ep(0)))
        assertFalse(RuntimeRules.isPlayable(ep(-1)))
        for (t in listOf(MediaType.SERIES, MediaType.OTHER)) {
            assertFalse(RuntimeRules.isPlayable(ep(1000, t)))
        }
        for (t in listOf(MediaType.MOVIE, MediaType.EPISODE, MediaType.VIDEO)) {
            assertTrue(RuntimeRules.isPlayable(ep(1000, t)))
        }
    }

    @Test
    fun labels() {
        assertEquals("7 min", DurationLabels.runtime(Ticks.ofMinutes(6) + Ticks.ofSeconds(1)))
        assertEquals("1 h 30 min", DurationLabels.runtime(Ticks.ofMinutes(90)))
        assertEquals("6 min left", DurationLabels.remaining(Ticks.ofMinutes(7) - 1))
        assertEquals("Less than 1 min left", DurationLabels.remaining(Ticks.ofSeconds(59)))
        assertEquals("1:05", DurationLabels.clock(Ticks.ofSeconds(65)))
        assertEquals("1:00:00", DurationLabels.clock(Ticks.ofMinutes(60)))
    }
}
