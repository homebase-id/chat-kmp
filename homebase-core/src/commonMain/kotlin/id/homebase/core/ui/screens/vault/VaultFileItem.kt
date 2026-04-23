@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable


const val VAULT_FILE_TYPE = 5572
const val VAULT_SECTION_TYPE = 5573

/**
 * JSON content stored in appData.content for vault files.
 */
@Serializable
data class VaultFileContent(
    val name: String,
    val notes: String? = null,
)

@Serializable
data class VaultSectionContent(
    val title: String,
    val sortOrder: Int,
)

/**
 * Represents a vault file in the UI layer.
 */
@Immutable
data class VaultFileItem(
    val fileId: Uuid,
    val driveId: Uuid,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val previewThumbnail: EmbeddedThumb?,
    val payloadKey: String,
    val keyHeader: KeyHeader,
    val payloadIv: String? = null,
    val isEncrypted: Boolean,
    val versionTag: Uuid?,
    val uploadStatus: VaultUploadStatus? = null,
    val pendingFileUri: String? = null,
    val groupId: Uuid? = null,
    val payloadDescriptors: List<PayloadDescriptor> = emptyList(),
    val notes: String? = null,
) {
    val isPending: Boolean get() = pendingFileUri != null

    val pageCount: Int get() = payloadDescriptors.size.coerceAtLeast(1)
    val hasMultiplePages: Boolean get() = pageCount > 1

    @OptIn(ExperimentalEncodingApi::class)
    val payloadKeyHeader: KeyHeader
        get() = payloadIv?.let { ivBase64 ->
            try {
                KeyHeader(Base64.decode(ivBase64), keyHeader.aesKey)
            } catch (_: Exception) {
                keyHeader
            }
        } ?: keyHeader

    val isImage: Boolean get() = contentType.startsWith("image/")

    val isVideo: Boolean get() = contentType.startsWith("video/")

    val isAudio: Boolean get() = contentType.startsWith("audio/")

    val isPdf: Boolean get() = contentType == "application/pdf"
}

/**
 * Represents the upload status of a vault file.
 */
sealed interface VaultUploadStatus {
    data object Preparing : VaultUploadStatus
    data class Uploading(val progress: Float) : VaultUploadStatus
    data object Completed : VaultUploadStatus
    data class Failed(val error: String) : VaultUploadStatus
}

/**
 * Guesses a MIME content type from the file extension.
 */
internal fun guessContentType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "heic", "heif" -> "image/heic"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        else -> "application/octet-stream"
    }
}

/**
 * Maps a [HomebaseFile] to a [VaultFileItem], or returns null if the file
 * has no payloads or the content cannot be parsed.
 */
@OptIn(ExperimentalEncodingApi::class)
fun HomebaseFile.toVaultFileItem(): VaultFileItem? {
    val payloads = fileMetadata.payloads
    if (payloads.isNullOrEmpty()) return null

    val firstPayload = payloads.first()

    val contentJson = fileMetadata.appData.content ?: return null
    val vaultFileContent = try {
        OdinSystemSerializer.deserialize<VaultFileContent>(contentJson)
    } catch (e: Exception) {
        return null
    }

    return VaultFileItem(
        fileId = fileId,
        driveId = driveId,
        fileName = vaultFileContent.name,
        contentType = firstPayload.contentType ?: "",
        sizeBytes = firstPayload.bytesWritten ?: 0L,
        createdAt = fileMetadata.created.milliseconds,
        previewThumbnail = fileMetadata.appData.previewThumbnail,
        payloadKey = firstPayload.key,
        keyHeader = keyHeader,
        payloadIv = firstPayload.iv,
        isEncrypted = fileMetadata.isEncrypted,
        versionTag = fileMetadata.versionTag,
        groupId = fileMetadata.appData.groupId,
        payloadDescriptors = payloads,
        notes = vaultFileContent.notes,
    )
}

/**
 * Maps a [HomebaseFile] to a [VaultSectionContent], or returns null if the file
 * has no content or the content cannot be parsed.
 */
fun HomebaseFile.toVaultSection(): VaultSectionContent? {
    val contentJson = fileMetadata.appData.content ?: return null
    return try {
        OdinSystemSerializer.deserialize<VaultSectionContent>(contentJson)
    } catch (e: Exception) {
        null
    }
}
