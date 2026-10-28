package id.homebase.api.client.drives

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.crypto.EncryptionType
import id.homebase.api.youauth.ZkKeyStore
import id.homebase.api.client.KeyHeader
import id.homebase.api.serialization.UuidSerializer
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray
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
                sharedSecretEncryptedKeyHeader.open(driveId, sharedSecret)
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

/** Opens a key header as the server returns it: locked with the shared secret, or on a zero-knowledge drive with its key. */
suspend fun EncryptedKeyHeader.open(driveId: Uuid, sharedSecret: SecureByteArray): KeyHeader = when (type) {
    EncryptionType.Aes -> decryptAesToKeyHeader(sharedSecret)
    EncryptionType.ZkDrive -> decryptAesToKeyHeader(SecureByteArray(zkDriveKey(driveId)))
    EncryptionType.Sealed -> throw FileDecryptionException.Sealed()
}

/**
 * Locks [keyHeader] for an upload to [driveId]: with the shared secret under [transferIv], which the server
 * re-locks with the drive key; or on a zero-knowledge drive with its key under the file's own IV, stored as sent.
 */
suspend fun lockKeyHeader(keyHeader: KeyHeader?, driveId: Uuid, sharedSecret: ByteArray, transferIv: ByteArray): EncryptedKeyHeader =
    if (keyHeader != null && ZkKeyStore.isZeroKnowledge(driveId)) {
        EncryptedKeyHeader.encryptKeyHeaderAes(keyHeader, keyHeader.iv, SecureByteArray(zkDriveKey(driveId))).apply { type = EncryptionType.ZkDrive }
    } else {
        EncryptedKeyHeader.encryptKeyHeaderAes(keyHeader ?: KeyHeader.empty(), transferIv, SecureByteArray(sharedSecret))
    }

/** The key that locks key headers on the zero-knowledge drive [driveId] (a drive's id is its alias). */
private fun zkDriveKey(driveId: Uuid): ByteArray = ZkKeyStore.driveKey(driveId) ?: throw FileDecryptionException.NoDriveKey(driveId)

internal const val DECRYPTION_FAILURE_APP_DATA =
    """{"message":"Decryption Failure","deliveryStatus":50,"isEdited":false}"""

/** True when this file's appData could not be decrypted and holds the placeholder instead. */
fun HomebaseFile.appDataFailedToDecrypt(): Boolean =
    fileMetadata.appData.content == DECRYPTION_FAILURE_APP_DATA

fun FileMetadata.withDecryptedContent(bytes: ByteArray): FileMetadata =
    this.copy(
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
        Base64.decode(content)
    } catch (e: Throwable) {
        // Mirrors the AES-decrypt soft-fail immediately below: one file's undecodable content
        // must not take down the whole batch (DriveQueryProvider.mapQueryBatchResponse's
        // documented contract). A caller-side parser (e.g. ProfileAttributeParse) that requires
        // a `type` key on this placeholder will safely treat the file as unparseable and skip it.
        Logger.e("Base64 decode failure for AppData content: ${e.message}")
        return withDecryptedContent(
            DECRYPTION_FAILURE_APP_DATA.toByteArray(Charsets.UTF_8)
        )
    }

//    val decryptedBytes = try {
//        keyHeader.decrypt(encryptedBytes)
//    } catch (e: Throwable) {
//        throw FileDecryptionException.ContentDecryptionFailed(e)
//    }

    val decryptedBytes = try {
        keyHeader.decrypt(encryptedBytes)
    } catch (_: Throwable) {
        Logger.e("Decryption failure for AppData content")
        DECRYPTION_FAILURE_APP_DATA.toByteArray(Charsets.UTF_8)
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

    val ivBytes = local.iv?.bytes

    val decryptedBytes = try {
        keyHeader.decryptWithIv(encryptedBytes, ivBytes)
    } catch (e: Throwable) {
        Logger.e("Decryption failure for LocalAppData content")
        """{}""".toByteArray(Charsets.UTF_8)
//        throw FileDecryptionException.ContentDecryptionFailed(e)
    }

    return this.copy(
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

    class NoDriveKey(driveId: Uuid) :
        FileDecryptionException("This device holds no key for the zero-knowledge drive $driveId")

    class Sealed :
        FileDecryptionException("The key header is sealed to the drive's write-only key")
}
