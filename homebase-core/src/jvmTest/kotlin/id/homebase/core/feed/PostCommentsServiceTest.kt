@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostCommentsService
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PostCommentsServiceTest {

    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private lateinit var env: FeedTestEnv

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

    private fun service() = PostCommentsService(
        databaseManager = env.databaseManager,
        credentialsManager = env.credentialsManager,
        eventBus = env.eventBus,
        outboxSync = env.outboxSync,
        payloadBundleEncryptor = env.payloadBundleEncryptor,
        optimisticWriter = env.optimisticWriter,
        fileOps = env.fileOps,
        scope = env.scope,
    )

    /** Write a post row locally so the comment service can resolve the post's drive/audience. */
    private suspend fun seedPost(postId: Uuid) {
        val content = OdinSystemSerializer.serialize(
            PostContent(
                version = FeedProtocol.PostVersion,
                id = postId.toString(),
                channelId = channelDrive.toString(),
                type = PostType.Tweet,
                caption = "host post",
                slug = "host-post",
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

    @Test
    fun postComment_enqueuesCommentFileType801WithGroupIdEqualToPostId() = runFeedTest {
        val postId = Uuid.random()
        seedPost(postId)
        advanceUntilIdle()

        val commentId = Uuid.random()
        service().postComment(
            postId = postId,
            body = "first comment",
            attachment = null,
            replyToCommentId = null,
            commentUniqueId = commentId,
        )
        advanceUntilIdle()

        val row = env.outboxRow(channelDrive, commentId)
        assertNotNull(row, "postComment must enqueue an upload for the comment")
        assertEquals(DriveOutboxUploader.UploadNewFile, row.uploadType)

        val request = OdinSystemSerializer.deserialize<UploadFileRequest>(row.json.decodeToString())
        assertEquals(FeedProtocol.CommentFileType, request.metadata.appData.fileType)
        assertEquals(postId, request.metadata.appData.groupId, "a top-level comment's groupId is the post id")
    }

    @Test
    fun reply_carriesGroupIdEqualToParentCommentId() = runFeedTest {
        val postId = Uuid.random()
        seedPost(postId)
        advanceUntilIdle()

        val parentCommentId = Uuid.random()
        val replyId = Uuid.random()
        service().postComment(
            postId = postId,
            body = "a reply",
            attachment = null,
            replyToCommentId = parentCommentId,
            commentUniqueId = replyId,
        )
        advanceUntilIdle()

        val request = readRequest(channelDrive, replyId)
        assertEquals(FeedProtocol.CommentFileType, request.metadata.appData.fileType)
        assertEquals(
            parentCommentId, request.metadata.appData.groupId,
            "a reply's groupId is its parent comment id (one-level threading)",
        )
    }

    private suspend fun readRequest(driveId: Uuid, uniqueId: Uuid): UploadFileRequest {
        val row: Outbox = env.outboxRow(driveId, uniqueId) ?: error("no outbox row for $uniqueId")
        return OdinSystemSerializer.deserialize(row.json.decodeToString())
    }
}
