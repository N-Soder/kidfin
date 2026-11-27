// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

/**
 * Everything the locked shelf needs, fetched in one go so it can scroll without paging.
 * [titles] holds movies, videos and one entry per series (type SERIES), with Kids-account
 * play counts in [MediaItem.plays]. [keepWatching] is resumable items, then next-up episodes.
 */
data class ShelfContent(val keepWatching: List<MediaItem>, val titles: List<MediaItem>)

/** The shelf as shown: three sections, top to bottom. Empty sections are hidden. */
data class ShelfRows(
    val keepWatching: List<MediaItem>,
    val favourites: List<MediaItem>,
    val everything: List<MediaItem>,
)

data class SeriesEpisodes(val episodes: List<MediaItem>, val nextUpId: String?)

interface ShelfSource {
    /** Titles in the given libraries only, as the child account. */
    suspend fun shelf(libraryIds: Set<String>): ShelfContent

    /** The episode to play when a series poster is picked: next up, else the first episode. */
    suspend fun nextEpisode(series: MediaItem): MediaItem?

    /** All episodes of a series in order, and which one is next up. */
    suspend fun episodes(series: MediaItem): SeriesEpisodes

    /** Hearts (or un-hearts) a title for the child account. Parent action only. */
    suspend fun setFavourite(item: MediaItem, favourite: Boolean)
}

/**
 * Shelf ordering (not server order, not engagement-ranked):
 * Keep watching, then Favourites (hearted in Jellyfin for the child account, then by play
 * count), then everything in a daily shuffle.
 */
object ShelfOrdering {
    const val ROW = 7

    /** Up to two lines: hearted ones first, then most played. */
    const val FAVOURITES = ROW * 2

    fun arrange(content: ShelfContent, epochDay: Long): ShelfRows {
        val titles = dedupe(content.titles)
        val keep = content.keepWatching
            .distinctBy { it.seriesId ?: it.id }
            .take(ROW)
        val favourites = titles
            .filter { it.favourite || it.plays > 0 }
            .sortedWith(
                compareByDescending<MediaItem> { it.favourite }
                    .thenByDescending { it.plays }
                    .thenBy { it.title.lowercase() },
            )
            .take(FAVOURITES)
        return ShelfRows(keep, favourites, dailyShuffle(titles, epochDay))
    }

    /**
     * Same title, year and kind counts as one title (e.g. a leftover partial copy); the
     * longest copy wins. Different years stay separate (Dumbo 1941 and 2019).
     */
    fun dedupe(items: List<MediaItem>): List<MediaItem> =
        items.groupBy { Triple(it.title.trim().lowercase(), it.year, it.type == MediaType.SERIES) }
            .values
            .map { copies -> copies.maxWith(compareBy<MediaItem> { it.runtimeTicks ?: 0L }.thenBy { it.id }) }

    /** Stable for a whole day, different the next day. Independent of server order. */
    fun dailyShuffle(items: List<MediaItem>, epochDay: Long): List<MediaItem> =
        items.sortedBy { it.id }.shuffled(kotlin.random.Random(epochDay))
}
