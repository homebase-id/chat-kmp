@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostReactionService
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.emojiCounts
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * [PostReactionService]'s read paths ([PostReactionService.reactionSummary],
 * [PostReactionService.liveReactionSummary], [PostReactionService.listReactors]) plus the two
 * ways a toggle can decline to queue: an invalid emoji and a refused outbox insert.
 */
class PostReactionSummaryTest {

    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias
    private val heart = OdinSystemSerializer.serialize(ReactionContent(emoji = "❤️"))
    private val party = OdinSystemSerializer.serialize(ReactionContent(emoji = "🎉"))

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

    private fun keyHeader() = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16)))

    private fun service(engine: MockEngine = MockEngine { respond("", HttpStatusCode.OK) }) =
        PostReactionService(
            reactionProvider = DriveFileGroupReactionProvider(
                HttpClient(engine), env.credentialsManager,
            ),
            optimisticWriter = env.optimisticWriter,
            outboxSync = env.outboxSync,
            credentialsManager = env.credentialsManager,
        )

    private fun postItem(postId: Uuid, fileId: Uuid) = FeedPostItem(
        id = postId,
        fileId = fileId,
        globalTransitId = null,
        driveId = channelDrive,
        keyHeader = keyHeader(),
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
        isEncrypted = false,
        acl = null,
    )

    private suspend fun seedPost(): FeedPostItem {
        val postId = Uuid.random()
        val fileId = Uuid.random()
        env.optimisticWriter.writeNewFile(
            driveId = channelDrive,
            keyHeader = keyHeader(),
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
                            caption = "react to me",
                            slug = "react-to-me",
                        )
                    ),
                ),
            ),
            originalRecipientCount = 0,
            fileSystemType = FileSystemType.Standard,
            fileId = fileId,
        )
        return postItem(postId, fileId)
    }

    private suspend fun drainAllRows(): List<Outbox> {
        val seen = mutableListOf<Outbox>()
        while (true) {
            val row = env.databaseManager.outbox.checkout() ?: break
            seen.add(row)
        }
        return seen
    }

    private fun serviceRespondingWith(body: String) =
        service(MockEngine { respond(body, HttpStatusCode.OK) })

    private fun summaryEngine(vararg counts: Pair<String, Int>) = MockEngine {
        val body = counts.joinToString(",") { (raw, n) -> "${JsonPrimitive(raw)}: $n" }
        respond(
            """{ "reactions": { $body }, "total": ${counts.sumOf { it.second }} }""",
            HttpStatusCode.OK,
        )
    }

    @Test
    fun reactionSummary_decodesProviderKeysToGlyphsAndDropsUndecodableOnes() = runFeedTest {
        val post = seedPost()
        advanceUntilIdle()

        val summary = service(summaryEngine(heart to 3, "not-json" to 1)).reactionSummary(post)

        assertEquals(mapOf("❤️" to 3), summary.byEmoji)
        assertEquals(4, summary.total, "total comes from the provider, not the decoded subset")
    }

    @Test
    fun liveReactionSummary_keepsRawReactionJsonAsTheHeaderSummaryKey() = runFeedTest {
        val post = seedPost()
        advanceUntilIdle()

        val summary = service(summaryEngine(heart to 2)).liveReactionSummary(post)

        val entry = summary.reactions[heart]
        assertEquals(2, entry?.count)
        assertEquals(heart, entry?.reactionContent)
        assertEquals(emptyList(), summary.comments)
        assertEquals(0, summary.totalCommentCount)
    }

    @Test
    fun listReactors_decodesEachReactorAndFiltersByEmoji() = runFeedTest {
        val post = seedPost()
        advanceUntilIdle()

        val reactorsJson = """
                {
                  "reactions": [
                    {
                      "reactionContent": ${JsonPrimitive(heart)},
                      "odinId": "sam.homebase.id",
                      "fileId": { "driveId": "$channelDrive", "fileId": "${post.fileId}" },
                      "created": 1700
                    },
                    {
                      "reactionContent": ${JsonPrimitive(party)},
                      "odinId": "merry.homebase.id",
                      "fileId": { "driveId": "$channelDrive", "fileId": "${post.fileId}" },
                      "created": 1800
                    },
                    {
                      "reactionContent": "not-json",
                      "odinId": "pippin.homebase.id",
                      "fileId": { "driveId": "$channelDrive", "fileId": "${post.fileId}" },
                      "created": 1900
                    }
                  ]
                }
        """.trimIndent()

        val all = serviceRespondingWith(reactorsJson).listReactors(post)
        assertEquals(listOf("❤️", "🎉"), all.map { it.emoji })
        assertEquals(OdinId("sam.homebase.id"), all.first().odinId)
        assertEquals(1700, all.first().created.milliseconds)
        assertEquals(post.id, all.first().messageId)

        val hearts = serviceRespondingWith(reactorsJson).listReactors(post, emoji = "❤️")
        assertEquals(listOf("❤️"), hearts.map { it.emoji })
    }

    // -------------------- header tallies (reactors-sheet chips) --------------------

    // The reactors sheet labels its chips from the header, not from the roster: on a post hosted by
    // another identity `listReactors` only ever sees our own rows (the group-reactions endpoint is
    // addressed at our own domain), while the header preview stays correct.

    @Test
    fun emojiCounts_decodesGlyphsAndDropsUndecodableEntries() {
        val summary = ReactionSummary(
            reactions = mapOf(
                heart to ReactionEntry(key = heart, count = 3, reactionContent = heart),
                party to ReactionEntry(key = party, count = 1, reactionContent = party),
                "junk" to ReactionEntry(key = "junk", count = 9, reactionContent = "not-json"),
            ),
        )

        assertEquals(mapOf("❤️" to 3, "🎉" to 1), summary.emojiCounts())
    }

    @Test
    fun emojiCounts_dropsMachineReactionsAndSumsDuplicateGlyphs() {
        val vote = OdinSystemSerializer.serialize(ReactionContent(emoji = "_1Y"))
        // The same glyph can arrive under two keys (a re-serialised reactionContent); the chip must
        // show their sum, not whichever landed last.
        val heartAgain = """{"emoji":"❤️"}"""
        val summary = ReactionSummary(
            reactions = mapOf(
                heart to ReactionEntry(key = heart, count = 2, reactionContent = heart),
                "b" to ReactionEntry(key = "b", count = 5, reactionContent = heartAgain),
                vote to ReactionEntry(key = vote, count = 4, reactionContent = vote),
            ),
        )

        assertEquals(mapOf("❤️" to 7), summary.emojiCounts())
    }

    @Test
    fun emojiCounts_absentHeaderIsEmpty() {
        assertEquals(emptyMap(), (null as ReactionSummary?).emojiCounts())
        assertEquals(emptyMap(), ReactionSummary().emojiCounts())
    }

    @Test
    fun toggleReaction_blankOrOversizedEmoji_isRejectedWithoutTouchingTheOutbox() = runFeedTest {
        val post = seedPost()
        advanceUntilIdle()

        // Rejection has to reach the caller: it flips the like button before calling, and only
        // un-flips it on a failure. Returning quietly left the button lit with nothing queued.
        assertFailsWith<IllegalArgumentException> { service().toggleReaction(post, "") }
        assertFailsWith<IllegalArgumentException> { service().toggleReaction(post, "   ") }
        assertFailsWith<IllegalArgumentException> {
            service().toggleReaction(post, "way-too-long-for-a-glyph")
        }
        advanceUntilIdle()

        assertEquals(0L, env.outboxCount())
    }

    /**
     * A ZWJ sequence is one glyph in the picker but many UTF-16 units — 👨‍👩‍👧‍👦 and 👩‍❤️‍💋‍👨 are 11
     * each — so a `length` cap of 8 rejected 16 of the 1949 emoji in `emoji_data.json` outright.
     * Counted as code points they are 7 and 8.
     */
    @Test
    fun toggleReaction_zwjEmoji_isQueuedLikeAPlainGlyph() = runFeedTest {
        val post = seedPost()
        advanceUntilIdle()

        service().toggleReaction(post, "👨‍👩‍👧‍👦")
        service().toggleReaction(post, "❤️")
        advanceUntilIdle()

        val queued = drainAllRows()
            .filter { it.uploadType == DriveOutboxUploader.ToggleReaction }
            .map { it.json.decodeToString() }
        assertEquals(2, queued.size, "both glyphs must queue; was: $queued")
        assertTrue(
            queued.any { it.contains("👨‍👩‍👧‍👦") },
            "a ZWJ family emoji must reach the outbox; was: $queued",
        )
    }

    @Test
    fun toggleReaction_targetMissingLocally_reportsNoneWithoutEnqueuing() = runFeedTest {
        val phantom = postItem(Uuid.random(), Uuid.random())

        assertEquals(
            ToggleReactionResultType.None,
            service().toggleReaction(phantom, "❤️").resultType,
        )
        advanceUntilIdle()

        assertEquals(0L, env.outboxCount())
    }

    @Test
    fun toggleReaction_whenEnqueueFails_rollsBackTheOptimisticReaction() =
        runFeedTest(blockOutboxInserts = true) {
            val post = seedPost()
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
            assertFailsWith<IllegalStateException> { service().toggleReaction(post, "❤️") }
            advanceUntilIdle()

            assertEquals(0L, env.outboxCount(), "nothing may be queued when the insert fails")
            val restored = batches.last().batchData.single()
            assertEquals(post.id, restored.fileMetadata.appData.uniqueId)
            assertTrue(
                restored.fileMetadata.localAppData?.localReactions.isNullOrEmpty(),
                "the last state published must be the pre-toggle one (rollback)",
            )
            assertNull(restored.fileMetadata.reactionPreview)
        }
}
