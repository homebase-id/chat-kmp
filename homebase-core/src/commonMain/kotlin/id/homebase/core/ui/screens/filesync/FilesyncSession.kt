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

    /** Where the sign-in is kept between runs; tests keep it in memory. */
    internal interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    internal var store: Store = object : Store {
        override fun get(key: String) = SecureStorage.get(key)
        override fun put(key: String, value: String) = SecureStorage.put(key, value)
        override fun remove(key: String) = SecureStorage.remove(key)
    }

    /** Filesync's token, for the providers that write as Filesync. */
    val credentials = CredentialsManager()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** Filesync's AppZkK, which locks its circles' keys. */
    var appKey: ByteArray? = null
        private set

    /** [restore]s the identity [app] is signed in as; whether Filesync is signed in now. */
    suspend fun restoreFor(app: CredentialsManager): Boolean =
        app.getActiveCredentials()?.domain?.let { restore(it) } ?: false

    /** Loads the stored sign-in when it is [identity]'s; a stored one of another identity is forgotten. */
    suspend fun restore(identity: OdinId): Boolean {
        if (_signedIn.value) return true
        val stored = store.get(IDENTITY) ?: return false
        if (!stored.equals(identity.domainName, ignoreCase = true)) {
            forget()
            return false
        }
        val token = store.get(TOKEN) ?: return false
        val secret = store.get(SHARED_SECRET)?.let(Base64::decode) ?: return false
        val key = store.get(APP_KEY)?.let(Base64::decode) ?: return false
        use(ApiCredentials.create(identity, token, SecureByteArray(secret)), key)
        return true
    }

    internal suspend fun save(result: AuthResult) {
        val key = checkNotNull(result.zkAppKey) { "Filesync's sign-in brought no app key" }
        store.put(IDENTITY, result.identity.domainName)
        store.put(TOKEN, result.clientAuthToken)
        store.put(SHARED_SECRET, result.sharedSecret)
        store.put(APP_KEY, Base64.encode(key))
        use(ApiCredentials.create(result.identity, result.clientAuthToken, SecureByteArray(Base64.decode(result.sharedSecret))), key)
    }

    internal suspend fun use(filesync: ApiCredentials, key: ByteArray) {
        credentials.setActiveCredentials(filesync)
        appKey = key
        _signedIn.value = true
    }

    suspend fun forget() {
        listOf(IDENTITY, TOKEN, SHARED_SECRET, APP_KEY).forEach(store::remove)
        credentials.removeAllCredentials()
        appKey = null
        _signedIn.value = false
    }

    /**
     * The browser URL that signs [identity] in to Filesync; [onDone] hears whether it worked. It asks for
     * ReadConnections, which sharing needs; an installed Filesync gets it only through the
     * owner's app page ([sharingPermissions]).
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
