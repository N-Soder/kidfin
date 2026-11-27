// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalTest {
    private fun item(ticks: Long?, type: MediaType = MediaType.EPISODE) = MediaItem("i", "T", type, ticks, "lib")

    @Test
    fun shortTitleSuggestsShortestFittingAllowance() {
        val offer = Approval.offerFor(item(Ticks.ofMinutes(22)))
        assertEquals(Allowance.MIN_30, offer.suggested)
        assertFalse(offer.isLong)
        assertEquals(listOf(Allowance.MIN_15, Allowance.MIN_30, Allowance.MIN_60), offer.options.map { it.allowance })
        assertEquals(listOf(false, true, true), offer.options.map { it.fitsPickedTitle })
    }

    @Test
    fun presetsAllowThreeMinutesOverAndNotOneTickMore() {
        assertEquals(Allowance.MIN_15, Approval.offerFor(item(Ticks.ofMinutes(18))).suggested)
        assertEquals(Allowance.MIN_30, Approval.offerFor(item(Ticks.ofMinutes(18) + 1)).suggested)
        assertEquals(Allowance.MIN_60, Approval.offerFor(item(Ticks.ofMinutes(63))).suggested)
    }

    @Test
    fun longerThanLargestPresetIsLong() {
        val offer = Approval.offerFor(item(Ticks.ofMinutes(63) + 1, MediaType.MOVIE))
        assertTrue(offer.isLong)
        assertNull(offer.suggested)
        assertTrue(offer.options.none { it.fitsPickedTitle })
    }

    @Test
    fun unknownOrInvalidRuntimeIsLong() {
        listOf(null, 0L, -5L).forEach { assertTrue(Approval.offerFor(item(it, MediaType.MOVIE)).isLong) }
    }
}
