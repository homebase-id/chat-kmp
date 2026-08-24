package id.homebase.api.youauth

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.browser.RedirectConfig
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.client.http.UriBuilder
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.crypto.EccKeyPair
import id.homebase.api.crypto.EccKeySize
import id.homebase.api.crypto.generateEccKeyPair
import id.homebase.api.crypto.publicKeyToJwkBase64Url
import id.homebase.api.decodeUrl
import id.homebase.api.exception.AuthInProgressException
import id.homebase.api.generateUuidBytes
import id.homebase.api.generateUuidString
import id.homebase.api.storage.SecureStorage
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.share.ShareAuthBridge
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Authentication state for the YouAuth flow. */
@Immutable
sealed interface YouAuthState {
    /** Initial state before stores state is loaded */
    data object Initializing : YouAuthState

    /** User is not authenticated */
    data object Unauthenticated : YouAuthState

    /** Authentication flow is in progress */
    data object Authenticating : YouAuthState

    /** User is authenticated with valid tokens */
    data class Authenticated(
        val identity: OdinId,
        val clientAuthToken: String,
        val sharedSecret: String
    ) : YouAuthState

    /** Authentication failed with an error */
    data class Error(val message: String) : YouAuthState
}

/** Internal state for the auth code flow. */
private data class AuthCodeFlowState(
    val identity: OdinId,
    val password: SecureByteArray,
    val keyPair: EccKeyPair
)

/**
 * [AuthCodeFlowState] flattened for persistence across a full-page redirect (web seamless
 * login). The popup flow keeps the app — and the in-memory [AuthCodeFlowState] — alive; the
 * redirect flow reloads the whole app, so everything `completeAuth` needs must survive in
 * storage. The private key inside [keyPair] is already encrypted with [passwordB64]'s bytes.
 */
@Serializable
private data class PersistedRedirectFlow(
    val identityDomain: String,
    val passwordB64: String,
    val keyPair: EccKeyPair,
    val state: String
)

/**
 * Manages the complete YouAuth authentication flow with state management. Uses YouAuthProvider for
 * HTTP operations.
 *
 * This is the recommended entry point for UI components like LoginViewModel.
 */
