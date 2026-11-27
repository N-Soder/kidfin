// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.platform

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import au.kidfin.tv.power.PowerController
import au.kidfin.tv.power.StandbyRequestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * App-owned accessibility service whose only job is GLOBAL_ACTION_LOCK_SCREEN, which on a
 * Chromecast with Google TV sleeps the device and sends CEC <Standby> to the TV (tested on one
 * unit; other devices and TVs may behave differently).
 * It subscribes to no events, reads no window content and filters no keys; see
 * res/xml/native_standby_service.xml. A parent enables it in the TV's accessibility settings.
 */
class NativeStandbyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "standby service connected")
        NativeStandbyConnection.set(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately ignored. No event types are subscribed in the service config.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "standby service unbound")
        NativeStandbyConnection.clear(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        NativeStandbyConnection.clear(this)
        super.onDestroy()
    }

    internal fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        private const val TAG = "KidFin"
    }
}

/** Main-thread connection state. A weak reference so the process never pins a dead service. */
object NativeStandbyConnection {
    private var ref: WeakReference<NativeStandbyService>? = null
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    internal val current: NativeStandbyService? get() = ref?.get()

    internal fun set(service: NativeStandbyService) {
        ref = WeakReference(service)
        mutableConnected.value = true
    }

    internal fun clear(service: NativeStandbyService) {
        if (ref?.get() === service) {
            ref = null
            mutableConnected.value = false
        }
    }
}

/**
 * NATIVE power provider. Reports only whether Android accepted the request; it never claims
 * the TV panel turned off.
 */
object NativePowerController : PowerController {
    override suspend fun requestStandby(): StandbyRequestResult = withContext(Dispatchers.Main.immediate) {
        val service = NativeStandbyConnection.current
            ?: return@withContext StandbyRequestResult.Unavailable("standby service not connected")
        try {
            if (service.lockScreen()) {
                StandbyRequestResult.Requested
            } else {
                StandbyRequestResult.Failed("lock screen action refused")
            }
        } catch (e: RuntimeException) {
            StandbyRequestResult.Failed(e.javaClass.simpleName)
        }
    }
}
