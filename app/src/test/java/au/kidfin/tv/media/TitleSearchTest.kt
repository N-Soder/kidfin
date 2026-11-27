// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleSearchTest {
    private val titles = listOf(
        "Bluey", "Frog and Toad", "Puffin Rock", "Eva the Owlet", "The Aristocats", "Aladdin",
        "Alice in Wonderland", "Pippi Långstrump", "Wreck-It Ralph", "Big Hero 6",
    ).mapIndexed { i, t -> MediaItem("id$i", t, MediaType.MOVIE, 1L, "kids") }

    private fun find(q: String) = TitleSearch.search(titles, q).map { it.title }

    @Test
    fun prefixOfTitleComesFirst() {
        assertEquals("Aladdin", find("al").first())
        assertTrue("Alice in Wonderland" in find("al"))
    }

    @Test
    fun matchesAnyWordStart() {
        assertEquals(listOf("Frog and Toad"), find("toad"))
        assertEquals(listOf("Eva the Owlet"), find("owl"))
    }

    @Test
    fun toleratesTypos() {
        assertEquals("Bluey", find("bluy").first())
        assertEquals("Puffin Rock", find("pufin").first())
        assertEquals("The Aristocats", find("aristocat").first())
    }

    @Test
    fun ignoresAccentsPunctuationAndLeadingThe() {
        assertEquals(listOf("Pippi Långstrump"), find("langstrump"))
        assertEquals(listOf("Wreck-It Ralph"), find("wreck it"))
        assertEquals("The Aristocats", find("the aris").first())
    }

    @Test
    fun everyWordMustMatch() {
        assertEquals(listOf("Big Hero 6"), find("hero 6"))
        assertEquals(emptyList<String>(), find("bluey toad"))
    }

    @Test
    fun aPrebuiltIndexGivesTheSameResults() {
        val index = TitleSearch.index(titles)
        for (q in listOf("al", "toad", "bluy", "the aris", "hero 6", "zzz")) {
            assertEquals(find(q), TitleSearch.search(index, q).map { it.title })
        }
    }

    @Test
    fun emptyQueryFindsNothing() {
        assertEquals(emptyList<String>(), find("  "))
    }
}
