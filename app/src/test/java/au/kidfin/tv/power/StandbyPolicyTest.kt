// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.power

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StandbyPolicyTest {
    private var requests = 0
    private val controller = PowerController { requests++; StandbyRequestResult.Requested }
    private val onScreen = { true }

    @Test
    fun appOnlyNeverRequests() = runTest {
        assertEquals(StandbyDecision.SkippedAppOnly, StandbyPolicy(PowerMode.APP_ONLY, controller).afterSignOff(onScreen))
        assertEquals(0, requests)
    }

    @Test
    fun nativeRequestsStandby() = runTest {
        assertEquals(
            StandbyDecision.Attempted(StandbyRequestResult.Requested),
            StandbyPolicy.native(controller).afterSignOff(onScreen),
        )
        assertEquals(1, requests)
    }

    @Test
    fun nativeSkipsWhenAppNoLongerOnScreen() = runTest {
        assertEquals(StandbyDecision.SkippedNotOnScreen, StandbyPolicy.native(controller).afterSignOff { false })
        assertEquals(0, requests)
    }

    @Test
    fun unavailableAndFailedResultsAreReportedAsIs() = runTest {
        val unavailable = StandbyRequestResult.Unavailable("service not connected")
        assertEquals(
            StandbyDecision.Attempted(unavailable),
            StandbyPolicy.native { unavailable }.afterSignOff(onScreen),
        )
        val failed = StandbyRequestResult.Failed("performGlobalAction returned false")
        assertEquals(StandbyDecision.Attempted(failed), StandbyPolicy.native { failed }.afterSignOff(onScreen))
    }

    @Test
    fun controllerExceptionIsReportedNotThrown() = runTest {
        val broken = PowerController { throw IllegalStateException("boom") }
        assertEquals(
            StandbyDecision.Attempted(StandbyRequestResult.Failed("IllegalStateException")),
            StandbyPolicy.native(broken).afterSignOff(onScreen),
        )
    }
}
