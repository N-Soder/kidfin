// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.security

import au.kidfin.tv.session.ChecksummedFields
import au.kidfin.tv.session.ChecksummedFields.requireNonNegativeLong
import au.kidfin.tv.session.ChecksummedFields.requireText
import au.kidfin.tv.session.CorruptStateException
import au.kidfin.tv.session.MonotonicClock
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parent secret: a convenience barrier for a cooperative household, not hardened
 * authentication against the device owner.
 *
 * [MOVES] is six presses of the remote's arrows (U, D, L, R), entered with nothing on screen
 * but filling dots, so children watching the TV can't read it off a keypad highlight.
 * [DIGITS] is the original 4-digit PIN, still accepted once so it can be replaced.
 */
enum class PinFormat(val length: Int, private val alphabet: String) {
    DIGITS(4, "0123456789"),
    MOVES(6, "UDLR"),
    ;

    fun accepts(key: Char): Boolean = key in alphabet

    fun isValid(pin: String): Boolean = pin.length == length && pin.all(::accepts)
}

data class PinRecord(
    val salt: ByteArray,
    val hash: ByteArray,
    val iterations: Int,
    val format: PinFormat = PinFormat.MOVES,
) {
    override fun equals(other: Any?): Boolean =
        other is PinRecord && salt.contentEquals(other.salt) && hash.contentEquals(other.hash) &&
            iterations == other.iterations && format == other.format

    override fun hashCode(): Int =
        31 * (31 * (31 * salt.contentHashCode() + hash.contentHashCode()) + iterations) + format.hashCode()
}

/**
 * Failed-attempt state. A cooldown is anchored to a boot; after a reboot the monotonic clock
 * restarts, so the cooldown restarts in full rather than being skipped. The last failure is
 * anchored the same way: failures are forgotten an hour after it, and a reboot starts that
 * hour again.
 */
data class PinThrottle(
    val consecutiveFailures: Int = 0,
    val cooldownBootId: String? = null,
    val cooldownStartMs: Long = 0L,
    val cooldownMs: Long = 0L,
    val lastFailureBootId: String? = null,
    val lastFailureMs: Long = 0L,
)

data class PinData(val record: PinRecord?, val throttle: PinThrottle = PinThrottle())

interface PinStore {
    /** @throws CorruptStateException if unreadable. Null when nothing has been set up. */
    fun load(): PinData?

    /** @throws java.io.IOException on failure. */
    fun save(data: PinData)
}

/** Boot-scoped monotonic milliseconds. [bootId] changes on every reboot. */
interface BootClock {
    val bootId: String

    fun elapsedMs(): Long
}

