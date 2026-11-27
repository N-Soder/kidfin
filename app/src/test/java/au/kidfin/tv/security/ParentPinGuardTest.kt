// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.security

import au.kidfin.tv.session.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class FakeBootClock(override var bootId: String = "boot1", var ms: Long = 0) : BootClock {
    override fun elapsedMs() = ms
}

class ParentPinGuardTest {
    private val store = InMemoryPinStore()
    private val boot = FakeBootClock()
    private val mono = MonotonicClock { boot.ms * 10_000 }
    // Few iterations keep tests fast; production uses DEFAULT_ITERATIONS.
    private val guard = ParentPinGuard(store, boot, mono, iterations = 1_000, random = SecureRandom())

    @Test
    fun setupThenVerify() {
        assertEquals(PinStatus.NOT_SET, guard.status())
        assertEquals(PinResult.NotSet, guard.verify("UULDRU"))
        assertTrue(guard.setInitialPin("UULDRU") is PinResult.Granted)
        assertEquals(PinStatus.READY, guard.status())
        assertTrue(guard.verify("UULDRU") is PinResult.Granted)
        assertEquals(PinResult.AlreadySet, guard.setInitialPin("RRRRRR"))
    }

    @Test
    fun neverStoresPlaintextAndUsesSalt() {
        guard.setInitialPin("UULDRU")
        val encoded = PinCodec.encode(store.data!!)
        assertTrue("UULDRU" !in encoded.replace(Regex("[0-9a-f]{32,}"), ""))
        val other = InMemoryPinStore()
        ParentPinGuard(other, boot, mono, iterations = 1_000).setInitialPin("UULDRU")
        assertTrue(!store.data!!.record!!.hash.contentEquals(other.data!!.record!!.hash))
    }

    @Test
    fun rejectsBadFormat() {
        assertEquals(PinResult.InvalidFormat, guard.setInitialPin("UULDR"))
        assertEquals(PinResult.InvalidFormat, guard.setInitialPin("UULDRX"))
        assertEquals(PinResult.InvalidFormat, guard.setInitialPin("UULDRUU"))
        // New secrets are always moves, never a 4-digit PIN.
        assertEquals(PinResult.InvalidFormat, guard.setInitialPin("1234"))
    }

    @Test
    fun cooldownAfterFiveFailuresAndDoubling() {
        guard.setInitialPin("UULDRU")
        repeat(4) { assertEquals(PinResult.Wrong(4 - it), guard.verify("DDDDDD")) }
        assertEquals(PinResult.CoolingDown(30_000), guard.verify("DDDDDD"))
        // Even the right PIN is refused during cooldown.
        boot.ms += 29_999
        assertTrue(guard.verify("UULDRU") is PinResult.CoolingDown)
        boot.ms += 1
        assertEquals(PinResult.CoolingDown(60_000), guard.verify("DDDDDD"))
        boot.ms += 60_000
        assertTrue(guard.verify("UULDRU") is PinResult.Granted)
        assertEquals(PinThrottle(), store.data!!.throttle)
    }

    @Test
    fun cooldownSurvivesRestartAndRestartsAfterReboot() {
        guard.setInitialPin("UULDRU")
        repeat(5) { guard.verify("DDDDDD") }
        boot.ms += 20_000
        val restarted = ParentPinGuard(store, boot, mono, iterations = 1_000)
        assertEquals(PinResult.CoolingDown(10_000), restarted.verify("UULDRU"))
        boot.bootId = "boot2"
        boot.ms = 5
        assertEquals(PinResult.CoolingDown(30_000), restarted.verify("UULDRU"))
    }

    @Test
    fun cooldownCapped() {
        assertEquals(0L, ParentPinGuard.cooldownFor(4))
        assertEquals(30_000L, ParentPinGuard.cooldownFor(5))
        assertEquals(60_000L, ParentPinGuard.cooldownFor(6))
        assertEquals(120_000L, ParentPinGuard.cooldownFor(7))
        assertEquals(3 * 60_000L, ParentPinGuard.cooldownFor(8))
        assertEquals(3 * 60_000L, ParentPinGuard.cooldownFor(50))
    }

    @Test
    fun failuresAreForgottenAnHourAfterTheLast() {
        guard.setInitialPin("UULDRU")
        repeat(4) { guard.verify("DDDDDD") }
        boot.ms += ParentPinGuard.FORGET_FAILURES_AFTER_MS - 1
        // Still remembered: the fifth failure starts a cooldown.
        assertEquals(PinResult.CoolingDown(30_000), guard.verify("DDDDDD"))
        boot.ms += ParentPinGuard.FORGET_FAILURES_AFTER_MS
        assertEquals(0L, guard.cooldownRemainingMs())
        assertEquals(PinThrottle(), store.data!!.throttle)
        assertEquals(PinResult.Wrong(4), guard.verify("DDDDDD"))
    }

    @Test
    fun aRebootStartsTheForgettingHourAgain() {
        guard.setInitialPin("UULDRU")
        repeat(4) { guard.verify("DDDDDD") }
        boot.bootId = "boot2"
        boot.ms = ParentPinGuard.FORGET_FAILURES_AFTER_MS * 5
        // A fresh clock after reboot proves nothing about how long ago the failures were.
        assertEquals(PinResult.CoolingDown(30_000), guard.verify("DDDDDD"))
        boot.ms += ParentPinGuard.FORGET_FAILURES_AFTER_MS
        assertEquals(PinResult.Wrong(4), guard.verify("DDDDDD"))
    }

