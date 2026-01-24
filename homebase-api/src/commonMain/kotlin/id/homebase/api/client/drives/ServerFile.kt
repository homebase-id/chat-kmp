package id.homebase.api.client.drives

import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.homebasekmppoc.prototype.lib.crypto.KeyHeader
import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * The file data as it is sent back from the server.  Private provider use only
 */
@Serializable
data class ServerFile(
    @Serializable(with = UuidSerializer::class)
    val fileId: Uuid,
    val driveId: Uuid,
    val fileState: FileState,
    val fileSystemType: FileSystemType,
    val sharedSecretEncryptedKeyHeader: EncryptedKeyHeader,
    val fileMetadata: FileMetadata,
    val serverMetadata: ServerMetadata,
    val priority: Int = 0,
    val fileByteCount: Long = 0
) {
    suspend fun asHomebaseFile(sharedSecret: SecureByteArray): HomebaseFile {
        val resolvedKeyHeader: KeyHeader
        var resolvedMetadata: FileMetadata
        var serverFileIsEncrypted: Boolean = false

        if (fileMetadata.isEncrypted) {

            serverFileIsEncrypted = true

            if (sharedSecretEncryptedKeyHeader == EncryptedKeyHeader.empty()) {
                throw FileDecryptionException.MissingEncryptedHeader()
            }

            resolvedKeyHeader = try {
                sharedSecretEncryptedKeyHeader.decryptAesToKeyHeader(sharedSecret)
            } catch (e: Throwable) {
                throw FileDecryptionException.KeyHeaderDecryptionFailed(e)
            }

            resolvedMetadata = fileMetadata

            // ---- server appData ----
            resolvedMetadata = resolvedMetadata.decryptAppData(resolvedKeyHeader)

            // ---- localAppData (optional) ----
            resolvedMetadata = resolvedMetadata.decryptLocalAppData(resolvedKeyHeader)
        } else {
            resolvedKeyHeader = KeyHeader.empty()
            resolvedMetadata = fileMetadata
        }

        return HomebaseFile(
            fileId = fileId,
            driveId = driveId,
            serverFileIsEncrypted = serverFileIsEncrypted,
            fileState = fileState,
            fileSystemType = fileSystemType,
            keyHeader = resolvedKeyHeader,
            fileMetadata = resolvedMetadata,
            serverMetadata = serverMetadata,
            priority = priority,
            fileByteCount = fileByteCount
        )
    }
}

fun FileMetadata.withDecryptedContent(bytes: ByteArray): FileMetadata =
    FileMetadata(
        appData = appData.copy(
            content = bytes.decodeToString()
        ),
        isEncrypted = false
    )

private suspend fun FileMetadata.decryptAppData(
    keyHeader: KeyHeader
): FileMetadata {
    val content = appData.content
    if (content.isNullOrEmpty()) {
        return withDecryptedContent(ByteArray(0))
    }

    val encryptedBytes = try {
        Base64.Default.decode(content)
    } catch (e: Throwable) {
        throw FileDecryptionException.ContentBase64DecodeFailed(e)
    }

    val decryptedBytes = try {
        keyHeader.decrypt(encryptedBytes)
    } catch (e: Throwable) {
        throw FileDecryptionException.ContentDecryptionFailed(e)
    }

    return withDecryptedContent(decryptedBytes)
}

private suspend fun FileMetadata.decryptLocalAppData(
    keyHeader: KeyHeader
): FileMetadata {
    val local = localAppData ?: return this
    val content = local.content ?: return this

    val encryptedBytes = try {
        Base64.Default.decode(content)
    } catch (e: Throwable) {
        throw FileDecryptionException.ContentBase64DecodeFailed(e)
    }

    val ivBytes = local.iv?.let {
        try {
            Base64.Default.decode(it)
        } catch (e: Throwable) {
            throw FileDecryptionException.ContentBase64DecodeFailed(e)
        }
    }

    val decryptedBytes = try {
        keyHeader.decryptWithIv(encryptedBytes, ivBytes)
    } catch (e: Throwable) {
        throw FileDecryptionException.ContentDecryptionFailed(e)
    }

    return FileMetadata(
        localAppData = local.copy(
            content = decryptedBytes.decodeToString(),
            iv = null
        )
    )
}


sealed class FileDecryptionException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class MissingEncryptedHeader :
        FileDecryptionException("File is marked encrypted but has no encrypted key header")

    class KeyHeaderDecryptionFailed(cause: Throwable) :
        FileDecryptionException("Failed to decrypt key header", cause)

    class ContentBase64DecodeFailed(cause: Throwable) :
        FileDecryptionException("Failed to decode encrypted content (Base64)", cause)

    class ContentDecryptionFailed(cause: Throwable) :
        FileDecryptionException("Failed to decrypt file content", cause)
}
