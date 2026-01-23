package id.homebase.homebasekmppoc.lib.youauth

import co.touchlab.kermit.Logger
import id.homebase.api.crypto.AesCbc
import id.homebase.api.youauth.YouAuthorizationParams
import id.homebase.homebasekmppoc.prototype.lib.core.SecureByteArray
import id.homebase.homebasekmppoc.prototype.lib.crypto.EccKeyPair
import id.homebase.homebasekmppoc.prototype.lib.crypto.EccKeySize
import id.homebase.homebasekmppoc.prototype.lib.crypto.HashUtil
import id.homebase.homebasekmppoc.prototype.lib.crypto.generateEccKeyPair
import id.homebase.homebasekmppoc.prototype.lib.crypto.performEcdhKeyAgreement
import id.homebase.homebasekmppoc.prototype.lib.crypto.publicKeyFromJwkBase64Url
import id.homebase.homebasekmppoc.prototype.lib.crypto.publicKeyToJwkBase64Url
import id.homebase.homebasekmppoc.prototype.lib.http.CreateHttpClientOptions
import id.homebase.homebasekmppoc.prototype.lib.http.OdinClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.io.encoding.Base64

/** Result of successful authentication. */
data class AuthResult(val clientAuthToken: String, val sharedSecret: String, val identity: String)

/**
 * YouAuth authentication provider. Handles token verification, token exchange, and authentication
 * finalization.
 *
 * This is a service layer class that does not manage UI state. Use with YouAuthManager for complete
 * auth flow with UI integration.
 *
 * @param odinClient The OdinClient instance to use for HTTP requests
 */
class YouAuthProvider(private val odinClient: OdinClient) {

    companion object {
        private const val TAG = "YouAuthProvider"
    }

    /**
     * Check if the current authentication token is valid.
     *
     * @return true if valid, false if invalid, null if network error
     */
    suspend fun hasValidToken(): Boolean? {
        return try {
            val client =
                odinClient.createHttpClient(CreateHttpClientOptions(overrideEncryption = true))
            val response = client.get("/auth/verifytoken")
            when (response.status.value) {
                200 -> true
                401, 403 -> false
                else -> null
            }
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Error verifying token" }
            null
        }
    }

    /**
     * Build registration parameters for app authorization.
     *
     * @param returnUrl The redirect URL after authorization
     * @param appName Application name to display
     * @param appId Application identifier
     * @param drives List of drive access requests
     * @param publicKey ECC public key for key exchange
     * @param host Optional host override
     * @param clientFriendlyName Optional client name (e.g., "Chrome | macOS")
     * @param state Optional state for CSRF protection
     * @param permissions Optional permission keys
     * @param circlePermissions Optional circle permission keys
     */
    suspend fun getRegistrationParams(
        returnUrl: String,
        appName: String,
        appId: String,
        drives: List<TargetDriveAccessRequest> = emptyList(),
        publicKey: EccKeyPair,
        password: SecureByteArray,
        host: String? = null,
        clientFriendlyName: String? = null,
        state: String? = null,
        permissions: List<Int>? = null,
        circlePermissions: List<Int>? = null,
        circleDrives: List<TargetDriveAccessRequest>? = null,
        circles: List<String>? = null
    ): YouAuthorizationParams {
        val clientFriendly = clientFriendlyName ?: "Homebase KMP App"

        val permissionRequest =
            AppAuthorizationParams.create(
                appName = appName,
                appId = appId,
                friendlyName = clientFriendly,
                drives = drives,
                circleDrives = circleDrives,
                circles = circles,
                permissions = permissions,
                circlePermissions = circlePermissions,
                returnUrl = returnUrl,
                origin = host
            )

        val publicEccKey = publicKeyToJwkBase64Url(publicKey.publicKey)

        return YouAuthorizationParams(
            clientId = appId,
            clientType = ClientType.app,
            clientInfo = clientFriendly,
            publicKey = publicEccKey,
            permissionRequest = permissionRequest.toJson(),
            state = state ?: "",
            redirectUri = returnUrl
        )
    }

