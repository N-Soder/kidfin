// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

import java.text.Normalizer

/**
 * Titles with their search words worked out once (per shelf load), so each key press only
 * compares words.
 */
class TitleIndex internal constructor(internal val entries: List<Entry>) {
    internal class Entry(val item: MediaItem, val words: List<String>, val joined: String, val sortTitle: String)
}

/**
 * Instant, typo-tolerant title search over the shelf already in memory (no server call).
 * Every query word must match a title word by prefix, by containment, or within a small
 * edit distance of a word's start ("bluy" finds Bluey, "toad" finds Frog and Toad).
 */
object TitleSearch {
    private val MARKS = Regex("\\p{M}+")
    private val SEPARATORS = Regex("[^a-z0-9]+")
    private val IGNORED = setOf("the", "a", "an", "and", "of")

    fun index(items: List<MediaItem>): TitleIndex = TitleIndex(
        items.map { item ->
            val w = words(item.title)
            TitleIndex.Entry(item, w, w.joinToString(" "), item.title.lowercase())
        },
    )

    fun search(items: List<MediaItem>, query: String, limit: Int = 60): List<MediaItem> = search(index(items), query, limit)

    fun search(index: TitleIndex, query: String, limit: Int = 60): List<MediaItem> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        val whole = q.joinToString(" ")
        return index.entries
            .mapNotNull { e ->
                val score = when {
                    e.joined.startsWith(whole) -> 1000
                    else -> q.sumOf { wordScore(it, e.words) ?: return@mapNotNull null }
                }
                e to score
            }
            .sortedWith(compareByDescending<Pair<TitleIndex.Entry, Int>> { it.second }.thenBy { it.first.sortTitle })
            .take(limit)
            .map { it.first.item }
    }

    private fun wordScore(q: String, titleWords: List<String>): Int? =
        titleWords.maxOfOrNull { w ->
            when {
                w == q -> 100
                w.startsWith(q) -> 80
                q.length >= 3 && w.contains(q) -> 50
                q.length >= 3 && prefixDistance(q, w) <= allowedTypos(q) -> 30 - prefixDistance(q, w)
                else -> -1
            }
        }?.takeIf { it >= 0 }

    private fun allowedTypos(q: String) = if (q.length >= 6) 2 else 1

    /** Edit distance between [q] and the best-matching start of [w] (so partial words count). */
    internal fun prefixDistance(q: String, w: String): Int {
        // Damerau-Levenshtein (optimal string alignment) of q against every prefix of w.
        val rows = q.length + 1
        val cols = w.length + 1
        val d = Array(rows) { IntArray(cols) }
        for (i in 0 until rows) d[i][0] = i
        for (j in 0 until cols) d[0][j] = j
        for (i in 1 until rows) for (j in 1 until cols) {
            val cost = if (q[i - 1] == w[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && q[i - 1] == w[j - 2] && q[i - 2] == w[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return d[q.length].minOrNull() ?: q.length
    }

    /** Lower case, no accents or punctuation; "the", "a" and "and" are ignored. */
    internal fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace('&', ' ')
            .split(SEPARATORS)
            .filter { it.isNotEmpty() && it !in IGNORED }
}
