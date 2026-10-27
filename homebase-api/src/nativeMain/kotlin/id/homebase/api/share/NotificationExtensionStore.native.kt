package id.homebase.api.share

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.storage.KeychainStore
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import platform.Foundation.NSUserDefaults

actual object NotificationExtensionStore {

    private val keychain = KeychainStore("id.homebase.nse.credentials", APP_GROUP_ID)
    private const val KEY_CREDENTIALS = "nse_credentials"
    private const val KEY_CONTENT_LEVEL = "notification_content_level"

    // One item, so the extension can never read a token from one identity with another's secret.
    @Serializable
    private data class StoredCredentials(val domain: String, val token: String, val secret: String)

    actual fun publishCredentials(credentials: ApiCredentials) {
        val stored = StoredCredentials(
            domain = credentials.domain.domainName,
            token = credentials.clientAccessToken,
            secret = Base64.encode(credentials.sharedSecret.unsafeBytes),
        )
        keychain.put(KEY_CREDENTIALS, OdinSystemSerializer.serialize(stored))
    }

    actual fun readCredentials(): ApiCredentials? {
        val raw = keychain.get(KEY_CREDENTIALS) ?: return null
        return try {
            val stored = OdinSystemSerializer.deserialize<StoredCredentials>(raw)
            ApiCredentials.create(OdinId(stored.domain), stored.token, SecureByteArray(stored.secret))
        } catch (_: Exception) {
            null
        }
    }

    actual fun clearCredentials() = keychain.delete(KEY_CREDENTIALS)

    actual fun publishContentLevel(level: String) {
        NSUserDefaults(suiteName = APP_GROUP_ID).setObject(level, forKey = KEY_CONTENT_LEVEL)
    }

    actual fun readContentLevel(): String? =
        NSUserDefaults(suiteName = APP_GROUP_ID).stringForKey(KEY_CONTENT_LEVEL)
}
