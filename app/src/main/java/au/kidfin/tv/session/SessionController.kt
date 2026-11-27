// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.RuntimeRules
import au.kidfin.tv.security.ParentGrant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Identifies one admitted playback. Callbacks with any other token are ignored. */
data class PlaybackToken(val sessionId: String, val playbackId: Long)

/** The single ending transition. The UI shows sign-off only when [showSignOff] and the app is in front. */
data class EndCommand(val sessionId: String, val revision: Long, val reason: EndReason) {
    val showSignOff: Boolean get() = reason.showsSignOff
}

enum class Rejection {
    NOT_LOCKED,
    NOT_ACTIVE,
    WRONG_SESSION,
    PARENT_NOT_AUTHORISED,
    NOT_PLAYABLE,
    DOES_NOT_FIT,
    NOT_APPROVED_ITEM,
    ACCESS_DENIED,
    ALREADY_PLAYING,
    NO_TIME_LEFT,
    STORAGE_FAILED,
}

sealed interface Admission {
    data object Allowed : Admission
    data class Denied(val reason: Rejection) : Admission
}

sealed interface StartResult {
    data class Started(val state: SessionState.Active) : StartResult
    data class Rejected(val reason: Rejection) : StartResult
}

sealed interface PlaybackStart {
    data class Granted(val token: PlaybackToken, val resumeFromTicks: Long) : PlaybackStart
    data class Denied(val reason: Rejection) : PlaybackStart
}

sealed interface PlayingResult {
    /** Video may play. Check the deadline at [cutoffAtTicks] (null: no time limit, One title). */
    data class Playing(val cutoffAtTicks: Long?) : PlayingResult

    /** Stop now. */
    data class Ended(val command: EndCommand) : PlayingResult

    /** Stop now; the open-interval marker could not be persisted, so playing would be unaccounted. */
    data object StorageFailed : PlayingResult

    /** Token no longer current. Stop this player. */
    data object Stale : PlayingResult
}

sealed interface StopResult {
    /** Session continues: timed returns to browse, One title to its Resume screen. */
    data object Continue : StopResult
    data class Ended(val command: EndCommand) : StopResult
    data object Stale : StopResult
}

sealed interface ExtendResult {
    data class Extended(val remainingTicks: Long, val cutoffAtTicks: Long?) : ExtendResult
    data object AlreadyApplied : ExtendResult
    data class Rejected(val reason: Rejection) : ExtendResult
}

/**
 * The only place session time and approval change. Pure Kotlin; time comes from an injected
 * monotonic clock. Every method is synchronised and persists before returning.
 *
 * Accounting: only intervals where video is actually playing consume a timed allowance.
 * The player reports playing/not-playing edges; seeking never changes the charge.
 */
