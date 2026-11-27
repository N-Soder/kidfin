// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaType
import au.kidfin.tv.media.ShelfCodec
import au.kidfin.tv.media.ShelfContent
import au.kidfin.tv.session.FakeClock
import au.kidfin.tv.session.Ticks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShelfCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ShelfCodec.key("http://jellyfin.test", "user", setOf("kids"))
    private val content = ShelfContent(emptyList(), listOf(MediaItem("m1", "Movie", MediaType.MOVIE, Ticks.ofMinutes(20), "kids")))

    @Test
    fun writesAndReadsBack() {
        val cache = ShelfCache(File(tmp.root, "shelf.cache"))
        assertNull(cache.read(key))
        cache.write(key, content)
        assertEquals(content, cache.read(key))
        assertFalse(File(tmp.root, "shelf.cache.tmp").exists())
    }

    @Test
    fun anotherAccountsCacheIsIgnoredAndKept() {
        val file = File(tmp.root, "shelf.cache")
        val cache = ShelfCache(file)
        cache.write(key, content)
        assertNull(cache.read(ShelfCodec.key("http://jellyfin.test", "other", setOf("kids"))))
        assertTrue(file.exists())
    }

    @Test
    fun damagedCacheIsDeleted() {
        val file = File(tmp.root, "shelf.cache")
        file.writeText("not a shelf")
        assertNull(ShelfCache(file).read(key))
        assertFalse(file.exists())
    }

    @Test
    fun aFailedWriteIsIgnored() {
        val cache = ShelfCache(File(tmp.root, "missing/dir/shelf.cache"))
        cache.write(key, content)
        assertNull(cache.read(key))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfLoaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val source = FakeShelfSource()
    private val config = READY_CONFIG
    private var allowed = setOf("kids")

    private fun movie(id: String, lib: String = "kids", minutes: Int? = 20, favourite: Boolean = false) =
        MediaItem(id, "Title $id", MediaType.MOVIE, minutes?.let { Ticks.ofMinutes(it) }, lib, favourite = favourite)

    private fun TestScope.loader(scope: CoroutineScope = backgroundScope): ShelfLoader {
        val d = UnconfinedTestDispatcher(testScheduler)
        return ShelfLoader(scope, source, ShelfCache(File(tmp.root, "shelf.cache")), clock, { allowed }, d, d, {}, { 20_000L })
    }

    private fun titles(loader: ShelfLoader) = (loader.shelf.value as ShelfState.Ready).rows.everything.map { it.id }.toSet()

    @Test
    fun notConnectedFailsWithoutFetching() = runTest(UnconfinedTestDispatcher()) {
        val loader = loader()
        loader.load(null)
        assertEquals(ShelfState.Failed("Not connected."), loader.shelf.value)
        assertEquals(0, source.fetches)
    }

    @Test
    fun keepsOnlyPlayableTitlesFromChosenLibraries() = runTest(UnconfinedTestDispatcher()) {
        source.content = ShelfContent(
            keepWatching = listOf(movie("k1"), movie("k2", lib = "adults"), movie("k3", minutes = null)),
            titles = listOf(
                movie("a"),
                movie("b", lib = "adults"),
                movie("c", minutes = null),
                MediaItem("s", "Show", MediaType.SERIES, null, "kids"),
            ),
        )
        val loader = loader()
        loader.load(config)
        assertEquals(setOf("a", "s"), titles(loader))
        assertEquals(listOf("k1"), (loader.shelf.value as ShelfState.Ready).rows.keepWatching.map { it.id })
        assertFalse(loader.needsLoad())
    }

    @Test
    fun diskCopyIsShownWhenTheServerCantBeReached() = runTest(UnconfinedTestDispatcher()) {
        source.content = ShelfContent(emptyList(), listOf(movie("a")))
        loader().load(config)

        source.failFetch = true
        val next = loader()
        next.load(config)
        assertEquals(setOf("a"), titles(next))
        // Shown, but not counted as fresh.
        assertTrue(next.needsLoad())
    }

    @Test
    fun failedFetchWithNoCopySaysSo() = runTest(UnconfinedTestDispatcher()) {
        source.failFetch = true
        val loader = loader()
        loader.load(config)
        assertEquals(ShelfState.Failed("Can't reach the videos right now."), loader.shelf.value)
    }

    @Test
    fun refreshesWhenStaleOrFiveMinutesOld() = runTest(UnconfinedTestDispatcher()) {
        source.content = ShelfContent(emptyList(), listOf(movie("a")))
        val loader = loader()
        loader.load(config)
        assertFalse(loader.needsLoad())
        loader.markStale()
        assertTrue(loader.needsLoad())
        loader.load(config)
        assertFalse(loader.needsLoad())
        clock.advanceSeconds(5 * 60 + 1)
        assertTrue(loader.needsLoad())
    }

    @Test
    fun heartsComeFromTheShelfAndRevertIfRefused() = runTest(UnconfinedTestDispatcher()) {
        source.content = ShelfContent(emptyList(), listOf(movie("a", favourite = true), movie("b")))
        val loader = loader()
        loader.load(config)
        assertEquals(setOf("a"), loader.favouriteIds.value)

        var failures = 0
        loader.toggleFavourite(movie("b")) { failures++ }
        assertEquals(setOf("a", "b"), loader.favouriteIds.value)
        assertTrue(loader.needsLoad())

        source.failFavourite = true
        loader.toggleFavourite(movie("a")) { failures++ }
        assertEquals(setOf("a", "b"), loader.favouriteIds.value)
        assertEquals(1, failures)
    }

    @Test
    fun searchUsesTheShownShelf() = runTest(UnconfinedTestDispatcher()) {
        source.content = ShelfContent(emptyList(), listOf(movie("a"), MediaItem("z", "Zebra Song", MediaType.MOVIE, Ticks.ofMinutes(5), "kids")))
        val loader = loader()
        assertTrue(loader.titles().isEmpty())
        loader.load(config)
        assertEquals(listOf("z"), loader.search(loader.titles(), "zeb").map { it.id })
    }

    @Test
    fun resetDropsTheShownShelf() = runTest(UnconfinedTestDispatcher()) {
        source.content = ShelfContent(emptyList(), listOf(movie("a")))
        val loader = loader()
        loader.load(config)
        loader.reset()
        assertEquals(ShelfState.Loading, loader.shelf.value)
        assertTrue(loader.needsLoad())
    }
}