fun interface PinHasher {
    fun hash(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray
}

object Pbkdf2PinHasher : PinHasher {
    override fun hash(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

enum class PinStatus { NOT_SET, READY, CORRUPT }

sealed interface PinResult {
    data class Granted(val grant: ParentGrant) : PinResult
    data class Wrong(val attemptsBeforeCooldown: Int) : PinResult
    data class CoolingDown(val remainingMs: Long) : PinResult
    data object InvalidFormat : PinResult

    /** Stored PIN data is unreadable. Fail closed; an adult must reprovision (clear app data). */
    data object Corrupt : PinResult
    data object NotSet : PinResult
    data object AlreadySet : PinResult
    data object StorageFailed : PinResult
    data object Expired : PinResult
}

class ParentPinGuard(
    private val store: PinStore,
    private val bootClock: BootClock,
    private val monotonic: MonotonicClock,
    private val hasher: PinHasher = Pbkdf2PinHasher,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
) {
    @Synchronized
    fun status(): PinStatus = try {
        if (store.load()?.record == null) PinStatus.NOT_SET else PinStatus.READY
    } catch (e: CorruptStateException) {
        PinStatus.CORRUPT
    }

    /** Format of the stored secret, or null when none is set or it can't be read. */
    @Synchronized
    fun format(): PinFormat? = try {
        store.load()?.record?.format
    } catch (e: CorruptStateException) {
        null
    }

    /** First-run setup only. New secrets are always [PinFormat.MOVES]. */
    @Synchronized
    fun setInitialPin(pin: String): PinResult {
        if (!PinFormat.MOVES.isValid(pin)) return PinResult.InvalidFormat
        val existing = try {
            store.load()
        } catch (e: CorruptStateException) {
            return PinResult.Corrupt
        }
        if (existing?.record != null) return PinResult.AlreadySet
        return saveNew(pin)
    }

    /** Replaces the secret (e.g. an old 4-digit PIN with moves). Needs a current grant. */
    @Synchronized
    fun changePin(grant: ParentGrant, pin: String): PinResult {
        if (!grant.isValidAt(monotonic.nowTicks())) return PinResult.Expired
        if (!PinFormat.MOVES.isValid(pin)) return PinResult.InvalidFormat
        try {
            store.load()?.record ?: return PinResult.NotSet
        } catch (e: CorruptStateException) {
            return PinResult.Corrupt
        }
        return saveNew(pin)
    }

    private fun saveNew(pin: String): PinResult {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val record = PinRecord(salt, hasher.hash(pin.toCharArray(), salt, iterations), iterations, PinFormat.MOVES)
        return try {
            store.save(PinData(record))
            PinResult.Granted(ParentGrant(monotonic.nowTicks()))
        } catch (e: java.io.IOException) {
            PinResult.StorageFailed
        }
    }

    @Synchronized
    fun verify(pin: String): PinResult {
        val data = try {
            store.load()
        } catch (e: CorruptStateException) {
            return PinResult.Corrupt
        }
        val record = data?.record ?: return PinResult.NotSet
        val throttle = currentThrottle(data) ?: return PinResult.StorageFailed
        val remaining = cooldownLeft(throttle)
        if (remaining > 0) return PinResult.CoolingDown(remaining)
        if (!record.format.isValid(pin)) return PinResult.InvalidFormat

        val candidate = hasher.hash(pin.toCharArray(), record.salt, record.iterations)
        if (MessageDigest.isEqual(candidate, record.hash)) {
            if (throttle != PinThrottle() && !save(data.copy(throttle = PinThrottle()))) return PinResult.StorageFailed
            return PinResult.Granted(ParentGrant(monotonic.nowTicks()))
        }

        val failures = throttle.consecutiveFailures + 1
        val cooldown = cooldownFor(failures)
        val now = bootClock.elapsedMs()
        val next = PinThrottle(
            consecutiveFailures = failures,
            cooldownBootId = if (cooldown > 0) bootClock.bootId else null,
            cooldownStartMs = if (cooldown > 0) now else 0L,
            cooldownMs = cooldown,
            lastFailureBootId = bootClock.bootId,
            lastFailureMs = now,
        )
        // If the failure cannot be recorded, refuse rather than allow unlimited guesses.
        if (!save(data.copy(throttle = next))) return PinResult.StorageFailed
        return if (cooldown > 0) PinResult.CoolingDown(cooldown) else PinResult.Wrong(FREE_ATTEMPTS - failures)
    }

    /**
     * Milliseconds until the secret may be tried again (0 when it may), for a live countdown.
     * Null if the stored data can't be read or the throttle can't be updated.
     */
    @Synchronized
    fun cooldownRemainingMs(): Long? {
        val data = try {
            store.load()
        } catch (e: CorruptStateException) {
            return null
        } ?: return 0L
        if (data.record == null) return 0L
        return currentThrottle(data)?.let(::cooldownLeft)
    }

    private fun cooldownLeft(throttle: PinThrottle): Long =
        if (throttle.cooldownMs > 0) (throttle.cooldownStartMs + throttle.cooldownMs - bootClock.elapsedMs()).coerceAtLeast(0L) else 0L

    /**
     * The throttle as it applies now, persisted if it changed: anchors from another boot are
     * moved to this boot (restarting the cooldown and the forgetting hour in full), and
     * failures an hour old are forgotten. Null if a needed write failed.
     */
    private fun currentThrottle(data: PinData): PinThrottle? {
        var throttle = data.throttle
        val now = bootClock.elapsedMs()
        if (throttle.consecutiveFailures > 0) {
            if (throttle.lastFailureBootId != bootClock.bootId) {
                throttle = throttle.copy(lastFailureBootId = bootClock.bootId, lastFailureMs = now)
            } else if (now - throttle.lastFailureMs >= FORGET_FAILURES_AFTER_MS) {
                throttle = PinThrottle()
            }
        }
        if (throttle.cooldownMs > 0 && throttle.cooldownBootId != bootClock.bootId) {
            throttle = throttle.copy(cooldownBootId = bootClock.bootId, cooldownStartMs = now)
        }
        if (throttle != data.throttle && !save(data.copy(throttle = throttle))) return null
        return throttle
    }

    private fun save(data: PinData): Boolean = try {
        store.save(data)
        true
    } catch (e: java.io.IOException) {
        false
    }

    companion object {
        const val DEFAULT_ITERATIONS = 120_000
        const val SALT_BYTES = 16
        const val FREE_ATTEMPTS = 5
        const val FIRST_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 3L * 60_000L

        /** Consecutive failures are forgotten after this long without another failure. */
        const val FORGET_FAILURES_AFTER_MS = 60L * 60_000L

        /** No cooldown for the first four failures; 30 s at the fifth, doubling, capped at 3 min. */
        fun cooldownFor(failures: Int): Long {
            if (failures < FREE_ATTEMPTS) return 0L
            val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(10)
            return (FIRST_COOLDOWN_MS shl doublings).coerceAtMost(MAX_COOLDOWN_MS)
        }
    }
}

object PinCodec {
    private const val VERSION = "1"

    fun encode(data: PinData): String {
        val f = linkedMapOf("v" to VERSION)
        data.record?.let {
            f["salt"] = it.salt.toHex()
            f["hash"] = it.hash.toHex()
            f["iter"] = it.iterations.toString()
            if (it.format == PinFormat.MOVES) f["fmt"] = "moves"
        }
        f["fails"] = data.throttle.consecutiveFailures.toString()
        data.throttle.cooldownBootId?.let { f["cd_boot"] = it }
        f["cd_start"] = data.throttle.cooldownStartMs.toString()
        f["cd_ms"] = data.throttle.cooldownMs.toString()
        data.throttle.lastFailureBootId?.let { f["lf_boot"] = it }
        f["lf_ms"] = data.throttle.lastFailureMs.toString()
        return ChecksummedFields.encode(f)
    }

    fun decode(text: String): PinData {
        val f = ChecksummedFields.decode(text)
        if (f["v"] != VERSION) throw CorruptStateException("unsupported version")
        val record = if ("hash" in f) {
            val iterations = f.requireNonNegativeLong("iter")
            if (iterations !in 1..10_000_000) throw CorruptStateException("bad iterations")
            // Records written before secret moves have no "fmt": they are 4-digit PINs.
            val format = when (f["fmt"]) {
                null -> PinFormat.DIGITS
                "moves" -> PinFormat.MOVES
                else -> throw CorruptStateException("bad format")
            }
            PinRecord(f.requireText("salt").fromHex(), f.requireText("hash").fromHex(), iterations.toInt(), format)
        } else {
            null
        }
        val fails = f.requireNonNegativeLong("fails")
        if (fails > 1_000_000) throw CorruptStateException("bad failures")
        val throttle = PinThrottle(
            consecutiveFailures = fails.toInt(),
            cooldownBootId = f["cd_boot"],
            cooldownStartMs = f.requireNonNegativeLong("cd_start"),
            cooldownMs = f.requireNonNegativeLong("cd_ms"),
            // Older files have no last-failure anchor: treated as another boot, so the
            // forgetting hour starts in full (never sooner).
            lastFailureBootId = f["lf_boot"],
            lastFailureMs = if ("lf_ms" in f) f.requireNonNegativeLong("lf_ms") else 0L,
        )
        return PinData(record, throttle)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray {
        if (length % 2 != 0 || any { it !in '0'..'9' && it !in 'a'..'f' }) throw CorruptStateException("bad hex")
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
