package id.homebase.api.client.auth

import co.touchlab.kermit.Logger
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import id.homebase.api.crypto.AesGcm
import id.homebase.api.crypto.Base64Encoder
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.EccKeyPair
import id.homebase.api.crypto.EccKeySize
import id.homebase.api.crypto.EccPublicKey
import id.homebase.api.crypto.generateEccKeyPair
import id.homebase.api.crypto.performEcdhKeyAgreement
import id.homebase.api.crypto.publicKeyFromJwkBase64Url
import id.homebase.api.crypto.publicKeyToJwk
import id.homebase.api.client.http.createHttpClient
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import id.homebase.api.client.http.ownerCookieName
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.setCookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

sealed class AuthState {
    /** User is not authenticated */
    data object Unauthenticated : AuthState()

    /** Authentication flow is in progress */
    data object Authenticating : AuthState()

    /** User is authenticated with valid tokens */
    data class Authenticated(
        val identity: String,
        val clientAuthToken: String,  // Base64 encoded
        val sharedSecret: String      // Base64 encoded
    ) : AuthState()

    /** Authentication failed with an error */
    data class Error(val message: String) : AuthState()
}

class AuthenticationManager {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    suspend fun authenticate(identity: String, password: String): AuthenticationResponse? {
        try {
            // Sanity
            if (_authState.value == AuthState.Authenticating || _authState.value is AuthState.Authenticated) {
                Logger.e("AuthenticationManager") { "Already authenticated" }
                return null
            }

            _authState.value = AuthState.Authenticating

            val client = createHttpClient()

            // Get nonce
            val nonceUrl = "https://${identity}/api/owner/v1/authentication/nonce"
            val nonceData = client.get(nonceUrl).body<NonceData>()

            Logger.d("authenticate") { "Received nonce: ${nonceData.nonce64}" }

            // Prepare authentication payload (CPU-intensive operations on background thread)
            val reply = withContext(Dispatchers.Default) {
                prepareAuthPassword(password, nonceData)
            }

            // POST authentication
            val authUrl = "https://${identity}/api/owner/v1/authentication"
            val response = client.post(authUrl) {
                contentType(ContentType.Application.Json)
                setBody(reply)
            }

            val clientAuthToken = response.setCookie()
                .find { it.name == ownerCookieName }
                ?.value
                ?: throw IllegalStateException("$ownerCookieName cookie not found in response")

            val result = OdinSystemSerializer.deserialize<AuthenticationResponse>(response.body())

            Logger.d("authenticate") { "Authentication successful" }

            // Update auth state
            _authState.value = AuthState.Authenticated(
                identity = identity,
                clientAuthToken = clientAuthToken,
                sharedSecret = result.sharedSecret
            )

            return result

        } catch (e: Exception) {
            Logger.e("authenticate", e) { "Authentication failed: ${e.message}" }
            _authState.value = AuthState.Error(e.message ?: "Authentication failed")
            return null
        }
    }

    //

    // Ported from typescript
    private suspend fun prepareAuthPassword(password: String, nonceData: NonceData): AuthenticationReplyNonce
    {
        val iterations = 100000;
        val len = 16;

        val hashedPassword64 =  wrapPbkdf2HmacSha256(
            password,
            nonceData.saltPassword64,
            iterations,
            len
        )
        val keK64 = wrapPbkdf2HmacSha256(password, nonceData.saltKek64, iterations, len);

        val hashNoncePassword64 = wrapPbkdf2HmacSha256(
            hashedPassword64,
            nonceData.nonce64,
            iterations,
            len
        )

        val hostBase64PublicJWK = Base64Encoder.encode(nonceData.publicJwk)

        val hostEccPublicKey = publicKeyFromJwkBase64Url(hostBase64PublicJWK)

        val clientEccKey = generateEccKeyPair(
            SecureByteArray(hashedPassword64.encodeToByteArray()),
            EccKeySize.P384,
            1)

        val exchangedSecret = getEccSharedSecret(
            keyPair = clientEccKey,
            password = SecureByteArray(hashedPassword64.encodeToByteArray()),
            remotePublicKey = hostEccPublicKey,
            nonce64 = nonceData.nonce64
        )

        val payload = AuthenticationPayload(
            hpwd64 = hashedPassword64,
            kek64 = keK64,
            secret = Base64Encoder.encode(ByteArrayUtil.getRndByteArray(16))
        )
        val encryptable = Json.encodeToString(payload)

        // Encrypt with AES-GCM
        val nonce = Base64Encoder.decode(nonceData.nonce64)
        val nonceFirst12 = nonce.sliceArray(0 until 12)  // GCM uses 12-byte nonce

        val encryptedGcm = AesGcm.encrypt(
            data = encryptable.encodeToByteArray(),
            key = exchangedSecret,
            iv = nonceFirst12
        )

        val encryptedGcm64 = Base64Encoder.encode(encryptedGcm)
        Logger.d("prepareAuthPassword") { "Encrypted payload: $encryptedGcm64" }

        return AuthenticationReplyNonce(
            nonce64 = nonceData.nonce64,
            nonceHashedPassword64 = hashNoncePassword64,
            crc = nonceData.crc,
            gcmEncrypted64 = encryptedGcm64,
            publicKeyJwk = exportEccPublicKey(clientEccKey.publicKey)
        )
    }

