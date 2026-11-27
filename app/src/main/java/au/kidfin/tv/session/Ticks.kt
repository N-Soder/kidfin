// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.session

/**
 * Time is accounted in Jellyfin ticks (100 ns) as Long, so runtimes keep their
 * fractional milliseconds and allowance comparisons are exact.
 */
object Ticks {
    const val PER_MILLISECOND: Long = 10_000L
    const val PER_SECOND: Long = 1_000L * PER_MILLISECOND
    const val PER_MINUTE: Long = 60L * PER_SECOND

    fun ofMinutes(minutes: Int): Long = Math.multiplyExact(minutes.toLong(), PER_MINUTE)

    fun ofSeconds(seconds: Long): Long = Math.multiplyExact(seconds, PER_SECOND)

    fun ofMillis(millis: Long): Long = Math.multiplyExact(millis, PER_MILLISECOND)

    /** Rounds toward zero. Use only for display or scheduling, never for eligibility. */
    fun toMillisFloor(ticks: Long): Long = ticks / PER_MILLISECOND

    /** Rounds up, so a scheduled check never fires before the true deadline. */
    fun toMillisCeil(ticks: Long): Long = Math.floorDiv(ticks + PER_MILLISECOND - 1, PER_MILLISECOND)
}

/** Monotonic time since boot in ticks. Never wall-clock time. */
fun interface MonotonicClock {
    fun nowTicks(): Long
}

/** Approve-screen presets: 15, 30 or 60 minutes. */
enum class Allowance(val minutes: Int) {
    MIN_15(15), MIN_30(30), MIN_60(60);

    val ticks: Long get() = Ticks.ofMinutes(minutes)
}

enum class Extension(val minutes: Int) {
    MIN_5(5), MIN_10(10), MIN_15(15);

    val ticks: Long get() = Ticks.ofMinutes(minutes)
}
