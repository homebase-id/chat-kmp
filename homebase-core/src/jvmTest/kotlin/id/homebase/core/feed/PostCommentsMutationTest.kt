package id.homebase.core.feed

import app.cash.sqldelight.db.SqlDriver
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.GlobalTransitIdFileIdentifier
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PostCommentsMutationTest {

    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private lateinit var env: FeedTestEnv
    private var blockingDriver: OutboxInsertBlockingDriver? = null

    private fun runFeedTest(
        blockOutboxInserts: Boolean = false,
        body: suspend TestScope.() -> Unit,
    ) = runTest {
        env = if (blockOutboxInserts) {
            FeedTestEnv(this) { raw ->
                OutboxInsertBlockingDriver(raw).also { blockingDriver = it }
            }
        } else {
            FeedTestEnv(this)
        }
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
        blockingDriver = null
        if (::env.isInitialized) runCatching { env.close() }
    }

    private fun TestScope.service(
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    ) = PostCommentsService(
        databaseManager = env.databaseManager,
        credentialsManager = env.credentialsManager,
        eventBus = env.eventBus,
        outboxSync = env.outboxSync,
        uploadService = env.uploadService,
        optimisticWriter = env.optimisticWriter,
        fileOps = env.fileOps,
        driveQueryProvider = env.driveQueryProvider,
        scope = scope,
        userPreferences = env.userPreferences,
    )

    private fun keyHeader(fill: Byte = 1) =
        KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16) { fill }))

    private fun postContent(postId: Uuid) = OdinSystemSerializer.serialize(
        PostContent(
            version = FeedProtocol.PostVersion,
            id = postId.toString(),
            channelId = channelDrive.toString(),
            type = PostType.Tweet,
            caption = "host post",
            slug = "host-post",
        )
    )

    private suspend fun upsertSynced(file: HomebaseFile) {
        MainIndexMetaHelpers.HomebaseFileProcessor(env.databaseManager).baseUpsertEntryZapZap(
            identityId = env.credentialsManager.requireActiveCredentials().getIdentityId(),
            driveId = channelDrive,
            fileHeaders = listOf(file),
            cursor = null,
        )
    }

    private suspend fun seedPost(postId: Uuid, encrypted: Boolean = false) {
        env.optimisticWriter.writeNewFile(
            driveId = channelDrive,
            keyHeader = keyHeader(),
            unecryptedMetadata = UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = encrypted,
                appData = UploadAppFileMetaData(
                    uniqueId = postId,
                    fileType = FeedProtocol.PostFileType,
                    content = postContent(postId),
                ),
            ),
            originalRecipientCount = 0,
            fileSystemType = FileSystemType.Standard,
        )
    }

    // Sync stores the decrypted header: fileMetadata.isEncrypted is false, only serverFileIsEncrypted keeps the truth.
    private suspend fun seedSyncedPost(
        postId: Uuid,
        serverEncrypted: Boolean,
        acl: AccessControlList? = null,
    ) {
        val file = HomebaseFile(
            fileId = Uuid.random(),
            driveId = channelDrive,
            serverFileIsEncrypted = serverEncrypted,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = keyHeader(),
            fileMetadata = FileMetadata(
                globalTransitId = Uuid.random(),
                created = UnixTimeUtc(1_000),
                updated = UnixTimeUtc(1_000),
                isEncrypted = false,
                versionTag = Uuid.random(),
                appData = AppFileMetaData(
                    uniqueId = postId,
                    fileType = FeedProtocol.PostFileType,
                    userDate = 1_000,
                    content = postContent(postId),
                ),
            ),
            serverMetadata = ServerMetadata(accessControlList = acl),
        )
        upsertSynced(file)
    }

    // updateComment requires a server-assigned versionTag, which an optimistic local write never
    // has — only a synced row does.
    private suspend fun seedSyncedComment(
        commentId: Uuid,
        groupId: Uuid,
        body: String,
        versionTag: Uuid,
        isEncrypted: Boolean = false,
        referencedFile: GlobalTransitIdFileIdentifier? = null,
    ): HomebaseFile {
        val file = HomebaseFile(
            fileId = Uuid.random(),
            driveId = channelDrive,
            serverFileIsEncrypted = isEncrypted,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = keyHeader(fill = 7),
            fileMetadata = FileMetadata(
                created = UnixTimeUtc(1_000),
                updated = UnixTimeUtc(1_000),
                isEncrypted = isEncrypted,
                versionTag = versionTag,
                referencedFile = referencedFile,
                appData = AppFileMetaData(
                    uniqueId = commentId,
                    groupId = groupId,
                    fileType = FeedProtocol.CommentFileType,
                    userDate = 1_000,
                    content = OdinSystemSerializer.serialize(
                        PostCommentContent(version = FeedProtocol.CommentVersion, body = body)
                    ),
                ),
            ),
            serverMetadata = ServerMetadata(),
        )
        upsertSynced(file)
        return file
    }

    private fun ownPost(postId: Uuid) = FeedPostItem(
        id = postId,
        fileId = Uuid.random(),
        globalTransitId = null,
        driveId = channelDrive,
        keyHeader = keyHeader(),
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

    private suspend fun updateRequest(uniqueId: Uuid): UpdateFileByUniqueIdRequest {
        val row = env.outboxRow(channelDrive, uniqueId) ?: error("no outbox row for $uniqueId")
        assertEquals(DriveOutboxUploader.UpdateFile, row.uploadType)
        return OdinSystemSerializer.deserialize(row.json.decodeToString())
    }

    private suspend fun localFile(uniqueId: Uuid): HomebaseFile? =
        env.databaseManager.driveMainIndex.selectHomebaseFileByUnique(
            env.credentialsManager.requireActiveCredentials().getIdentityId(),
            channelDrive,
            uniqueId,
        )

    private suspend fun drainAllRows(): List<Outbox> {
        val seen = mutableListOf<Outbox>()
        while (true) {
            val row = env.databaseManager.outbox.checkout() ?: break
            seen.add(row)
        }
        return seen
    }

    @Test
    fun postComment_onOwnerOnlyPost_usesOwnerAcl() = runFeedTest {
        val postId = Uuid.random()
        seedSyncedPost(
            postId,
            serverEncrypted = false,
            acl = AccessControlList(requiredSecurityGroup = "owner"),
        )
        advanceUntilIdle()

        val commentId = Uuid.random()
        service().postComment(postId = postId, body = "hi", commentUniqueId = commentId)
        advanceUntilIdle()

        val row = env.outboxRow(channelDrive, commentId) ?: error("no outbox row")
        val acl = OdinSystemSerializer.deserialize<UploadFileRequest>(row.json.decodeToString())
            .metadata.accessControlList
        assertEquals(SecurityGroupType.Owner.value, acl?.requiredSecurityGroup)
    }

    @Test
    fun updateComment_onPublicPost_enqueuesUnencryptedUpdateKeepingGroupIdAndVersionTag() =
        runFeedTest {
            val postId = Uuid.random()
            val commentId = Uuid.random()
            val versionTag = Uuid.random()
            seedPost(postId, encrypted = false)
            seedSyncedComment(commentId, groupId = postId, body = "before", versionTag = versionTag)
            advanceUntilIdle()

            service().updateComment(commentId, versionTag, "after")
            advanceUntilIdle()

            val request = updateRequest(commentId)
            assertEquals(FeedProtocol.CommentFileType, request.metadata.appData.fileType)
            assertEquals(postId, request.metadata.appData.groupId)
            assertEquals(versionTag, request.metadata.versionTag)
            assertNull(request.fileSystemType, "a comment written before the Comment file system stays Standard")
            assertFalse(
                request.metadata.isEncrypted,
                "an edit of a comment on a public post must stay unencrypted",
            )
            assertEquals(
                SecurityGroupType.Anonymous.value,
                request.metadata.accessControlList?.requiredSecurityGroup,
            )
            val content: PostCommentContent =
                OdinSystemSerializer.deserialize(request.metadata.appData.content!!)
            assertEquals("after", content.body)
        }

    @Test
    fun updateComment_onACommentFileSystemComment_patchesTheCommentFileSystemByItsReference() = runFeedTest {
        val postId = Uuid.random()
        val commentId = Uuid.random()
        val versionTag = Uuid.random()
        val reference = GlobalTransitIdFileIdentifier(
            Uuid.random(), TargetDrive(channelDrive, FeedProtocol.ChannelDriveType),
        )
        seedPost(postId)
        seedSyncedComment(
            commentId, groupId = postId, body = "before", versionTag = versionTag, referencedFile = reference,
        )
        advanceUntilIdle()

        service().updateComment(commentId, versionTag, "after")
        advanceUntilIdle()

        val request = updateRequest(commentId)
        assertEquals(FileSystemType.Comment, request.fileSystemType)
        assertNull(request.metadata.appData.groupId, "the Comment update writer rejects a groupId")
        assertEquals(reference, request.metadata.referencedFile)
        assertEquals(versionTag, request.metadata.versionTag)
        assertEquals(postId, localFile(commentId)?.fileMetadata?.appData?.groupId, "the local thread keeps its groupId")
    }

    @Test
    fun updateComment_onEncryptedPost_enqueuesEncryptedUpdateWithCiphertextContent() = runFeedTest {
        val postId = Uuid.random()
        val commentId = Uuid.random()
        val versionTag = Uuid.random()
        seedPost(postId, encrypted = true)
        seedSyncedComment(
            commentId, groupId = postId, body = "before", versionTag = versionTag,
            isEncrypted = true,
        )
        advanceUntilIdle()

        service().updateComment(commentId, versionTag, "after")
        advanceUntilIdle()

        val request = updateRequest(commentId)
        assertTrue(
            request.metadata.isEncrypted,
            "an edit of a comment on an encrypted post must stay encrypted",
        )
        assertFalse(
            request.metadata.appData.content.orEmpty().contains("after"),
            "the edited body must be ciphertext on the wire",
        )
    }

    @Test
    fun postComment_onSyncedEncryptedPost_isEncryptedWithAutoConnectedAcl() = runFeedTest {
        val postId = Uuid.random()
        val commentId = Uuid.random()
        seedSyncedPost(postId, serverEncrypted = true)
        advanceUntilIdle()

        service().postComment(postId = postId, body = "secret", commentUniqueId = commentId)
        advanceUntilIdle()

        val row = env.outboxRow(channelDrive, commentId) ?: error("no outbox row")
        val request = OdinSystemSerializer.deserialize<UploadFileRequest>(row.json.decodeToString())
        assertTrue(request.metadata.isEncrypted, "a comment on an encrypted post must be encrypted")
        assertEquals(
            SecurityGroupType.AutoConnected.value,
            request.metadata.accessControlList?.requiredSecurityGroup,
        )
        assertFalse(request.metadata.appData.content.orEmpty().contains("secret"))
    }

    @Test
    fun updateComment_onSyncedEncryptedPost_staysEncrypted() = runFeedTest {
        val postId = Uuid.random()
        val commentId = Uuid.random()
        val versionTag = Uuid.random()
        seedSyncedPost(postId, serverEncrypted = true)
        seedSyncedComment(
            commentId, groupId = postId, body = "before", versionTag = versionTag,
            isEncrypted = true,
        )
        advanceUntilIdle()

        service().updateComment(commentId, versionTag, "after")
        advanceUntilIdle()

        val request = updateRequest(commentId)
        assertTrue(request.metadata.isEncrypted)
        assertFalse(request.metadata.appData.content.orEmpty().contains("after"))
    }

    @Test
    fun updateComment_versionTagMismatch_failsAndEnqueuesNothing() = runFeedTest {
        val postId = Uuid.random()
        val commentId = Uuid.random()
        seedPost(postId)
        seedSyncedComment(commentId, groupId = postId, body = "before", versionTag = Uuid.random())
        advanceUntilIdle()

        assertFails { service().updateComment(commentId, Uuid.random(), "after") }
        advanceUntilIdle()

        assertEquals(0L, env.outboxCount())
    }

    @Test
    fun updateComment_commentNotFoundLocally_fails() = runFeedTest {
        assertFails { service().updateComment(Uuid.random(), Uuid.random(), "after") }
    }

    @Test
    fun updateComment_whileItsCreateIsStillQueued_failsWithoutStrandingTheCreate() = runFeedTest {
        val postId = Uuid.random()
        val commentId = Uuid.random()
        val versionTag = Uuid.random()
        seedPost(postId)
        seedSyncedComment(commentId, groupId = postId, body = "before", versionTag = versionTag)
        env.outboxSync.tryEnqueue(
            UploadFileRequest(
                driveId = channelDrive,
                keyHeader = keyHeader(),
                metadata = UploadFileMetadata(
                    allowDistribution = false,
                    isEncrypted = false,
                    appData = UploadAppFileMetaData(
                        uniqueId = commentId,
                        groupId = postId,
                        fileType = FeedProtocol.CommentFileType,
                        content = "{}",
                    ),
                ),
            )
        )
        advanceUntilIdle()

        assertFails { service().updateComment(commentId, versionTag, "after") }
        advanceUntilIdle()

        val row = env.outboxRow(channelDrive, commentId)
        assertNotNull(row)
        assertEquals(
            DriveOutboxUploader.UploadNewFile, row.uploadType,
            "the un-sent create must survive a failed edit, not be replaced by an update",
        )
    }

    @Test
    fun removeComment_softDeletesLocallyAndEnqueuesDeleteByFileId() = runFeedTest {
        val postId = Uuid.random()
        val commentId = Uuid.random()
        seedPost(postId)
        val comment =
            seedSyncedComment(commentId, groupId = postId, body = "bye", versionTag = Uuid.random())
        advanceUntilIdle()

        service().removeComment(commentId)
        advanceUntilIdle()

        assertEquals(true, localFile(commentId)?.isSoftDeleted())
        val rows = drainAllRows()
        val delete = rows.firstOrNull { it.uploadType == DriveOutboxUploader.DeleteFile }
        assertNotNull(delete, "removeComment must enqueue a DeleteFile")
        assertTrue(
            delete.json.decodeToString().contains(comment.fileId.toString()),
            "the delete must target the comment's fileId",
        )
    }

    @Test
    fun removeComment_commentNotFoundLocally_enqueuesNothing() = runFeedTest {
        service().removeComment(Uuid.random())
        advanceUntilIdle()

        assertEquals(0L, env.outboxCount())
    }

    @Test
    fun removeComment_whenEnqueueFails_leavesTheCommentInTheThread() =
        runFeedTest(blockOutboxInserts = true) {
            val postId = Uuid.random()
            val commentId = Uuid.random()
            seedPost(postId)
            seedSyncedComment(commentId, groupId = postId, body = "stays", versionTag = Uuid.random())
            advanceUntilIdle()

            val service = service()
            val comments = service.commentsFor(ownPost(postId))
            advanceUntilIdle()
            assertEquals(listOf(commentId), comments.value!!.map { it.id })

            blockingDriver!!.failOutboxInserts = true
            service.removeComment(commentId)
            advanceUntilIdle()

            assertEquals(0L, env.outboxCount(), "the delete must not be queued when the insert fails")
            assertEquals(
                listOf(commentId), comments.value!!.map { it.id },
                "a refused enqueue must leave the comment in place",
            )
        }
}
