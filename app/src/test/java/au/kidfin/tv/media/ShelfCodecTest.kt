// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

import au.kidfin.tv.session.ChecksummedFields
import au.kidfin.tv.session.CorruptStateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ShelfCodecTest {
    private val key = ShelfCodec.key("http://jellyfin.test", "user", setOf("kids", "tv"))

    private val full = MediaItem(
        id = "e1",
        title = "Episode one",
        type = MediaType.EPISODE,
        runtimeTicks = 12_345_678L,
        libraryId = "tv",
        seriesName = "Show",
        imageUrl = "http://jellyfin.test/Items/e1/Images/Thumb",
        posterUrl = "http://jellyfin.test/Items/s1/Images/Primary",
        year = 2021,
        plays = 3,
        seriesId = "s1",
        progress = 0.25f,
        favourite = true,
        resumeTicks = 99L,
    )

    private val bare = MediaItem("m1", "", MediaType.MOVIE, null, "kids")

    @Test
    fun everyCachedFieldRoundTrips() {
        val content = ShelfContent(keepWatching = listOf(full), titles = listOf(full, bare))
        assertEquals(content, ShelfCodec.decode(ShelfCodec.encode(key, content), key))
    }

    @Test
    fun emptyShelfRoundTrips() {
        val empty = ShelfContent(emptyList(), emptyList())
        assertEquals(empty, ShelfCodec.decode(ShelfCodec.encode(key, empty), key))
    }

    @Test
    fun fieldsNotCachedComeBackAsDefaults() {
        // Season, episode number and watched are for the episode list, which is never cached.
        val episode = full.copy(season = 2, episode = 5, watched = true)
        val back = ShelfCodec.decode(ShelfCodec.encode(key, ShelfContent(emptyList(), listOf(episode))), key)!!
        assertEquals(full, back.titles.single())
    }

    @Test
    fun separatorInsideATitleCannotSplitTheRecord() {
        val tricky = bare.copy(title = "A\u001FB\nC=D")
        val back = ShelfCodec.decode(ShelfCodec.encode(key, ShelfContent(emptyList(), listOf(tricky))), key)!!
        assertEquals("A B\nC=D", back.titles.single().title)
    }

    @Test
    fun keyIgnoresLibraryOrderButNotAccountServerOrLibraries() {
        assertEquals(key, ShelfCodec.key("http://jellyfin.test", "user", setOf("tv", "kids")))
        assertNotEquals(key, ShelfCodec.key("http://jellyfin.test", "user", setOf("kids")))
        assertNotEquals(key, ShelfCodec.key("http://other.test", "user", setOf("kids", "tv")))
        assertNotEquals(key, ShelfCodec.key("http://jellyfin.test", "other", setOf("kids", "tv")))
    }

    @Test
    fun anotherKeyIsIgnoredNotAnError() {
        val text = ShelfCodec.encode(key, ShelfContent(emptyList(), listOf(bare)))
        assertNull(ShelfCodec.decode(text, ShelfCodec.key("http://jellyfin.test", "user", setOf("kids"))))
    }

    @Test
    fun unreadableCachesAreCorrupt() {
        val good = ShelfCodec.encode(key, ShelfContent(emptyList(), listOf(bare)))
        val cases = listOf(
            "",
            "garbage",
            good.dropLast(3),
            good.replace("m1", "m2"),
            ChecksummedFields.encode(mapOf("v" to "2", "key" to key)),
            ChecksummedFields.encode(mapOf("key" to key)),
            // An item with too few fields, an unknown type, or no id or library.
            ChecksummedFields.encode(mapOf("v" to "3", "key" to key, "t0" to "m1\u001Ftitle")),
            item(type = "SEASON"),
            item(id = ""),
            item(library = ""),
        )
        for (text in cases) {
            assertThrows(text, CorruptStateException::class.java) { ShelfCodec.decode(text, key) }
        }
    }

    @Test
    fun badNumbersAreDroppedNotFatal() {
        val back = ShelfCodec.decode(item(runtime = "soon", year = "MMXX"), key)!!.titles.single()
        assertNull(back.runtimeTicks)
        assertNull(back.year)
    }

    private fun item(id: String = "m1", type: String = "MOVIE", library: String = "kids", runtime: String = "", year: String = ""): String {
        val fields = listOf(id, "Title", type, runtime, library, "", "", "", year, "0", "", "", "", "")
        return ChecksummedFields.encode(mapOf("v" to "3", "key" to key, "t0" to fields.joinToString("\u001F")))
    }
}
