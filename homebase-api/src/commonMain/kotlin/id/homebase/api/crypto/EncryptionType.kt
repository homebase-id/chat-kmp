package id.homebase.homebasekmppoc.prototype.lib.crypto

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
