// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

import au.kidfin.tv.session.Ticks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListRepository(private val all: List<MediaItem>) : MediaRepository {
    var calls = 0
    var failAt: Int? = null

    override suspend fun libraries() = listOf(MediaLibrary("kids", "Kids"))

    override suspend fun page(libraryIds: Set<String>, startIndex: Int, limit: Int): MediaPage {
        calls++
        if (failAt == startIndex) throw MediaUnavailableException("down")
        val visible = all.filter { it.libraryId in libraryIds }
        return MediaPage(visible.drop(startIndex).take(limit), visible.size)
    }

    override suspend fun item(itemId: String) = all.firstOrNull { it.id == itemId }
}

class EligibleCatalogueTest {
    private fun ep(id: String, minutes: Int, lib: String = "kids") =
        MediaItem(id, id, MediaType.EPISODE, Ticks.ofMinutes(minutes), lib)

    private val kids = setOf("kids")

    @Test
    fun offersOnlyFittingItemsInServerOrder() = runTest {
        val repo = ListRepository(listOf(ep("a", 30), ep("b", 10), ep("c", 15), ep("d", 19)))
        val r = EligibleCatalogue(repo, pageSize = 2, offerSize = 8).find(kids, Ticks.ofMinutes(15))
        assertEquals(CatalogueResult.Offer(listOf(ep("b", 10), ep("c", 15)), null), r)
    }

    @Test
    fun noneFitOnlyAfterScanningEveryPage() = runTest {
        val repo = ListRepository(List(10) { ep("x$it", 40) })
        val r = EligibleCatalogue(repo, pageSize = 3).find(kids, Ticks.ofMinutes(15))
        assertEquals(CatalogueResult.NoneFit, r)
        assertEquals(4, repo.calls)
    }

    @Test
    fun lateFittingItemIsFound() = runTest {
        val repo = ListRepository(List(10) { ep("x$it", 40) } + ep("late", 5))
        val r = EligibleCatalogue(repo, pageSize = 3).find(kids, Ticks.ofMinutes(15))
        assertEquals(CatalogueResult.Offer(listOf(ep("late", 5)), null), r)
    }

    @Test
    fun serverErrorIsRetryableNotNoneFit() = runTest {
        val repo = ListRepository(List(10) { ep("x$it", 40) }).apply { failAt = 6 }
        val r = EligibleCatalogue(repo, pageSize = 3).find(kids, Ticks.ofMinutes(15))
        assertTrue(r is CatalogueResult.Failed)
    }

    @Test
    fun paginatesWithCursorAndWrapsForCompleteCheck() = runTest {
        val repo = ListRepository(List(5) { ep("e$it", 5) })
        val cat = EligibleCatalogue(repo, pageSize = 2, offerSize = 2)
        val first = cat.find(kids, Ticks.ofMinutes(15)) as CatalogueResult.Offer
        assertEquals(listOf("e0", "e1"), first.items.map { it.id })
        val second = cat.find(kids, Ticks.ofMinutes(15), first.nextCursor!!) as CatalogueResult.Offer
        assertEquals(listOf("e2", "e3"), second.items.map { it.id })
        val third = cat.find(kids, Ticks.ofMinutes(15), second.nextCursor!!) as CatalogueResult.Offer
        assertEquals(listOf("e4"), third.items.map { it.id })
        assertEquals(null, third.nextCursor)
    }

    @Test
    fun cursorPastFittingItemsRescansFromStart() = runTest {
        val repo = ListRepository(listOf(ep("fit", 5), ep("big1", 50), ep("big2", 50)))
        val r = EligibleCatalogue(repo, pageSize = 2).find(kids, Ticks.ofMinutes(15), cursor = 1)
        assertEquals(CatalogueResult.Offer(listOf(ep("fit", 5)), null), r)
    }

    @Test
    fun itemsOutsidePermittedLibrariesAreIgnoredEvenIfReturned() = runTest {
        val leaky = object : MediaRepository {
            override suspend fun libraries() = emptyList<MediaLibrary>()
            override suspend fun page(libraryIds: Set<String>, startIndex: Int, limit: Int) =
                MediaPage(listOf(ep("adult", 5, lib = "grownups")), 1)
            override suspend fun item(itemId: String): MediaItem? = null
        }
        assertEquals(CatalogueResult.NoneFit, EligibleCatalogue(leaky).find(kids, Ticks.ofMinutes(15)))
    }

    @Test
    fun accessCheckerRequiresPermittedLibrary() = runTest {
        val repo = ListRepository(listOf(ep("k", 5), ep("g", 5, lib = "grownups")))
        val checker = AccessChecker(repo)
        assertEquals("k", checker.check("k", kids)?.id)
        assertEquals(null, checker.check("g", kids))
        assertEquals(null, checker.check("missing", kids))
    }
}
