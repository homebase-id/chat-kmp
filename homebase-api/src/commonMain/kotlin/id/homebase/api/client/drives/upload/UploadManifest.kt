package id.homebase.homebasekmppoc.prototype.lib.drives.upload

import id.homebase.homebasekmppoc.prototype.lib.crypto.ByteArrayUtil
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.homebasekmppoc.prototype.lib.serialization.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Payload descriptor for uploads. */
@Serializable
data class UploadPayloadDescriptor(
    @SerialName("payloadKey") val payloadKey: String,
    val descriptorContent: String? = null,
    val contentType: String? = null,
    val previewThumbnail: id.homebase.homebasekmppoc.prototype.lib.drives.upload.EmbeddedThumb? = null,
    val thumbnails: List<id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadThumbnailDescriptor>? = null,
    @Serializable(with = Base64ByteArraySerializer::class) val iv: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadPayloadDescriptor
        if (payloadKey != other.payloadKey) return false
        if (descriptorContent != other.descriptorContent) return false
        if (contentType != other.contentType) return false
        if (previewThumbnail != other.previewThumbnail) return false
        if (thumbnails != other.thumbnails) return false
        if (iv != null) {
            if (other.iv == null) return false
            if (!iv.contentEquals(other.iv)) return false
        } else if (other.iv != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = payloadKey.hashCode()
        result = 31 * result + (descriptorContent?.hashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (previewThumbnail?.hashCode() ?: 0)
        result = 31 * result + (thumbnails?.hashCode() ?: 0)
        result = 31 * result + (iv?.contentHashCode() ?: 0)
        return result
    }
}

/** Thumbnail descriptor for upload payloads. */
@Serializable
data class UploadThumbnailDescriptor(
    val thumbnailKey: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val contentType: String
)

/** Upload manifest containing payload descriptors. */
@Serializable
data class UploadManifest(
    @SerialName("PayloadDescriptors")
    val payloadDescriptors: List<id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadPayloadDescriptor>? = null
) {
    companion object {
        /**
         * Builds an UploadManifest from payload files and thumbnails.
         *
         * @param payloads List of payload files to include
         * @param thumbnails Optional list of thumbnails associated with payloads
         * @param generatePayloadIv Whether to generate random IVs for payloads
         * @return A new UploadManifest with payload descriptors
         */
        fun build(
            payloads: List<id.homebase.homebasekmppoc.prototype.lib.drives.files.PayloadFile>?,
            thumbnails: List<ThumbnailFile>? = null,
            generatePayloadIv: Boolean = false
        ): id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadManifest {
            val descriptors =
                payloads?.map { payload ->
                    _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadPayloadDescriptor(
                        payloadKey = payload.key,
                        descriptorContent = payload.descriptorContent,
                        previewThumbnail = payload.previewThumbnail,
                        contentType = payload.contentType,
                        thumbnails =
                            thumbnails
                                ?.filter { it.key == payload.key }
                                ?.map { thumb ->
                                    _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadThumbnailDescriptor(
                                        thumbnailKey =
                                            thumb.key +
                                                    thumb.pixelWidth,
                                        pixelWidth =
                                            thumb.pixelWidth,
                                        pixelHeight =
                                            thumb.pixelHeight,
                                        contentType =
                                            thumb.contentType
                                    )
                                },
                        iv = payload.iv
                            ?: if (generatePayloadIv)
                                ByteArrayUtil
                                    .getRndByteArray(16)
                            else null
                    )
                }
            return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadManifest(
                payloadDescriptors = descriptors
            )
        }
    }
}

/** Update payload instruction. */
@Serializable
data class UploadManifestPayloadDescriptor(
    val payloadKey: String,
    @SerialName("payloadUpdateOperationType") val operationType: id.homebase.homebasekmppoc.prototype.lib.drives.upload.PayloadOperationType,
    val descriptorContent: String? = null,
    val contentType: String? = null,
    val previewThumbnail: id.homebase.homebasekmppoc.prototype.lib.drives.upload.EmbeddedThumb? = null,
    val thumbnails: List<id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadThumbnailDescriptor>? = null,
    @Serializable(with = Base64ByteArraySerializer::class) val iv: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadManifestPayloadDescriptor
        if (payloadKey != other.payloadKey) return false
        if (operationType != other.operationType) return false
        if (descriptorContent != other.descriptorContent) return false
        if (contentType != other.contentType) return false
        if (previewThumbnail != other.previewThumbnail) return false
        if (thumbnails != other.thumbnails) return false
        if (iv != null) {
            if (other.iv == null) return false
            if (!iv.contentEquals(other.iv)) return false
        } else if (other.iv != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = payloadKey.hashCode()
        result = 31 * result + operationType.hashCode()
        result = 31 * result + (descriptorContent?.hashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (previewThumbnail?.hashCode() ?: 0)
        result = 31 * result + (thumbnails?.hashCode() ?: 0)
        result = 31 * result + (iv?.contentHashCode() ?: 0)
        return result
    }
}

/** Payload operation types for updates. */
@Serializable
enum class PayloadOperationType {
    @SerialName("appendOrOverwrite")
    AppendOrOverwrite,
    @SerialName("deletePayload")
    DeletePayload
}

/** Update manifest containing payload update instructions. */
@Serializable
data class UpdateManifest(
    @SerialName("PayloadDescriptors")
    val payloadDescriptors: List<id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadManifestPayloadDescriptor>? = null
) {
    companion object {
        /**
         * Builds an UpdateManifest from payload files, deletions, and thumbnails.
         *
         * @param payloads List of payload files to append/overwrite
         * @param toDeletePayloads List of payload keys to delete
         * @param thumbnails Optional list of thumbnails associated with payloads
         * @param generatePayloadIv Whether to generate random IVs for payloads
         * @return A new UpdateManifest with payload instructions
         */
        fun build(
            payloads: List<id.homebase.homebasekmppoc.prototype.lib.drives.files.PayloadFile>? = null,
            toDeletePayloads: List<id.homebase.homebasekmppoc.prototype.lib.drives.upload.PayloadDeleteKey>? = null,
            thumbnails: List<ThumbnailFile>? = null,
            generatePayloadIv: Boolean = false
        ): id.homebase.homebasekmppoc.prototype.lib.drives.upload.UpdateManifest {
            val appendInstructions =
                payloads?.map { payload ->
                    _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadManifestPayloadDescriptor(
                        payloadKey = payload.key,
                        operationType =
                            _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.upload.PayloadOperationType.AppendOrOverwrite,
                        descriptorContent = payload.descriptorContent,
                        previewThumbnail = payload.previewThumbnail,
                        contentType = payload.contentType,
                        thumbnails =
                            thumbnails
                                ?.filter { it.key == payload.key }
                                ?.map { thumb ->
                                    _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadThumbnailDescriptor(
                                        thumbnailKey =
                                            thumb.key +
                                                    thumb.pixelWidth,
                                        pixelWidth =
                                            thumb.pixelWidth,
                                        pixelHeight =
                                            thumb.pixelHeight,
                                        contentType =
                                            thumb.contentType
                                    )
                                },
                        iv = payload.iv
                            ?: if (generatePayloadIv)
                                ByteArrayUtil
                                    .getRndByteArray(16)
                            else null
                    )
                }
                    ?: emptyList()

            val deleteInstructions =
                toDeletePayloads?.map { toDelete ->
                    _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.upload.UploadManifestPayloadDescriptor(
                        payloadKey = toDelete.key,
                        operationType = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.upload.PayloadOperationType.DeletePayload
                    )
                }
                    ?: emptyList()

            return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.upload.UpdateManifest(
                payloadDescriptors = appendInstructions + deleteInstructions
            )
        }
    }
}

/** Helper class for specifying payloads to delete. */
data class PayloadDeleteKey(val key: String)
