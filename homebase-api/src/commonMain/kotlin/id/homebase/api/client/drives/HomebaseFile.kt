package id.homebase.api.client.drives

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import id.homebase.api.common.OdinId

/**
 * A file from a homebase server, fully unencrypted ready for client usage
 */
@Serializable
data class HomebaseFile(
    @Serializable(with = UuidSerializer::class)
    val fileId: Uuid,
    val driveId: Uuid,
    val serverFileIsEncrypted: Boolean = false,
    val fileState: FileState,
    val fileSystemType: FileSystemType,
    val keyHeader: KeyHeader,
    val fileMetadata: FileMetadata,
    val serverMetadata: ServerMetadata,
    val priority: Int = 0,
    val fileByteCount: Long = 0
) {


    fun assertFileIsActive() {
        if (fileState == FileState.Deleted) {
            throw Exception("File is deleted.")
        }
    }

    fun assertOriginalAuthor(odinId: OdinId) {
        val originalAuthor = fileMetadata.originalAuthor
        if (originalAuthor == null) {
            // backwards compatibility
            assertOriginalSender(odinId)
            return
        }

        if (originalAuthor != odinId) {
            throw Exception("Sender does not match original author")
        }
    }

    fun isOriginalSender(odinId: OdinId): Boolean {
        return fileMetadata.senderOdinId == odinId
    }

    fun assertOriginalSender(odinId: OdinId) {
        val senderOdinId = fileMetadata.senderOdinId
        if (senderOdinId == null) {
            throw Exception(
                "Original file does not have a sender (FileId: $fileId on Drive: $driveId"
            )
        }

        if (!isOriginalSender(odinId)) {
            throw Exception("Sender does not match original sender")
        }
    }
}