    //

    /**
     * PBKDF2-HMAC-SHA256 key derivation with base64 encoding
     *
     * @param password The password to derive from
     * @param saltArray64 Base64-encoded salt
     * @param iterations Number of iterations (e.g., 100000)
     * @param len Output key length in bytes
     * @return Base64-encoded derived key
     */
    private suspend fun wrapPbkdf2HmacSha256(
        password: String,
        saltArray64: String,
        iterations: Int,
        len: Int
    ): String {
        // Decode base64 salt (from server, uses standard base64)
        val saltArray = Base64Encoder.decode(saltArray64)

        // Perform PBKDF2
        val derivedKey = pbkdf2(password, saltArray, iterations, len)

        // Encode result to base64url
        return Base64Encoder.encode(derivedKey)
    }

    /**
     * PBKDF2-HMAC-SHA256 key derivation
     *
     * @param password The password to derive from
     * @param saltArray Salt bytes
     * @param iterations Number of iterations
     * @param len Output key length in bytes
     * @return Derived key bytes
     */
    private suspend fun pbkdf2(
        password: String,
        saltArray: ByteArray,
        iterations: Int,
        len: Int
    ): ByteArray {
        val crypto = CryptographyProvider.Default
        val pbkdf2Algo = crypto.get(PBKDF2)

        // Convert password to bytes
        val passwordBytes = password.encodeToByteArray()

        // Perform PBKDF2 derivation with SHA-256
        val derivation = pbkdf2Algo.secretDerivation(
            digest = SHA256,
            iterations = iterations,
            outputSize = len.bytes,
            salt = saltArray
        )

        return derivation.deriveSecretToByteArray(passwordBytes)
    }

    //

    suspend fun getEccSharedSecret(
        keyPair: EccKeyPair,
        password: SecureByteArray,
        remotePublicKey: EccPublicKey,
        nonce64: String
    ): ByteArray {
        val salt = Base64Encoder.decode(nonce64)

        return performEcdhKeyAgreement(
            keyPair = keyPair,
            password = password,
            remotePublicKey = remotePublicKey,
            salt = salt
        ).unsafeBytes  // Returns 16-byte AES-128 key
    }

    //

    /**
     * Export ECC public key to JWK format (JSON string)
     *
     * Equivalent to TypeScript's exportEccPublicKey function.
     * Removes key_ops and ext fields from the JWK to match server expectations.
     *
     * @param publicKey The ECC public key to export
     * @return JWK JSON string with key_ops and ext fields removed
     */
    private suspend fun exportEccPublicKey(publicKey: EccPublicKey): String {
        // Convert public key to JWK format
        val jwkString = publicKeyToJwk(publicKey)

        // Parse JWK JSON
        val jwk = Json.decodeFromString<JsonObject>(jwkString)

        // Remove key_ops and ext fields (server doesn't need these)
        val cleanedJwk = buildJsonObject {
            jwk.forEach { (key, value) ->
                if (key != "key_ops" && key != "ext") {
                    put(key, value)
                }
            }
        }

        // Return as JSON string
        return Json.encodeToString(cleanedJwk)
    }

    //

    suspend fun logout() {
        _authState.value = AuthState.Unauthenticated
    }
}

