// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.security.PinFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Presses on the secret screen. Holds the presses so far (never shown, only counted) and the
 * live cooldown countdown, during which presses are ignored.
 */
internal class SecretPad(private val scope: CoroutineScope, private val nowMs: () -> Long) {
    private val mutableState = MutableStateFlow(PinEntryState())
    val state: StateFlow<PinEntryState> = mutableState.asStateFlow()

    private var buffer = StringBuilder()
    private var cooldownJob: Job? = null

    /** Starts again from no presses, with [message] shown. */
    fun reset(message: String?, format: PinFormat = mutableState.value.format) {
        cooldownJob?.cancel()
        buffer = StringBuilder()
        mutableState.value = PinEntryState(format = format, message = message)
    }

    /** Plain state with no message, after the secret was accepted. */
    fun idle(format: PinFormat) {
        mutableState.value = PinEntryState(format = format)
    }

    /**
     * One press: an arrow ('U', 'D', 'L', 'R') for moves, or a digit on the old keypad. Returns
     * the whole entry once the last press is in (the pad is then busy until reset), else null.
     */
    fun press(key: Char): String? {
        val state = mutableState.value
        if (state.busy || state.coolingDown || buffer.length >= state.format.length) return null
        if (!state.format.accepts(key)) return null
        buffer.append(key)
        mutableState.value = state.copy(entered = buffer.length)
        if (buffer.length != state.format.length) return null
        val entered = buffer.toString()
        buffer = StringBuilder()
        mutableState.value = mutableState.value.copy(busy = true)
        return entered
    }

    fun delete() {
        if (mutableState.value.busy || mutableState.value.coolingDown || buffer.isEmpty()) return
        buffer.deleteCharAt(buffer.length - 1)
        mutableState.value = mutableState.value.copy(entered = buffer.length)
    }

    /** OK while entering moves: start again (there's no visible key to delete with). */
    fun clear() {
        if (mutableState.value.busy || mutableState.value.coolingDown) return
        buffer = StringBuilder()
        mutableState.value = mutableState.value.copy(entered = 0)
    }

    /** Live countdown; presses are ignored until it reaches zero. */
    fun showCooldown(remainingMs: Long) {
        cooldownJob?.cancel()
        buffer = StringBuilder()
        val until = nowMs() + remainingMs
        cooldownJob = scope.launch {
            while (true) {
                val left = until - nowMs()
                if (left <= 0) break
                mutableState.value = PinEntryState(
                    format = mutableState.value.format,
                    message = "Too many tries. Try again in ${countdownLabel(left)}.",
                    coolingDown = true,
                )
                delay((left - 1) % 1000 + 1)
            }
            mutableState.value = PinEntryState(format = mutableState.value.format, message = "Try again")
        }
    }

    companion object {
        /** "2:45" or "40 s", rounded up so it never shows 0 while still waiting. */
        fun countdownLabel(ms: Long): String {
            val s = (ms + 999) / 1000
            return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "$s s"
        }
    }
}