    /**
     * Exchange the secret digest for authentication tokens.
     *
     * @param base64ExchangedSecretDigest SHA-256 digest of exchanged secret, base64 encoded
     * @return Token response with encrypted tokens
     */
    suspend fun exchangeDigestForToken(base64ExchangedSecretDigest: String): YouAuthTokenResponse {
        val client = odinClient.createHttpClient(CreateHttpClientOptions(overrideEncryption = true))

        // Token endpoint is on owner API
        val baseUrl = odinClient.getRoot() + "/api/owner/v1"
        val response =
            client.post("$baseUrl/youauth/token") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("secret_digest" to base64ExchangedSecretDigest))
            }

        if (response.status.value != 200) {
            throw Exception("Token exchange failed with status ${response.status.value}")
        }

        return response.body()
    }

    /**
     * Finalize authentication by deriving shared secret and decrypting tokens.
     *
     * @param identity The authenticated identity
     * @param keyPair The ECC key pair used for key exchange
     * @param password The password used to generate the key pair
     * @param publicKey Remote public key from callback (base64url JWK)
     * @param salt Salt from callback (base64 encoded)
     * @return Authentication result with clientAuthToken and sharedSecret
     */
    suspend fun finalizeAuthentication(
        identity: String,
        keyPair: EccKeyPair,
        password: SecureByteArray,
        publicKey: String,
        salt: String
    ): AuthResult {
        // Import the remote public key
        val remotePublicKey = publicKeyFromJwkBase64Url(publicKey)
        val saltBytes = Base64.decode(salt)

        // Perform ECDH key agreement
        val exchangedSecret = performEcdhKeyAgreement(keyPair, password, remotePublicKey, saltBytes)

        // Hash the exchanged secret
        val exchangedSecretDigest = HashUtil.sha256(exchangedSecret.unsafeBytes)
        val base64ExchangedSecretDigest = Base64.encode(exchangedSecretDigest)

        Logger.d(TAG) { "Exchange secret digest generated" }

        // Exchange for tokens
        val token = exchangeDigestForToken(base64ExchangedSecretDigest)

        // Decrypt shared secret
        val sharedSecretCipher = Base64.decode(token.base64SharedSecretCipher)
        val sharedSecretIv = Base64.decode(token.base64SharedSecretIv)
        val sharedSecret =
            AesCbc.decrypt(sharedSecretCipher, exchangedSecret.unsafeBytes, sharedSecretIv)

        // Decrypt client auth token
        val clientAuthTokenCipher = Base64.decode(token.base64ClientAuthTokenCipher)
        val clientAuthTokenIv = Base64.decode(token.base64ClientAuthTokenIv)
        val clientAuthToken =
            AesCbc.decrypt(
                clientAuthTokenCipher,
                exchangedSecret.unsafeBytes,
                clientAuthTokenIv
            )

        return AuthResult(
            clientAuthToken = Base64.encode(clientAuthToken),
            sharedSecret = Base64.encode(sharedSecret),
            identity = identity
        )
    }

    /**
     * Logout from the current session.
     *
     * @return true if successful, false otherwise
     */
    suspend fun logout(): Boolean {
        return try {
            val client = odinClient.createHttpClient(
                CreateHttpClientOptions(overrideEncryption = true))
            client.post("/auth/logout")
            odinClient.close()
            true
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Error during logout" }
            false
        }
    }

    /**
     * Pre-auth notification for push notifications.
     *
     * @return true if successful, false otherwise
     */
    suspend fun preAuth(): Boolean {
        return try {
            val client =
                odinClient.createHttpClient(CreateHttpClientOptions(overrideEncryption = true))
            client.post("/notify/preauth")
            true
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Error during preAuth" }
            false
        }
    }

    /**
     * Generate a new ECC key pair for authentication.
     *
     * @param password Secure password for key derivation
     * @return Generated key pair
     */
    suspend fun generateKeyPair(password: SecureByteArray): EccKeyPair {
        return generateEccKeyPair(password, EccKeySize.P384, 1)
    }
}
