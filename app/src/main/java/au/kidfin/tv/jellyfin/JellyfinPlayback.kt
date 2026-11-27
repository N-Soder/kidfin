// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.jellyfin

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.serializer.toUUID

/** A stream the player can open. [url] may carry the token as a query parameter: never log it. */
data class ResolvedStream(
    val itemId: String,
    val url: String,
    val isHls: Boolean,
    val method: PlayMethod,
    val mediaSourceId: String?,
    val playSessionId: String?,
)

sealed interface ResolveResult {
    data class Ready(val stream: ResolvedStream) : ResolveResult
    data class Failed(val reason: String) : ResolveResult
}

/**
 * Asks the server how this device should play an item (PlaybackInfo with the probed device
 * profile), preferring direct play, then remux/direct stream, then transcode.
 */
class PlaybackResolver(
    private val connection: JellyfinConnection,
    private val profile: () -> DeviceProfile,
) {
    suspend fun resolve(itemId: String): ResolveResult = try {
        connection.call { api, config ->
            val uuid = itemId.toUUID()
            val info = api.mediaInfoApi.getPostedPlaybackInfo(
                itemId = uuid,
                data = PlaybackInfoDto(
                    userId = config.userId.toUUID(),
                    maxStreamingBitrate = DeviceProfiles.MAX_BITRATE,
                    deviceProfile = profile(),
                    // Subtitles are not offered in the MVP; never burn them in.
                    subtitleStreamIndex = -1,
                    enableDirectPlay = true,
                    enableDirectStream = true,
                    enableTranscoding = true,
                    allowVideoStreamCopy = true,
                    allowAudioStreamCopy = true,
                    autoOpenLiveStream = false,
                ),
            ).content
            info.errorCode?.let { return@call ResolveResult.Failed("server: $it") }
            val source = info.mediaSources.firstOrNull() ?: return@call ResolveResult.Failed("no media source")
            val base = api.baseUrl ?: return@call ResolveResult.Failed("no server")
            when {
                source.supportsDirectPlay -> ResolveResult.Ready(
                    ResolvedStream(
                        itemId = itemId,
                        // Static file; the token travels in the Authorization header, not the URL.
                        url = api.videoApi.getVideoStreamUrl(
                            itemId = uuid,
                            container = source.container?.substringBefore(','),
                            static = true,
                            mediaSourceId = source.id,
                            playSessionId = info.playSessionId,
                            deviceId = config.deviceId,
                        ),
                        isHls = false,
                        method = PlayMethod.DIRECT_PLAY,
                        mediaSourceId = source.id,
                        playSessionId = info.playSessionId,
                    ),
                )
                source.transcodingUrl != null -> ResolveResult.Ready(
                    ResolvedStream(
                        itemId = itemId,
                        url = base + source.transcodingUrl,
                        isHls = source.transcodingSubProtocol == MediaStreamProtocol.HLS,
                        method = if (source.supportsDirectStream) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE,
                        mediaSourceId = source.id,
                        playSessionId = info.playSessionId,
                    ),
                )
                else -> ResolveResult.Failed("no playable stream")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ResolveResult.Failed(if (e is NotConnectedException) "not connected" else "server unavailable")
    }
}

/**
 * Jellyfin play-state reports. Fire-and-forget on [scope] with a short timeout: reporting
 * never delays starting, pausing or the local cutoff. Stopping also ends any transcode
 * the server started for this PlaySessionId.
 */
class PlaybackReporter(private val connection: JellyfinConnection, private val scope: CoroutineScope) {
    fun started(s: ResolvedStream, positionTicks: Long) = send("start") {
        connection.call { api, _ ->
            api.sessionApi.reportPlaybackStart(
                PlaybackStartInfo(
                    canSeek = false,
                    itemId = s.itemId.toUUID(),
                    mediaSourceId = s.mediaSourceId,
                    isPaused = false,
                    isMuted = false,
                    positionTicks = positionTicks,
                    playMethod = s.method,
                    playSessionId = s.playSessionId,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        }
    }

    fun progress(s: ResolvedStream, positionTicks: Long, paused: Boolean) = send("progress") {
        connection.call { api, _ ->
            api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    canSeek = false,
                    itemId = s.itemId.toUUID(),
                    mediaSourceId = s.mediaSourceId,
                    isPaused = paused,
                    isMuted = false,
                    positionTicks = positionTicks,
                    playMethod = s.method,
                    playSessionId = s.playSessionId,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        }
    }

    fun stopped(s: ResolvedStream, positionTicks: Long, failed: Boolean) = send("stopped") {
        connection.call { api, _ ->
            api.sessionApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = s.itemId.toUUID(),
                    mediaSourceId = s.mediaSourceId,
                    positionTicks = positionTicks,
                    playSessionId = s.playSessionId,
                    failed = failed,
                ),
            )
        }
    }

    private fun send(what: String, block: suspend () -> Unit) {
        scope.launch {
            val ok = try {
                withTimeoutOrNull(REPORT_TIMEOUT_MS) { block() } != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) Log.i(TAG, "playback report '$what' not delivered")
        }
    }

    private companion object {
        const val TAG = "KidFin"
        const val REPORT_TIMEOUT_MS = 5_000L
    }
}
