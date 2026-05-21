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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64

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
        clientFriendlyName: String? = null
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
        val authCodeFlowState = callbackRegistry[state]
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
            callbackReceived = false
        }
    }

    /** Logout and clear credentials. */
    suspend fun logout() {
        // Notify the backend first — this needs valid credentials.
        try {
            val credentials = CredentialStorage.getCredentials()
            if (credentials != null) {
                val provider = YouAuthProvider(httpClient, credentials.identity)
                provider.logout()
            }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Error during logout" }
        }

        // Tear down background work that reads credentials BEFORE nulling them.
        // Cancels in-flight DriveSync jobs and empties driveSyncs so the retry
        // scheduler in DriveSyncManager.init can't schedule new work against
        // cleared credentials (was a source of uncaught
        // IllegalStateException: No active credentials set). stop() is idempotent;
        // AuthConnectionCoordinator.disconnect() will call it again when the
        // authState flip below lands.
        driveSyncManager.stop()

        // Wipe all identity-scoped state BEFORE flipping _authState to Unauthenticated.
        // Emitting Unauthenticated tears down the authenticated nav graph (and with it
        // SettingsViewModel.viewModelScope, which is the coroutine currently running
        // this logout). If we emit first, driveSyncManager.clearStorage() — and any
        // other cache clears — get cancelled mid-flight, leaving stale DB rows behind.
        credentialsManager.removeActiveCredentials()
        driveSyncManager.clearStorage()
        driveFileProviderCached.clearCaches()
        publicProfileProviderCached.clearCaches()
        // Platform caches (Coil memory cache, orphan coil3_disk_cache dir, anything
        // else the app-level module wants to flush). Wrapped in runCatching so a
        // failing hook can't block the authState flip that follows — we'd rather
        // log out with a stale Coil entry than get stuck half-authenticated.
        runCatching { clearPlatformCaches() }
            .onFailure { Logger.e(throwable = it, tag = TAG) { "clearPlatformCaches failed" } }
        CredentialStorage.clearCredentials()
        ShareAuthBridge.clearAuth()

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
