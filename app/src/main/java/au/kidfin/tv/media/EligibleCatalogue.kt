// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

import kotlinx.coroutines.CancellationException

sealed interface CatalogueResult {
    /** [nextCursor] continues the scan for "More"; null when the catalogue was fully scanned. */
    data class Offer(val items: List<MediaItem>, val nextCursor: Int?) : CatalogueResult

    /** Every page was checked and no playable title fits. Only this result may end a session. */
    data object NoneFit : CatalogueResult

    /** Retryable. Never ends a session and never changes the allowance. */
    data class Failed(val message: String) : CatalogueResult
}

/**
 * Finds a small, stable selection of titles that fit the remaining allowance.
 * Not randomised or engagement-ranked: server order, a few at a time.
 */
class EligibleCatalogue(
    private val repository: MediaRepository,
    private val pageSize: Int = 50,
    private val offerSize: Int = 8,
    private val maxPages: Int = 200,
) {
    init {
        require(pageSize > 0 && offerSize > 0 && maxPages > 0)
    }

    /**
     * Scans from [cursor]. If nothing fits from a non-zero cursor, repeats a complete scan
     * from the start, so [CatalogueResult.NoneFit] always means every page was checked.
     */
    suspend fun find(libraryIds: Set<String>, remainingTicks: Long, cursor: Int = 0): CatalogueResult {
        if (libraryIds.isEmpty()) return CatalogueResult.NoneFit
        val result = scan(libraryIds, remainingTicks, cursor.coerceAtLeast(0))
        return if (result == CatalogueResult.NoneFit && cursor > 0) scan(libraryIds, remainingTicks, 0) else result
    }

    private suspend fun scan(libraryIds: Set<String>, remainingTicks: Long, start: Int): CatalogueResult {
        val found = ArrayList<MediaItem>(offerSize)
        var index = start
        repeat(maxPages) {
            val page = try {
                repository.page(libraryIds, index, pageSize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return CatalogueResult.Failed(e.message ?: "server unavailable")
            }
            for ((offset, item) in page.items.withIndex()) {
                if (item.libraryId !in libraryIds || !RuntimeRules.fits(item, remainingTicks)) continue
                found += item
                if (found.size == offerSize) {
                    val next = index + offset + 1
                    return CatalogueResult.Offer(found, next.takeIf { it < page.totalCount })
                }
            }
            index += page.items.size
            if (index >= page.totalCount) {
                return if (found.isEmpty()) CatalogueResult.NoneFit else CatalogueResult.Offer(found, null)
            }
            if (page.items.isEmpty()) return CatalogueResult.Failed("server returned an empty page before the end")
        }
        return CatalogueResult.Failed("catalogue too large to scan")
    }
}
