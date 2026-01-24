package id.homebase.api.client.auth

import id.homebase.api.common.SecureByteArray

import kotlin.uuid.Uuid

@ConsistentCopyVisibility
data class ApiCredentials private constructor(
    val domain: String,
    val clientAccessToken: String,
    val sharedSecret: SecureByteArray
) {
    init {
        require(domain.isNotBlank())
        require(clientAccessToken.isNotBlank())
        require(sharedSecret.unsafeBytes.isNotEmpty())
    }

    fun getIdentityId(): Uuid {
        // TODO: <- get the real identityId
        val identityId = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
        return identityId
    }

    companion object {
        fun create(
            domain: String,
            clientAccessToken: String,
            sharedSecret: SecureByteArray
        ): ApiCredentials {
            return ApiCredentials(
                domain = domain.lowercase().trim(),
                clientAccessToken = clientAccessToken,
                sharedSecret = sharedSecret
            )
        }
    }

}
