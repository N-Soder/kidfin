// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

/**
 * Persisted session state. [revision] increases on every change and is never reused,
 * including across sessions, so callbacks from older states can be recognised.
 */
sealed interface SessionState {
    val revision: Long

    /** No session. The welcome screen requires a parent. */
    data class Locked(
        override val revision: Long,
        val lastEnd: EndReason? = null,
    ) : SessionState

    /**
     * [bootId] is the device boot the session was started in; leftover time never survives a
     * reboot. [idleSinceTicks] is when video last stopped actually playing (monotonic, same
     * boot), null while it plays: a session idle for [IDLE_LIMIT_TICKS] ends.
     */
    sealed interface Active : SessionState {
        val sessionId: String
        val nowPlaying: NowPlaying?
        val bootId: String
        val idleSinceTicks: Long?
    }

    /**
     * Timed session. [settledRemainingTicks] excludes any currently open playing interval and
     * includes the [au.kidfin.tv.media.RuntimeRules.GRACE_TICKS] reserve on top of the
     * allowance, so the last title may run that much over. Children see it without the reserve.
     */
    data class Timed(
        override val sessionId: String,
        override val revision: Long,
        val settledRemainingTicks: Long,
        override val nowPlaying: NowPlaying? = null,
        val lastExtensionRequestId: String? = null,
        override val bootId: String = UNKNOWN_BOOT,
        override val idleSinceTicks: Long? = null,
    ) : Active

    /** One parent-approved title, playable to its natural end. */
    data class OneTitle(
        override val sessionId: String,
        override val revision: Long,
        val approvedItemId: String,
        val approvedItemTitle: String,
        val resumePositionTicks: Long = 0L,
        override val nowPlaying: NowPlaying? = null,
        override val bootId: String = UNKNOWN_BOOT,
        override val idleSinceTicks: Long? = null,
    ) : Active

    /** Terminal. Playback is closed; only foreground sign-off and standby remain. */
    data class Ending(
        val sessionId: String,
        override val revision: Long,
        val reason: EndReason,
    ) : SessionState

    companion object {
        /** Never matches a real boot, so a session carrying it is treated as from another boot. */
        const val UNKNOWN_BOOT = "unknown"

        /** Nothing playing for this long (paused, browsing, or away) ends a session. */
        val IDLE_LIMIT_TICKS: Long = Ticks.ofMinutes(30)
    }
}

/**
 * An admitted item. [playbackId] identifies this admission for player callbacks.
 * [intervalStartTicks] is non-null only while video is actually playing.
 */
data class NowPlaying(
    val itemId: String,
    val playbackId: Long,
    val intervalStartTicks: Long? = null,
) {
    val isPlaying: Boolean get() = intervalStartTicks != null
}

enum class EndReason(val showsSignOff: Boolean) {
    ALLOWANCE_USED(true),
    NO_ELIGIBLE_TITLE(true),
    PARENT_ENDED(true),
    ONE_TITLE_COMPLETED(true),

    /** Parent ended a session to choose a different mode. They are present; no sign-off or standby. */
    PARENT_SWITCHED_MODE(false),

    /** Process died with an open playing interval, or a clean return was impossible. */
    RECOVERED_FAIL_CLOSED(false),

    /** Persisted state could not be read. */
    CORRUPT_STATE(false),

    /** Nothing played for [SessionState.IDLE_LIMIT_TICKS] while Kidfin was on screen. */
    IDLE(true),

    /** Leftover time found after a reboot, or idle too long while Kidfin was away. Locks quietly. */
    EXPIRED(false),

    /** The approved title couldn't be started; the session is withdrawn so nothing sits unused. */
    START_FAILED(false),
}
