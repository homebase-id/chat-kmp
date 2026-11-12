package id.homebase.core.feed

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.core.feed.services.postAcl
import id.homebase.core.feed.services.PostAudience
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.core.feed.services.FeedPostSenderService
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FeedPostSenderServiceTest {

    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private lateinit var env: FeedTestEnv

    private fun sender() = FeedPostSenderService(
        outboxSync = env.outboxSync,
        uploadService = env.uploadService,
        optimisticWriter = env.optimisticWriter,
        fileOps = env.fileOps,
        userPreferences = env.userPreferences,
        scope = env.scope,
    )

    private suspend fun readRequest(postId: Uuid): UploadFileRequest {
        val row = env.outboxRow(channelDrive, postId)
        assertNotNull(row, "createPost must enqueue an upload for the post")
        assertEquals(DriveOutboxUploader.UploadNewFile, row.uploadType)
        return OdinSystemSerializer.deserialize<UploadFileRequest>(row.json.decodeToString())
    }

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
    fun createPost_publicText_enqueuesUnencryptedAnonymousTweet() = runFeedTest {
        val result = sender().createPost(
            channelId = channelDrive,
            caption = "hello feed",
            attachments = emptyList(),
            acl = postAcl(PostAudience.Public),
        )
        advanceUntilIdle()

        val request = readRequest(result.uniqueId)
        assertEquals(FeedProtocol.PostFileType, request.metadata.appData.fileType)
        assertEquals(FeedProtocol.TweetDataType, request.metadata.appData.dataType)
        assertEquals(result.uniqueId, request.metadata.appData.uniqueId)
        assertFalse(request.metadata.isEncrypted, "a public post must ship unencrypted")
        assertEquals(SecurityGroupType.Anonymous.value, request.metadata.accessControlList?.requiredSecurityGroup)
        val content = OdinSystemSerializer.deserialize<PostContent>(request.metadata.appData.content!!)
        assertEquals("hello feed", content.caption)
        assertEquals(PostType.Tweet, content.type)
        assertEquals(channelDrive.toString(), content.channelId)
    }

    @Test
    fun createPost_circles_isEncryptedConnectedWithTheCircleIds() = runFeedTest {
        val circleId = Uuid.random().toString()
        val result = sender().createPost(
            channelId = channelDrive,
            caption = "just for the circle",
            attachments = emptyList(),
            acl = postAcl(PostAudience.Circles, listOf(circleId)),
        )
        advanceUntilIdle()

        val request = readRequest(result.uniqueId)
        assertTrue(request.metadata.isEncrypted, "a circles post must be encrypted")
        assertEquals(SecurityGroupType.Connected.value, request.metadata.accessControlList?.requiredSecurityGroup)
        assertEquals(listOf(circleId), request.metadata.accessControlList?.circleIdList)
    }

    @Test
    fun createPost_sameCaptionTwice_getsDistinctPosts() = runFeedTest {
        val acl = postAcl(PostAudience.Public)
        val first = sender().createPost(channelDrive, "same words", emptyList(), acl)
        val second = sender().createPost(channelDrive, "same words", emptyList(), acl)
        assertNotEquals(first.uniqueId, second.uniqueId)
    }

    @Test
    fun createPost_captionOverTheHeaderBudget_spillsToTheTextPayload() = runFeedTest {
        val long = "x".repeat(HomebaseProtocol.MaxHeaderContentBytes + 100)
        val result = sender().createPost(channelDrive, long, emptyList(), postAcl(PostAudience.Public))
        advanceUntilIdle()

        val request = readRequest(result.uniqueId)
        assertTrue(request.payloads.any { it.key == FeedProtocol.FullTextPayloadKey })
        val content = OdinSystemSerializer.deserialize<PostContent>(request.metadata.appData.content!!)
        assertEquals("", content.caption)
    }

    @Test
    fun createPost_withNothingToPost_isRefused() = runFeedTest {
        assertFailsWith<IllegalArgumentException> {
            sender().createPost(channelDrive, "   ", emptyList(), postAcl(PostAudience.Public))
        }
    }

    @Test
    fun postAcl_circlesNeedAtLeastOneCircle() {
        assertFailsWith<IllegalArgumentException> { postAcl(PostAudience.Circles, emptyList()) }
        assertEquals(
            AccessControlList(requiredSecurityGroup = SecurityGroupType.Anonymous.value),
            postAcl(PostAudience.Public),
        )
    }

    @Test
    fun deletePost_enqueuesDeleteAndCommentCleanupByGroupId() = runFeedTest {
        val postId = Uuid.random()
        seedPost(postId)
        advanceUntilIdle()

        sender().deletePost(channelId = channelDrive, postUniqueId = postId)
        advanceUntilIdle()

        val rows = drainAllRows()
        val groupDelete = rows.firstOrNull { it.uploadType == DriveOutboxUploader.DeleteFilesByGroupId }
        assertNotNull(groupDelete, "deletePost must enqueue a DeleteFilesByGroupId comment cleanup")
        assertTrue(
            groupDelete.json.decodeToString().contains(postId.toString()),
            "the comment-cleanup row must target the post id ($postId) as its groupId",
        )
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
