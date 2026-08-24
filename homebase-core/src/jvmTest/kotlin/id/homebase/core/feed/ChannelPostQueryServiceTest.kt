@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.ChannelPostQueryService
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.toDataType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * [ChannelPostQueryService.getPosts] — the local (SQL index) branch and the over-peer branch,
 * including how each reports the next-page cursor through [CursoredResult].
 */
class ChannelPostQueryServiceTest {

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

    private fun service(driveQueryProvider: DriveQueryProvider = env.driveQueryProvider) =
        ChannelPostQueryService(
            databaseManager = env.databaseManager,
            credentialsManager = env.credentialsManager,
            driveQueryProvider = driveQueryProvider,
        )

    private fun postContent(postId: Uuid, caption: String, type: PostType) =
        OdinSystemSerializer.serialize(
            PostContent(
                version = FeedProtocol.PostVersion,
                id = postId.toString(),
                channelId = channelDrive.toString(),
                type = type,
                caption = caption,
                slug = caption,
            )
        )

    private suspend fun seedPost(caption: String, userDateMs: Long, type: PostType = PostType.Tweet) {
        val postId = Uuid.random()
        env.optimisticWriter.writeNewFile(
            driveId = channelDrive,
            keyHeader = keyHeader(),
            unecryptedMetadata = UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = false,
                appData = UploadAppFileMetaData(
                    uniqueId = postId,
                    fileType = FeedProtocol.PostFileType,
                    dataType = type.toDataType(),
                    userDate = userDateMs,
                    content = postContent(postId, caption, type),
                ),
            ),
            originalRecipientCount = 0,
            fileSystemType = FileSystemType.Standard,
        )
    }

    @Test
    fun getPosts_local_returnsNewestFirstAndCarriesTheCursorToTheNextPage() = runFeedTest {
        seedPost("oldest", userDateMs = 1_000)
        seedPost("middle", userDateMs = 2_000)
        seedPost("newest", userDateMs = 3_000)
        advanceUntilIdle()

        val firstPage = service().getPosts(channelId = channelDrive, pageSize = 2)
        assertEquals(listOf("newest", "middle"), firstPage.results.map { it.caption })
        assertTrue(firstPage.cursorState.isNotEmpty(), "a partial page must expose a cursor")

        val secondPage =
            service().getPosts(channelId = channelDrive, pageSize = 2, cursor = firstPage.cursorState)
        assertEquals(listOf("oldest"), secondPage.results.map { it.caption })
        assertEquals("", secondPage.cursorState, "the last page must report an empty cursor")
    }

    @Test
    fun getPosts_local_filtersByPostType() = runFeedTest {
        seedPost("a tweet", userDateMs = 1_000, type = PostType.Tweet)
        seedPost("a photo", userDateMs = 2_000, type = PostType.Media)
        advanceUntilIdle()

        val media = service().getPosts(channelId = channelDrive, type = PostType.Media)

        assertEquals(listOf("a photo"), media.results.map { it.caption })
    }

    @Test
    fun getPosts_local_withoutCredentials_returnsAnEmptyPage() = runTest {
        env = FeedTestEnv(this)
        try {
            val page = service().getPosts(channelId = channelDrive)

            assertEquals(emptyList(), page.results)
            assertEquals("", page.cursorState)
        } finally {
            env.close()
        }
    }

    private fun remoteBatchJson(
        driveId: Uuid,
        caption: String,
        cursorState: String?,
        hasMoreRows: Boolean,
    ): String {
        val postId = Uuid.random()
        val content = JsonPrimitive(postContent(postId, caption, PostType.Tweet)).toString()
        return """
            {
              "invalidDrive": false,
              "queryTime": 0,
              "includeMetadataHeader": true,
              "cursorState": ${cursorState?.let { "\"$it\"" } ?: "null"},
              "hasMoreRows": $hasMoreRows,
              "searchResults": [
                {
                  "fileId": "${Uuid.random()}",
                  "driveId": "$driveId",
                  "fileState": "active",
                  "fileSystemType": "standard",
                  "sharedSecretEncryptedKeyHeader": {
                    "encryptionVersion": 1, "iv": "AAAA", "encryptedAesKey": "AAAA"
                  },
                  "fileMetadata": {
                    "created": 5000,
                    "updated": 5000,
                    "isEncrypted": false,
                    "globalTransitId": "${Uuid.random()}",
                    "appData": {
                      "uniqueId": "$postId",
                      "fileType": ${FeedProtocol.PostFileType},
                      "userDate": 5000,
                      "content": $content
                    }
                  },
                  "serverMetadata": {}
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun getPosts_overPeer_readsTheOwnersDriveAndPropagatesTheServerCursor() = runFeedTest {
        val owner = OdinId("frodo.homebase.id")
        lateinit var captured: HttpRequestData
        val provider = DriveQueryProvider(
            httpClient = HttpClient(
                MockEngine { request ->
                    captured = request
                    respond(
                        remoteBatchJson(channelDrive, "a remote post", "next-page", true),
                        HttpStatusCode.OK,
                    )
                }
            ),
            credentialsManager = env.credentialsManager,
        )

        val page = service(provider).getPosts(channelId = channelDrive, ownerOdinId = owner)

        assertEquals(listOf("a remote post"), page.results.map { it.caption })
        assertEquals("next-page", page.cursorState)
        assertTrue(
            captured.url.encodedPath.contains("/peer/${owner.domainName}/drives/$channelDrive"),
            "a remote channel must be read through the peer broker route; was ${captured.url.encodedPath}",
        )
    }

    @Test
    fun getPosts_overPeer_lastPageReportsAnEmptyCursor() = runFeedTest {
        val provider = DriveQueryProvider(
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        remoteBatchJson(channelDrive, "the last remote post", "trailing", false),
                        HttpStatusCode.OK,
                    )
                }
            ),
            credentialsManager = env.credentialsManager,
        )

        val page = service(provider)
            .getPosts(channelId = channelDrive, ownerOdinId = OdinId("frodo.homebase.id"))

        assertEquals(1, page.results.size)
        assertEquals(
            "", page.cursorState,
            "with hasMoreRows false the server cursor must not be handed back as a next page",
        )
    }
}
