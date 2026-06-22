@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostReactionService
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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

class PostReactionServiceTest {

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

    private fun service(): PostReactionService {
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        return PostReactionService(
            reactionProvider = DriveFileGroupReactionProvider(http, env.credentialsManager),
            optimisticWriter = env.optimisticWriter,
            outboxSync = env.outboxSync,
            credentialsManager = env.credentialsManager,
        )
    }

    /** Seed a post row locally so writeReactionToggle can read it, returning the matching item. */
    private suspend fun seedPost(): FeedPostItem {
        val postId = Uuid.random()
        val fileId = Uuid.random()
        val content = OdinSystemSerializer.serialize(
            PostContent(
                version = FeedProtocol.PostVersion,
                id = postId.toString(),
                channelId = channelDrive.toString(),
                type = PostType.Tweet,
                caption = "react to me",
                slug = "react-to-me",
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
            fileId = fileId,
        )
        return FeedPostItem(
            id = postId,
            fileId = fileId,
            globalTransitId = null,
            driveId = channelDrive,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            payloads = emptyList(),
            caption = "react to me",
            type = PostType.Tweet,
            channelId = channelDrive.toString(),
            slug = "react-to-me",
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
    }

    @Test
    fun toggleReaction_usesOptimisticWriteThenEnqueuesToggleReactionOutboxRequest() = runFeedTest {
        val post = seedPost()
        advanceUntilIdle()

        val result = service().toggleReaction(post, "❤️")
        advanceUntilIdle()

        // The optimistic writer reported a real toggle (added) — proves writeReactionToggle ran.
        assertEquals(ToggleReactionResultType.Added, result.resultType)

        // And a ToggleReaction outbox row was enqueued (NOT a direct provider toggle).
        val rows = drainAllRows()
        val toggleRow = rows.firstOrNull { it.uploadType == DriveOutboxUploader.ToggleReaction }
        assertNotNull(toggleRow, "toggleReaction must enqueue a ToggleReactionOutboxRequest")
        val json = toggleRow.json.decodeToString()
        assertTrue(json.contains("❤️"), "the queued reaction must carry the emoji; was: $json")
        assertTrue(
            json.contains(post.fileId.toString()),
            "the queued reaction must target the post's fileId",
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
