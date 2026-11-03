package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.storage.SecureStorage
import id.homebase.api.youauth.AppPermissionType
import id.homebase.api.youauth.AuthResult
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.config.filesyncAppRegistration
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64

/**
 * Temporary, until one sign-in reaches several apps: this device signs in to Filesync as well, since only the app that
 * owns its drive may write circles, their keys and shares on it. That token does nothing else; the app's own session,
 * keys and credentials never see it. Signing out of the app forgets it ([forget]).
 */
object FilesyncSession {
    private const val IDENTITY = "filesync_session_identity"
    private const val TOKEN = "filesync_session_token"
    private const val SHARED_SECRET = "filesync_session_shared_secret"
    private const val APP_KEY = "filesync_session_app_key"

    /** Filesync's token, for the providers that write as Filesync. */
    val credentials = CredentialsManager()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** Filesync's AppZkK, which locks its circles' keys. */
    var appKey: ByteArray? = null
        private set

    /** Loads the stored sign-in when it is [identity]'s; a stored one of another identity is forgotten. */
    suspend fun restore(identity: OdinId): Boolean {
        if (_signedIn.value) return true
        val stored = SecureStorage.get(IDENTITY) ?: return false
        if (!stored.equals(identity.domainName, ignoreCase = true)) {
            forget()
            return false
        }
        val token = SecureStorage.get(TOKEN) ?: return false
        val secret = SecureStorage.get(SHARED_SECRET)?.let(Base64::decode) ?: return false
        val key = SecureStorage.get(APP_KEY)?.let(Base64::decode) ?: return false
        credentials.setActiveCredentials(ApiCredentials.create(identity, token, SecureByteArray(secret)))
        appKey = key
        _signedIn.value = true
        return true
    }

    internal suspend fun save(result: AuthResult) {
        val key = checkNotNull(result.zkAppKey) { "Filesync's sign-in brought no app key" }
        val secret = Base64.decode(result.sharedSecret)
        SecureStorage.put(IDENTITY, result.identity.domainName)
        SecureStorage.put(TOKEN, result.clientAuthToken)
        SecureStorage.put(SHARED_SECRET, Base64.encode(secret))
        SecureStorage.put(APP_KEY, Base64.encode(key))
        credentials.setActiveCredentials(ApiCredentials.create(result.identity, result.clientAuthToken, SecureByteArray(secret)))
        appKey = key
        _signedIn.value = true
    }

    suspend fun forget() {
        listOf(IDENTITY, TOKEN, SHARED_SECRET, APP_KEY).forEach(SecureStorage::remove)
        credentials.removeAllCredentials()
        appKey = null
        _signedIn.value = false
    }

    /**
     * The browser URL that signs [identity] in to Filesync; [onDone] hears whether it worked. It asks for
     * ReadConnections, which the circle every connection joins needs; an installed Filesync gets it only through
     * the owner's app page ([sharingPermissions]).
     */
    suspend fun signInUrl(flow: YouAuthFlowManager, identity: OdinId, onDone: suspend (Boolean) -> Unit): String =
        flow.authorize(
            identity = identity,
            appId = filesyncAppRegistration.appId,
            appName = filesyncAppRegistration.name,
            appSlug = FilesyncProtocol.AppSlug,
            drives = filesyncAppRegistration.drives,
            permissions = sharingPermissions,
            otherApp = { result -> onDone(result.mapCatching { save(it) }.isSuccess) },
        )

    val sharingPermissions = listOf(AppPermissionType.ReadConnections)
}
