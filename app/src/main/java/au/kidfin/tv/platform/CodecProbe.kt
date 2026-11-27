// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.platform

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import au.kidfin.tv.jellyfin.CodecSupport

/** Probes the real decoders and the TV's passthrough formats; never assumes a codec set. */
object CodecProbe {
    private val VIDEO = mapOf(
        MediaFormat.MIMETYPE_VIDEO_AVC to "h264",
        MediaFormat.MIMETYPE_VIDEO_HEVC to "hevc",
        MediaFormat.MIMETYPE_VIDEO_VP9 to "vp9",
        // Literal: the constant is API 29+, the MIME string is the same on every version.
        "video/av01" to "av1",
    )
    private val AUDIO = mapOf(
        MediaFormat.MIMETYPE_AUDIO_AAC to "aac",
        MediaFormat.MIMETYPE_AUDIO_MPEG to "mp3",
        MediaFormat.MIMETYPE_AUDIO_AC3 to "ac3",
        MediaFormat.MIMETYPE_AUDIO_EAC3 to "eac3",
        MediaFormat.MIMETYPE_AUDIO_OPUS to "opus",
        MediaFormat.MIMETYPE_AUDIO_FLAC to "flac",
        MediaFormat.MIMETYPE_AUDIO_VORBIS to "vorbis",
    )

    @OptIn(UnstableApi::class)
    fun probe(context: Context): CodecSupport {
        val video = HashSet<String>()
        val audio = HashSet<String>()
        var hevc10 = false
        var dolbyVision = false
        var maxW = 1920
        var maxH = 1080
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                val mime = type.lowercase()
                VIDEO[mime]?.let { codec ->
                    video += codec
                    val caps = info.getCapabilitiesForType(type)
                    caps.videoCapabilities?.let { v ->
                        maxW = maxOf(maxW, v.supportedWidths.upper)
                        maxH = maxOf(maxH, v.supportedHeights.upper)
                    }
                    if (codec == "hevc" && caps.profileLevels.any {
                            it.profile == CodecProfileLevel.HEVCProfileMain10 ||
                                it.profile == CodecProfileLevel.HEVCProfileMain10HDR10
                        }
                    ) {
                        hevc10 = true
                    }
                }
                AUDIO[mime]?.let { audio += it }
                if (mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) dolbyVision = true
            }
        }
        // Formats the TV or soundbar accepts over HDMI, played as passthrough.
        val passthrough = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
        if (passthrough.supportsEncoding(C.ENCODING_AC3)) audio += "ac3"
        if (passthrough.supportsEncoding(C.ENCODING_E_AC3) || passthrough.supportsEncoding(C.ENCODING_E_AC3_JOC)) audio += "eac3"
        if (passthrough.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) audio += "truehd"
        if (passthrough.supportsEncoding(C.ENCODING_DTS)) audio += "dts"
        return CodecSupport(video, audio, hevc10, dolbyVision, maxW.coerceAtMost(3840), maxH.coerceAtMost(2160))
    }
}
