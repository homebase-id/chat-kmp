package id.homebase.api.youauth

import co.touchlab.kermit.Logger
import id.homebase.api.crypto.AesCbc
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.EccKeyPair
import id.homebase.api.crypto.EccKeySize
import id.homebase.api.crypto.HashUtil
import id.homebase.api.crypto.generateEccKeyPair
import id.homebase.api.crypto.performEcdhKeyAgreement
import id.homebase.api.crypto.publicKeyFromJwkBase64Url
import id.homebase.api.crypto.publicKeyToJwkBase64Url
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.io.encoding.Base64

data class AuthResult(
    val clientAuthToken: String,
    val sharedSecret: String,
    val identity: String
)

class YouAuthProvider(
    private val httpClient: HttpClient,
    private val identity: String
) {

    private val baseApiUrl: String = identity.toHttpsBaseUrl()
    private val ownerApiUrl: String = identity.toHttpsBaseUrl()

    companion object {
        private const val TAG = "YouAuthProvider"
    }

    suspend fun hasValidToken(): Boolean? =
        try {
            val response = httpClient.get("$baseApiUrl/api/apps/v1/auth/verifytoken")
            when (response.status.value) {
                200 -> true
                401, 403 -> false
                else -> null
            }
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Token verification failed" }
            null
        }

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

        val permissionRequest =
            AppAuthorizationParams.create(
                appName = appName,
                appId = appId,
                friendlyName = clientFriendlyName ?: "Homebase KMP App",
                drives = drives,
                circleDrives = circleDrives,
                circles = circles,
                permissions = permissions,
                circlePermissions = circlePermissions,
                returnUrl = returnUrl,
                origin = host
            )

        return YouAuthorizationParams(
            clientId = appId,
            clientType = ClientType.app,
            clientInfo = clientFriendlyName ?: "Homebase KMP App",
            publicKey = publicKeyToJwkBase64Url(publicKey.publicKey),
            permissionRequest = permissionRequest.toJson(),
            state = state ?: "",
            redirectUri = returnUrl
        )
    }

    suspend fun exchangeDigestForToken(
        base64ExchangedSecretDigest: String
    ): YouAuthTokenResponse {

        val response =
            httpClient.post("$ownerApiUrl/api/owner/v1/youauth/token") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("secret_digest" to base64ExchangedSecretDigest))
            }

        if (response.status.value != 200) {
            error("Token exchange failed: ${response.status.value}")
        }

        return response.body()
    }

    suspend fun finalizeAuthentication(
        identity: String,
        keyPair: EccKeyPair,
        password: SecureByteArray,
        publicKey: String,
        salt: String
    ): AuthResult {

        val remotePublicKey = publicKeyFromJwkBase64Url(publicKey)
        val saltBytes = Base64.decode(salt)

        val exchangedSecret =
            performEcdhKeyAgreement(keyPair, password, remotePublicKey, saltBytes)

        val digest =
            HashUtil.sha256(exchangedSecret.unsafeBytes)

        val token =
            exchangeDigestForToken(Base64.encode(digest))

        val sharedSecret =
            AesCbc.decrypt(
                Base64.decode(token.base64SharedSecretCipher),
                exchangedSecret.unsafeBytes,
                Base64.decode(token.base64SharedSecretIv)
            )

        val clientAuthToken =
            AesCbc.decrypt(
                Base64.decode(token.base64ClientAuthTokenCipher),
                exchangedSecret.unsafeBytes,
                Base64.decode(token.base64ClientAuthTokenIv)
            )

        return AuthResult(
            clientAuthToken = Base64.encode(clientAuthToken),
            sharedSecret = Base64.encode(sharedSecret),
            identity = identity
        )
    }

    suspend fun logout(): Boolean =
        try {

            httpClient.post("$baseApiUrl/api/apps/v1/auth/logout")
            true
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Logout failed" }
            false
        }

    suspend fun preAuth(): Boolean =
        try {
            httpClient.post("$baseApiUrl/api/apps/v1/notify/preauth")
            true
        } catch (e: Exception) {
            Logger.e(TAG, e) { "PreAuth failed" }
            false
        }

    suspend fun generateKeyPair(password: SecureByteArray): EccKeyPair =
        generateEccKeyPair(password, EccKeySize.P384, 1)
}

private fun String.toHttpsBaseUrl(): String {
    val trimmed = trim()
    return when {
        trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("http://") ->
            "https://${trimmed.removePrefix("http://")}"
        else -> "https://$trimmed"
    }
}
