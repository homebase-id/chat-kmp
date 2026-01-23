package id.homebase.homebasekmppoc.lib.youauth

import kotlinx.serialization.Serializable

/** Token response from the YouAuth token exchange endpoint. */
@Serializable
data class YouAuthTokenResponse(
        val base64SharedSecretCipher: String,
        val base64SharedSecretIv: String,
        val base64ClientAuthTokenCipher: String,
        val base64ClientAuthTokenIv: String
)
