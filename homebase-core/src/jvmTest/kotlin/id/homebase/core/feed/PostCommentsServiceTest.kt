@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostCommentContent
import id.homebase.core.feed.services.PostCommentsService
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        uploadService = env.uploadService,
        optimisticWriter = env.optimisticWriter,
        fileOps = env.fileOps,
        driveQueryProvider = env.driveQueryProvider,
        scope = env.scope,
    )

    /** A minimal own-post [FeedPostItem] (null senderOdinId → no over-peer comment load). */
    private fun ownPost(postId: Uuid) = FeedPostItem(
        id = postId,
        fileId = Uuid.random(),
        globalTransitId = null,
        driveId = channelDrive,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        payloads = emptyList(),
        caption = "",
        type = PostType.Tweet,
        channelId = channelDrive.toString(),
        slug = "",
        reactAccess = ReactAccess.All,
        embeddedPost = null,
        userDateMs = 0,
        createdMs = 0,
        previewThumbnail = null,
        reactionPreview = null,
        senderOdinId = null,
        originalAuthor = null,
        versionTag = null,
        ownReactions = emptyList(),
        commentCount = 0,
    )

    /** Write a post row locally so the comment service can resolve the post's drive/audience. */
    private suspend fun seedPost(postId: Uuid, encrypted: Boolean = false) {
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
                isEncrypted = encrypted,
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

    /** Write a comment row locally (fileType 801) with the given groupId, for cold-load tests. */
    private suspend fun seedComment(commentId: Uuid, groupId: Uuid, body: String) {
        val content = OdinSystemSerializer.serialize(
            PostCommentContent(version = FeedProtocol.CommentVersion, body = body)
        )
        env.optimisticWriter.writeNewFile(
            driveId = channelDrive,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            unecryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = false,
                appData = UploadAppFileMetaData(
                    uniqueId = commentId,
                    groupId = groupId,
                    fileType = FeedProtocol.CommentFileType,
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

    @Test
    fun coldLoad_postWithNoComments_doesNotLeakOtherPostsComments() = runFeedTest {
        // The post under view has ZERO comments...
        val postId = Uuid.random()
        seedPost(postId)

        // ...but an UNRELATED post on the same drive has a top-level comment. The old reply pass
        // queried with an empty groupId set when the host post had no top-level comments, which
        // QueryBatch turns into "every comment on the drive" — leaking this decoy across posts.
        val decoyPostId = Uuid.random()
        seedPost(decoyPostId)
        seedComment(Uuid.random(), groupId = decoyPostId, body = "comment on the OTHER post")
        advanceUntilIdle()

        val comments = service().commentsFor(ownPost(postId))
        advanceUntilIdle()

        assertEquals(
            emptyList(), comments.value,
            "a post with no comments must not surface another post's comments (cross-post leak)",
        )
    }

    @Test
    fun postComment_onUnencryptedPost_isUnencryptedAnonymous_onEncryptedPost_isEncrypted() =
        runFeedTest {
            // Public (unencrypted) post → comment must be unencrypted + Anonymous ACL.
            val publicPostId = Uuid.random()
            seedPost(publicPostId, encrypted = false)
            val publicCommentId = Uuid.random()
            service().postComment(
                postId = publicPostId,
                body = "public comment",
                commentUniqueId = publicCommentId,
            )

            // Encrypted post → comment must be encrypted.
            val privatePostId = Uuid.random()
            seedPost(privatePostId, encrypted = true)
            val privateCommentId = Uuid.random()
            service().postComment(
                postId = privatePostId,
                body = "private comment",
                commentUniqueId = privateCommentId,
            )
            advanceUntilIdle()

            val publicReq = readRequest(channelDrive, publicCommentId)
            assertFalse(
                publicReq.metadata.isEncrypted,
                "a comment on an unencrypted (public) post must be unencrypted",
            )
            assertEquals(
                SecurityGroupType.Anonymous.value,
                publicReq.metadata.accessControlList?.requiredSecurityGroup,
                "a comment on a public post must carry an Anonymous ACL",
            )

            val privateReq = readRequest(channelDrive, privateCommentId)
            assertTrue(
                privateReq.metadata.isEncrypted,
                "a comment on an encrypted post must be encrypted",
            )
        }

    private suspend fun readRequest(driveId: Uuid, uniqueId: Uuid): UploadFileRequest {
        val row: Outbox = env.outboxRow(driveId, uniqueId) ?: error("no outbox row for $uniqueId")
        return OdinSystemSerializer.deserialize(row.json.decodeToString())
    }
}