class SessionController(
    private val store: SessionStore,
    private val clock: MonotonicClock,
    /** This device boot. Sessions from another boot never resume. */
    private val bootId: String,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Locked(0))
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val current: SessionState get() = mutableState.value

    // ---- Lifecycle -------------------------------------------------------------------------

    /** Loads persisted state at process start. Anything uncertain locks and needs a parent. */
    @Synchronized
    fun restore(): SessionState {
        val loaded = try {
            store.load()
        } catch (e: CorruptStateException) {
            return commitBestEffort(SessionState.Locked(0, EndReason.CORRUPT_STATE))
        }
        val restored = when (loaded) {
            null -> SessionState.Locked(0)
            is SessionState.Locked -> loaded
            is SessionState.Ending -> SessionState.Locked(loaded.revision + 1, loaded.reason)
            is SessionState.Active -> when {
                loaded.nowPlaying?.isPlaying == true ->
                    SessionState.Locked(loaded.revision + 1, EndReason.RECOVERED_FAIL_CLOSED)
                isStale(loaded, clock.nowTicks()) -> SessionState.Locked(loaded.revision + 1, EndReason.EXPIRED)
                loaded is SessionState.Timed -> loaded.copy(revision = loaded.revision + 1, nowPlaying = null)
                loaded is SessionState.OneTitle -> loaded.copy(revision = loaded.revision + 1, nowPlaying = null)
                else -> error("unreachable")
            }
        }
        return commitBestEffort(restored)
    }

    /**
     * Orderly backgrounding (Home). The player must already be stopped; this closes any interval
     * it failed to report so the budget is frozen while away.
     */
    @Synchronized
    fun onBackgrounded(): EndCommand? {
        val s = current
        if (s !is SessionState.Active || s.nowPlaying == null) return null
        val position = (s as? SessionState.OneTitle)?.resumePositionTicks ?: 0L
        return when (val r = stopInternal(s, position)) {
            is StopResult.Ended -> r.command
            else -> null
        }
    }

    /**
     * Ends a session that has had nothing playing for [SessionState.IDLE_LIMIT_TICKS]. [quiet]
     * (returning to the app after a long time away) locks without the sign-off or standby.
     * Idempotent; safe from stale timers.
     */
    @Synchronized
    fun checkIdle(quiet: Boolean = false): EndCommand? {
        val s = current as? SessionState.Active ?: return null
        if (s.nowPlaying?.isPlaying == true) return null
        val since = s.idleSinceTicks ?: return null
        if (clock.nowTicks() - since < SessionState.IDLE_LIMIT_TICKS) return null
        return end(s, if (quiet) EndReason.EXPIRED else EndReason.IDLE)
    }

    /**
     * Withdraws a just-approved session whose first title could not be started (or was
     * interrupted before it played), so no approved time sits unused. No grant needed: locking
     * is always safe. Ignored once anything has actually played or for another session.
     */
    @Synchronized
    fun cancelStart(sessionId: String): Boolean {
        val s = current as? SessionState.Active ?: return false
        if (s.sessionId != sessionId || s.nowPlaying?.isPlaying == true) return false
        commitBestEffort(SessionState.Locked(s.revision + 1, EndReason.START_FAILED))
        return true
    }

    // ---- Queries ---------------------------------------------------------------------------

    /**
     * Live remaining allowance for a timed session, including any open playing interval and
     * excluding the grace reserve (so it reads 0 once the allowance itself is used).
     */
    @Synchronized
    fun remainingTicks(): Long? =
        (current as? SessionState.Timed)?.let { (liveRemaining(it, clock.nowTicks()) - GRACE).coerceAtLeast(0L) }

    /** Whether [item] could start now, without opening anything: the rule [beginPlayback] applies. */
    @Synchronized
    fun admit(item: MediaItem, accessConfirmed: Boolean): Admission =
        when (val reason = admissionProblem(current, item, accessConfirmed)) {
            null -> Admission.Allowed
            else -> Admission.Denied(reason)
        }

    // ---- Parent actions --------------------------------------------------------------------

    @Synchronized
    fun startTimed(grant: ParentGrant, allowance: Allowance): StartResult {
        if (!grant.isValidAt(clock.nowTicks())) return StartResult.Rejected(Rejection.PARENT_NOT_AUTHORISED)
        val s = current as? SessionState.Locked ?: return StartResult.Rejected(Rejection.NOT_LOCKED)
        val next = SessionState.Timed(
            newSessionId(), s.revision + 1, allowance.ticks + GRACE, bootId = bootId, idleSinceTicks = clock.nowTicks(),
        )
        if (!commitCritical(next)) return StartResult.Rejected(Rejection.STORAGE_FAILED)
        return StartResult.Started(next)
    }

    /** [accessConfirmed] must come from a fresh server lookup against the permitted libraries. */
    @Synchronized
    fun startOneTitle(grant: ParentGrant, item: MediaItem, accessConfirmed: Boolean): StartResult {
        if (!grant.isValidAt(clock.nowTicks())) return StartResult.Rejected(Rejection.PARENT_NOT_AUTHORISED)
        val s = current as? SessionState.Locked ?: return StartResult.Rejected(Rejection.NOT_LOCKED)
        if (!accessConfirmed) return StartResult.Rejected(Rejection.ACCESS_DENIED)
        if (!RuntimeRules.isApprovableForOneTitle(item)) return StartResult.Rejected(Rejection.NOT_PLAYABLE)
        val next = SessionState.OneTitle(
            newSessionId(), s.revision + 1, item.id, item.title, bootId = bootId, idleSinceTicks = clock.nowTicks(),
        )
        if (!commitCritical(next)) return StartResult.Rejected(Rejection.STORAGE_FAILED)
        return StartResult.Started(next)
    }

    /**
     * Adds time to the running timed session. Playback up to now is settled first. A repeated
     * [requestId] is applied at most once.
     */
    @Synchronized
    fun extend(grant: ParentGrant, extension: Extension, sessionId: String, requestId: String): ExtendResult {
        val now = clock.nowTicks()
        if (!grant.isValidAt(now)) return ExtendResult.Rejected(Rejection.PARENT_NOT_AUTHORISED)
        val s = current as? SessionState.Timed ?: return ExtendResult.Rejected(Rejection.NOT_ACTIVE)
        if (s.sessionId != sessionId) return ExtendResult.Rejected(Rejection.WRONG_SESSION)
        if (s.lastExtensionRequestId == requestId) return ExtendResult.AlreadyApplied
        val settled = settle(s, now)
        if (settled.settledRemainingTicks <= 0L) return ExtendResult.Rejected(Rejection.NO_TIME_LEFT)
        val reopened = settled.nowPlaying?.takeIf { s.nowPlaying?.isPlaying == true }?.copy(intervalStartTicks = now)
        val next = settled.copy(
            revision = s.revision + 1,
            settledRemainingTicks = Math.addExact(settled.settledRemainingTicks, extension.ticks),
            nowPlaying = reopened ?: settled.nowPlaying,
            lastExtensionRequestId = requestId,
            // A grown-up was just here: the idle clock starts again unless something is playing.
            idleSinceTicks = if (reopened != null) null else now,
        )
        if (!commitCritical(next)) return ExtendResult.Rejected(Rejection.STORAGE_FAILED)
        val cutoff = if (next.nowPlaying?.isPlaying == true) now + next.settledRemainingTicks else null
        return ExtendResult.Extended((next.settledRemainingTicks - GRACE).coerceAtLeast(0L), cutoff)
    }

    /** Parent ends the session early: stop, lock, sign-off and standby as for any ending. */
    @Synchronized
    fun parentEnd(grant: ParentGrant): EndCommand? {
        if (!grant.isValidAt(clock.nowTicks())) return null
        val s = current as? SessionState.Active ?: return null
        return end(s, EndReason.PARENT_ENDED)
    }

    /** Parent ends the session to start a different mode. No sign-off or standby; parent is present. */
    @Synchronized
    fun parentSwitchMode(grant: ParentGrant): Boolean {
        val now = clock.nowTicks()
        if (!grant.isValidAt(now)) return false
        val s = current as? SessionState.Active ?: return false
        commitBestEffort(SessionState.Locked(s.revision + 1, EndReason.PARENT_SWITCHED_MODE))
        return true
    }

    // ---- Playback --------------------------------------------------------------------------

    /** Play tap. Revalidates the item and admits it; nothing is charged until [onPlaying]. */
    @Synchronized
    fun beginPlayback(item: MediaItem, accessConfirmed: Boolean): PlaybackStart {
        val s = current
        admissionProblem(s, item, accessConfirmed)?.let { return PlaybackStart.Denied(it) }
        s as SessionState.Active
        val playbackId = s.revision + 1
        val nowPlaying = NowPlaying(item.id, playbackId)
        val next = when (s) {
            is SessionState.Timed -> s.copy(revision = playbackId, nowPlaying = nowPlaying)
            is SessionState.OneTitle -> s.copy(revision = playbackId, nowPlaying = nowPlaying)
        }
        commitBestEffort(next)
        // Carry on where it was left off: this session's own stop point, else the server's.
        val ownStop = (s as? SessionState.OneTitle)?.resumePositionTicks?.takeIf { it > 0 }
        val resume = ownStop ?: (item.runtimeTicks?.let { it - RuntimeRules.ticksToWatch(item) } ?: 0L)
        return PlaybackStart.Granted(PlaybackToken(s.sessionId, playbackId), resume)
    }

    /** Second check immediately before the player starts. */
    @Synchronized
    fun revalidate(token: PlaybackToken, item: MediaItem, accessConfirmed: Boolean): Admission {
        val s = current
        if (!matches(s, token)) return Admission.Denied(Rejection.WRONG_SESSION)
        s as SessionState.Active
        if (s.nowPlaying?.itemId != item.id) return Admission.Denied(Rejection.WRONG_SESSION)
        val withoutAdmission = when (s) {
            is SessionState.Timed -> s.copy(nowPlaying = null)
            is SessionState.OneTitle -> s.copy(nowPlaying = null)
        }
        return admissionProblem(withoutAdmission, item, accessConfirmed)?.let { Admission.Denied(it) } ?: Admission.Allowed
    }

    /** Player entered the actually-playing state. Opens a charged interval for timed sessions. */
    @Synchronized
    fun onPlaying(token: PlaybackToken): PlayingResult {
        val s = current
        if (!matches(s, token)) return PlayingResult.Stale
        s as SessionState.Active
        val now = clock.nowTicks()
        val np = s.nowPlaying!!
        if (np.isPlaying) {
            return PlayingResult.Playing((s as? SessionState.Timed)?.let { now + liveRemaining(it, now) })
        }
        return when (s) {
            is SessionState.Timed -> {
                if (s.settledRemainingTicks <= 0L) return PlayingResult.Ended(end(s, EndReason.ALLOWANCE_USED))
                val next = s.copy(revision = s.revision + 1, nowPlaying = np.copy(intervalStartTicks = now), idleSinceTicks = null)
                if (!commitCritical(next)) return PlayingResult.StorageFailed
                PlayingResult.Playing(now + next.settledRemainingTicks)
            }
            is SessionState.OneTitle -> {
                val next = s.copy(revision = s.revision + 1, nowPlaying = np.copy(intervalStartTicks = now), idleSinceTicks = null)
                if (!commitCritical(next)) return PlayingResult.StorageFailed
                PlayingResult.Playing(null)
            }
        }
    }

    /** Paused, buffering or preparing: settle the interval. Free time. */
    @Synchronized
    fun onNotPlaying(token: PlaybackToken): StopResult {
        val s = current
        if (!matches(s, token)) return StopResult.Stale
        s as SessionState.Active
        if (s.nowPlaying?.isPlaying != true) return StopResult.Continue
        return when (s) {
            is SessionState.Timed -> {
                val now = clock.nowTicks()
                val settled = settle(s, now)
                if (settled.settledRemainingTicks <= 0L) return StopResult.Ended(end(s, EndReason.ALLOWANCE_USED))
                commitBestEffort(settled.copy(revision = s.revision + 1, idleSinceTicks = now))
                StopResult.Continue
            }
            is SessionState.OneTitle -> {
                commitBestEffort(
                    s.copy(
                        revision = s.revision + 1,
                        nowPlaying = s.nowPlaying!!.copy(intervalStartTicks = null),
                        idleSinceTicks = clock.nowTicks(),
                    ),
                )
                StopResult.Continue
            }
        }
    }

    /**
     * Child stopped early (Back, Home) or a playback error occurred. Settles; keeps the unused
     * allowance or the One title approval and [positionTicks] for Resume.
     */
    @Synchronized
    fun onPlaybackStopped(token: PlaybackToken, positionTicks: Long): StopResult {
        val s = current
        if (!matches(s, token)) return StopResult.Stale
        return stopInternal(s as SessionState.Active, positionTicks)
    }

    /** Natural end of the title. */
    @Synchronized
    fun onPlaybackCompleted(token: PlaybackToken): StopResult {
        val s = current
        if (!matches(s, token)) return StopResult.Stale
        return when (val active = s as SessionState.Active) {
            is SessionState.OneTitle -> StopResult.Ended(end(active, EndReason.ONE_TITLE_COMPLETED))
            is SessionState.Timed -> stopInternal(active, 0L)
        }
    }

    /**
     * Idempotent deadline check. Safe to call from stale timers: it inspects the current state only.
     */
    @Synchronized
    fun checkDeadline(): EndCommand? {
        val s = current as? SessionState.Timed ?: return null
        return if (liveRemaining(s, clock.nowTicks()) <= 0L) end(s, EndReason.ALLOWANCE_USED) else null
    }

    /**
     * The UI completed a full catalogue check at [checkedRemainingTicks] and nothing fit.
     * Ignored if the session changed or gained time since.
     */
    @Synchronized
    fun reportNoEligibleTitles(sessionId: String, checkedRemainingTicks: Long): EndCommand? {
        val s = current as? SessionState.Timed ?: return null
        if (s.sessionId != sessionId || s.nowPlaying != null) return null
        if (s.settledRemainingTicks - GRACE > checkedRemainingTicks) return null
        return end(s, EndReason.NO_ELIGIBLE_TITLE)
    }

    /** Sign-off finished (or was skipped) and any standby request was made. */
    @Synchronized
    fun completeSignOff(sessionId: String): Boolean {
        val s = current as? SessionState.Ending ?: return false
        if (s.sessionId != sessionId) return false
        commitBestEffort(SessionState.Locked(s.revision + 1, s.reason))
        return true
    }

    // ---- Internals -------------------------------------------------------------------------

    private fun stopInternal(s: SessionState.Active, positionTicks: Long): StopResult = when (s) {
        is SessionState.Timed -> {
            val now = clock.nowTicks()
            val settled = settle(s, now)
            // Between titles the grace reserve doesn't count: once the allowance is used, it ends.
            if (settled.settledRemainingTicks <= GRACE) {
                StopResult.Ended(end(s, EndReason.ALLOWANCE_USED))
            } else {
                commitBestEffort(settled.copy(revision = s.revision + 1, nowPlaying = null, idleSinceTicks = now))
                StopResult.Continue
            }
        }
        is SessionState.OneTitle -> {
            // No position (stopped or failed before it got going) keeps the last saved stop point.
            val resume = positionTicks.takeIf { it > 0L } ?: s.resumePositionTicks
            commitBestEffort(
                s.copy(revision = s.revision + 1, nowPlaying = null, resumePositionTicks = resume, idleSinceTicks = clock.nowTicks()),
            )
            StopResult.Continue
        }
    }

    /**
     * Leftover time does not survive a reboot or a long idle while Kidfin wasn't running. A
     * missing idle time or a clock reading before it (impossible within one boot) also counts.
     */
    private fun isStale(s: SessionState.Active, now: Long): Boolean {
        if (s.bootId != bootId) return true
        val since = s.idleSinceTicks ?: return true
        return now < since || now - since >= SessionState.IDLE_LIMIT_TICKS
    }

    private fun end(s: SessionState.Active, reason: EndReason): EndCommand {
        val next = SessionState.Ending(s.sessionId, s.revision + 1, reason)
        // Playback must stop even if storage fails; restore() then fails closed anyway.
        commitBestEffort(next)
        return EndCommand(next.sessionId, next.revision, reason)
    }

    private fun admissionProblem(s: SessionState, item: MediaItem, accessConfirmed: Boolean): Rejection? {
        if (s !is SessionState.Active) return Rejection.NOT_ACTIVE
        if (s.nowPlaying?.isPlaying == true) return Rejection.ALREADY_PLAYING
        if (!accessConfirmed) return Rejection.ACCESS_DENIED
        return when (s) {
            is SessionState.Timed -> when {
                !RuntimeRules.isPlayable(item) -> Rejection.NOT_PLAYABLE
                !RuntimeRules.fits(item, s.settledRemainingTicks - GRACE) -> Rejection.DOES_NOT_FIT
                else -> null
            }
            is SessionState.OneTitle -> when {
                item.id != s.approvedItemId -> Rejection.NOT_APPROVED_ITEM
                !RuntimeRules.isApprovableForOneTitle(item) -> Rejection.NOT_PLAYABLE
                else -> null
            }
        }
    }

    private fun matches(s: SessionState, token: PlaybackToken): Boolean =
        s is SessionState.Active && s.sessionId == token.sessionId && s.nowPlaying?.playbackId == token.playbackId

    private fun liveRemaining(s: SessionState.Timed, now: Long): Long {
        val start = s.nowPlaying?.intervalStartTicks ?: return s.settledRemainingTicks
        return (s.settledRemainingTicks - elapsed(start, now)).coerceAtLeast(0L)
    }

    /** Closes the open interval (if any) at [now]. Revision is left for the caller to set. */
    private fun settle(s: SessionState.Timed, now: Long): SessionState.Timed {
        val np = s.nowPlaying ?: return s
        val start = np.intervalStartTicks ?: return s
        return s.copy(
            settledRemainingTicks = (s.settledRemainingTicks - elapsed(start, now)).coerceAtLeast(0L),
            nowPlaying = np.copy(intervalStartTicks = null),
        )
    }

    /** A monotonic clock should not go backwards; if it does, charge nothing rather than refund. */
    private fun elapsed(start: Long, now: Long): Long = (now - start).coerceAtLeast(0L)

    private fun commitCritical(next: SessionState): Boolean = try {
        store.save(next)
        mutableState.value = next
        true
    } catch (e: java.io.IOException) {
        false
    }

    private companion object {
        val GRACE = RuntimeRules.GRACE_TICKS
    }

    private fun commitBestEffort(next: SessionState): SessionState {
        try {
            store.save(next)
        } catch (e: java.io.IOException) {
            // In-memory state stays authoritative; restore() fails closed on what was last written.
        }
        mutableState.value = next
        return next
    }
}
