// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.security

import au.kidfin.tv.session.CorruptStateException

/** Test double: holds the secret in memory and can simulate corrupt data or failed writes. */
class InMemoryPinStore(var data: PinData? = null) : PinStore {
    var failWrites = false
    var corrupt = false

    override fun load(): PinData? {
        if (corrupt) throw CorruptStateException("simulated")
        return data
    }

    override fun save(data: PinData) {
        if (failWrites) throw java.io.IOException("simulated write failure")
        this.data = data
    }
}
