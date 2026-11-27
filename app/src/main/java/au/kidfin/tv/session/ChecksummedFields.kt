// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

import java.util.zip.CRC32

/**
 * Versionless key=value lines with a CRC32 trailer, for small atomic state files.
 * CRC detects truncation and accidental damage, not tampering.
 */
object ChecksummedFields {
    fun encode(fields: Map<String, String>): String {
        val body = fields.entries.joinToString("\n") { (k, v) ->
            require(k.isNotEmpty() && '=' !in k && '\n' !in k) { "bad key" }
            "$k=${escape(v)}"
        }
        return "$body\ncrc=${crc(body)}\n"
    }

    /** @throws CorruptStateException on any irregularity. */
    fun decode(text: String): Map<String, String> {
        val trimmed = text.trimEnd('\n')
        val crcIndex = trimmed.lastIndexOf("\ncrc=")
        if (crcIndex < 0) throw CorruptStateException("missing checksum")
        val body = trimmed.substring(0, crcIndex)
        if (trimmed.substring(crcIndex + "\ncrc=".length) != crc(body)) throw CorruptStateException("checksum mismatch")
        val fields = LinkedHashMap<String, String>()
        for (line in body.split('\n')) {
            val eq = line.indexOf('=')
            if (eq <= 0) throw CorruptStateException("malformed line")
            val key = line.substring(0, eq)
            if (fields.put(key, unescape(line.substring(eq + 1))) != null) throw CorruptStateException("duplicate key")
        }
        return fields
    }

    fun Map<String, String>.requireText(key: String): String =
        this[key]?.takeIf { it.isNotEmpty() } ?: throw CorruptStateException("missing $key")

    fun Map<String, String>.requireNonNegativeLong(key: String): Long {
        val value = requireText(key).toLongOrNull() ?: throw CorruptStateException("bad number $key")
        if (value < 0) throw CorruptStateException("negative $key")
        return value
    }

    private fun crc(body: String): String =
        CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value.toString(16)

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= value.length) throw CorruptStateException("bad escape")
            out.append(
                when (value[i + 1]) {
                    '\\' -> '\\'
                    'n' -> '\n'
                    'r' -> '\r'
                    else -> throw CorruptStateException("bad escape")
                },
            )
            i += 2
        }
        return out.toString()
    }
}
