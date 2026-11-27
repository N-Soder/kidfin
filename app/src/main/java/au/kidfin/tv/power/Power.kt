// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.power

import kotlinx.coroutines.CancellationException

/**
 * How the TV is put to standby when a session ends. NATIVE (accessibility lock-screen action,
 * which makes a Google TV device send CEC standby) is the implemented mode; APP_ONLY never
 * requests standby.
 */
enum class PowerMode { APP_ONLY, NATIVE }

sealed interface StandbyRequestResult {
    /** The provider accepted the request. This is not proof the panel turned off. */
    data object Requested : StandbyRequestResult
    data class Unavailable(val reason: String) : StandbyRequestResult
    data class Failed(val reason: String) : StandbyRequestResult
}

fun interface PowerController {
    suspend fun requestStandby(): StandbyRequestResult
}

sealed interface StandbyDecision {
    data object SkippedAppOnly : StandbyDecision
    data object SkippedNotOnScreen : StandbyDecision
    data class Attempted(val result: StandbyRequestResult) : StandbyDecision
}

/**
 * Runs once per ending, after the foreground sign-off. Standby is requested only while the app
 * is still on screen, checked at the moment of the request so a Home press during the goodbye
 * skips it. The app stays locked whatever the outcome.
 */
class StandbyPolicy(
    val mode: PowerMode,
    private val controller: PowerController,
) {
    suspend fun afterSignOff(appOnScreen: () -> Boolean): StandbyDecision {
        if (mode == PowerMode.APP_ONLY) return StandbyDecision.SkippedAppOnly
        if (!appOnScreen()) return StandbyDecision.SkippedNotOnScreen
        val result = try {
            controller.requestStandby()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StandbyRequestResult.Failed(e.javaClass.simpleName)
        }
        return StandbyDecision.Attempted(result)
    }

    companion object {
        /** Native mode: lock-screen standby. */
        fun native(controller: PowerController): StandbyPolicy = StandbyPolicy(PowerMode.NATIVE, controller)
    }
}
