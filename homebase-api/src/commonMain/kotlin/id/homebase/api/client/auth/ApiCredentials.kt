package id.homebase.api.client.auth

import id.homebase.api.common.SecureByteArray

import kotlin.uuid.Uuid
import id.homebase.api.common.OdinId

@ConsistentCopyVisibility
data class ApiCredentials private constructor(
    val domain: OdinId,
    val clientAccessToken: String,
    val sharedSecret: SecureByteArray
) {
    init {
        //require(domain.isNotBlank())
        require(clientAccessToken.isNotBlank())
        require(sharedSecret.unsafeBytes.isNotEmpty())
    }

    fun getIdentityId(): Uuid {
        // TODO: <- get the real identityId
//        val identityId = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
        val identityId = this.domain.toHashId()
        return identityId
    }

    companion object {
        fun create(
            domain: OdinId,
            clientAccessToken: String,
            sharedSecret: SecureByteArray
        ): ApiCredentials {
            return ApiCredentials(
                domain = domain,
                clientAccessToken = clientAccessToken,
                sharedSecret = sharedSecret
            )
        }
    }

}
