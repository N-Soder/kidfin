// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import au.kidfin.tv.security.BootClock
import au.kidfin.tv.security.PinCodec
import au.kidfin.tv.security.PinData
import au.kidfin.tv.security.PinStore
import au.kidfin.tv.session.CorruptStateException
import au.kidfin.tv.session.MonotonicClock
import au.kidfin.tv.session.SessionCodec
import au.kidfin.tv.session.SessionState
import au.kidfin.tv.session.SessionStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** elapsedRealtimeNanos keeps counting in deep sleep and ignores wall-clock changes. */
object SystemMonotonicClock : MonotonicClock {
    override fun nowTicks(): Long = SystemClock.elapsedRealtimeNanos() / 100L
}

class AndroidBootClock(context: Context) : BootClock {
    /**
     * Settings.Global.BOOT_COUNT changes each boot. If unavailable, a per-process id is used,
     * which restarts PIN cooldowns more often (stricter, never looser).
     */
    override val bootId: String = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        .takeIf { it >= 0 }?.toString() ?: "process-${UUID.randomUUID()}"

    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}

/** Atomic text file. Missing file reads as null; unreadable content as corrupt. */
private class AtomicTextFile(file: File) {
    private val atomic = AtomicFile(file)

    fun read(): String? = try {
        atomic.readFully().toString(Charsets.UTF_8)
    } catch (e: FileNotFoundException) {
        null
    } catch (e: IOException) {
        throw CorruptStateException("unreadable", e)
    }

    fun write(text: String) {
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }
}

/** Lives in noBackupFilesDir: session state is never backed up or transferred. */
class FileSessionStore(file: File) : SessionStore {
    private val file = AtomicTextFile(file)

    override fun load(): SessionState? = file.read()?.let(SessionCodec::decode)

    override fun save(state: SessionState) = file.write(SessionCodec.encode(state))
}

class FilePinStore(file: File) : PinStore {
    private val file = AtomicTextFile(file)

    override fun load(): PinData? = file.read()?.let(PinCodec::decode)

    override fun save(data: PinData) = file.write(PinCodec.encode(data))
}
