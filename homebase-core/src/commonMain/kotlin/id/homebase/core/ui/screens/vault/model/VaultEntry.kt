@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault.model

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.ui.screens.vault.VaultUploadStatus
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Represents a vault file in the UI layer.
 */
@Immutable
data class VaultEntry(
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
    val pdfPageCount: Int? = null,
) {
    val isPending: Boolean get() = pendingFileUri != null

    val pageCount: Int get() = pdfPageCount ?: payloadDescriptors.size.coerceAtLeast(1)
    val hasMultiplePages: Boolean get() = pageCount > 1

    val isImage: Boolean get() = contentType.startsWith("image/")

    val isVideo: Boolean get() = contentType.startsWith("video/")

    val isAudio: Boolean get() = contentType.startsWith("audio/")

    val isPdf: Boolean get() = contentType == "application/pdf"
}

/**
 * Maps a [HomebaseFile] to a [VaultEntry], or returns null if the file
 * has no payloads or the content cannot be parsed.
 */
fun HomebaseFile.toVaultEntry(): VaultEntry? {
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
    val hasPayloadData = payloads.any { (it.bytesWritten ?: 0L) > 0L }
    val isReallyUploading = isPending && !hasPayloadData

    return VaultEntry(
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
        uploadStatus = if (isReallyUploading) VaultUploadStatus.Uploading(0f) else null,
        groupId = fileMetadata.appData.groupId,
        payloadDescriptors = payloads,
        notes = vaultFileContent.notes,
        pdfPageCount = vaultFileContent.pdfPageCount,
    )
}
