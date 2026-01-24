package id.homebase.api.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Encryption type enumeration
 */
@Serializable
enum class EncryptionType {
    @SerialName("aes")
    Aes
}
