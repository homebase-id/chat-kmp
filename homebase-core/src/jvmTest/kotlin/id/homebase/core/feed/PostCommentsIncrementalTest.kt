@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostCommentContent
import id.homebase.core.feed.services.PostCommentsService
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * The two ways comments reach a [PostCommentsService] thread after the first cold-load: the
 * incremental `BatchReceived` path (including the reply-before-parent buffer) and the over-peer
 * read for a followed author's post.
 */
class PostCommentsIncrementalTest {

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

    private fun keyHeader() = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16)))

    private fun TestScope.service(driveQueryProvider: DriveQueryProvider = env.driveQueryProvider) =
        PostCommentsService(
            databaseManager = env.databaseManager,
            credentialsManager = env.credentialsManager,
            eventBus = env.eventBus,
            outboxSync = env.outboxSync,
            uploadService = env.uploadService,
            optimisticWriter = env.optimisticWriter,
            fileOps = env.fileOps,
            driveQueryProvider = driveQueryProvider,
            // Unconfined so the EventBus collector subscribes eagerly and processes emits
            // synchronously — a StandardTestDispatcher would leave it unsubscribed until the
            // next dispatch and the test's batch would be dropped.
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

    private fun post(
        id: Uuid,
        senderOdinId: OdinId? = null,
        globalTransitId: Uuid? = null,
        channelId: String = channelDrive.toString(),
    ) = FeedPostItem(
        id = id,
        fileId = Uuid.random(),
        globalTransitId = globalTransitId,
        driveId = channelDrive,
        keyHeader = keyHeader(),
        payloads = emptyList(),
        caption = "",
        type = PostType.Tweet,
        channelId = channelId,
        slug = "",
        reactAccess = ReactAccess.All,
        embeddedPost = null,
        userDateMs = 0,
        createdMs = 0,
        previewThumbnail = null,
        reactionPreview = null,
        senderOdinId = senderOdinId,
        originalAuthor = senderOdinId,
        versionTag = null,
        ownReactions = emptyList(),
        commentCount = 0,
        isEncrypted = false,
        acl = null,
    )

    private fun commentFile(uniqueId: Uuid, groupId: Uuid, body: String, userDateMs: Long) =
        HomebaseFile(
            fileId = Uuid.random(),
            driveId = channelDrive,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = keyHeader(),
            fileMetadata = FileMetadata(
                created = UnixTimeUtc(userDateMs),
                updated = UnixTimeUtc(userDateMs),
                appData = AppFileMetaData(
                    uniqueId = uniqueId,
                    groupId = groupId,
                    fileType = FeedProtocol.CommentFileType,
                    userDate = userDateMs,
                    content = OdinSystemSerializer.serialize(
                        PostCommentContent(version = FeedProtocol.CommentVersion, body = body)
                    ),
                ),
            ),
            serverMetadata = ServerMetadata(),
        )

    private suspend fun seedLocalComment(commentId: Uuid, groupId: Uuid, body: String) {
        env.optimisticWriter.writeNewFile(
            driveId = channelDrive,
            keyHeader = keyHeader(),
            unecryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = false,
                appData = UploadAppFileMetaData(
                    uniqueId = commentId,
                    groupId = groupId,
                    fileType = FeedProtocol.CommentFileType,
                    userDate = 500,
                    content = OdinSystemSerializer.serialize(
                        PostCommentContent(version = FeedProtocol.CommentVersion, body = body)
                    ),
                ),
            ),
            originalRecipientCount = 0,
            fileSystemType = FileSystemType.Standard,
        )
    }

    @Test
    fun replyArrivingBeforeItsParent_isBufferedThenSurfacesWhenTheParentLands() = runFeedTest {
        val postId = Uuid.random()
        val parentCommentId = Uuid.random()
        val replyId = Uuid.random()

        val comments = service().commentsFor(post(postId))
        advanceUntilIdle()
        assertEquals(emptyList(), comments.value)

        env.eventBus.emit(
            BackendEvent.DataEvent.BatchReceived(
                driveId = channelDrive,
                batchData = listOf(commentFile(replyId, parentCommentId, "a reply", 2_000)),
            )
        )
        advanceUntilIdle()
        assertEquals(
            emptyList(), comments.value,
            "a reply whose parent hasn't been seen must be buffered, not shown under nothing",
        )

        env.eventBus.emit(
            BackendEvent.DataEvent.BatchReceived(
                driveId = channelDrive,
                batchData = listOf(commentFile(parentCommentId, postId, "the parent", 1_000)),
            )
        )
        advanceUntilIdle()

        assertEquals(listOf(parentCommentId, replyId), comments.value.map { it.id })
        assertEquals(parentCommentId, comments.value.last().replyToId)
    }

    @Test
    fun commentOnAnUnobservedPost_isNotRoutedIntoAnotherPostsThread() = runFeedTest {
        val postId = Uuid.random()
        val comments = service().commentsFor(post(postId))
        advanceUntilIdle()

        env.eventBus.emit(
            BackendEvent.DataEvent.BatchReceived(
                driveId = channelDrive,
                batchData = listOf(commentFile(Uuid.random(), Uuid.random(), "elsewhere", 1_000)),
            )
        )
        advanceUntilIdle()

        assertEquals(emptyList(), comments.value)
    }

    /**
     * The over-peer read is executed by the Ktor engine on its own dispatcher, which the virtual
     * scheduler can't drain — `advanceUntilIdle()` returns before the request is even issued. Wait
     * for the result on a real clock instead.
     */
    private suspend fun <T> awaitOffTestClock(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    /** A followed author's comment thread lives on their drive: `/peer/<author>/…/query-batch`. */
    private fun peerBatchJson(
        author: OdinId,
        peerDrive: Uuid,
        uniqueId: Uuid,
        groupId: Uuid,
        body: String,
    ): String {
        val content = JsonPrimitive(
            OdinSystemSerializer.serialize(
                PostCommentContent(version = FeedProtocol.CommentVersion, body = body)
            )
        ).toString()
        return """
            {
              "invalidDrive": false,
              "queryTime": 0,
              "includeMetadataHeader": true,
              "cursorState": null,
              "hasMoreRows": false,
              "searchResults": [
                {
                  "fileId": "${Uuid.random()}",
                  "driveId": "$peerDrive",
                  "fileState": "active",
                  "fileSystemType": "comment",
                  "sharedSecretEncryptedKeyHeader": {
                    "encryptionVersion": 1, "iv": "AAAA", "encryptedAesKey": "AAAA"
                  },
                  "fileMetadata": {
                    "created": 3000,
                    "updated": 3000,
                    "isEncrypted": false,
                    "senderOdinId": "${author.domainName}",
                    "appData": {
                      "uniqueId": "$uniqueId",
                      "groupId": "$groupId",
                      "fileType": ${FeedProtocol.CommentFileType},
                      "userDate": 3000,
                      "content": $content
                    }
                  },
                  "serverMetadata": {}
                }
              ]
            }
        """.trimIndent()
    }

    private fun emptyPeerBatchJson(): String = """
        {
          "invalidDrive": false,
          "queryTime": 0,
          "includeMetadataHeader": true,
          "cursorState": null,
          "hasMoreRows": false,
          "searchResults": []
        }
    """.trimIndent()

    @Test
    fun followedPost_loadsTheAuthorsCommentsOverPeer() = runFeedTest {
        val author = OdinId("frodo.homebase.id")
        val peerDrive = FeedProtocol.PublicChannelDriveAlias
        // A feed-drive reference has no uniqueId, so its id IS its globalTransitId.
        val gtid = Uuid.random()
        val peerCommentId = Uuid.random()

        var calls = 0
        val provider = DriveQueryProvider(
            httpClient = HttpClient(
                MockEngine {
                    calls++
                    val body = if (calls == 1) {
                        peerBatchJson(author, peerDrive, peerCommentId, gtid, "peer comment")
                    } else {
                        emptyPeerBatchJson()
                    }
                    respond(body, HttpStatusCode.OK)
                }
            ),
            credentialsManager = env.credentialsManager,
        )

        val comments = service(provider).commentsFor(
            post(
                id = gtid,
                senderOdinId = author,
                globalTransitId = gtid,
                channelId = peerDrive.toString(),
            )
        )
        advanceUntilIdle()

        val loaded = awaitOffTestClock { comments.first { it.isNotEmpty() } }
        assertEquals(listOf(peerCommentId), loaded.map { it.id })
        assertEquals("peer comment", loaded.single().body)
    }

    @Test
    fun followedPost_whenThePeerQueryFails_degradesToTheLocalComments() = runFeedTest {
        val author = OdinId("frodo.homebase.id")
        val gtid = Uuid.random()
        val localCommentId = Uuid.random()
        seedLocalComment(localCommentId, groupId = gtid, body = "my own comment")
        advanceUntilIdle()

        val peerQueried = CompletableDeferred<Unit>()
        val provider = DriveQueryProvider(
            httpClient = HttpClient(
                MockEngine {
                    peerQueried.complete(Unit)
                    respondError(HttpStatusCode.InternalServerError)
                }
            ),
            credentialsManager = env.credentialsManager,
        )

        val comments = service(provider).commentsFor(
            post(
                id = gtid,
                senderOdinId = author,
                globalTransitId = gtid,
                channelId = FeedProtocol.PublicChannelDriveAlias.toString(),
            )
        )
        advanceUntilIdle()
        awaitOffTestClock { peerQueried.await() }

        assertEquals(
            listOf(localCommentId), comments.value.map { it.id },
            "a failing peer read must not throw away the locally-known comments",
        )
    }
}
