package id.homebase.api.client.drives.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.serialization.OdinSystemSerializer

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
    keyHeader: KeyHeader?,
    metadata: UploadFileMetadata,
    sharedSecret: ByteArray,
    transferIv: ByteArray,
): ByteArray {
    val sharedSecretEncryptedKeyHeader =
        EncryptedKeyHeader.encryptKeyHeaderAes(
            keyHeader ?: KeyHeader.empty(),
            transferIv,
            SecureByteArray(sharedSecret),
        )

    val descriptor =
        UploadFileDescriptor(
            encryptedKeyHeader = sharedSecretEncryptedKeyHeader,
            fileMetadata = metadata,
        )

    val descriptorJson = OdinSystemSerializer.json.encodeToString(descriptor)
    val descriptorBytes = descriptorJson.encodeToByteArray()

    return AesCbc.encrypt(descriptorBytes, sharedSecret, transferIv)
}
