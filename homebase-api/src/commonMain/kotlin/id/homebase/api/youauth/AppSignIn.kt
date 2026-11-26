package id.homebase.api.youauth

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.storage.SecureStorage
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64

/**
 * Temporary, until one sign-in reaches several apps: this device signs in to another installed app as well, since only
 * the app that owns a drive may write circles, their keys and shares on it. That token does nothing else; this app's
 * own session, keys and credentials never see it. Signing out of this app forgets it ([forget]).
 */
open class AppSignIn(
    private val keyPrefix: String,
    private val registration: AppRegistrationParams,
    /** What sharing needs of the app's grant; an installed app gets it only through the owner's app page. */
    val sharingPermissions: List<AppPermissionType>,
    private val requester: String,
) {
    private val identityKey get() = "${keyPrefix}_identity"
    private val tokenKey get() = "${keyPrefix}_token"
    private val sharedSecretKey get() = "${keyPrefix}_shared_secret"
    private val appKeyKey get() = "${keyPrefix}_app_key"

    /** Where the sign-in is kept between runs; tests keep it in memory. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    var store: Store = object : Store {
        override fun get(key: String) = SecureStorage.get(key)
        override fun put(key: String, value: String) = SecureStorage.put(key, value)
        override fun remove(key: String) = SecureStorage.remove(key)
    }

    /** The app's token, for the providers that write as it. */
    val credentials = CredentialsManager()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** The app's AppZkK, which locks its circles' keys. */
    var appKey: ByteArray? = null
        private set

    /** [restore]s the identity [app] is signed in as; whether this app is signed in now. */
    suspend fun restoreFor(app: CredentialsManager): Boolean =
        app.getActiveCredentials()?.domain?.let { restore(it) } ?: false

    /** Loads the stored sign-in when it is [identity]'s; a stored one of another identity is forgotten. */
    suspend fun restore(identity: OdinId): Boolean {
        if (_signedIn.value) return true
        val stored = store.get(identityKey) ?: return false
        if (!stored.equals(identity.domainName, ignoreCase = true)) {
            forget()
            return false
        }
        val token = store.get(tokenKey) ?: return false
        val secret = store.get(sharedSecretKey)?.let(Base64::decode) ?: return false
        val key = store.get(appKeyKey)?.let(Base64::decode) ?: return false
        use(ApiCredentials.create(identity, token, SecureByteArray(secret)), key)
        return true
    }

    suspend fun save(result: AuthResult) {
        val key = checkNotNull(result.zkAppKey) { "${registration.name}'s sign-in brought no app key" }
        store.put(identityKey, result.identity.domainName)
        store.put(tokenKey, result.clientAuthToken)
        store.put(sharedSecretKey, result.sharedSecret)
        store.put(appKeyKey, Base64.encode(key))
        use(ApiCredentials.create(result.identity, result.clientAuthToken, SecureByteArray(Base64.decode(result.sharedSecret))), key)
    }

    suspend fun use(app: ApiCredentials, key: ByteArray) {
        credentials.setActiveCredentials(app)
        appKey = key
        _signedIn.value = true
    }

    suspend fun forget() {
        listOf(identityKey, tokenKey, sharedSecretKey, appKeyKey).forEach(store::remove)
        credentials.removeAllCredentials()
        appKey = null
        _signedIn.value = false
    }

    /** The browser URL that signs [identity] in to the app, asking for [sharingPermissions]; [onDone] hears whether it worked. */
    suspend fun signInUrl(flow: YouAuthFlowManager, identity: OdinId, onDone: suspend (Boolean) -> Unit): String =
        flow.authorize(
            identity = identity,
            appId = registration.appId,
            appName = registration.name,
            appSlug = registration.appSlug,
            drives = registration.drives,
            permissions = sharingPermissions,
            otherApp = { result -> onDone(result.mapCatching { save(it) }.isSuccess) },
        )

    /** The owner's page granting the app what sharing needs, or null once it has it. */
    suspend fun missingPermissionsUrl(httpClient: HttpClient, identity: OdinId, returnUrl: () -> String): String? {
        val config = PermissionExtensionConfig(
            appId = registration.appId,
            appName = registration.name,
            drives = emptyList(),
            permissions = sharingPermissions,
            returnUrl = returnUrl,
            requester = requester,
        )
        return when (val result = PermissionExtensionManager(SecurityContextProvider(httpClient, credentials), identity.domainName).getMissingPermissions(config)) {
            PermissionCheckResult.AllGranted -> null
            is PermissionCheckResult.Missing -> result.details.buildExtendPermissionUrl()
            PermissionCheckResult.Unknown -> error("couldn't read ${registration.name}'s permissions")
        }
    }
}
