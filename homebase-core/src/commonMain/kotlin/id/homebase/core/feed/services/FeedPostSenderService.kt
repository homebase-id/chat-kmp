package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.outbox.OptimisticWriter
import kotlin.uuid.Uuid

/**
 * Owner-side post mutations for the native feed. Currently only [deletePost].
 *
 * ponytail: the create/edit/repost send path (createPost/updatePost + the descriptor/metadata
 * builders) was removed while feed compose is disabled (PR #802). It uploaded payloads the
 * pre-#844 way (direct `encryptBundle`); reviving compose means re-adding it on top of the
 * shared [id.homebase.upload.UploadService] pipeline, the way [PostCommentsService.postComment]
 * and the Moments sender now do. [deletePost] stays (it's a soft-delete, no payload upload).
 */
class FeedPostSenderService(
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
) {

    companion object {
        private const val TAG = "FeedPostSenderService"
    }

    /**
     * Soft-delete a post and bulk-delete all of its comments. The optimistic writer removes the
     * post from the local feed immediately; the outbox carries the post delete and a
     * [DeleteFilesByGroupIdOutboxRequest] that cleans up the comment files keyed by `groupId`.
     */
    suspend fun deletePost(channelId: Uuid, postUniqueId: Uuid) {
        val original = optimisticWriter.writeDelete(channelId, postUniqueId)
        if (original == null) {
            Logger.w(tag = TAG) { "deletePost: post $postUniqueId not found locally" }
            return
        }

        // 1. Delete the post file itself (recipients null = local + own-host removal).
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

        // 2. Clean up the post's comments by groupId (= the post uniqueId).
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
