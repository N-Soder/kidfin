// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.jellyfin.ServerConfig
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaType
import au.kidfin.tv.media.RuntimeRules
import au.kidfin.tv.media.ShelfCodec
import au.kidfin.tv.media.ShelfContent
import au.kidfin.tv.media.ShelfOrdering
import au.kidfin.tv.media.ShelfSource
import au.kidfin.tv.media.TitleIndex
import au.kidfin.tv.media.TitleSearch
import au.kidfin.tv.session.MonotonicClock
import au.kidfin.tv.session.Ticks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Disk copy of the last shelf for an instant start. Cache only: anything unreadable is deleted
 * and ignored, and a failed write is ignored.
 */
internal class ShelfCache(private val file: File) {
    fun read(key: String): ShelfContent? = try {
        file.takeIf { it.exists() }?.readText()?.let { ShelfCodec.decode(it, key) }
    } catch (e: Exception) {
        file.delete()
        null
    }

    fun write(key: String, content: ShelfContent) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(ShelfCodec.encode(key, content))
            tmp.renameTo(file)
        }
    }
}

/**
 * The locked shelf: shows the disk copy at once (if it matches this account and these
 * libraries), then fetches a fresh one. Also the account's hearts and the title search index.
 */
internal class ShelfLoader(
    private val scope: CoroutineScope,
    private val source: ShelfSource,
    private val cache: ShelfCache,
    private val clock: MonotonicClock,
    /** Parent-approved libraries, read when needed (they change with setup). */
    private val allowedLibraryIds: () -> Set<String>,
    private val io: CoroutineDispatcher,
    private val compute: CoroutineDispatcher,
    private val log: (String) -> Unit,
    private val today: () -> Long,
) {
    private val mutableShelf = MutableStateFlow<ShelfState>(ShelfState.Loading)
    val shelf: StateFlow<ShelfState> = mutableShelf.asStateFlow()

    /** Titles (or whole series) hearted for the child account, by id. */
    private val mutableFavouriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favouriteIds: StateFlow<Set<String>> = mutableFavouriteIds.asStateFlow()

    private var job: Job? = null
    private var fetchedAtTicks: Long? = null
    private var stale = true

    @Volatile
    private var titleIndex: Pair<List<MediaItem>, TitleIndex>? = null

    /** Play counts, Keep watching or hearts changed: fetch again next time the shelf is shown. */
    fun markStale() {
        stale = true
    }

    /** Whether showing the shelf should start a load. */
    fun needsLoad(): Boolean = mutableShelf.value !is ShelfState.Ready || stale || isOld()

    private fun isOld(): Boolean {
        val at = fetchedAtTicks ?: return true
        return clock.nowTicks() - at > REFRESH_TICKS
    }

    /** Different account or libraries: drop what's shown and the disk copy's relevance. */
    fun reset() {
        job?.cancel()
        mutableShelf.value = ShelfState.Loading
        stale = true
    }

    /** One load at a time. [config] is null while Kidfin isn't fully set up. */
    fun load(config: ServerConfig?) {
        if (job?.isActive == true) return
        if (config == null) {
            // ShelfScreen shows the setup card instead.
            mutableShelf.value = ShelfState.Failed("Not connected.")
            return
        }
        val key = ShelfCodec.key(config.baseUrl, config.userId, config.libraryIds)
        val day = today()
        job = scope.launch {
            if (mutableShelf.value !is ShelfState.Ready) {
                withContext(io) { cache.read(key) }?.let {
                    show(it, day)
                    log("shelf shown from cache: ${it.titles.size} titles")
                }
            }
            val t0 = clock.nowTicks()
            try {
                val fresh = source.shelf(allowedLibraryIds())
                log("shelf fetched in ${(clock.nowTicks() - t0) / Ticks.PER_MILLISECOND} ms: ${fresh.titles.size} titles")
                // Same rule as everywhere: only titles from the libraries the parent chose.
                val allowed = allowedLibraryIds()
                val scoped = ShelfContent(
                    keepWatching = fresh.keepWatching.filter { it.libraryId in allowed && RuntimeRules.isPlayable(it) },
                    titles = fresh.titles.filter {
                        it.libraryId in allowed && (it.type == MediaType.SERIES || RuntimeRules.isPlayable(it))
                    },
                )
                show(scoped, day)
                fetchedAtTicks = clock.nowTicks()
                stale = false
                withContext(io) { cache.write(key, scoped) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("shelf fetch failed: ${e.javaClass.simpleName}")
                // Keep showing the cached shelf if there is one; play time is re-checked anyway.
                if (mutableShelf.value !is ShelfState.Ready) mutableShelf.value = ShelfState.Failed("Can't reach the videos right now.")
            }
        }
    }

    private fun show(content: ShelfContent, epochDay: Long) {
        val rows = ShelfOrdering.arrange(content, epochDay)
        mutableShelf.value = ShelfState.Ready(rows)
        mutableFavouriteIds.value = content.titles.filter { it.favourite }.map { it.id }.toSet()
        // Ready before the first key press in search.
        scope.launch(compute) { indexFor(rows.everything) }
    }

    /** Every title on the shown shelf (empty until it has loaded), for search. */
    fun titles(): List<MediaItem> = (mutableShelf.value as? ShelfState.Ready)?.rows?.everything.orEmpty()

    /** Those of [titles] matching [query]. */
    suspend fun search(titles: List<MediaItem>, query: String): List<MediaItem> =
        withContext(compute) { TitleSearch.search(indexFor(titles), query) }

    /** Search words for the shelf's titles, worked out once per shelf. */
    private fun indexFor(titles: List<MediaItem>): TitleIndex =
        titleIndex?.takeIf { it.first === titles }?.second ?: TitleSearch.index(titles).also { titleIndex = titles to it }

    /**
     * Hearts or un-hearts [item] (its whole series for an episode). Callers have checked the
     * grant. Optimistic; reverted and [onFailed] called if the server refuses.
     */
    fun toggleFavourite(item: MediaItem, onFailed: () -> Unit) {
        val id = item.seriesId ?: item.id
        val before = mutableFavouriteIds.value
        val add = id !in before
        mutableFavouriteIds.value = if (add) before + id else before - id
        scope.launch {
            try {
                source.setFavourite(item, add)
                stale = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableFavouriteIds.value = before
                onFailed()
            }
        }
    }

    private companion object {
        val REFRESH_TICKS = Ticks.ofMinutes(5)
    }
}
