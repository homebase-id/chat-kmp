package id.homebase.homebasekmppoc.prototype.lib.authentication

import kotlinx.serialization.Serializable

@Serializable
data class AuthenticationPayload(
    val hpwd64: String,
    val kek64: String,
    val secret: String  // Base64-encoded random bytes
)
