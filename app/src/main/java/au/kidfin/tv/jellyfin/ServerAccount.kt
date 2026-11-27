// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.jellyfin

import au.kidfin.tv.media.MediaLibrary
import kotlinx.coroutines.flow.StateFlow

/** The child's Jellyfin account as the parent screens see it. [JellyfinConnection] is the real one. */
interface ServerAccount {
    val state: StateFlow<ConnectionState>

    /** Current config if signed in, for the parent screens. */
    val config: ServerConfig?

    /** Libraries the child may see. Empty unless fully set up and signed in. */
    val allowedLibraryIds: Set<String>

    /** The password is used once for this call and never stored. */
    suspend fun signIn(address: String, username: String, password: String): SignInResult

    /** Whether the server lets this account see every library (a parent should restrict it). */
    suspend fun canSeeAllLibraries(): Boolean

    /** False if the choice couldn't be saved; nothing changes then. */
    fun saveLibraries(libraries: List<MediaLibrary>): Boolean
}
