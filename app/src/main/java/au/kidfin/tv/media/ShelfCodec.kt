// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.media

import au.kidfin.tv.session.ChecksummedFields
import au.kidfin.tv.session.CorruptStateException

/**
 * Last fetched shelf, kept on disk so the next launch shows posters at once while a fresh
 * copy loads. Tied to [key] (server, account and libraries): any change makes it unusable.
 * Holds titles and artwork URLs only, never a token.
 */
object ShelfCodec {
    private const val VERSION = "3"
    private const val SEP = '\u001F'

    fun key(baseUrl: String, userId: String, libraryIds: Set<String>): String =
        listOf(baseUrl, userId, libraryIds.sorted().joinToString(",")).joinToString("|")

    fun encode(key: String, content: ShelfContent): String {
        val fields = linkedMapOf("v" to VERSION, "key" to key)
        content.keepWatching.forEachIndexed { i, item -> fields["k$i"] = item.encode() }
        content.titles.forEachIndexed { i, item -> fields["t$i"] = item.encode() }
        return ChecksummedFields.encode(fields)
    }

    /** Null if the cache belongs to a different key. @throws CorruptStateException if unreadable. */
    fun decode(text: String, expectedKey: String): ShelfContent? {
        val f = ChecksummedFields.decode(text)
        if (f["v"] != VERSION) throw CorruptStateException("unsupported version")
        if (f["key"] != expectedKey) return null
        fun list(prefix: String) = generateSequence(0) { it + 1 }
            .map { f["$prefix$it"] }
            .takeWhile { it != null }
            .map { decodeItem(it!!) }
            .toList()
        return ShelfContent(keepWatching = list("k"), titles = list("t"))
    }

    private fun MediaItem.encode(): String = listOf(
        id, title, type.name, runtimeTicks?.toString(), libraryId, seriesName, imageUrl, posterUrl,
        year?.toString(), plays.toString(), seriesId, progress?.toString(), if (favourite) "1" else "",
        resumeTicks?.toString(),
    ).joinToString(SEP.toString()) { it.orEmpty().replace(SEP, ' ') }

    private fun decodeItem(text: String): MediaItem {
        val p = text.split(SEP)
        if (p.size != 14) throw CorruptStateException("bad item")
        fun opt(i: Int) = p[i].ifEmpty { null }
        return MediaItem(
            id = opt(0) ?: throw CorruptStateException("no id"),
            title = p[1],
            type = MediaType.entries.firstOrNull { it.name == p[2] } ?: throw CorruptStateException("bad type"),
            runtimeTicks = opt(3)?.toLongOrNull(),
            libraryId = opt(4) ?: throw CorruptStateException("no library"),
            seriesName = opt(5),
            imageUrl = opt(6),
            posterUrl = opt(7),
            year = opt(8)?.toIntOrNull(),
            plays = opt(9)?.toIntOrNull() ?: 0,
            seriesId = opt(10),
            progress = opt(11)?.toFloatOrNull(),
            favourite = p[12] == "1",
            resumeTicks = opt(13)?.toLongOrNull(),
        )
    }
}
