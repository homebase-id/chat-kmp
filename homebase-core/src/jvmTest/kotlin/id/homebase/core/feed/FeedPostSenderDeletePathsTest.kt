@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.FeedPostSenderService
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * The two [FeedPostSenderService.deletePost] branches the happy-path test doesn't reach: a post
 * that isn't present locally, and a post whose delete can't be queued.
 */
class FeedPostSenderDeletePathsTest {

    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private lateinit var env: FeedTestEnv
    private var blockingDriver: OutboxInsertBlockingDriver? = null

    private fun runFeedTest(
        blockOutboxInserts: Boolean = false,
        body: suspend TestScope.() -> Unit,
    ) = runTest {
        env = if (blockOutboxInserts) {
            FeedTestEnv(this) { raw -> OutboxInsertBlockingDriver(raw).also { blockingDriver = it } }
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

    private fun sender() = FeedPostSenderService(
        outboxSync = env.outboxSync,
        optimisticWriter = env.optimisticWriter,
    )

    private suspend fun seedPost(postId: Uuid) {
        env.optimisticWriter.writeNewFile(
            driveId = channelDrive,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            unecryptedMetadata = UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = false,
                appData = UploadAppFileMetaData(
                    uniqueId = postId,
                    fileType = FeedProtocol.PostFileType,
                    content = OdinSystemSerializer.serialize(
                        PostContent(
                            version = FeedProtocol.PostVersion,
                            id = postId.toString(),
                            channelId = channelDrive.toString(),
                            type = PostType.Tweet,
                            caption = "delete me",
                            slug = "post-to-delete",
                        )
                    ),
                ),
            ),
            originalRecipientCount = 0,
            fileSystemType = FileSystemType.Standard,
        )
    }

    @Test
    fun deletePost_postNotFoundLocally_enqueuesNothing() = runFeedTest {
        sender().deletePost(channelId = channelDrive, postUniqueId = Uuid.random())
        advanceUntilIdle()

        assertEquals(0L, env.outboxCount())
    }

    @Test
    fun deletePost_whenTheDeleteCannotBeQueued_rollsBackAndSkipsTheCommentCleanup() =
        runFeedTest(blockOutboxInserts = true) {
            val postId = Uuid.random()
            seedPost(postId)
            advanceUntilIdle()

            val batches = mutableListOf<BackendEvent.DataEvent.BatchReceived>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                env.eventBus.events.collect {
                    if (it is BackendEvent.DataEvent.BatchReceived) batches += it
                }
            }
            advanceUntilIdle()
            batches.clear()

            blockingDriver!!.failOutboxInserts = true
            sender().deletePost(channelId = channelDrive, postUniqueId = postId)
            advanceUntilIdle()

            assertEquals(
                0L, env.outboxCount(),
                "a post delete that can't be queued must not queue the comment cleanup either",
            )
            val restored = batches.last().batchData.single()
            assertEquals(postId, restored.fileMetadata.appData.uniqueId)
            assertEquals(
                FileState.Active, restored.fileState,
                "the optimistic delete must be rolled back for observers",
            )
        }
}
