package id.homebase.api.client.drives

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.ArchivalStatus
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

    // Belt-and-suspenders: `fileState == Deleted` is the real/new marker; the
    // older `archivalStatus == Removed` hack still appears on some rows. Both
    // paths are treated as soft-deleted by every consumer in the codebase.
    fun isSoftDeleted(): Boolean =
        fileState == FileState.Deleted ||
            fileMetadata.appData.archivalStatus == ArchivalStatus.Removed

    /**
     * The userDate stored in `DriveMainIndex.userDate` for this file —
     * `appData.userDate` if present, else `metadata.created.milliseconds`.
     *
     * Mirrors the formula in `MainIndexMetaHelpers` (sync-side projection)
     * so callers that need to compare against the SQL column (notably the
     * unread-count and last-read tracking) get the same value regardless
     * of whether they go through the in-memory message mapper or the SQL.
     *
     * Distinct from `MessageUiModel.userDate`, which clamps to the
     * server-stamped `transitCreated` for display safety. That clamp is
     * correct for rendering but wrong for read-bookkeeping comparisons.
     */
    fun sqlUserDateMs(): Long =
        fileMetadata.appData.userDate ?: fileMetadata.created.milliseconds

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