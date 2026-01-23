package id.homebase.api.client.drives

import id.homebase.homebasekmppoc.prototype.lib.crypto.KeyHeader
import id.homebase.homebasekmppoc.prototype.lib.drives.FileState
import id.homebase.homebasekmppoc.prototype.lib.drives.FileSystemType
import id.homebase.homebasekmppoc.prototype.lib.drives.ServerMetadata
import id.homebase.homebasekmppoc.prototype.lib.drives.files.FileMetadata
import id.homebase.homebasekmppoc.prototype.lib.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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

    fun assertOriginalAuthor(odinId: String) {
        val originalAuthor = fileMetadata.originalAuthor
        if (originalAuthor.isNullOrEmpty()) {
            // backwards compatibility
            assertOriginalSender(odinId)
            return
        }

        if (originalAuthor != odinId) {
            throw Exception("Sender does not match original author")
        }
    }

    fun isOriginalSender(odinId: String): Boolean {
        return fileMetadata.senderOdinId == odinId
    }

    fun assertOriginalSender(odinId: String) {
        val senderOdinId = fileMetadata.senderOdinId
        if (senderOdinId.isNullOrEmpty()) {
            throw Exception(
                "Original file does not have a sender (FileId: $fileId on Drive: $driveId"
            )
        }

        if (!isOriginalSender(odinId)) {
            throw Exception("Sender does not match original sender")
        }
    }
}