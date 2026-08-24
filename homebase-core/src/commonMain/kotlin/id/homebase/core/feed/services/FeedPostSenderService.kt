package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.outbox.OptimisticWriter
import kotlin.uuid.Uuid

// ponytail: the create/edit/repost send path was removed while feed compose is disabled. It uploaded payloads
// the old way (direct encryptBundle); reviving compose means re-adding it on top of the shared UploadService
// pipeline, as PostCommentsService.postComment now does. [deletePost] stays — a soft-delete, no payload upload.
class FeedPostSenderService(
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
) {

    companion object {
        private const val TAG = "FeedPostSenderService"
    }

    suspend fun deletePost(channelId: Uuid, postUniqueId: Uuid) {
        val original = optimisticWriter.writeDelete(channelId, postUniqueId)
        if (original == null) {
            Logger.w(tag = TAG) { "deletePost: post $postUniqueId not found locally" }
            return
        }

        // recipients null = local + own-host removal.
        val postDelete = outboxSync.tryEnqueue(
            request = DeleteLocalFilesByFileIdRequest(
                driveId = channelId,
                fileIds = listOf(original.fileId),
                recipients = null,
                hardDelete = false,
            ),
        )
        if (!postDelete.enqueued) {
            Logger.w(tag = TAG) { "deletePost: post delete enqueue -> $postDelete; rolling back" }
            optimisticWriter.rollbackWrite(channelId, original)
            return
        }

        // The post's comments are keyed by groupId (= the post uniqueId).
        try {
            outboxSync.tryEnqueue(
                request = DeleteFilesByGroupIdOutboxRequest(
                    driveId = channelId,
                    groupIds = listOf(postUniqueId),
                ),
            )
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) {
                "deletePost: comment cleanup enqueue failed post=$postUniqueId (post already deleted)"
            }
        }
    }
}
