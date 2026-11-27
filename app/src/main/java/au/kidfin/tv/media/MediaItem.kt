// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

import au.kidfin.tv.session.Ticks

enum class MediaType { MOVIE, EPISODE, VIDEO, SERIES, OTHER }

data class MediaItem(
    val id: String,
    val title: String,
    val type: MediaType,
    /** Item-level runtime in ticks; never a series-level value. Null when unknown. */
    val runtimeTicks: Long?,
    val libraryId: String,
    val seriesName: String? = null,
    val imageUrl: String? = null,
    /** Portrait 2:3 art (movie poster, or the series poster for an episode). */
    val posterUrl: String? = null,
    val year: Int? = null,
    /** Times the child account has played it (summed over episodes for a series). */
    val plays: Int = 0,
    /** For episodes and series cards: the series this belongs to. */
    val seriesId: String? = null,
    /** 0..1 when partly watched, for the Keep watching bar. */
    val progress: Float? = null,
    /** Hearted for the child account in Jellyfin: always in Favourites. */
    val favourite: Boolean = false,
    /** Episodes only: season and episode numbers, and whether it's been watched. */
    val season: Int? = null,
    val episode: Int? = null,
    val watched: Boolean = false,
    /** Where the child account left off, when partly watched; playback resumes here. */
    val resumeTicks: Long? = null,
)

data class MediaLibrary(val id: String, val name: String)

object RuntimeRules {
    private val playableTypes = setOf(MediaType.MOVIE, MediaType.EPISODE, MediaType.VIDEO)

    /** Playable leaf item with a usable runtime. Missing, zero or negative runtimes are excluded. */
    fun isPlayable(item: MediaItem): Boolean =
        item.type in playableTypes && (item.runtimeTicks ?: 0L) > 0L

    /** Kind of item a parent may approve for One title (runtime does not matter there). */
    fun isApprovableForOneTitle(item: MediaItem): Boolean = item.type in playableTypes

    /**
     * How long the last title of a timed session may run past the allowance (a small reserve instead of
     * an exact fit, so a title a minute or two over still fits).
     */
    val GRACE_TICKS: Long = Ticks.ofMinutes(3)

    /** What's left to watch: the full runtime, or the rest of it from where it was left off. */
    fun ticksToWatch(item: MediaItem): Long {
        val runtime = item.runtimeTicks ?: return 0L
        val resume = item.resumeTicks?.takeIf { it in 1 until runtime } ?: 0L
        return runtime - resume
    }

    /**
     * Eligible when what's left to watch is within the remaining allowance plus [GRACE_TICKS],
     * compared in ticks. [remainingTicks] may be negative (into the grace already).
     */
    fun fits(item: MediaItem, remainingTicks: Long): Boolean =
        isPlayable(item) && ticksToWatch(item) <= remainingTicks + GRACE_TICKS
}

/** Child-facing labels. Runtimes round up; remaining time rounds down. Never used for eligibility. */
object DurationLabels {
    fun runtime(ticks: Long): String {
        val minutes = Math.floorDiv(ticks + Ticks.PER_MINUTE - 1, Ticks.PER_MINUTE)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    fun remaining(ticks: Long): String {
        if (ticks < Ticks.PER_MINUTE) return "Less than 1 min left"
        val minutes = ticks / Ticks.PER_MINUTE
        return "$minutes min left"
    }

    fun clock(ticks: Long): String {
        val totalSeconds = ticks.coerceAtLeast(0) / Ticks.PER_SECOND
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
