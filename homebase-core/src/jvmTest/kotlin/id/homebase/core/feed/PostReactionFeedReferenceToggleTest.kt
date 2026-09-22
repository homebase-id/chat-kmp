@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.sync.database.Outbox
import id.homebase.core.config.feedLabeledDrive
import id.homebase.core.feed.services.FeedPostItem
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

// A followed identity's post lands on the feed drive with no uniqueId, so the optimistic write —
// which resolves the row by uniqueId — misses; the send itself only needs (driveId, fileId).
class PostReactionFeedReferenceToggleTest {

    private val feedDrive = feedLabeledDrive.drive.alias

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

    private fun service() = PostReactionService(
        reactionProvider = DriveFileGroupReactionProvider(
            HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            env.credentialsManager,
        ),
        optimisticWriter = env.optimisticWriter,
        outboxSync = env.outboxSync,
        credentialsManager = env.credentialsManager,
    )

    @Test
    fun toggleReaction_stillEnqueues_whenTheFeedReferenceHasNoLocalRowToWriteOptimistically() =
        runFeedTest {
            // No seeded row: nothing to resolve by uniqueId, exactly like a feed-drive reference.
            val post = feedReferencePost()

            service().toggleReaction(post, "❤️")
            advanceUntilIdle()

            val toggleRow = drainAllRows()
                .firstOrNull { it.uploadType == DriveOutboxUploader.ToggleReaction }
            assertNotNull(
                toggleRow,
                "a reaction on a followed post must still be enqueued, not silently dropped",
            )
            val json = toggleRow.json.decodeToString()
            assertTrue(json.contains("❤️"), "the queued reaction must carry the emoji; was: $json")
            assertTrue(
                json.contains(post.fileId.toString()),
                "the queued reaction must target the post's own fileId; was: $json",
            )
            assertTrue(
                json.contains(post.senderOdinId!!.domainName),
                "the author must be a recipient so the reaction reaches their drive; was: $json",
            )
        }

    // The server strips senderOdinId on a follower's copy, so the author can only come from
    // originalAuthor — otherwise the reaction queues with an empty recipient list.
    @Test
    fun toggleReaction_recipientsSurvive_whenTheServerStrippedTheSenderOnOurCopy() = runFeedTest {
        val post = feedReferencePost().copy(senderOdinId = null)

        service().toggleReaction(post, "❤️")
        advanceUntilIdle()

        val toggleRow = drainAllRows()
            .firstOrNull { it.uploadType == DriveOutboxUploader.ToggleReaction }
        assertNotNull(toggleRow, "a reaction on a followed post must still be enqueued")
        val json = toggleRow.json.decodeToString()
        assertTrue(
            json.contains(post.originalAuthor!!.domainName),
            "the author must be a recipient even with senderOdinId stripped; was: $json",
        )
    }

    @Test
    fun toggleReaction_stillDeclines_whenAPostThatHasAUniqueIdIsMissingLocally() = runFeedTest {
        // Own-drive shape: a uniqueId distinct from the globalTransitId. The optimistic write
        // failing here means the row really is gone, so the guard must stay.
        val orphan = feedReferencePost().copy(globalTransitId = Uuid.random())

        service().toggleReaction(orphan, "❤️")
        advanceUntilIdle()

        assertEquals(0L, env.outboxCount())
    }

    private fun feedReferencePost() = Uuid.random().let { globalTransitId -> FeedPostItem(
        // Feed references carry no uniqueId, so the id IS the globalTransitId (FeedModels).
        id = globalTransitId,
        fileId = Uuid.random(),
        globalTransitId = globalTransitId,
        driveId = feedDrive,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        payloads = emptyList(),
        caption = "a followed post",
        type = PostType.Tweet,
        channelId = feedDrive.toString(),
        slug = "a-followed-post",
        reactAccess = ReactAccess.All,
        embeddedPost = null,
        userDateMs = 0,
        createdMs = 0,
        previewThumbnail = null,
        reactionPreview = null,
        senderOdinId = OdinId("frodo.example.com"),
        originalAuthor = OdinId("frodo.example.com"),
        versionTag = null,
        ownReactions = emptyList(),
        commentCount = 0,
        isEncrypted = false,
        acl = null,
    ) }

    private suspend fun drainAllRows(): List<Outbox> {
        val seen = mutableListOf<Outbox>()
        while (true) {
            val row = env.databaseManager.outbox.checkout() ?: break
            seen.add(row)
        }
        return seen
    }
}
