// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.security.ParentGrant
import au.kidfin.tv.session.MonotonicClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The grown-up's grant, and what they were doing when the secret was asked for. Every
 * parent-only action calls [require] at the moment it runs; nothing here trusts which screen
 * happens to be showing.
 */
internal class ParentGate(private val clock: MonotonicClock) {
    private var grant: ParentGrant? = null

    private val mutablePending = MutableStateFlow<MediaItem?>(null)

    /** What the child picked on the locked shelf, waiting for the secret. Never starts anything alone. */
    val pending: StateFlow<MediaItem?> = mutablePending.asStateFlow()

    /** Show waiting for the secret so it can be hearted from its episode list. */
    var pendingHeart: MediaItem? = null

    /** Parent screen to return to after re-entering a lapsed secret (setup can take a while). */
    var returnAfterSecret: Screen? = null

    /** First-run setup from the shelf: Back and Save return to the shelf, not to Settings. */
    var setupFlow = false

    /** Where the child picked the title waiting for the secret or approval (shelf, episodes, search). */
    var pickOrigin: Screen? = null

    fun setPending(item: MediaItem?) {
        mutablePending.value = item
    }

    /** The grant if it still holds now. A lapsed one is dropped: it can never become valid again. */
    fun valid(): ParentGrant? = grant?.takeIf { it.isValidAt(clock.nowTicks()) } ?: run {
        grant = null
        null
    }

    fun accept(granted: ParentGrant) {
        grant = granted
    }

    fun forget() {
        grant = null
    }

    /** Forgets the grant and everything waiting for the secret. */
    fun clearAll() {
        grant = null
        returnAfterSecret = null
        setupFlow = false
        pendingHeart = null
        pickOrigin = null
        mutablePending.value = null
    }

    /**
     * A key press on [here]: on a settings screen the grown-up is still busy, so the grant's two
     * minutes start again (never beyond its overall lifetime, never once lapsed). Not on Approve
     * or the in-session menu: there every press can start or add time, so an abandoned grant
     * must still lapse two minutes after the secret.
     */
    fun onActivity(here: Screen, locked: Boolean) {
        if (!renewsOn(here, locked)) return
        grant?.renewedAt(clock.nowTicks())?.let { grant = it }
    }

    /**
     * For a parent-only action on [here]: the grant if it holds. Otherwise it is dropped and
     * what the grown-up was doing is remembered so the secret brings them back to it (a setup
     * screen, or the title on the approve screen). The caller then asks for the secret.
     */
    fun require(here: Screen, locked: Boolean): ParentGrant? {
        valid()?.let { return it }
        returnAfterSecret = here.takeIf { it == Screen.ServerSetup || it == Screen.PickLibraries }
        if (here is Screen.Approve && locked) mutablePending.value = here.item
        return null
    }

    /** Where the secret just entered leads: a waiting heart, a setup step, the picked title or the menu. */
    fun afterSecret(locked: Boolean): AfterSecret {
        pendingHeart?.let { show ->
            pendingHeart = null
            pickOrigin = null
            return AfterSecret.Heart(show)
        }
        val picked = mutablePending.value
        val back = returnAfterSecret
        returnAfterSecret = null
        return when {
            back != null -> AfterSecret.Return(back)
            picked != null && locked -> AfterSecret.Approve(picked)
            else -> AfterSecret.Menu
        }
    }

    sealed interface AfterSecret {
        data class Heart(val show: MediaItem) : AfterSecret
        data class Return(val screen: Screen) : AfterSecret
        data class Approve(val item: MediaItem) : AfterSecret
        data object Menu : AfterSecret
    }

    companion object {
        /** Settings screens, where activity keeps the grant alive. */
        fun renewsOn(here: Screen, locked: Boolean): Boolean =
            here == Screen.ServerSetup || here == Screen.PickLibraries || here == Screen.TvPower ||
                here == Screen.ConfirmTvPowerTest || here == Screen.ChangePin ||
                (here == Screen.ParentMenu && locked)
    }
}
