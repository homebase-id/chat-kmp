package id.homebase.api.youauth

import id.homebase.api.serialization.Base64Bytes
import kotlinx.serialization.Serializable

/** Token response from the YouAuth token exchange endpoint. */
@Serializable
data class YouAuthTokenResponse(
        val base64SharedSecretCipher: Base64Bytes,
        val base64SharedSecretIv: Base64Bytes,
        val base64ClientAuthTokenCipher: Base64Bytes,
        val base64ClientAuthTokenIv: Base64Bytes,
        /** `seal` of the app's zero-knowledge key to the authorize request's public key; only when asked for. */
        val zkAppKey: Base64Bytes? = null
)
