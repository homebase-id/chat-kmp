@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.api.client.drives.upload

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.GlobalTransitIdFileIdentifier
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.prototype.lib.serialization.Base64ByteArraySerializer
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

/**
 * Embedded thumbnail for preview in upload context. This is a serializable DTO version that differs
 * from the sync/image EmbeddedThumb which uses contentBase64 field name.
 */
@Serializable
@Immutable
data class EmbeddedThumb(
        val pixelWidth: Int,
        val pixelHeight: Int,
        val contentType: String? = null,
        val content: String? = null
)

/** Application file metadata for uploads. */
@Serializable
data class UploadAppFileMetaData(
    val uniqueId: String? = null,
    val tags: List<String>? = null,
    val fileType: Int? = null,
    val dataType: Int? = null,
    val userDate: Long? = null,
    val groupId: String? = null,
    val archivalStatus: ArchivalStatus? = null,
    val content: String? = null,
    val previewThumbnail: EmbeddedThumb? = null
)

/** File metadata for uploads. */
@Serializable
data class UploadFileMetadata(
    val allowDistribution: Boolean,
    val isEncrypted: Boolean,
    val accessControlList: AccessControlList? = null,
    val appData: UploadAppFileMetaData,
    val referencedFile: GlobalTransitIdFileIdentifier? = null,
    val versionTag: Uuid? = null
) {
    /**
     * Encrypts the appData.content using the provided KeyHeader. If keyHeader is null or content is
     * empty, returns this unchanged.
     *
     * @param keyHeader The KeyHeader to use for encryption
     * @return A new UploadFileMetadata with encrypted content, or this if no encryption needed
     */
    suspend fun encryptContent(keyHeader: KeyHeader?): UploadFileMetadata {
        if (keyHeader == null || appData.content.isNullOrEmpty()) {
            return this
        }

        val encryptedBytes = keyHeader.encryptDataAes(appData.content.encodeToByteArray())
        val encryptedContent = Base64.encode(encryptedBytes)

        return copy(appData = appData.copy(content = encryptedContent))
    }
}

/** File descriptor for uploads. */
@Serializable
data class UploadFileDescriptor(
        val encryptedKeyHeader: EncryptedKeyHeader? = null,
        val fileMetadata: UploadFileMetadata
)


@Serializable
data class UpdateFileDescriptor(
    val encryptedKeyHeader: EncryptedKeyHeader? = null,
    val fileMetadata: UploadFileMetadata
)

/**
 * Serializable key header for upload results. This is a DTO version meant for API response
 * deserialization. For actual cryptographic operations, use the crypto.KeyHeader class.
 */
@Serializable
data class UploadKeyHeader(
        @Serializable(with = Base64ByteArraySerializer::class) val iv: ByteArray? = null,
        @Serializable(with = Base64ByteArraySerializer::class) val aesKey: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as UploadKeyHeader

        if (iv != null) {
            if (other.iv == null) return false
            if (!iv.contentEquals(other.iv)) return false
        } else if (other.iv != null) return false
        if (aesKey != null) {
            if (other.aesKey == null) return false
            if (!aesKey.contentEquals(other.aesKey)) return false
        } else if (other.aesKey != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = iv?.contentHashCode() ?: 0
        result = 31 * result + (aesKey?.contentHashCode() ?: 0)
        return result
    }
}
