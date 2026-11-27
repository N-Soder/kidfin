// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import android.content.Context
import android.util.Log
import au.kidfin.tv.BuildConfig
import au.kidfin.tv.jellyfin.DeviceProfiles
import au.kidfin.tv.jellyfin.JellyfinConnection
import au.kidfin.tv.jellyfin.JellyfinMediaRepository
import au.kidfin.tv.jellyfin.PlaybackReporter
import au.kidfin.tv.jellyfin.PlaybackResolver
import au.kidfin.tv.jellyfin.ServerAccount
import au.kidfin.tv.media.AccessChecker
import au.kidfin.tv.media.EligibleCatalogue
import au.kidfin.tv.media.MediaRepository
import au.kidfin.tv.media.ShelfSource
import au.kidfin.tv.platform.AndroidBootClock
import au.kidfin.tv.platform.CodecProbe
import au.kidfin.tv.platform.FilePinStore
import au.kidfin.tv.platform.FileServerConfigStore
import au.kidfin.tv.platform.FileSessionStore
import au.kidfin.tv.platform.KeystoreTokenStore
import au.kidfin.tv.platform.NativePowerController
import au.kidfin.tv.platform.NativeStandbyConnection
import au.kidfin.tv.platform.SystemMonotonicClock
import au.kidfin.tv.power.StandbyPolicy
import au.kidfin.tv.security.ParentPinGuard
import au.kidfin.tv.session.SessionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import java.io.File
import java.time.LocalDate

/** Manual constructor injection. One instance per process. */
internal class AppContainer(context: Context) : AppServices {
    private val app = context.applicationContext
    private val dir: File = app.noBackupFilesDir

    /** Outlives screens: best-effort server reports finish even after the player is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val clock = SystemMonotonicClock
    private val bootClock = AndroidBootClock(app)
    override val controller = SessionController(FileSessionStore(File(dir, "session.state")), clock, bootClock.bootId).also { it.restore() }
    override val pinGuard = ParentPinGuard(FilePinStore(File(dir, "parent_pin.state")), bootClock, clock)

    val jellyfin = JellyfinConnection(
        createJellyfin {
            this.context = app
            clientInfo = ClientInfo(name = "Kidfin", version = BuildConfig.VERSION_NAME)
        },
        FileServerConfigStore(File(dir, "jellyfin.state")),
        KeystoreTokenStore(File(dir, "jellyfin.token")),
    ).also { it.restore() }
    override val account: ServerAccount get() = jellyfin

    private val jellyfinMedia = JellyfinMediaRepository(jellyfin)
    override val repository: MediaRepository = jellyfinMedia
    override val shelfSource: ShelfSource = jellyfinMedia

    /** Last shelf, for an instant start. Cache only: safe to lose. */
    override val shelfCache = ShelfCache(File(app.cacheDir, "shelf.cache"))

    override val catalogue = EligibleCatalogue(repository, offerSize = 14)
    override val access = AccessChecker(repository)

    /** For media and artwork. Adds the Jellyfin Authorization header to server requests only. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val header = jellyfin.authHeaderFor(request.url.toString())
            chain.proceed(if (header != null) request.newBuilder().header("Authorization", header).build() else request)
        }
        .build()

    private val deviceProfile by lazy { DeviceProfiles.build(CodecProbe.probe(app)) }
    val resolver = PlaybackResolver(jellyfin) { deviceProfile }
    val reporter = PlaybackReporter(jellyfin, appScope)

    override fun newPlayer(scope: CoroutineScope): VideoPlayer = ExoVideoPlayer(app, scope, resolver, reporter, http)

    // Native standby: it only runs while Kidfin is on screen, so no TV-input check is needed.
    // If the accessibility service is not enabled, requests report Unavailable and the app
    // just locks; the TV power setting shows "Off".
    override val standby: StandbyPolicy = StandbyPolicy.native(NativePowerController)
    override val standbyConnected: StateFlow<Boolean> = NativeStandbyConnection.connected

    override val io: CoroutineDispatcher = Dispatchers.IO
    override val compute: CoroutineDispatcher = Dispatchers.Default

    override fun today(): Long = LocalDate.now().toEpochDay()

    override fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "KidFin"
    }
}
