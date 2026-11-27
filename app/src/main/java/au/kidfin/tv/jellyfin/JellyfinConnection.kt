// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.jellyfin

import au.kidfin.tv.media.MediaLibrary
import au.kidfin.tv.session.CorruptStateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.serializer.toUUID
import java.net.URI
import java.net.UnknownServiceException
import java.util.UUID

interface ServerConfigStore {
    /** Null when never set up. @throws CorruptStateException if unreadable. */
    fun load(): ServerConfig?
    fun save(config: ServerConfig)
    fun clear()
}

interface TokenStore {
    /** Null when absent or undecryptable (for example after the Keystore key was lost). */
    fun load(): String?
    fun save(token: String)
    fun clear()
}

sealed interface ConnectionState {
    data object NotSetUp : ConnectionState

    /** Signed in, but the parent has not chosen any library yet. */
    data class NeedsLibraries(val config: ServerConfig) : ConnectionState
    data class Ready(val config: ServerConfig) : ConnectionState

    /** The token was rejected or lost. Nothing is shown to the child until a parent signs in. */
    data class SignedOut(val config: ServerConfig) : ConnectionState
}

sealed interface SignInResult {
    data object Done : SignInResult
    data class Refused(val message: String) : SignInResult
}

/** The Jellyfin account and libraries are not usable right now. Never shown verbatim to the child. */
class NotConnectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns the Jellyfin session for the dedicated child account: sign-in, token, chosen libraries.
 * The token is held in memory and in [TokenStore] only; it is never logged or put in config.
 */
