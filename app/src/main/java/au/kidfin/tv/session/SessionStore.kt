// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

/** Persistence boundary. Implementations must write atomically. */
interface SessionStore {
    /** @throws CorruptStateException if stored data is unreadable. Returns null when nothing is stored. */
    fun load(): SessionState?

    /** @throws java.io.IOException if the write did not complete. */
    fun save(state: SessionState)
}

class CorruptStateException(message: String, cause: Throwable? = null) : Exception(message, cause)
