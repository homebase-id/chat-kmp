package id.homebase.homebasekmppoc.prototype.lib.http

import kotlinx.serialization.Serializable

/**
 * Payload structure for query strings encrypted with the shared secret
 */
@Serializable
data class SharedSecretEncryptedPayload(
    val iv: String,    // Base64 encoded IV
    val data: String   // Base64 encoded encrypted data
)