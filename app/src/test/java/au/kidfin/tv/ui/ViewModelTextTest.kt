// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.session.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewModelTextTest {
    @Test
    fun refusalsAreNeverShownAsEnumNames() {
        for (r in Rejection.entries) {
            val text = MainViewModel.describe(r)
            assertTrue(text, r.name !in text && '_' !in text && text.isNotBlank())
        }
    }

    @Test
    fun countdownRoundsUpAndShowsMinutes() {
        assertEquals("1 s", SecretPad.countdownLabel(1))
        assertEquals("30 s", SecretPad.countdownLabel(29_001))
        assertEquals("1:00", SecretPad.countdownLabel(59_001))
        assertEquals("2:45", SecretPad.countdownLabel(165_000))
        assertEquals("3:00", SecretPad.countdownLabel(180_000))
    }
}