class JellyfinConnection(
    private val jellyfin: Jellyfin,
    private val configStore: ServerConfigStore,
    private val tokenStore: TokenStore,
) : ServerAccount {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.NotSetUp)
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    @Volatile
    private var client: ApiClient? = null

    fun restore() {
        val config = try {
            configStore.load()
        } catch (e: CorruptStateException) {
            configStore.clear()
            tokenStore.clear()
            null
        }
        if (config == null) {
            mutableState.value = ConnectionState.NotSetUp
            return
        }
        val token = tokenStore.load()
        if (token == null) {
            mutableState.value = ConnectionState.SignedOut(config)
            return
        }
        client = createClient(config, token)
        mutableState.value = stateFor(config)
    }

    override val config: ServerConfig?
        get() = when (val s = state.value) {
            ConnectionState.NotSetUp -> null
            is ConnectionState.NeedsLibraries -> s.config
            is ConnectionState.Ready -> s.config
            is ConnectionState.SignedOut -> s.config
        }

    override val allowedLibraryIds: Set<String>
        get() = (state.value as? ConnectionState.Ready)?.config?.libraryIds.orEmpty()

    override suspend fun signIn(address: String, username: String, password: String): SignInResult =
        withContext(Dispatchers.IO) { signInBlocking(address, username, password) }

    private suspend fun signInBlocking(address: String, username: String, password: String): SignInResult {
        val baseUrl = ServerAddress.normalise(address)
            ?: return SignInResult.Refused("That doesn't look like a server address.")
        if (username.isBlank()) return SignInResult.Refused("Type the child account's username.")
        val previous = config
        val deviceId = previous?.deviceId ?: UUID.randomUUID().toString()
        val api = jellyfin.createApi(baseUrl = baseUrl, deviceInfo = DeviceInfo(deviceId, DEVICE_NAME))

        val info = try {
            api.systemApi.getPublicSystemInfo().content
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure("server info", e)
            return SignInResult.Refused(describeUnreachable(e))
        }
        val version = info.version?.let(ServerVersion::fromString)
        if (version == null || version < Jellyfin.minimumVersion) {
            return SignInResult.Refused("This server is too old for Kidfin (needs Jellyfin ${Jellyfin.minimumVersion} or newer).")
        }

        val auth = try {
            api.authenticationApi.authenticateUserByName(username.trim(), password).content
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidStatusException) {
            return SignInResult.Refused(
                if (e.status == 401 || e.status == 403) "Wrong username or password." else "The server refused the sign-in (${e.status}).",
            )
        } catch (e: Exception) {
            logFailure("sign-in", e)
            return SignInResult.Refused(describeUnreachable(e))
        }
        val token = auth.accessToken
        val user = auth.user
        if (token == null || user == null) return SignInResult.Refused("The server didn't return a session.")
        api.update(accessToken = token)

        if (user.policy?.isAdministrator != false) {
            // Never keep an admin session on a child-facing device.
            runCatching { api.sessionApi.reportSessionEnded() }
            return SignInResult.Refused("That's an admin account. Use the child account, not an admin.")
        }

        val sameAccount = previous != null && previous.baseUrl == baseUrl && previous.userId == user.id.toString()
        val config = ServerConfig(
            baseUrl = baseUrl,
            userId = user.id.toString(),
            userName = user.name ?: username.trim(),
            deviceId = deviceId,
            libraryIds = if (sameAccount) previous.libraryIds else emptySet(),
            libraryNames = if (sameAccount) previous.libraryNames else emptyList(),
        )
        try {
            tokenStore.save(token)
            configStore.save(config)
        } catch (e: Exception) {
            // Keystore or disk trouble: don't half-sign-in. Forget the token and the server session.
            logFailure("saving sign-in", e)
            runCatching { tokenStore.clear() }
            runCatching { api.sessionApi.reportSessionEnded() }
            return SignInResult.Refused("Couldn't save the sign-in on this TV. Try again.")
        }
        client = api
        mutableState.value = stateFor(config)
        return SignInResult.Done
    }

    override suspend fun canSeeAllLibraries(): Boolean = call { api, _ ->
        api.userApi.getCurrentUser().content.policy?.enableAllFolders == true
    }

    /** Video libraries visible to the child account, for the parent to choose from. */
    suspend fun videoLibraries(): List<MediaLibrary> = call { api, config ->
        api.userViewApi.getUserViews(userId = config.userId.toUUID()).content.items
            .filter { it.collectionType in VIDEO_COLLECTIONS }
            .map { MediaLibrary(it.id.toString(), it.name ?: "Library") }
    }

    override fun saveLibraries(libraries: List<MediaLibrary>): Boolean {
        val current = config ?: return false
        val updated = current.copy(libraryIds = libraries.map { it.id }.toSet(), libraryNames = libraries.map { it.name })
        try {
            configStore.save(updated)
        } catch (e: Exception) {
            logFailure("saving libraries", e)
            return false
        }
        if (state.value !is ConnectionState.SignedOut) mutableState.value = stateFor(updated)
        return true
    }

    /** Ends the server session (best effort) and forgets everything. */
    suspend fun forget() = withContext(Dispatchers.IO) {
        val api = client
        client = null
        tokenStore.clear()
        configStore.clear()
        mutableState.value = ConnectionState.NotSetUp
        if (api != null) runCatching { api.sessionApi.reportSessionEnded() }
        Unit
    }

    /**
     * Runs [block] with the signed-in client. A 401 marks the connection signed out so the
     * child sees nothing until a parent signs in again.
     */
    suspend fun <T> call(block: suspend (ApiClient, ServerConfig) -> T): T {
        val api = client ?: throw NotConnectedException("not signed in")
        val config = config ?: throw NotConnectedException("not set up")
        try {
            // The SDK's OkHttp client may block the calling thread; never let that be Main.
            return withContext(Dispatchers.IO) { block(api, config) }
        } catch (e: InvalidStatusException) {
            if (e.status == 401) {
                markSignedOut()
                throw NotConnectedException("session expired", e)
            }
            throw e
        }
    }

    /** Authorization header for media and artwork requests to the server only. */
    fun authHeaderFor(url: String): String? {
        val api = client ?: return null
        val token = api.accessToken ?: return null
        val base = api.baseUrl ?: return null
        if (!sameOrigin(base, url)) return null
        return AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = token,
        )
    }

    /** Class names only: exception messages can contain URLs. */
    private fun logFailure(step: String, e: Throwable) {
        val chain = generateSequence(e) { it.cause }.take(4).joinToString(" <- ") { it.javaClass.simpleName }
        android.util.Log.i("KidFin", "jellyfin $step failed: $chain")
    }

    private fun markSignedOut() {
        val current = config ?: return
        client = null
        tokenStore.clear()
        mutableState.value = ConnectionState.SignedOut(current)
    }

    private fun createClient(config: ServerConfig, token: String): ApiClient = jellyfin.createApi(
        baseUrl = config.baseUrl,
        accessToken = token,
        deviceInfo = DeviceInfo(config.deviceId, DEVICE_NAME),
    )

    private fun stateFor(config: ServerConfig): ConnectionState =
        if (config.libraryIds.isEmpty()) ConnectionState.NeedsLibraries(config) else ConnectionState.Ready(config)

    companion object {
        const val DEVICE_NAME = "Kidfin TV"

        private val VIDEO_COLLECTIONS = setOf(
            CollectionType.MOVIES,
            CollectionType.TVSHOWS,
            CollectionType.HOMEVIDEOS,
            CollectionType.MUSICVIDEOS,
            CollectionType.FOLDERS,
            null,
        )

        fun sameOrigin(base: String, url: String): Boolean = try {
            val a = URI(base)
            val b = URI(url)
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                effectivePort(a) == effectivePort(b)
        } catch (e: Exception) {
            false
        }

        private fun effectivePort(u: URI): Int = when {
            u.port != -1 -> u.port
            u.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

        internal fun describeUnreachable(e: Throwable): String {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is UnknownServiceException) {
                    return "This TV refused a plain http:// connection to that server. Try its https:// address."
                }
                cause = cause.cause
            }
            return if (e is ApiClientException) "Can't reach that server. Check the address and that it's on." else "Can't reach that server."
        }
    }
}