    @Test
    fun cooldownRemainingCountsDown() {
        guard.setInitialPin("UULDRU")
        assertEquals(0L, guard.cooldownRemainingMs())
        repeat(5) { guard.verify("DDDDDD") }
        assertEquals(30_000L, guard.cooldownRemainingMs())
        boot.ms += 12_000
        assertEquals(18_000L, guard.cooldownRemainingMs())
        boot.ms += 18_000
        assertEquals(0L, guard.cooldownRemainingMs())
        store.corrupt = true
        assertEquals(null, guard.cooldownRemainingMs())
    }

    @Test
    fun throttleWithoutLastFailureAnchorDecodes() {
        guard.setInitialPin("UULDRU")
        repeat(2) { guard.verify("DDDDDD") }
        val text = PinCodec.encode(store.data!!)
        val old = au.kidfin.tv.session.ChecksummedFields.encode(
            au.kidfin.tv.session.ChecksummedFields.decode(text).filterKeys { it != "lf_boot" && it != "lf_ms" },
        )
        val decoded = PinCodec.decode(old).throttle
        assertEquals(2, decoded.consecutiveFailures)
        assertEquals(null, decoded.lastFailureBootId)
    }

    @Test
    fun corruptFailsClosed() {
        guard.setInitialPin("UULDRU")
        store.corrupt = true
        assertEquals(PinStatus.CORRUPT, guard.status())
        assertEquals(PinResult.Corrupt, guard.verify("UULDRU"))
        assertEquals(PinResult.Corrupt, guard.setInitialPin("UULDRU"))
    }

    @Test
    fun failureThatCannotBeRecordedIsRefused() {
        guard.setInitialPin("UULDRU")
        store.failWrites = true
        assertEquals(PinResult.StorageFailed, guard.verify("DDDDDD"))
    }

    @Test
    fun codecRoundTripAndDamage() {
        guard.setInitialPin("UULDRU")
        repeat(5) { guard.verify("DDDDDD") }
        val data = store.data!!
        assertEquals(data, PinCodec.decode(PinCodec.encode(data)))
        val damaged = PinCodec.encode(data).replace("fails=5", "fails=0")
        try {
            PinCodec.decode(damaged)
            throw AssertionError("expected corrupt")
        } catch (expected: au.kidfin.tv.session.CorruptStateException) {
            // ok
        }
    }

    @Test
    fun grantExpires() {
        guard.setInitialPin("UULDRU")
        val g = (guard.verify("UULDRU") as PinResult.Granted).grant
        assertTrue(g.isValidAt(mono.nowTicks()))
        assertTrue(!g.isValidAt(mono.nowTicks() + ParentGrant.VALIDITY_TICKS + 1))
        assertTrue(!g.isValidAt(mono.nowTicks() - 1))
    }

    @Test
    fun grantRenewsOnActivityButNotBeyondItsLifetime() {
        guard.setInitialPin("UULDRU")
        var g = (guard.verify("UULDRU") as PinResult.Granted).grant
        val start = mono.nowTicks()
        var t = start
        // Activity every 90 s keeps it going...
        while (t + 90 * 10_000_000L <= start + ParentGrant.MAX_LIFETIME_TICKS) {
            t += 90 * 10_000_000L
            g = g.renewedAt(t)!!
        }
        // ...but never past the lifetime cap from entering the secret.
        assertTrue(!g.isValidAt(start + ParentGrant.MAX_LIFETIME_TICKS + 1))
        // A lapsed grant can't be renewed.
        val lapsed = (guard.verify("UULDRU") as PinResult.Granted).grant
        assertEquals(null, lapsed.renewedAt(mono.nowTicks() + ParentGrant.VALIDITY_TICKS + 1))
    }

    private fun legacyPin(pin: String) {
        val salt = ByteArray(16) { it.toByte() }
        store.data = PinData(PinRecord(salt, Pbkdf2PinHasher.hash(pin.toCharArray(), salt, 1_000), 1_000, PinFormat.DIGITS))
    }

    @Test
    fun oldFourDigitPinStillWorksUntilReplacedWithMoves() {
        legacyPin("1234")
        assertEquals(PinFormat.DIGITS, guard.format())
        assertEquals(PinResult.InvalidFormat, guard.verify("UULDRU"))
        val g = (guard.verify("1234") as PinResult.Granted).grant
        assertTrue(guard.changePin(g, "UULDRU") is PinResult.Granted)
        assertEquals(PinFormat.MOVES, guard.format())
        assertTrue(guard.verify("UULDRU") is PinResult.Granted)
        assertEquals(PinResult.InvalidFormat, guard.verify("1234"))
    }

    @Test
    fun changeNeedsACurrentGrant() {
        legacyPin("1234")
        val g = (guard.verify("1234") as PinResult.Granted).grant
        boot.ms += ParentGrant.VALIDITY_TICKS / 10_000 + 1
        assertEquals(PinResult.Expired, guard.changePin(g, "UULDRU"))
        assertEquals(PinFormat.DIGITS, guard.format())
    }

    @Test
    fun recordsWithoutAFormatAreOldPins() {
        legacyPin("1234")
        val text = PinCodec.encode(store.data!!)
        assertTrue("fmt" !in text)
        assertEquals(PinFormat.DIGITS, PinCodec.decode(text).record!!.format)
    }
}
