// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

import au.kidfin.tv.session.ChecksummedFields.requireNonNegativeLong
import au.kidfin.tv.session.ChecksummedFields.requireText

/**
 * Versioned session file format. Anything unexpected is corrupt; the caller locks and
 * requires a parent rather than guessing.
 */
object SessionCodec {
    private const val VERSION = "1"

    /** Marker for "an interval was open when this was written". Monotonic values do not survive reboot. */
    const val OPEN_INTERVAL_UNKNOWN_START: Long = Long.MIN_VALUE

    fun encode(state: SessionState): String {
        val f = linkedMapOf("v" to VERSION, "rev" to state.revision.toString())
        when (state) {
            is SessionState.Locked -> {
                f["kind"] = "locked"
                state.lastEnd?.let { f["last_end"] = it.name }
            }
            is SessionState.Timed -> {
                f["kind"] = "timed"
                f["sid"] = state.sessionId
                f["remaining"] = state.settledRemainingTicks.toString()
                state.lastExtensionRequestId?.let { f["ext_req"] = it }
                putNowPlaying(f, state.nowPlaying)
                putActivity(f, state)
            }
            is SessionState.OneTitle -> {
                f["kind"] = "one_title"
                f["sid"] = state.sessionId
                f["item"] = state.approvedItemId
                f["title"] = state.approvedItemTitle
                f["resume"] = state.resumePositionTicks.toString()
                putNowPlaying(f, state.nowPlaying)
                putActivity(f, state)
            }
            is SessionState.Ending -> {
                f["kind"] = "ending"
                f["sid"] = state.sessionId
                f["reason"] = state.reason.name
            }
        }
        return ChecksummedFields.encode(f)
    }

    fun decode(text: String): SessionState {
        val f = ChecksummedFields.decode(text)
        if (f["v"] != VERSION) throw CorruptStateException("unsupported version")
        val revision = f.requireNonNegativeLong("rev")
        return when (f["kind"]) {
            "locked" -> SessionState.Locked(revision, f["last_end"]?.let(::reason))
            "timed" -> SessionState.Timed(
                sessionId = f.requireText("sid"),
                revision = revision,
                settledRemainingTicks = f.requireNonNegativeLong("remaining"),
                nowPlaying = nowPlaying(f),
                lastExtensionRequestId = f["ext_req"],
                bootId = f.requireText("boot"),
                idleSinceTicks = idleSince(f),
            )
            "one_title" -> SessionState.OneTitle(
                sessionId = f.requireText("sid"),
                revision = revision,
                approvedItemId = f.requireText("item"),
                approvedItemTitle = f.requireText("title"),
                resumePositionTicks = f.requireNonNegativeLong("resume"),
                nowPlaying = nowPlaying(f),
                bootId = f.requireText("boot"),
                idleSinceTicks = idleSince(f),
            )
            "ending" -> SessionState.Ending(f.requireText("sid"), revision, reason(f.requireText("reason")))
            else -> throw CorruptStateException("unknown kind")
        }
    }

    private fun putNowPlaying(f: MutableMap<String, String>, nowPlaying: NowPlaying?) {
        if (nowPlaying == null) return
        f["np_item"] = nowPlaying.itemId
        f["np_pid"] = nowPlaying.playbackId.toString()
        f["np_open"] = if (nowPlaying.isPlaying) "1" else "0"
    }

    /** Required for active sessions: a file without them (older or damaged) reads as corrupt. */
    private fun putActivity(f: MutableMap<String, String>, state: SessionState.Active) {
        f["boot"] = state.bootId
        f["idle"] = state.idleSinceTicks?.toString() ?: PLAYING
    }

    private fun idleSince(f: Map<String, String>): Long? =
        if (f.requireText("idle") == PLAYING) null else f.requireNonNegativeLong("idle")

    private const val PLAYING = "playing"

    private fun nowPlaying(f: Map<String, String>): NowPlaying? {
        val item = f["np_item"] ?: return null
        val open = when (f["np_open"]) {
            "1" -> true
            "0" -> false
            else -> throw CorruptStateException("bad np_open")
        }
        return NowPlaying(item, f.requireNonNegativeLong("np_pid"), if (open) OPEN_INTERVAL_UNKNOWN_START else null)
    }

    private fun reason(name: String): EndReason =
        EndReason.entries.firstOrNull { it.name == name } ?: throw CorruptStateException("unknown reason")
}
