// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.jellyfin

import au.kidfin.tv.session.ChecksummedFields
import au.kidfin.tv.session.ChecksummedFields.requireText
import au.kidfin.tv.session.CorruptStateException

/**
 * Non-secret connection settings chosen by a parent on the TV. The access token is stored
 * separately, encrypted with an Android Keystore key (see `KeystoreTokenStore`); it never appears here.
 */
data class ServerConfig(
    val baseUrl: String,
    val userId: String,
    val userName: String,
    /** Random per-install id sent to Jellyfin; one signed-in user per device id. */
    val deviceId: String,
    /** Parent-approved libraries. Empty until the parent picks at least one. */
    val libraryIds: Set<String>,
    val libraryNames: List<String> = emptyList(),
)

object ServerConfigCodec {
    private const val VERSION = "1"
    private const val SEP = "\u001F"

    fun encode(c: ServerConfig): String = ChecksummedFields.encode(
        linkedMapOf(
            "v" to VERSION,
            "url" to c.baseUrl,
            "user_id" to c.userId,
            "user" to c.userName,
            "device" to c.deviceId,
            "libs" to c.libraryIds.sorted().joinToString(SEP),
            "lib_names" to c.libraryNames.joinToString(SEP),
        ),
    )

    fun decode(text: String): ServerConfig {
        val f = ChecksummedFields.decode(text)
        if (f["v"] != VERSION) throw CorruptStateException("unsupported version")
        val url = f.requireText("url")
        if (ServerAddress.normalise(url) != url) throw CorruptStateException("bad url")
        return ServerConfig(
            baseUrl = url,
            userId = f.requireText("user_id"),
            userName = f.requireText("user"),
            deviceId = f.requireText("device"),
            libraryIds = f["libs"].orEmpty().split(SEP).filter { it.isNotEmpty() }.toSet(),
            libraryNames = f["lib_names"].orEmpty().split(SEP).filter { it.isNotEmpty() },
        )
    }
}

object ServerAddress {
    /**
     * Canonical base URL: http or https, a host, optional port and base path, no query,
     * fragment, credentials or trailing slash. A bare host gets http://. Null if unusable.
     */
    fun normalise(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "http://$s"
        val uri = try {
            java.net.URI(s)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = (uri.rawPath ?: "").trimEnd('/')
        return "$scheme://$host$port$path"
    }
}
