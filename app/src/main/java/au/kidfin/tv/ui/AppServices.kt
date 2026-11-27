// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.jellyfin.ServerAccount
import au.kidfin.tv.media.AccessChecker
import au.kidfin.tv.media.EligibleCatalogue
import au.kidfin.tv.media.MediaRepository
import au.kidfin.tv.media.ShelfSource
import au.kidfin.tv.power.StandbyPolicy
import au.kidfin.tv.security.ParentPinGuard
import au.kidfin.tv.session.MonotonicClock
import au.kidfin.tv.session.SessionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** What [MainViewModel] needs. [AppContainer] provides the real ones; tests pass fakes. */
internal interface AppServices {
    val clock: MonotonicClock
    val controller: SessionController
    val pinGuard: ParentPinGuard
    val account: ServerAccount
    val repository: MediaRepository
    val shelfSource: ShelfSource
    val shelfCache: ShelfCache
    val catalogue: EligibleCatalogue
    val access: AccessChecker
    val standby: StandbyPolicy

    /** The TV power accessibility service is connected. */
    val standbyConnected: StateFlow<Boolean>

    /** For storage reads and writes. */
    val io: CoroutineDispatcher

    /** For search indexing. */
    val compute: CoroutineDispatcher

    fun newPlayer(scope: CoroutineScope): VideoPlayer

    /** Local day number, for the shelf's daily shuffle. */
    fun today(): Long

    fun log(message: String)
}
