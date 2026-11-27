// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

import au.kidfin.tv.session.CorruptStateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ShelfTest {
    private fun movie(id: String, title: String = id, year: Int? = 2000, minutes: Long = 90, plays: Int = 0) =
        MediaItem(id, title, MediaType.MOVIE, minutes * 600_000_000L, "kids", year = year, plays = plays)

    private fun series(id: String, plays: Int = 0) =
        MediaItem(id, id, MediaType.SERIES, null, "tv", plays = plays, seriesId = id)

    private fun episode(id: String, series: String) =
        MediaItem(id, id, MediaType.EPISODE, 600_000_000L * 20, "tv", seriesId = series, progress = 0.4f)

    @Test
    fun duplicateCopiesCollapseToTheLongest() {
        val items = listOf(movie("a", "Bamse", 1991, 61), movie("b", "Bamse", 1991, 1))
        assertEquals(listOf("a"), ShelfOrdering.dedupe(items).map { it.id })
    }

    @Test
    fun sameTitleDifferentYearsStaySeparate() {
        val items = listOf(movie("old", "Dumbo", 1941, 64), movie("new", "Dumbo", 2019, 112))
        assertEquals(setOf("old", "new"), ShelfOrdering.dedupe(items).map { it.id }.toSet())
    }

    @Test
    fun favouritesAreMostPlayedFirstAndSkipUnplayed() {
        val content = ShelfContent(emptyList(), listOf(movie("a", plays = 2), series("s", plays = 9), movie("b"), movie("c", plays = 5)))
        assertEquals(listOf("s", "c", "a"), ShelfOrdering.arrange(content, 1).favourites.map { it.id })
    }

    @Test
    fun heartedTitlesLeadFavouritesEvenUnplayed() {
        val hearted = series("bluey").copy(favourite = true)
        val content = ShelfContent(emptyList(), listOf(movie("a", plays = 9), hearted, movie("b")))
        assertEquals(listOf("bluey", "a"), ShelfOrdering.arrange(content, 1).favourites.map { it.id })
    }

    @Test
    fun dailyShuffleIsStableWithinADayAndIndependentOfServerOrder() {
        val items = (1..30).map { movie("m$it") }
        val day = ShelfOrdering.dailyShuffle(items, 20_000)
        assertEquals(day, ShelfOrdering.dailyShuffle(items.reversed(), 20_000))
        assertNotEquals(day, ShelfOrdering.dailyShuffle(items, 20_001))
        assertEquals(items.toSet(), day.toSet())
    }

    @Test
    fun keepWatchingShowsOneEntryPerSeries() {
        val content = ShelfContent(listOf(episode("e1", "s"), episode("e2", "s"), movie("m")), emptyList())
        assertEquals(listOf("e1", "m"), ShelfOrdering.arrange(content, 1).keepWatching.map { it.id })
    }

    @Test
    fun cacheRoundTripsAndIsIgnoredForAnotherAccount() {
        val content = ShelfContent(listOf(episode("e1", "s")), listOf(movie("a", plays = 3), series("s", plays = 1).copy(favourite = true)))
        val key = ShelfCodec.key("http://srv", "user", setOf("kids", "tv"))
        val text = ShelfCodec.encode(key, content)
        assertEquals(content, ShelfCodec.decode(text, key))
        assertNull(ShelfCodec.decode(text, ShelfCodec.key("http://srv", "other", setOf("kids", "tv"))))
    }

    @Test
    fun damagedCacheIsRejected() {
        val key = ShelfCodec.key("http://srv", "user", setOf("kids"))
        val text = ShelfCodec.encode(key, ShelfContent(emptyList(), listOf(movie("a"))))
        assertThrows(CorruptStateException::class.java) { ShelfCodec.decode(text.replace("a", "b"), key) }
    }
}
