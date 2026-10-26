package id.homebase.api.youauth

import id.homebase.api.serialization.Base64Bytes
import kotlinx.serialization.Serializable

/** Token response from the YouAuth token exchange endpoint. */
@Serializable
data class YouAuthTokenResponse(
        val base64SharedSecretCipher: Base64Bytes,
        val base64SharedSecretIv: Base64Bytes,
        val base64ClientAuthTokenCipher: Base64Bytes,
        val base64ClientAuthTokenIv: Base64Bytes
)
