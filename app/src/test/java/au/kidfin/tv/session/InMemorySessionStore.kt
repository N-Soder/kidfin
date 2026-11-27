// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

/** Test double: holds the session in memory and can simulate failed writes. */
class InMemorySessionStore(var stored: SessionState? = null) : SessionStore {
    var failWrites = false
    var saves = 0
        private set

    override fun load(): SessionState? = stored

    override fun save(state: SessionState) {
        if (failWrites) throw java.io.IOException("simulated write failure")
        saves++
        stored = state
    }
}
