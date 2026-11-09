package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.uuid.Uuid

/**
 * Builds the shared-secret-encrypted upload descriptor (the `metadata` multipart part) shared by the
 * own-host upload path ([DriveUploadProvider.uploadFile]) and the over-peer transit-send path
 * ([id.homebase.api.client.peer.PeerDriveUploadProvider.uploadFileOverPeer]). Both wire shapes are
 * identical: the per-file [keyHeader] is encrypted with the caller's [sharedSecret] under
 * [transferIv], wrapped in an [UploadFileDescriptor] alongside the (already content-encrypted)
 * [metadata], and the whole descriptor is then AES-CBC encrypted with the same shared secret + IV.
 *
 * Matches the TypeScript `buildDescriptor` helper.
 */
internal suspend fun buildSharedSecretEncryptedUploadDescriptor(
    encryptedKeyHeader: EncryptedKeyHeader,
    metadata: UploadFileMetadata,
    sharedSecret: ByteArray,
    transferIv: ByteArray,
    shares: List<ShareRequest>? = null,
    globalTransitId: Uuid? = null,
): ByteArray {
    val descriptor =
        UploadFileDescriptor(
            encryptedKeyHeader = encryptedKeyHeader,
            fileMetadata = metadata,
            shares = shares,
            globalTransitId = globalTransitId,
        )

    val descriptorJson = OdinSystemSerializer.json.encodeToString(descriptor)
    val descriptorBytes = descriptorJson.encodeToByteArray()

    return AesCbc.encrypt(descriptorBytes, sharedSecret, transferIv)
}

/** The update's `metadata` part, as [buildSharedSecretEncryptedUploadDescriptor] builds an upload's. */
internal suspend fun buildSharedSecretEncryptedUpdateDescriptor(
    encryptedKeyHeader: EncryptedKeyHeader,
    metadata: UploadFileMetadata,
    sharedSecret: ByteArray,
    transferIv: ByteArray,
): ByteArray {
    val descriptorJson = OdinSystemSerializer.json.encodeToString(UpdateFileDescriptor(encryptedKeyHeader, metadata))
    return AesCbc.encrypt(descriptorJson.encodeToByteArray(), sharedSecret, transferIv)
}
