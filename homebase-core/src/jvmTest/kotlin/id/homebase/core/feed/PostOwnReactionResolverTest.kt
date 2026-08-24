@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.GetGroupReactionsResponse
import id.homebase.api.client.drives.files.reactions.GroupReactionItem
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.client.websockets.InternalDriveFileId
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.PostOwnReactionResolver
import id.homebase.core.feed.services.PostReactionService
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.withOwnReactions
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// The header tally carries no per-identity information and localAppData.localReactions is null on
// every post header, so the roster read is the only source for "which of these is mine".
class PostOwnReactionResolverTest {

    private val self = OdinId("test.example.com")
    private val other = OdinId("frodo.example.com")
    private val feedDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private lateinit var env: FeedTestEnv
    private lateinit var roster: RosterServer

    private fun runResolverTest(body: suspend TestScope.() -> Unit) = runTest {
        env = FeedTestEnv(this)
        env.login(self.domainName)
        advanceUntilIdle()
        roster = RosterServer()
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

    private fun resolver(): PostOwnReactionResolver = PostOwnReactionResolver(
        PostReactionService(
            reactionProvider = DriveFileGroupReactionProvider(
                HttpClient(roster.engine),
                env.credentialsManager,
            ),
            optimisticWriter = env.optimisticWriter,
            outboxSync = env.outboxSync,
            credentialsManager = env.credentialsManager,
        )
    )


    @Test
    fun resolve_marksOurOwnGlyphActive_withoutTouchingTheHeaderTally() = runResolverTest {
        val post = post(preview = preview("❤️" to 2))
        roster.reactors(post.fileId, self to "❤️", other to "❤️")

        val resolver = resolver()
        resolver.resolve(listOf(post), limit = 10)

        val rendered = post.withOwnReactions(resolver.ownReactions.value[post.fileId])
        assertEquals(listOf("❤️"), rendered.ownReactions)
        assertEquals(2, rendered.reactionPreview?.reactions?.values?.single()?.count)
    }

    @Test
    fun resolve_ignoresOtherIdentitiesReactions() = runResolverTest {
        val post = post(preview = preview("❤️" to 1))
        roster.reactors(post.fileId, other to "❤️")

        val resolver = resolver()
        resolver.resolve(listOf(post), limit = 10)

        assertEquals(emptyList(), resolver.ownReactions.value[post.fileId])
        assertEquals(emptyList(), post.withOwnReactions(emptyList()).ownReactions)
    }

    @Test
    fun overlay_addsAGlyphTheHeaderHasNotCaughtUpOnYet() = runResolverTest {
        // Ours is enqueued but the author hasn't redistributed the preview yet.
        val post = post(preview = preview("💀" to 1))
        roster.reactors(post.fileId, other to "💀", self to "❤️")

        val resolver = resolver()
        resolver.resolve(listOf(post), limit = 10)

        val rendered = post.withOwnReactions(resolver.ownReactions.value[post.fileId])
        val byEmoji = rendered.reactionPreview!!.reactions.values
            .associate { OdinSystemSerializer.deserialize<ReactionContent>(it.reactionContent).emoji to it.count }
        assertEquals(mapOf("💀" to 1, "❤️" to 1), byEmoji)
        assertEquals(listOf("❤️"), rendered.ownReactions)
    }


    @Test
    fun resolve_skipsPostsWhoseHeaderShowsNoReactions() = runResolverTest {
        val posts = listOf(post(preview = null), post(preview = preview()), post(preview = null))

        resolver().resolve(posts, limit = 10)

        assertEquals(0, roster.requests, "a post with no reactions must not be read")
    }

    @Test
    fun resolve_doesNotRefetchOnRepeatedPasses() = runResolverTest {
        // The timeline re-emits on every sync batch and every scroll-triggered page.
        val posts = List(3) { post(preview = preview("❤️" to 1)) }
        posts.forEach { roster.reactors(it.fileId, self to "❤️") }

        val resolver = resolver()
        repeat(4) { resolver.resolve(posts, limit = 10) }

        assertEquals(3, roster.requests, "each post must be read once, not once per emission")
    }

    @Test
    fun resolve_readsAgainOnlyWhenTheHeaderPreviewChanged() = runResolverTest {
        val post = post(preview = preview("❤️" to 1))
        roster.reactors(post.fileId, self to "❤️")

        val resolver = resolver()
        resolver.resolve(listOf(post), limit = 10)
        resolver.resolve(listOf(post), limit = 10)
        assertEquals(1, roster.requests)

        val bumped = post.copy(reactionPreview = preview("❤️" to 2))
        resolver.resolve(listOf(bumped), limit = 10)
        assertEquals(2, roster.requests)
    }

    @Test
    fun resolve_staysInsideTheWindow_thenExtends() = runResolverTest {
        val posts = List(5) { post(preview = preview("❤️" to 1)) }
        posts.forEach { roster.reactors(it.fileId, self to "❤️") }

        val resolver = resolver()
        resolver.resolve(posts, limit = 2)
        assertEquals(2, roster.requests, "posts below the window must not be read")

        resolver.resolve(posts, limit = 5)
        assertEquals(5, roster.requests, "widening the window reads only the newly covered posts")
    }


    @Test
    fun resolve_failedReadLeavesTheHeaderSnapshotIntact() = runResolverTest {
        val post = post(preview = preview("❤️" to 3))
        roster.reactors(post.fileId, self to "❤️")
        roster.failing = true

        val resolver = resolver()
        resolver.resolve(listOf(post), limit = 10)

        val rendered = post.withOwnReactions(resolver.ownReactions.value[post.fileId])
        assertEquals(post, rendered)
        assertEquals(3, rendered.reactionPreview?.reactions?.values?.single()?.count)

        roster.failing = false
        resolver.resolve(listOf(post), limit = 10)
        assertEquals(listOf("❤️"), resolver.ownReactions.value[post.fileId])
    }


    @Test
    fun applyLocalToggle_flipsOurGlyphAndSuppressesTheRefetchThatWouldUndoIt() = runResolverTest {
        val post = post(preview = preview("😆" to 1))
        // The server still reports no reaction from us — the toggle is only in the outbox.
        roster.reactors(post.fileId, other to "😆")

        val resolver = resolver()
        resolver.applyLocalToggle(post, "❤️")
        assertEquals(listOf("❤️"), resolver.ownReactions.value[post.fileId])

        resolver.resolve(listOf(post), limit = 10)
        assertEquals(0, roster.requests, "a locally toggled post must not be re-read straight away")
        assertEquals(listOf("❤️"), resolver.ownReactions.value[post.fileId])

        // The rollback path relies on toggling the same glyph again removing it.
        resolver.applyLocalToggle(post, "❤️")
        assertEquals(emptyList(), resolver.ownReactions.value[post.fileId])
    }

    @Test
    fun overlay_leavesAnUnresolvedPostExactlyAsTheHeaderDescribesIt() = runResolverTest {
        val post = post(preview = preview("❤️" to 7))
        assertEquals(post, post.withOwnReactions(null))
        assertEquals(post, post.withOwnReactions(emptyList()))
        assertTrue(post.withOwnReactions(null).ownReactions.isEmpty())
    }


    private fun preview(vararg counts: Pair<String, Int>) = ReactionSummary(
        reactions = counts.associate { (emoji, count) ->
            val raw = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji))
            raw to ReactionEntry(key = raw, count = count, reactionContent = raw)
        },
    )

    private fun post(preview: ReactionSummary?) = FeedPostItem(
        id = Uuid.random(),
        fileId = Uuid.random(),
        globalTransitId = Uuid.random(),
        driveId = feedDrive,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        payloads = emptyList(),
        caption = "post",
        type = PostType.Tweet,
        channelId = feedDrive.toString(),
        slug = "post",
        reactAccess = ReactAccess.All,
        embeddedPost = null,
        userDateMs = 0,
        createdMs = 0,
        previewThumbnail = null,
        reactionPreview = preview,
        senderOdinId = other,
        originalAuthor = other,
        versionTag = null,
        ownReactions = emptyList(),
        commentCount = 0,
        isEncrypted = false,
        acl = null,
    )

    /** Stands in for `GET /drives/{driveId}/files/{fileId}/group-reactions`, counting the reads made. */
    private class RosterServer {
        private val byFile = mutableMapOf<Uuid, List<GroupReactionItem>>()

        var requests = 0
            private set

        var failing = false

        fun reactors(fileId: Uuid, vararg reactions: Pair<OdinId, String>) {
            byFile[fileId] = reactions.map { (odinId, emoji) ->
                GroupReactionItem(
                    reactionContent = OdinSystemSerializer.serialize(ReactionContent(emoji = emoji)),
                    odinId = odinId,
                    fileId = InternalDriveFileId(driveId = Uuid.random(), fileId = fileId),
                    created = 0,
                )
            }
        }

        val engine = MockEngine { request ->
            requests++
            if (failing) {
                respondError(HttpStatusCode.InternalServerError)
            } else {
                val fileId = request.url.encodedPath.split("/")
                    .let { segments -> segments.getOrNull(segments.lastIndex - 1) }
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                respond(
                    OdinSystemSerializer.serialize(
                        GetGroupReactionsResponse(reactions = byFile[fileId].orEmpty()),
                    ),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
    }
}
