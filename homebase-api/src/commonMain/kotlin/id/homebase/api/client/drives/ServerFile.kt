package id.homebase.homebasekmppoc.prototype.lib.drives

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.homebasekmppoc.prototype.lib.core.SecureByteArray
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.homebasekmppoc.prototype.lib.crypto.KeyHeader
import id.homebase.homebasekmppoc.prototype.lib.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * The file data as it is sent back from the server.  Private provider use only
 */
@Serializable
data class ServerFile(
    @Serializable(with = UuidSerializer::class)
    val fileId: Uuid,
    val driveId: Uuid,
    val fileState: id.homebase.homebasekmppoc.prototype.lib.drives.FileState,
    val fileSystemType: id.homebase.homebasekmppoc.prototype.lib.drives.FileSystemType,
    val sharedSecretEncryptedKeyHeader: EncryptedKeyHeader,
    val fileMetadata: id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata,
    val serverMetadata: id.homebase.homebasekmppoc.prototype.lib.drives.ServerMetadata,
    val priority: Int = 0,
    val fileByteCount: Long = 0
) {
    suspend fun asHomebaseFile(sharedSecret: SecureByteArray): HomebaseFile {
        val resolvedKeyHeader: KeyHeader
        var resolvedMetadata: id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata
        var serverFileIsEncrypted: Boolean = false

        if (fileMetadata.isEncrypted) {

            serverFileIsEncrypted = true

            if (sharedSecretEncryptedKeyHeader == EncryptedKeyHeader.empty()) {
                throw _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException.MissingEncryptedHeader()
            }

            resolvedKeyHeader = try {
                sharedSecretEncryptedKeyHeader.decryptAesToKeyHeader(sharedSecret)
            } catch (e: Throwable) {
                throw _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException.KeyHeaderDecryptionFailed(e)
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

fun id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata.withDecryptedContent(bytes: ByteArray): id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata =
    _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata(
        appData = appData.copy(
            content = bytes.decodeToString()
        ),
        isEncrypted = false
    )

private suspend fun id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata.decryptAppData(
    keyHeader: KeyHeader
): id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata {
    val content = appData.content
    if (content.isNullOrEmpty()) {
        return withDecryptedContent(ByteArray(0))
    }

    val encryptedBytes = try {
        _root_ide_package_.kotlin.io.encoding.Base64.Default.decode(content)
    } catch (e: Throwable) {
        throw _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException.ContentBase64DecodeFailed(e)
    }

    val decryptedBytes = try {
        keyHeader.decrypt(encryptedBytes)
    } catch (e: Throwable) {
        throw _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException.ContentDecryptionFailed(e)
    }

    return withDecryptedContent(decryptedBytes)
}

private suspend fun id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata.decryptLocalAppData(
    keyHeader: KeyHeader
): id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata {
    val local = localAppData ?: return this
    val content = local.content ?: return this

    val encryptedBytes = try {
        _root_ide_package_.kotlin.io.encoding.Base64.Default.decode(content)
    } catch (e: Throwable) {
        throw _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException.ContentBase64DecodeFailed(e)
    }

    val ivBytes = local.iv?.let {
        try {
            _root_ide_package_.kotlin.io.encoding.Base64.Default.decode(it)
        } catch (e: Throwable) {
            throw _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException.ContentBase64DecodeFailed(e)
        }
    }

    val decryptedBytes = try {
        keyHeader.decryptWithIv(encryptedBytes, ivBytes)
    } catch (e: Throwable) {
        throw _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException.ContentDecryptionFailed(e)
    }

    return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata(
        localAppData = local.copy(
            content = decryptedBytes.decodeToString(),
            iv = null
        )
    )
}


sealed class FileDecryptionException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class MissingEncryptedHeader :
        id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException("File is marked encrypted but has no encrypted key header")

    class KeyHeaderDecryptionFailed(cause: Throwable) :
        id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException("Failed to decrypt key header", cause)

    class ContentBase64DecodeFailed(cause: Throwable) :
        id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException("Failed to decode encrypted content (Base64)", cause)

    class ContentDecryptionFailed(cause: Throwable) :
        id.homebase.homebasekmppoc.prototype.lib.drives.FileDecryptionException("Failed to decrypt file content", cause)
}
