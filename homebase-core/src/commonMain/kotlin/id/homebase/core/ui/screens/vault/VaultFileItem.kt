@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
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
    val label: String? = null,
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
    val uniqueId: Uuid,
    val driveId: Uuid,
    val fileName: String,
    val label: String? = null,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val previewThumbnail: EmbeddedThumb?,
    val keyHeader: KeyHeader,
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
 * Maps a [HomebaseFile] to a [VaultFileItem], or returns null if the file
 * has no payloads or the content cannot be parsed.
 */
fun HomebaseFile.toVaultFileItem(): VaultFileItem? {
    val payloads = fileMetadata.payloads
    if (payloads.isNullOrEmpty()) return null

    val contentJson = fileMetadata.appData.content ?: return null
    val vaultFileContent = try {
        OdinSystemSerializer.deserialize<VaultFileContent>(contentJson)
    } catch (e: Exception) {
        return null
    }

    val isPending = fileMetadata.localAppData?.tags
        ?.contains(ChatProtocol.isPendingSendTag) == true

    return VaultFileItem(
        fileId = fileId,
        uniqueId = fileMetadata.appData.uniqueId ?: fileId,
        driveId = driveId,
        fileName = vaultFileContent.name,
        label = vaultFileContent.label,
        contentType = payloads.first().contentType ?: "",
        sizeBytes = payloads.sumOf { it.bytesWritten ?: 0L },
        createdAt = fileMetadata.created.milliseconds,
        previewThumbnail = fileMetadata.appData.previewThumbnail,
        keyHeader = keyHeader,
        isEncrypted = fileMetadata.isEncrypted,
        versionTag = fileMetadata.versionTag,
        uploadStatus = if (isPending) VaultUploadStatus.Uploading(0f) else null,
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
