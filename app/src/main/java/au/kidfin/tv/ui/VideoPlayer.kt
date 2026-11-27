// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import au.kidfin.tv.jellyfin.PlaybackReporter
import au.kidfin.tv.jellyfin.PlaybackResolver
import au.kidfin.tv.jellyfin.ResolveResult
import au.kidfin.tv.jellyfin.ResolvedStream
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.session.Ticks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** PREPARING (resolving, buffering) and PAUSED are free; only PLAYING is charged. */
enum class PlayerPhase { IDLE, PREPARING, PLAYING, PAUSED, ENDED, FAILED }

data class PlayerSnapshot(
    val phase: PlayerPhase = PlayerPhase.IDLE,
    val positionTicks: Long = 0L,
    val durationTicks: Long = 0L,
)

interface VideoPlayer {
    val snapshot: StateFlow<PlayerSnapshot>

    /** The Media3 player to render, while one exists. */
    val surface: StateFlow<Player?>

    fun start(item: MediaItem, fromTicks: Long)
    fun togglePause()

    /** Stops and releases; returns the last position. */
    fun stop(): Long
}

/**
 * Media3 ExoPlayer over Jellyfin. One ExoPlayer per title, created on start and released on
 * stop. Main thread only. Reports phase edges exactly as the session engine expects.
 */
@OptIn(UnstableApi::class)
class ExoVideoPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val resolver: PlaybackResolver,
    private val reporter: PlaybackReporter,
    private val http: OkHttpClient,
) : VideoPlayer {
    private val state = MutableStateFlow(PlayerSnapshot())
    override val snapshot: StateFlow<PlayerSnapshot> = state.asStateFlow()
    private val mutableSurface = MutableStateFlow<Player?>(null)
    override val surface: StateFlow<Player?> = mutableSurface.asStateFlow()

    private var exo: ExoPlayer? = null
    private var stream: ResolvedStream? = null
    private var job: Job? = null
    private var reportedStart = false
    private var generation = 0

    override fun start(item: MediaItem, fromTicks: Long) {
        release(report = true)
        val gen = ++generation
        val duration = item.runtimeTicks ?: 0L
        state.value = PlayerSnapshot(PlayerPhase.PREPARING, fromTicks.coerceAtLeast(0L), duration)
        job = scope.launch {
            val resolved = resolver.resolve(item.id)
            if (gen != generation) return@launch
            when (resolved) {
                is ResolveResult.Failed -> {
                    Log.i(TAG, "playback not resolved: ${resolved.reason}")
                    state.value = state.value.copy(phase = PlayerPhase.FAILED)
                }
                is ResolveResult.Ready -> open(resolved.stream, fromTicks, gen)
            }
        }
    }

    private fun open(s: ResolvedStream, fromTicks: Long, gen: Int) {
        Log.i(TAG, "playback method: ${s.method}")
        stream = s
        reportedStart = false
        val dataSource = OkHttpDataSource.Factory(http)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setUri(s.url)
            .apply { if (s.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (gen == generation) publish(p)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Code name only: messages can contain the stream URL and token.
                Log.i(TAG, "player error: ${error.errorCodeName}")
            }
        })
        player.setMediaItem(mediaItem, fromTicks / Ticks.PER_MILLISECOND)
        player.playWhenReady = true
        player.prepare()
        exo = player
        mutableSurface.value = player
        job = scope.launch {
            var sinceReport = 0L
            while (isActive && gen == generation) {
                delay(POLL_MS)
                val p = exo ?: break
                publish(p)
                sinceReport += POLL_MS
                if (sinceReport >= PROGRESS_MS && reportedStart) {
                    sinceReport = 0
                    reporter.progress(s, state.value.positionTicks, paused = state.value.phase != PlayerPhase.PLAYING)
                }
            }
        }
    }

    private fun publish(p: Player) {
        val position = p.currentPosition.coerceAtLeast(0L) * Ticks.PER_MILLISECOND
        val phase = when {
            p.playerError != null -> PlayerPhase.FAILED
            p.playbackState == Player.STATE_ENDED -> PlayerPhase.ENDED
            p.isPlaying -> PlayerPhase.PLAYING
            p.playbackState == Player.STATE_READY && !p.playWhenReady -> PlayerPhase.PAUSED
            // Buffering, or ready but suppressed (e.g. audio focus lost): not charged.
            p.playbackState == Player.STATE_READY -> PlayerPhase.PAUSED
            else -> PlayerPhase.PREPARING
        }
        if (phase == PlayerPhase.PLAYING && !reportedStart) {
            reportedStart = true
            stream?.let { reporter.started(it, position) }
        }
        val current = state.value
        val keepDuration = current.durationTicks.takeIf { it > 0 } ?: (p.duration.takeIf { it > 0 } ?: 0L) * Ticks.PER_MILLISECOND
        state.value = PlayerSnapshot(phase, if (phase == PlayerPhase.ENDED) maxOf(position, keepDuration) else position, keepDuration)
    }

    override fun togglePause() {
        val p = exo ?: return
        p.playWhenReady = !p.playWhenReady
    }

    override fun stop(): Long {
        val position = exo?.currentPosition?.coerceAtLeast(0L)?.times(Ticks.PER_MILLISECOND) ?: state.value.positionTicks
        val failed = state.value.phase == PlayerPhase.FAILED
        release(report = true, failed = failed, position = position)
        state.value = PlayerSnapshot()
        return position
    }

    private fun release(report: Boolean, failed: Boolean = false, position: Long? = null) {
        generation++
        job?.cancel()
        job = null
        val p = exo
        exo = null
        mutableSurface.value = null
        val s = stream
        stream = null
        val at = position ?: p?.currentPosition?.coerceAtLeast(0L)?.times(Ticks.PER_MILLISECOND) ?: 0L
        p?.release()
        if (report && s != null) reporter.stopped(s, at, failed)
    }

    private companion object {
        const val TAG = "KidFin"
        const val POLL_MS = 250L
        const val PROGRESS_MS = 10_000L
    }
}
