package id.homebase.api.youauth

import id.homebase.api.storage.SecureStorage
import id.homebase.api.client.http.OdinClient
import id.homebase.api.client.http.ProviderOptions
import kotlin.io.encoding.Base64

/**
 * Factory for creating OdinClient instances with proper configuration. Handles loading credentials
 * from SecureStorage.
 */
object OdinClientFactory {

    /**
     * Create an OdinClient from stored credentials.
     * @return OdinClient if credentials exist, null otherwise
     */
    fun createFromStorage(): OdinClient? {
        val identity = SecureStorage.get(YouAuthStorageKeys.IDENTITY) ?: return null
        val sharedSecretBase64 = SecureStorage.get(YouAuthStorageKeys.SHARED_SECRET) ?: return null
        val clientAuthToken = SecureStorage.get(YouAuthStorageKeys.CLIENT_AUTH_TOKEN) ?: return null

        val sharedSecret =
            try {
                Base64.decode(sharedSecretBase64)
            } catch (e: Exception) {
                return null
            }

        return create(
            identity = identity,
            sharedSecret = sharedSecret,
            clientAuthToken = clientAuthToken
        )
    }

    /**
     * Create an authenticated OdinClient.
     *
     * @param identity The user's identity (e.g., "user.homebase.id")
     * @param sharedSecret The shared secret for request encryption
     * @param clientAuthToken The client auth token for authentication headers
     */
    fun create(
        identity: String,
        sharedSecret: ByteArray,
        clientAuthToken: String
    ): OdinClient {
        return OdinClient(
            ProviderOptions(
                sharedSecret = sharedSecret,
                hostIdentity = identity,
                loggedInIdentity = identity,
                headers = buildAuthHeaders(clientAuthToken)
            )
        )
    }

    /**
     * Create an unauthenticated OdinClient for use during auth flow.
     *
     * @param identity The host identity for API requests
     */
    fun createUnauthenticated(identity: String): OdinClient {
        return OdinClient(
            ProviderOptions(
                sharedSecret = null,
                hostIdentity = identity,
                loggedInIdentity = null,
                headers = null
            )
        )
    }

    /** Save authentication credentials to secure storage. */
    fun saveCredentials(identity: String, clientAuthToken: String, sharedSecret: ByteArray) {
        SecureStorage.put(YouAuthStorageKeys.IDENTITY, identity)
        SecureStorage.put(YouAuthStorageKeys.CLIENT_AUTH_TOKEN, clientAuthToken)
        SecureStorage.put(YouAuthStorageKeys.SHARED_SECRET, Base64.encode(sharedSecret))
    }

    /** Clear all stored credentials. */
    fun clearCredentials() {
        SecureStorage.remove(YouAuthStorageKeys.IDENTITY)
        SecureStorage.remove(YouAuthStorageKeys.CLIENT_AUTH_TOKEN)
        SecureStorage.remove(YouAuthStorageKeys.SHARED_SECRET)
    }

    /** Check if credentials are stored. */
    fun hasStoredCredentials(): Boolean {
        return SecureStorage.contains(YouAuthStorageKeys.IDENTITY) &&
                SecureStorage.contains(YouAuthStorageKeys.SHARED_SECRET) &&
                SecureStorage.contains(YouAuthStorageKeys.CLIENT_AUTH_TOKEN)
    }

    private fun buildAuthHeaders(clientAuthToken: String): Map<String, String> {
        return mapOf("Authorization" to "Bearer $clientAuthToken")
    }
}
