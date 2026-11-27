// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.RuntimeRules

/**
 * What the grown-up is offered after the child picks a title (content-first flow). Time
 * limits apply only when the picked title itself fits, using the same exact-fit rule as
 * browsing. When none fits (longer than the largest preset, or the
 * runtime is unknown) the title is "long" and only "Just this one" (One title) is offered.
 */
data class ApprovalOffer(
    val options: List<AllowanceOption>,
    /** Shortest allowance that fits the picked title; null when none does. */
    val suggested: Allowance?,
) {
    val isLong: Boolean get() = suggested == null
}

data class AllowanceOption(val allowance: Allowance, val fitsPickedTitle: Boolean)

object Approval {
    fun offerFor(item: MediaItem): ApprovalOffer {
        val options = Allowance.entries.map { AllowanceOption(it, RuntimeRules.fits(item, it.ticks)) }
        return ApprovalOffer(options, options.firstOrNull { it.fitsPickedTitle }?.allowance)
    }
}
