// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import au.kidfin.tv.jellyfin.ConnectionState
import au.kidfin.tv.jellyfin.ServerAccount
import au.kidfin.tv.jellyfin.ServerConfig
import au.kidfin.tv.jellyfin.SignInResult
import au.kidfin.tv.media.AccessChecker
import au.kidfin.tv.media.EligibleCatalogue
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaLibrary
import au.kidfin.tv.media.MediaPage
import au.kidfin.tv.media.MediaRepository
import au.kidfin.tv.media.SeriesEpisodes
import au.kidfin.tv.media.ShelfContent
import au.kidfin.tv.media.ShelfSource
import au.kidfin.tv.power.StandbyPolicy
import au.kidfin.tv.power.StandbyRequestResult
import au.kidfin.tv.security.FakeBootClock
import au.kidfin.tv.security.InMemoryPinStore
import au.kidfin.tv.security.ParentPinGuard
import au.kidfin.tv.session.BOOT
import au.kidfin.tv.session.FakeClock
import au.kidfin.tv.session.InMemorySessionStore
import au.kidfin.tv.session.SessionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.SecureRandom

val READY_CONFIG = ServerConfig("http://jellyfin.test:8096", "user-1", "kids", "device-1", setOf("kids"), listOf("Kids"))

class FakeAccount(initial: ConnectionState = ConnectionState.Ready(READY_CONFIG)) : ServerAccount {
    override val state = MutableStateFlow(initial)
    val signIns = mutableListOf<String>()
    val saved = mutableListOf<List<MediaLibrary>>()

    override val config: ServerConfig?
        get() = when (val s = state.value) {
            ConnectionState.NotSetUp -> null
            is ConnectionState.NeedsLibraries -> s.config
            is ConnectionState.Ready -> s.config
            is ConnectionState.SignedOut -> s.config
        }

    override val allowedLibraryIds: Set<String>
        get() = (state.value as? ConnectionState.Ready)?.config?.libraryIds.orEmpty()

    override suspend fun signIn(address: String, username: String, password: String): SignInResult {
        signIns += username
        state.value = ConnectionState.NeedsLibraries(READY_CONFIG.copy(libraryIds = emptySet(), libraryNames = emptyList()))
        return SignInResult.Done
    }

    override suspend fun canSeeAllLibraries(): Boolean = false

    override fun saveLibraries(libraries: List<MediaLibrary>): Boolean {
        saved += libraries
        state.value = ConnectionState.Ready(READY_CONFIG.copy(libraryIds = libraries.map { it.id }.toSet()))
        return true
    }
}

class FakeRepository(items: List<MediaItem>) : MediaRepository {
    private val byId = items.associateBy { it.id }
    private val all = items

    override suspend fun libraries(): List<MediaLibrary> = listOf(MediaLibrary("kids", "Kids"))

    override suspend fun page(libraryIds: Set<String>, startIndex: Int, limit: Int): MediaPage {
        val inLibs = all.filter { it.libraryId in libraryIds }
        return MediaPage(inLibs.drop(startIndex).take(limit), inLibs.size)
    }

    override suspend fun item(itemId: String): MediaItem? = byId[itemId]
}

class FakeShelfSource(var content: ShelfContent = ShelfContent(emptyList(), emptyList())) : ShelfSource {
    var fetches = 0
    var failFetch = false
    var failFavourite = false
    val favourites = mutableListOf<Pair<String, Boolean>>()
    var episodes = SeriesEpisodes(emptyList(), null)

    override suspend fun shelf(libraryIds: Set<String>): ShelfContent {
        fetches++
        if (failFetch) throw java.io.IOException("offline")
        return content
    }

    override suspend fun nextEpisode(series: MediaItem): MediaItem? = episodes.episodes.firstOrNull()

    override suspend fun episodes(series: MediaItem): SeriesEpisodes = episodes

    override suspend fun setFavourite(item: MediaItem, favourite: Boolean) {
        if (failFavourite) throw java.io.IOException("refused")
        favourites += (item.seriesId ?: item.id) to favourite
    }
}

class FakePlayer : VideoPlayer {
    override val snapshot = MutableStateFlow(PlayerSnapshot())
    override val surface: StateFlow<androidx.media3.common.Player?> = MutableStateFlow(null)
    val started = mutableListOf<String>()

    override fun start(item: MediaItem, fromTicks: Long) {
        started += item.id
        snapshot.value = PlayerSnapshot(PlayerPhase.PREPARING)
    }

    override fun togglePause() = Unit

    override fun stop(): Long {
        snapshot.value = PlayerSnapshot(PlayerPhase.IDLE)
        return 0L
    }
}

/** Real session engine and secret guard over in-memory stores; everything else faked. */
internal class FakeServices(
    dispatcher: CoroutineDispatcher,
    items: List<MediaItem>,
    cacheDir: File,
    override val account: FakeAccount = FakeAccount(),
) : AppServices {
    val fakeClock = FakeClock()
    val boot = FakeBootClock()
    val pinStore = InMemoryPinStore()
    val sessionStore = InMemorySessionStore()
    val source = FakeShelfSource()
    val fakePlayer = FakePlayer()
    var standbyRequests = 0

    override val clock = fakeClock
    override val controller = SessionController(sessionStore, fakeClock, BOOT).also { it.restore() }

    // Few iterations keep tests fast; production uses DEFAULT_ITERATIONS.
    override val pinGuard = ParentPinGuard(pinStore, boot, fakeClock, iterations = 1_000, random = SecureRandom())
    override val repository = FakeRepository(items)
    override val shelfSource: ShelfSource = source
    override val shelfCache = ShelfCache(File(cacheDir, "shelf.cache"))
    override val catalogue = EligibleCatalogue(repository)
    override val access = AccessChecker(repository)
    override val standby = StandbyPolicy.native {
        standbyRequests++
        StandbyRequestResult.Requested
    }
    override val standbyConnected: StateFlow<Boolean> = MutableStateFlow(true)
    override val io: CoroutineDispatcher = dispatcher
    override val compute: CoroutineDispatcher = dispatcher

    override fun newPlayer(scope: CoroutineScope): VideoPlayer = fakePlayer

    override fun today(): Long = 20_000L

    override fun log(message: String) = Unit
}