class YouAuthFlowManager(
    private val driveSyncManager: DriveSyncManager,
    private val credentialsManager: CredentialsManager,
    private val httpClient: HttpClient,
    private val driveFileProviderCached: DriveFileProviderCached,
    private val publicProfileProviderCached: PublicProfileProviderCached,
    // Platform-level cache teardown invoked during logout, alongside the per-cache
    // clearCaches() calls below. Injected from the module that owns platform
    // singletons (homebase-core) so this class doesn't have to depend on coil3 or
    // on FileOperationsProvider directly. Default no-op keeps existing tests and
    // any construction path that doesn't care about platform caches working.
    private val clearPlatformCaches: suspend () -> Unit = {},
) {
    private val _authState = MutableStateFlow<YouAuthState>(YouAuthState.Initializing)
    val authState: StateFlow<YouAuthState> = _authState.asStateFlow()

    private val scope = CoroutineScope(Job() + ioDispatcher)

    // Registry for callback routing
    private val callbackRegistry = mutableMapOf<String, AuthCodeFlowState>()

    // True once handleCallback() is invoked, preventing onAppResumed from cancelling a
    // finalization that is still in-flight on a slow network.
    @Volatile private var callbackReceived = false

    companion object {
        private val TAG = "YouAuthFlowManager"
        private val json = Json { ignoreUnknownKeys = true }
    }

    init {
        scope.launch {
            try {
                restoreSession()
            } catch (e: Exception) {
                Logger.e(
                    throwable = e,
                    tag = TAG
                ) { "Error checking existing session: ${e.message}" }
            }
        }
    }

    /** Handle an authorization callback URL. */
    suspend fun handleCallback(url: String) {
        callbackReceived = true
        try {
            Logger.d(tag = TAG) { "Received callback: $url" }

            val query = url.substringAfter("?", "")
            if (query.isEmpty()) {
                Logger.e(tag = TAG) { "Missing query params in callback URL" }
                cancelAuth()
                return
            }

            val params =
                query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to (parts.getOrNull(1) ?: "")
                }

            val state = decodeUrl(params["state"] ?: "")
            if (state.isEmpty()) {
                Logger.e(tag = TAG) { "Missing state parameter in callback URL" }
                cancelAuth()
                return
            }

            completeAuth(url, state, params)
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Error handling callback" }
        }
    }

    /** Check if there are stored credentials and restore session. */
    suspend fun restoreSession() {
        if (CredentialStorage.hasStoredCredentials()) {
            val credentials = CredentialStorage.getCredentials()
            if (credentials != null) {
                val identity = credentials.identity
                val apiCredentials = ApiCredentials.create(
                    identity,
                    SecureStorage.get(YouAuthStorageKeys.CLIENT_AUTH_TOKEN)!!,
                    SecureByteArray(SecureStorage.get(YouAuthStorageKeys.SHARED_SECRET)!!)
                )
                credentialsManager.setActiveCredentials(apiCredentials)

                // We don't have the raw tokens here, but we know we're authenticated
                _authState.value =
                    YouAuthState.Authenticated(
                        identity = identity,
                        clientAuthToken = credentials.clientAuthToken,
                        // decided to ditch the old http code Not
                        // needed since OdinClient is configured
                        sharedSecret = Base64.encode(credentials.sharedSecret.unsafeBytes)
                    )
                ShareAuthBridge.setAuthenticated(true, identity.domainName)
                Logger.i(tag = TAG) { "Session restored for $identity" }
                return
            }
        }

        // If we got here, we are not authenticated
        _authState.value = YouAuthState.Unauthenticated
    }

    /**
     * Start the authentication flow.
     *
     * @param identity The user's identity (e.g., "user.homebase.id")
     * @param scope CoroutineScope for launching browser
     * @param appId Application ID
     * @param appName Application name
     * @param drives List of drive access requests
     * @param persistForRedirect Persist the flow state (ECC key pair, password, CSRF state) to
     *   [SecureStorage] so `completeAuth` can restore it after a full-page navigation. Used by
     *   the web seamless-login path, which redirects the top window to the authorize endpoint
     *   (unloading the app) instead of opening a popup. See issue #853.
     */
    suspend fun authorize(
        identity: OdinId,
        appId: String,
        appName: String,
        drives: List<TargetDriveAccessRequest> = emptyList(),
        permissions: List<AppPermissionType>? = null,
        circlePermissions: List<AppCirclePermissionType>? = null,
        circleDrives: List<TargetDriveAccessRequest>? = null,
        circles: List<String>? = null,
        clientFriendlyName: String? = null,
        persistForRedirect: Boolean = false
    ): String {
        if (_authState.value == YouAuthState.Authenticating ||
            _authState.value is YouAuthState.Authenticated
        ) {
            Logger.e(tag = TAG) { "Already authenticating or authenticated" }
            throw AuthInProgressException()
        }

        _authState.value = YouAuthState.Authenticating
        try {
            // Generate key pair for ECDH
            val password = SecureByteArray(generateUuidBytes())
            val keyPair = generateEccKeyPair(password, EccKeySize.P384, 1)

            // Generate unique state for CSRF protection and callback routing
            val state = generateUuidString()
            val authCodeFlowState = AuthCodeFlowState(identity, password, keyPair)

            // Register for callback
            callbackRegistry[state] = authCodeFlowState

            if (persistForRedirect) {
                SecureStorage.put(
                    YouAuthStorageKeys.PENDING_REDIRECT_FLOW,
                    json.encodeToString(
                        PersistedRedirectFlow.serializer(),
                        PersistedRedirectFlow(
                            identityDomain = identity.domainName,
                            passwordB64 = Base64.encode(password.unsafeBytes),
                            keyPair = keyPair,
                            state = state
                        )
                    )
                )
            }

            // Build redirect URI
            val redirectUri = RedirectConfig.buildRedirectUri(appId)

            // Build permission request
            val permissionRequest =
                AppAuthorizationParams.create(
                    appName = appName,
                    appId = appId,
                    friendlyName = clientFriendlyName ?: "Homebase KMP App",
                    drives = drives,
                    circleDrives = circleDrives,
                    circles = circles,
                    permissions = permissions?.map { it.value },
                    circlePermissions = circlePermissions?.map { it.value },
                    returnUrl = redirectUri
                )

            // Build authorization request
            val authRequest =
                YouAuthorizationParams(
                    clientId = appId,
                    clientType = ClientType.app,
                    clientInfo = clientFriendlyName ?: "Homebase KMP App",
                    publicKey = publicKeyToJwkBase64Url(keyPair.publicKey),
                    permissionRequest = permissionRequest.toJson(),
                    state = state,
                    redirectUri = redirectUri
                )

            // Build authorization URL
            val authorizeUrl =
                UriBuilder("https://$identity/api/owner/v1/youauth/authorize")
                    .apply { query = authRequest.toQueryString() }
                    .toString()

            return authorizeUrl
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Error starting authorization" }
            _authState.value = YouAuthState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    /** Complete the authentication flow after browser callback. */
    private suspend fun completeAuth(url: String, state: String, queryParams: Map<String, String>) {
        val authCodeFlowState = callbackRegistry[state] ?: restorePersistedRedirectFlow(state)
        if (authCodeFlowState == null) {
            // Duplicate or late callback — registry entry was already consumed.
            // Don't stomp on the current _authState.
            Logger.d(tag = TAG) { "Ignoring callback for state $state — no pending flow (likely duplicate delivery)" }
            return
        }

        try {
            if (!url.contains("/authorization-code-callback")) {
                throw Exception("Missing /authorization-code-callback")
            }

            val identity = try {
                OdinId(decodeUrl(queryParams["identity"] ?: ""))
            } catch (_: Exception) {
                throw Exception("Invalid query param: identity")
            }

            val publicKey = decodeUrl(queryParams["public_key"] ?: "")
            if (publicKey.isEmpty()) throw Exception("Missing query param: public_key")

            val salt = decodeUrl(queryParams["salt"] ?: "")
            if (salt.isEmpty()) throw Exception("Missing query param: salt")

            // Create unauthenticated client for token exchange
            val provider = YouAuthProvider(httpClient, authCodeFlowState.identity)

            // Finalize authentication
            val result =
                provider.finalizeAuthentication(
                    identity = identity,
                    keyPair = authCodeFlowState.keyPair,
                    password = authCodeFlowState.password,
                    publicKey = publicKey,
                    salt = salt
                )

            // Save credentials
            CredentialStorage.saveCredentials(
                identity = result.identity,
                clientAuthToken = result.clientAuthToken,
                sharedSecret = Base64.decode(result.sharedSecret)
            )

            val apiCredentials = ApiCredentials.create(
                result.identity,
                result.clientAuthToken,
                SecureByteArray(result.sharedSecret)
            )
            credentialsManager.setActiveCredentials(apiCredentials)

            // Update state
            _authState.value =
                YouAuthState.Authenticated(
                    identity = result.identity,
                    clientAuthToken = result.clientAuthToken,
                    sharedSecret = result.sharedSecret
                )

            ShareAuthBridge.setAuthenticated(true, result.identity.domainName)
            Logger.i(tag = TAG) { "Authentication completed successfully for ${result.identity}" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Error completing auth" }
            _authState.value = YouAuthState.Error(e.message ?: "Unknown error")
        } finally {
            callbackRegistry.remove(state)
            SecureStorage.remove(YouAuthStorageKeys.PENDING_REDIRECT_FLOW)
            callbackReceived = false
        }
    }

    /**
     * Restore a redirect-flow [AuthCodeFlowState] persisted by [authorize] with
     * `persistForRedirect = true`. Returns null unless a persisted flow exists AND its CSRF
     * `state` matches the callback's — a mismatched state means the callback is stale or forged
     * and must not consume the pending flow's key material.
     *
     * On success, flips [authState] to [YouAuthState.Authenticating]: this app instance booted
     * fresh after the redirect (restoreSession found no credentials and reported
     * Unauthenticated), and the token exchange is now genuinely in progress.
     */
    private fun restorePersistedRedirectFlow(state: String): AuthCodeFlowState? {
        val serialized = SecureStorage.get(YouAuthStorageKeys.PENDING_REDIRECT_FLOW) ?: return null
        return try {
            val persisted = json.decodeFromString(PersistedRedirectFlow.serializer(), serialized)
            if (persisted.state != state) {
                Logger.w(tag = TAG) { "Persisted redirect flow state mismatch — ignoring callback" }
                null
            } else {
                _authState.value = YouAuthState.Authenticating
                AuthCodeFlowState(
                    identity = OdinId(persisted.identityDomain),
                    password = SecureByteArray(persisted.passwordB64),
                    keyPair = persisted.keyPair
                )
            }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Failed to restore persisted redirect flow" }
            SecureStorage.remove(YouAuthStorageKeys.PENDING_REDIRECT_FLOW)
            null
        }
    }

    /**
     * Logout and clear credentials.
     *
     * Every teardown step is individually guarded: once we've decided to log out, no single
     * failing step may block the `_authState` flip below. A corrupted shared secret or DB key
     * makes calls like [DriveSyncManager.clearStorage] throw, and an unguarded throw here used
     * to leave the app wedged half-authenticated with a dead logout button — recoverable only
     * by clearing app data.
     *
     * Pass [force] to skip the backend notify entirely (dev menu escape hatch) — the server
     * round-trip needs valid credentials, which is exactly what's broken in that state.
     */
    suspend fun logout(force: Boolean = false) {
        // Notify the backend first — this needs valid credentials.
        if (!force) {
            try {
                val credentials = CredentialStorage.getCredentials()
                if (credentials != null) {
                    val provider = YouAuthProvider(httpClient, credentials.identity)
                    provider.logout()
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Error during logout" }
            }
        }

        // Tear down background work that reads credentials BEFORE nulling them.
        // Cancels in-flight DriveSync jobs and empties driveSyncs so the retry
        // scheduler in DriveSyncManager.init can't schedule new work against
        // cleared credentials (was a source of uncaught
        // IllegalStateException: No active credentials set). stop() is idempotent;
        // AuthConnectionCoordinator.disconnect() will call it again when the
        // authState flip below lands.
        stepOrLog("driveSyncManager.stop") { driveSyncManager.stop() }

        // Wipe all identity-scoped state BEFORE flipping _authState to Unauthenticated.
        // Emitting Unauthenticated tears down the authenticated nav graph (and with it
        // SettingsViewModel.viewModelScope, which is the coroutine currently running
        // this logout). If we emit first, driveSyncManager.clearStorage() — and any
        // other cache clears — get cancelled mid-flight, leaving stale DB rows behind.
        //
        // Credentials go first. They are what "logged in" actually means: CredentialStorage is
        // the persistent copy that restoreSession() reads on the next launch, so if it survives,
        // the user is silently signed back in no matter what the UI showed. Everything below it
        // is cache/row cleanup — worth doing, but none of it can un-log-you-out. Ordering the
        // fragile DB teardown ahead of the credential wipe (as this used to) meant a throwing
        // clearStorage() left the Keychain populated.
        stepOrLog("removeActiveCredentials") { credentialsManager.removeActiveCredentials() }
        stepOrLog("clearCredentials") { CredentialStorage.clearCredentials() }
        stepOrLog("clearAuth") { ShareAuthBridge.clearAuth() }

        stepOrLog("clearStorage") { driveSyncManager.clearStorage() }
        stepOrLog("driveFileProvider.clearCaches") { driveFileProviderCached.clearCaches() }
        stepOrLog("publicProfileProvider.clearCaches") { publicProfileProviderCached.clearCaches() }
        // Platform caches (Coil memory cache, orphan coil3_disk_cache dir, anything
        // else the app-level module wants to flush).
        stepOrLog("clearPlatformCaches") { clearPlatformCaches() }

        _authState.value = YouAuthState.Unauthenticated
        Logger.i(tag = TAG) { "User logged out" }
    }


    /** Check if authentication is in progress. */
    val isAuthenticating: Boolean
        get() = _authState.value == YouAuthState.Authenticating

    /**
     * Cancel the current authentication flow. Call this when the user cancels the browser or
     * navigates away.
     */
    suspend fun cancelAuth() {
        if (_authState.value == YouAuthState.Authenticating) {
            Logger.i(tag = TAG) { "Authentication cancelled by user" }
            callbackRegistry.clear()
            SecureStorage.remove(YouAuthStorageKeys.PENDING_REDIRECT_FLOW)
            callbackReceived = false
            _authState.value = YouAuthState.Unauthenticated
            credentialsManager.removeActiveCredentials()
        }
    }

    /**
     * Called when the app resumes from background. If we were authenticating and come back without
     * a callback, the user likely cancelled.
     *
     * @param delayMs Optional delay to wait for callback before cancelling (default 500ms)
     */
    suspend fun onAppResumed(delayMs: Long = 500) {
        // If the deep-link callback already arrived, do not interfere — finalizeAuthentication()
        // may still be running on a slow network and we must not cancel it.
        if (callbackReceived) return

        if (_authState.value == YouAuthState.Authenticating) {
            // Wait a short time for callback to potentially arrive
            delay(delayMs)

            // If still authenticating and no callback arrived, assume user cancelled
            if (!callbackReceived && _authState.value == YouAuthState.Authenticating) {
                Logger.i(tag = TAG) { "App resumed without auth callback, assuming user cancelled" }
                cancelAuth()
            }
        }
    }
}

/**
 * Run one logout teardown step, logging and swallowing any failure so the caller can always
 * reach the `_authState` flip. Cancellation is rethrown — it means our own scope is going away,
 * which is a different situation from a step that simply failed.
 */
internal suspend fun stepOrLog(name: String, step: suspend () -> Unit) {
    try {
        step()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = "YouAuth") { "Logout step '$name' failed — continuing" }
    }
}
