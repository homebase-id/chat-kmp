@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.Md5
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.core.feed.services.FeedPostSenderService
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.toDataType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
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

class FeedPostSenderServiceTest {

    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private lateinit var env: FeedTestEnv

    private fun aclFor(group: SecurityGroupType) =
        AccessControlList(requiredSecurityGroup = group.value)

    private fun driveFileProvider(creds: CredentialsManager): DriveFileProvider {
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        return DriveFileProvider(
            httpClient = http,
            credentialsManager = creds,
            driveCache = DriveFileProviderCached(http, creds, env.fileOps),
        )
    }

    private fun TestScope.sender(scope: CoroutineScope) = FeedPostSenderService(
        outboxSync = env.outboxSync,
        payloadBundleEncryptor = env.payloadBundleEncryptor,
        fileOps = env.fileOps,
        driveFileProvider = driveFileProvider(env.credentialsManager),
        optimisticWriter = env.optimisticWriter,
        scope = scope,
    )

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
    fun createPost_enqueuesUploadNewFileToChannelDriveWithFileType101() = runFeedTest {
        val slug = "my-first-post"
        val result = sender(backgroundScope).createPost(
            channelId = channelDrive,
            type = PostType.Tweet,
            caption = "Hello feed",
            attachments = emptyList(),
            linkPreview = null,
            acl = aclFor(SecurityGroupType.Connected),
            slug = slug,
        )
        advanceUntilIdle()

        val expectedId = Md5.toGuidId(slug)
        assertEquals(expectedId, result.uniqueId)

        val row = env.outboxRow(channelDrive, expectedId)
        assertNotNull(row, "createPost must enqueue an outbox row on the channel drive")
        assertEquals(channelDrive, row.driveId)
        assertEquals(DriveOutboxUploader.UploadNewFile, row.uploadType)

        val request = OdinSystemSerializer.deserialize<UploadFileRequest>(row.json.decodeToString())
        assertEquals(channelDrive, request.driveId)
        assertEquals(FeedProtocol.PostFileType, request.metadata.appData.fileType)
        assertEquals(PostType.Tweet.toDataType(), request.metadata.appData.dataType)
        assertEquals(expectedId, request.metadata.appData.uniqueId)
    }

    @Test
    fun createPost_publicPostIsUnencrypted_connectedIsEncrypted() = runFeedTest {
        sender(backgroundScope).createPost(
            channelId = channelDrive,
            type = PostType.Tweet,
            caption = "Public hello",
            attachments = emptyList(),
            linkPreview = null,
            acl = aclFor(SecurityGroupType.Anonymous),
            slug = "public-post",
        )
        sender(backgroundScope).createPost(
            channelId = channelDrive,
            type = PostType.Tweet,
            caption = "Private hello",
            attachments = emptyList(),
            linkPreview = null,
            acl = aclFor(SecurityGroupType.Connected),
            slug = "private-post",
        )
        advanceUntilIdle()

        val publicReq = readRequest(channelDrive, Md5.toGuidId("public-post"))
        assertFalse(publicReq.metadata.isEncrypted, "an Anonymous (public) post must be unencrypted")

        val privateReq = readRequest(channelDrive, Md5.toGuidId("private-post"))
        assertTrue(privateReq.metadata.isEncrypted, "a Connected post must be encrypted")
    }

    @Test
    fun deletePost_enqueuesDeleteAndCommentCleanupByGroupId() = runFeedTest {
        val slug = "post-to-delete"
        sender(backgroundScope).createPost(
            channelId = channelDrive,
            type = PostType.Tweet,
            caption = "delete me",
            attachments = emptyList(),
            linkPreview = null,
            acl = aclFor(SecurityGroupType.Anonymous),
            slug = slug,
        )
        advanceUntilIdle()
        val postId = Md5.toGuidId(slug)

        sender(backgroundScope).deletePost(channelId = channelDrive, postUniqueId = postId)
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

    private suspend fun readRequest(
        driveId: kotlin.uuid.Uuid,
        uniqueId: kotlin.uuid.Uuid,
    ): UploadFileRequest {
        val row = env.outboxRow(driveId, uniqueId)
            ?: error("no outbox row for ($driveId, $uniqueId)")
        return OdinSystemSerializer.deserialize(row.json.decodeToString())
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
