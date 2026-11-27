// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.jellyfin

import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.TranscodingProfile

/**
 * What this device can decode (or pass through to the TV), probed at runtime. Codec names
 * are Jellyfin's ("h264", "hevc", "aac", "truehd", ...).
 */
data class CodecSupport(
    val videoCodecs: Set<String>,
    val audioCodecs: Set<String>,
    val hevc10Bit: Boolean,
    val dolbyVision: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
)

/**
 * Builds the Jellyfin device profile from probed capabilities. Anything not listed for direct
 * play makes the server remux or transcode (video copied where possible, so a TrueHD movie
 * gets an audio-only transcode). Subtitles are not offered in the MVP.
 */
object DeviceProfiles {
    const val MAX_BITRATE = 120_000_000

    private val CONTAINERS = listOf("mp4", "m4v", "mov", "mkv", "webm")

    fun build(caps: CodecSupport): DeviceProfile {
        val video = caps.videoCodecs.intersect(KNOWN_VIDEO).sortedBy { KNOWN_VIDEO.indexOf(it) }
        val audio = caps.audioCodecs.intersect(KNOWN_AUDIO).sortedBy { KNOWN_AUDIO.indexOf(it) }
        val transcodeVideo = listOf("hevc", "h264").filter { it in video }.ifEmpty { listOf("h264") }
        val transcodeAudio = listOf("aac", "ac3", "eac3", "mp3").filter { it in audio }.ifEmpty { listOf("aac") }

        return DeviceProfile(
            name = "Kidfin",
            maxStreamingBitrate = MAX_BITRATE,
            maxStaticBitrate = MAX_BITRATE,
            directPlayProfiles = listOf(
                DirectPlayProfile(
                    container = CONTAINERS.joinToString(","),
                    videoCodec = video.joinToString(","),
                    audioCodec = audio.joinToString(","),
                    type = DlnaProfileType.VIDEO,
                ),
            ),
            transcodingProfiles = listOf(
                TranscodingProfile(
                    container = "ts",
                    type = DlnaProfileType.VIDEO,
                    videoCodec = transcodeVideo.joinToString(","),
                    audioCodec = transcodeAudio.joinToString(","),
                    protocol = MediaStreamProtocol.HLS,
                    context = EncodingContext.STREAMING,
                    copyTimestamps = false,
                    minSegments = 1,
                    breakOnNonKeyFrames = false,
                    maxAudioChannels = "6",
                    conditions = emptyList(),
                ),
            ),
            containerProfiles = emptyList(),
            codecProfiles = buildList {
                add(videoLimits("h264", 8, caps, sdrOnly = true))
                if ("hevc" in video) add(videoLimits("hevc", if (caps.hevc10Bit) 10 else 8, caps, sdrOnly = !caps.hevc10Bit))
            },
            subtitleProfiles = emptyList(),
        )
    }

    private fun videoLimits(codec: String, bitDepth: Int, caps: CodecSupport, sdrOnly: Boolean): CodecProfile {
        val ranges = when {
            sdrOnly -> "SDR"
            // Dolby Vision without a fallback layer shows wrong colours on non-DV decoders.
            caps.dolbyVision -> "SDR|HDR10|HDR10Plus|HLG|DOVI|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR"
            else -> "SDR|HDR10|HDR10Plus|HLG|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR"
        }
        return CodecProfile(
            type = CodecType.VIDEO,
            codec = codec,
            applyConditions = emptyList(),
            conditions = listOf(
                condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_BIT_DEPTH, bitDepth.toString()),
                condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, caps.maxWidth.toString()),
                condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.HEIGHT, caps.maxHeight.toString()),
                condition(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_RANGE_TYPE, ranges),
            ),
        )
    }

    private fun condition(type: ProfileConditionType, property: ProfileConditionValue, value: String) =
        ProfileCondition(condition = type, property = property, value = value, isRequired = false)

    val KNOWN_VIDEO = listOf("h264", "hevc", "vp9", "av1")
    val KNOWN_AUDIO = listOf("aac", "mp3", "ac3", "eac3", "opus", "flac", "vorbis", "truehd", "dts")
}
