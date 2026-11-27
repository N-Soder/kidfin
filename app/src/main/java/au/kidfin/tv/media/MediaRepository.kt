// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

/** Source of curated media. */
interface MediaRepository {
    suspend fun libraries(): List<MediaLibrary>

    /** Recursive playable items in the given libraries, stably ordered. */
    suspend fun page(libraryIds: Set<String>, startIndex: Int, limit: Int): MediaPage

    /** Fresh lookup used at play time. Null if missing or not visible to the child account. */
    suspend fun item(itemId: String): MediaItem?
}

data class MediaPage(val items: List<MediaItem>, val totalCount: Int)

class MediaUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Re-checks that an item exists for the child account and sits in a permitted library. */
class AccessChecker(private val repository: MediaRepository) {
    suspend fun check(itemId: String, allowedLibraryIds: Set<String>): MediaItem? {
        val item = repository.item(itemId) ?: return null
        return item.takeIf { it.libraryId in allowedLibraryIds }
    }
}
