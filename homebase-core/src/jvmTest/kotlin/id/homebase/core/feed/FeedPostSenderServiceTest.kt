@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.core.feed.services.FeedPostSenderService
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The post create/edit send path was removed while feed compose is disabled (PR #802), so this
 * only covers [FeedPostSenderService.deletePost]. Reviving compose re-adds create/update on the
 * shared UploadService pipeline — add their tests back then.
 */
class FeedPostSenderServiceTest {

    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private lateinit var env: FeedTestEnv

    private fun sender() = FeedPostSenderService(
        outboxSync = env.outboxSync,
        optimisticWriter = env.optimisticWriter,
    )

    /** Write a post row locally so [FeedPostSenderService.deletePost] can resolve + remove it. */
    private suspend fun seedPost(postId: Uuid) {
        val content = OdinSystemSerializer.serialize(
            PostContent(
                version = FeedProtocol.PostVersion,
                id = postId.toString(),
                channelId = channelDrive.toString(),
                type = PostType.Tweet,
                caption = "delete me",
                slug = "post-to-delete",
            )
        )
        env.optimisticWriter.writeNewFile(
            driveId = channelDrive,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            unecryptedMetadata = UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = false,
                appData = UploadAppFileMetaData(
                    uniqueId = postId,
                    fileType = FeedProtocol.PostFileType,
                    content = content,
                ),
            ),
            originalRecipientCount = 0,
            fileSystemType = FileSystemType.Standard,
        )
    }

    private fun runFeedTest(body: suspend TestScope.() -> Unit) = runTest {
        env = FeedTestEnv(this)
        env.login()
        advanceUntilIdle()
        try {
            body()
        } finally {
            env.close()
        }
    }

    @AfterTest
    fun teardown() {
        if (::env.isInitialized) runCatching { env.close() }
    }

    @Test
    fun deletePost_enqueuesDeleteAndCommentCleanupByGroupId() = runFeedTest {
        val postId = Uuid.random()
        seedPost(postId)
        advanceUntilIdle()

        sender().deletePost(channelId = channelDrive, postUniqueId = postId)
        advanceUntilIdle()

        // Drain every queued row and find the comment-cleanup (DeleteFilesByGroupId) entry.
        val rows = drainAllRows()
        val groupDelete = rows.firstOrNull { it.uploadType == DriveOutboxUploader.DeleteFilesByGroupId }
        assertNotNull(groupDelete, "deletePost must enqueue a DeleteFilesByGroupId comment cleanup")
        assertTrue(
            groupDelete.json.decodeToString().contains(postId.toString()),
            "the comment-cleanup row must target the post id ($postId) as its groupId",
        )
        // The post itself is also removed (DeleteFile) by fileId.
        assertTrue(
            rows.any { it.uploadType == DriveOutboxUploader.DeleteFile },
            "deletePost must enqueue the post's own DeleteFile",
        )
    }

    private suspend fun drainAllRows(): List<Outbox> {
        val seen = mutableListOf<Outbox>()
        while (true) {
            val row = env.databaseManager.outbox.checkout() ?: break
            seen.add(row)
        }
        return seen
    }
}
