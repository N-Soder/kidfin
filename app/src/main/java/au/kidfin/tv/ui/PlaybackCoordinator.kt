// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.session.Admission
import au.kidfin.tv.session.EndCommand
import au.kidfin.tv.session.PlaybackStart
import au.kidfin.tv.session.PlaybackToken
import au.kidfin.tv.session.PlayingResult
import au.kidfin.tv.session.SessionController
import au.kidfin.tv.session.StopResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Between the player and the session engine: the access re-check and admission before a title
 * starts, the deadline ticker while it plays, and what each player phase means for the
 * session. Where to go next is the [Host]'s business.
 */
internal class PlaybackCoordinator(
    private val scope: CoroutineScope,
    private val controller: SessionController,
    val player: VideoPlayer,
    /** Fresh server lookup: the item, if it still exists in a permitted library. */
    private val checkAccess: suspend (String) -> MediaItem?,
    private val host: Host,
    private val log: (String) -> Unit,
) {
    interface Host {
        /** Kidfin is on screen. Nothing starts playing behind another app. */
        val foreground: Boolean

        fun notice(text: String)

        /** The player has been started: show it. */
        fun playerStarted()

        /** The engine refused the title: it doesn't fit right now. */
        fun playDenied()

        /** Stopped and the session carries on: back to the child's screen, with [problem] if unplanned. */
        fun playbackStopped(problem: String?)

        /** Playback is stopped and the session persisted as ending. */
        fun ending(end: EndCommand)

        /** The title just approved never got going and its approval was withdrawn. */
        fun approvalWithdrawn()

        /** Every tick while playing, for the time-left label. */
        fun tick()
    }

    private val mutableNowPlaying = MutableStateFlow<MediaItem?>(null)
    val nowPlaying: StateFlow<MediaItem?> = mutableNowPlaying.asStateFlow()

    private var token: PlaybackToken? = null
    private var lastPhase = PlayerPhase.IDLE
    private var ticker: Job? = null

    /** Access check and start of a title; cancelled when the app goes to the background. */
    private var startJob: Job? = null

    /** Session just approved whose first title hasn't played yet: withdrawn if that title fails. */
    private var unplayedSessionId: String? = null

    /** A title is being checked or is playing: further starts are ignored. */
    val busy: Boolean get() = token != null || startJob?.isActive == true

    fun attach() {
        scope.launch { player.snapshot.collect(::onSnapshot) }
    }

    /** Runs [block] as the start in progress, so going to the background cancels it. */
    fun launchStart(block: suspend () -> Unit) {
        startJob = scope.launch { block() }
    }

    fun play(itemId: String) {
        if (busy) return
        launchStart { startChecked(itemId) }
    }

    /** The approval of [sessionId] stands only once its first title actually plays. */
    fun watchFirstStart(sessionId: String?) {
        unplayedSessionId = sessionId
    }

    /**
     * Starts the player if the title is (still) allowed. [prechecked] is a fresh access check
     * the caller just made. True once the player has been started.
     */
    suspend fun startChecked(itemId: String, prechecked: MediaItem? = null): Boolean {
        // Fresh server lookup: the item must still exist and be in a permitted library.
        val checked = prechecked ?: checkAccess(itemId)
        // Home while checking: never start playing behind another app.
        if (!host.foreground) return false
        if (checked == null) {
            host.notice(MainViewModel.UNAVAILABLE)
            return false
        }
        val granted = when (val r = controller.beginPlayback(checked, accessConfirmed = true)) {
            is PlaybackStart.Granted -> r
            is PlaybackStart.Denied -> {
                host.notice(DOES_NOT_FIT)
                log("play denied: ${r.reason}")
                host.playDenied()
                return false
            }
        }
        if (controller.revalidate(granted.token, checked, accessConfirmed = true) != Admission.Allowed) {
            controller.onPlaybackStopped(granted.token, 0L)
            host.notice(DOES_NOT_FIT)
            return false
        }
        token = granted.token
        mutableNowPlaying.value = checked
        lastPhase = PlayerPhase.IDLE
        host.playerStarted()
        player.start(checked, granted.resumeFromTicks)
        startTicker()
        return true
    }

    fun togglePause() = player.togglePause()

    /** Back during playback: stop, keep unused time or the One title position. */
    fun stop() {
        val t = token ?: return
        unplayedSessionId = null
        val position = player.stop()
        clear()
        finish(controller.onPlaybackStopped(t, position), problem = null)
    }

    /** Stops the player without telling the engine (the caller already has). */
    fun stopQuietly() {
        if (token != null) player.stop()
        clear()
    }

    /** Home or another app: drop a start in progress and stop playback. The engine's answer, if playing. */
    fun onAppStopped(): StopResult? {
        startJob?.cancel()
        startJob = null
        val t = token ?: return null
        val position = player.stop()
        clear()
        return controller.onPlaybackStopped(t, position)
    }

    fun release() {
        player.stop()
    }

    private fun onSnapshot(snapshot: PlayerSnapshot) {
        val t = token ?: return
        val phase = snapshot.phase
        if (phase == lastPhase) return
        val previous = lastPhase
        lastPhase = phase
        when (phase) {
            PlayerPhase.PLAYING -> when (val r = controller.onPlaying(t)) {
                is PlayingResult.Playing -> unplayedSessionId = null
                is PlayingResult.Ended -> {
                    stopQuietly()
                    host.ending(r.command)
                }
                PlayingResult.StorageFailed, PlayingResult.Stale -> {
                    val position = player.stop()
                    clear()
                    finish(controller.onPlaybackStopped(t, position), "Something went wrong.")
                }
            }
            PlayerPhase.PAUSED, PlayerPhase.PREPARING -> if (previous == PlayerPhase.PLAYING) {
                (controller.onNotPlaying(t) as? StopResult.Ended)?.let {
                    stopQuietly()
                    host.ending(it.command)
                }
            }
            PlayerPhase.ENDED -> {
                unplayedSessionId = null
                player.stop()
                clear()
                finish(controller.onPlaybackCompleted(t), problem = null)
            }
            PlayerPhase.FAILED -> {
                val position = player.stop()
                clear()
                if (previous == PlayerPhase.PLAYING) log("playback failed after starting")
                val unplayed = unplayedSessionId
                unplayedSessionId = null
                if (unplayed != null && unplayed == t.sessionId && controller.cancelStart(unplayed)) {
                    // The title just approved never got going: withdraw the approval rather
                    // than leave approved time with nothing to watch.
                    host.approvalWithdrawn()
                    return
                }
                // Playback errors keep the allowance (nothing was charged while not playing)
                // and, for One title, the approval and position.
                finish(controller.onPlaybackStopped(t, position), "That video won't play right now.")
            }
            PlayerPhase.IDLE -> Unit
        }
    }

    private fun finish(result: StopResult, problem: String?) = when (result) {
        is StopResult.Ended -> host.ending(result.command)
        StopResult.Continue, StopResult.Stale -> host.playbackStopped(problem)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && token != null) {
                controller.checkDeadline()?.let { end ->
                    stopQuietly()
                    host.ending(end)
                    return@launch
                }
                host.tick()
                delay(TICK_MS)
            }
        }
    }

    private fun clear() {
        token = null
        lastPhase = PlayerPhase.IDLE
        mutableNowPlaying.value = null
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val TICK_MS = 250L
        const val DOES_NOT_FIT = "That one doesn't fit right now."
    }
}
