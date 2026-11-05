package id.homebase.api.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Encryption type enumeration
 */
@Serializable
enum class EncryptionType {
    @SerialName("aes")
    Aes,

    /** Sealed to a drive's write-only key by a depositor; nothing in this app opens one yet. */
    @SerialName("sealed")
    Sealed,

    /** Locked with a zero-knowledge drive's key, which the server can't open. */
    @SerialName("zkDrive")
    ZkDrive,

    /** A comment on a SelectiveShare drive: its own IV and no key, encrypted with the key of the file it comments on. */
    @SerialName("fileKey")
    FileKey,

    /** A file shared with the reader: the file's own IV, and its key locked with the reader's share's circle key. */
    @SerialName("share")
    Share,
}
