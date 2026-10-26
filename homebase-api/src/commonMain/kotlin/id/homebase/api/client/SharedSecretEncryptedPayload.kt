package id.homebase.api.client

import id.homebase.api.serialization.Base64Bytes
import kotlinx.serialization.Serializable

/**
 * Payload structure for query strings encrypted with the shared secret
 */
@Serializable
data class SharedSecretEncryptedPayload(
    val iv: Base64Bytes,
    val data: Base64Bytes
)