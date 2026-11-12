package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.GlobalTransitIdFileIdentifier
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.Outbox
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostCommentContent
import id.homebase.core.feed.services.PostCommentsService
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.PostCommentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PostCommentsServiceTest {

    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias
    private val feedDrive = SystemDriveConstants.feedDrive.alias

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
        userPreferences = env.userPreferences,
    )

    // A null senderOdinId keeps the over-peer comment load out of the picture.
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
        isEncrypted = false,
        acl = null,
    )

    private val postGtids = mutableMapOf<Uuid, Uuid>()

    // A Comment-file-system comment is addressed by its post's globalTransitId, which only a synced post has.
    private suspend fun seedPost(postId: Uuid, encrypted: Boolean = false) {
        val gtid = Uuid.random().also { postGtids[postId] = it }
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
        val file = HomebaseFile(
            fileId = Uuid.random(),
            driveId = channelDrive,
            serverFileIsEncrypted = encrypted,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16) { 3 })),
            fileMetadata = FileMetadata(
                globalTransitId = gtid,
                created = UnixTimeUtc(1_000),
                updated = UnixTimeUtc(1_000),
                isEncrypted = false,
                versionTag = Uuid.random(),
                appData = AppFileMetaData(
                    uniqueId = postId,
                    fileType = FeedProtocol.PostFileType,
                    userDate = 1_000,
                    content = content,
                ),
            ),
            serverMetadata = ServerMetadata(),
        )
        MainIndexMetaHelpers.HomebaseFileProcessor(env.databaseManager).baseUpsertEntryZapZap(
            identityId = env.credentialsManager.requireActiveCredentials().getIdentityId(),
            driveId = channelDrive,
            fileHeaders = listOf(file),
            cursor = null,
        )
    }

    // Feed aggregation lands a followed author's post on the feed drive with no uniqueId, so it is
    // addressable only by its globalTransitId.
    private suspend fun seedFollowedPost(gtid: Uuid, author: OdinId) {
        val content = OdinSystemSerializer.serialize(
            PostContent(
                version = FeedProtocol.PostVersion,
                id = gtid.toString(),
                channelId = channelDrive.toString(),
                type = PostType.Tweet,
                caption = "followed post",
                slug = "followed-post",
            )
        )
        val file = HomebaseFile(
            fileId = Uuid.random(),
            driveId = feedDrive,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            fileMetadata = FileMetadata(
                globalTransitId = gtid,
                created = UnixTimeUtc(1_000),
                isEncrypted = false,
                originalAuthor = author,
                appData = AppFileMetaData(
                    fileType = FeedProtocol.PostFileType,
                    userDate = 1_000,
                    content = content,
                ),
            ),
            serverMetadata = ServerMetadata(),
        )
        MainIndexMetaHelpers.HomebaseFileProcessor(env.databaseManager).baseUpsertEntryZapZap(
            identityId = env.credentialsManager.requireActiveCredentials().getIdentityId(),
            driveId = feedDrive,
            fileHeaders = listOf(file),
            cursor = null,
        )
    }

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
    fun postComment_enqueuesCommentFileType801() = runFeedTest {
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
        assertNull(request.metadata.appData.groupId)
    }

    @Test
    fun reply_keepsItsParentLocallyAndReferencesThePostWhenTheParentHasNoGlobalTransitId() = runFeedTest {
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
        assertNull(request.metadata.appData.groupId)
        assertEquals(postGtids.getValue(postId), request.metadata.referencedFile?.globalTransitId)
        val local = env.databaseManager.driveMainIndex.selectHomebaseFileByUnique(
            env.credentialsManager.requireActiveCredentials().getIdentityId(), channelDrive, replyId,
        )
        assertEquals(parentCommentId, local?.fileMetadata?.appData?.groupId, "local one-level threading")
    }

    @Test
    fun coldLoad_postWithNoComments_doesNotLeakOtherPostsComments() = runFeedTest {
        val postId = Uuid.random()
        seedPost(postId)

        // QueryBatch reads an empty groupId set as "every comment on the drive", so a decoy on an
        // unrelated post is what catches the leak.
        val decoyPostId = Uuid.random()
        seedPost(decoyPostId)
        seedComment(Uuid.random(), groupId = decoyPostId, body = "comment on the OTHER post")
        advanceUntilIdle()

        val comments = service().commentsFor(ownPost(postId))
        advanceUntilIdle()

        assertEquals(
            emptyList(), awaitLoaded(comments),
            "a post with no comments must not surface another post's comments (cross-post leak)",
        )
    }

    @Test
    fun commentsFor_readsAsLoadingUntilTheColdLoadFinishes() = runFeedTest {
        val postId = Uuid.random()
        seedPost(postId)
        advanceUntilIdle()

        val comments = service().commentsFor(ownPost(postId))
        assertNull(comments.value, "an unloaded thread must not read as an empty one")

        advanceUntilIdle()
        assertEquals(emptyList(), awaitLoaded(comments))
    }

    // The cold load's DB read completes off the test clock, so advanceUntilIdle alone can observe it unfinished.
    private suspend fun awaitLoaded(comments: StateFlow<List<PostCommentItem>?>): List<PostCommentItem> =
        withContext(Dispatchers.Default) { withTimeout(10_000) { comments.filterNotNull().first() } }

    @Test
    fun postComment_onUnencryptedPost_isUnencryptedAnonymous_onEncryptedPost_isEncrypted() =
        runFeedTest {
            val publicPostId = Uuid.random()
            seedPost(publicPostId, encrypted = false)
            val publicCommentId = Uuid.random()
            service().postComment(
                postId = publicPostId,
                body = "public comment",
                commentUniqueId = publicCommentId,
            )

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

    @Test
    fun postComment_onFollowedPostAddressedByGlobalTransitId_uploadsToTheCommentGrantedChannelDriveForTheAuthor() =
        runFeedTest {
            val author = OdinId("author.example.com")
            val gtid = Uuid.random()
            seedFollowedPost(gtid, author)
            advanceUntilIdle()

            val commentId = Uuid.random()
            service().postComment(postId = gtid, body = "nice one", commentUniqueId = commentId)
            advanceUntilIdle()

            assertNull(
                env.outboxRow(feedDrive, commentId),
                "the feed drive grants no Comment permission, so the server answers 403",
            )
            val request = readRequest(channelDrive, commentId)
            assertEquals(
                listOf(author), request.transitOptions?.recipients,
                "the post author must be a recipient or the comment never reaches them",
            )
            assertTrue(request.metadata.allowDistribution)
            assertEquals(FileSystemType.Comment, request.fileSystemType)
            assertEquals(channelTarget, request.transitOptions?.remoteTargetDrive)
            assertEquals(
                GlobalTransitIdFileIdentifier(gtid, channelTarget),
                request.metadata.referencedFile,
            )
            assertFalse(
                request.metadata.isEncrypted,
                "a comment on a public followed post must stay unencrypted",
            )
            assertEquals(
                SecurityGroupType.Anonymous.value,
                request.metadata.accessControlList?.requiredSecurityGroup,
            )
        }

    private val channelTarget = TargetDrive(channelDrive, FeedProtocol.ChannelDriveType)

    @Test
    fun postComment_onOwnPost_isUploadedToTheCommentFileSystemReferencingThePost() = runFeedTest {
        val postId = Uuid.random()
        seedPost(postId)
        advanceUntilIdle()

        val commentId = Uuid.random()
        service().postComment(postId = postId, body = "from samwise", commentUniqueId = commentId)
        advanceUntilIdle()

        val request = readRequest(channelDrive, commentId)
        assertEquals(FileSystemType.Comment, request.fileSystemType)
        assertNull(request.metadata.appData.groupId, "the Comment writer rejects a groupId")
        assertEquals(
            GlobalTransitIdFileIdentifier(postGtids.getValue(postId), channelTarget),
            request.metadata.referencedFile,
        )
    }

    @Test
    fun postComment_onOwnPost_keepsTheLocalRowStandardAndGroupedSoTheThreadRenders() = runFeedTest {
        val postId = Uuid.random()
        seedPost(postId)
        advanceUntilIdle()

        val commentId = Uuid.random()
        service().postComment(postId = postId, body = "local", commentUniqueId = commentId)
        advanceUntilIdle()

        val local = env.databaseManager.driveMainIndex.selectHomebaseFileByUnique(
            env.credentialsManager.requireActiveCredentials().getIdentityId(), channelDrive, commentId,
        )
        assertEquals(postId, local?.fileMetadata?.appData?.groupId)
    }

    @Test
    fun postComment_onEncryptedOwnPost_usesItsOwnKeyAndTheCommentFileSystem() = runFeedTest {
        val postId = Uuid.random()
        seedPost(postId, encrypted = true)
        advanceUntilIdle()

        val commentId = Uuid.random()
        service().postComment(postId = postId, body = "secret", commentUniqueId = commentId)
        advanceUntilIdle()

        val request = readRequest(channelDrive, commentId)
        assertEquals(FileSystemType.Comment, request.fileSystemType)
        assertFalse(request.withCommentedFileKey, "odin-core refuses a FileKey header outside a SelectiveShare drive")
        assertNull(request.metadata.appData.groupId)
        assertNotNull(request.metadata.referencedFile)
    }

    @Test
    fun postComment_withoutAGlobalTransitId_refusesInsteadOfWritingAnInvisibleComment() = runFeedTest {
        val postId = Uuid.random()
        env.optimisticWriter.writeNewFile(
            driveId = channelDrive,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            unecryptedMetadata = UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = false,
                appData = UploadAppFileMetaData(uniqueId = postId, fileType = FeedProtocol.PostFileType),
            ),
            originalRecipientCount = 0,
            fileSystemType = FileSystemType.Standard,
        )
        advanceUntilIdle()

        val commentId = Uuid.random()
        assertFailsWith<IllegalStateException> {
            service().postComment(postId = postId, body = "too early", commentUniqueId = commentId)
        }
        assertNull(env.outboxRow(channelDrive, commentId))
    }

    private suspend fun readRequest(driveId: Uuid, uniqueId: Uuid): UploadFileRequest {
        val row: Outbox = env.outboxRow(driveId, uniqueId) ?: error("no outbox row for $uniqueId")
        return OdinSystemSerializer.deserialize(row.json.decodeToString())
    }
}
