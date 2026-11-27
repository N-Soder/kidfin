// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.security

/**
 * Proof that a parent entered the PIN recently. Checked again at execution of every
 * parent-only action, not just used to show parent UI.
 *
 * Valid for two minutes after the last grown-up activity on a parent screen ([renewedAt]),
 * and never more than [MAX_LIFETIME_TICKS] after the secret was entered.
 */
class ParentGrant internal constructor(val issuedAtTicks: Long, val lastActiveTicks: Long = issuedAtTicks) {
    fun isValidAt(nowTicks: Long): Boolean =
        nowTicks - lastActiveTicks in 0..VALIDITY_TICKS && nowTicks - issuedAtTicks in 0..MAX_LIFETIME_TICKS

    /** The same grant with its two minutes restarted, or null if it has already lapsed. */
    fun renewedAt(nowTicks: Long): ParentGrant? =
        if (isValidAt(nowTicks)) ParentGrant(issuedAtTicks, maxOf(lastActiveTicks, nowTicks)) else null

    companion object {
        /** Two minutes. */
        const val VALIDITY_TICKS: Long = 2L * 60L * 10_000_000L

        /** Fifteen minutes: enough to type a sign-in on the TV keyboard, then the secret again. */
        const val MAX_LIFETIME_TICKS: Long = 15L * 60L * 10_000_000L
    }
}
