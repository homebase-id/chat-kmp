package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.DenyReason
import id.homebase.core.feed.services.FeedPermissionService
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.toFeedPostItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class FeedPermissionServiceTest {

    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias
    private val me = OdinId("me.example.com")
    private val peer = OdinId("peer.example.com")

    // No X-SSE header, so SecurityContextProvider treats the body as plaintext.
    private fun contextJson(permission: String) = """
        {
          "caller": { "odinId": "$me", "securityLevel": "owner" },
          "permissionContext": {
            "permissionGroups": [
              {
                "driveGrants": [
                  {
                    "permissionedDrive": {
                      "drive": {
                        "alias": "$channelDrive",
                        "type": "${FeedProtocol.ChannelDriveType}"
                      },
                      "permission": "$permission"
                    }
                  }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private fun post(
        author: OdinId? = null,
        reactAccess: ReactAccess = ReactAccess.All,
    ): FeedPostItem {
        val content = OdinSystemSerializer.serialize(
            PostContent(
                version = FeedProtocol.PostVersion,
                id = Uuid.random().toString(),
                channelId = channelDrive.toString(),
                type = PostType.Tweet,
                caption = "hello",
                reactAccess = reactAccess,
            )
        )
        return HomebaseFile(
            fileId = Uuid.random(),
            driveId = channelDrive,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            fileMetadata = FileMetadata(
                created = UnixTimeUtc(0),
                updated = UnixTimeUtc(0),
                senderOdinId = author,
                originalAuthor = author,
                appData = AppFileMetaData(
                    uniqueId = Uuid.random(),
                    fileType = FeedProtocol.PostFileType,
                    content = content,
                ),
            ),
            serverMetadata = ServerMetadata(),
        ).toFeedPostItem()!!
    }

    private class Fixture(private val engine: MockEngine, credentials: CredentialsManager) {
        val service = FeedPermissionService(
            securityContextProvider = SecurityContextProvider(HttpClient(engine), credentials),
            credentialsManager = credentials,
        )
        val requestCount: Int get() = engine.requestHistory.size
    }

    private suspend fun fixture(engine: MockEngine, signedIn: Boolean = true): Fixture {
        val credentials = CredentialsManager()
        if (signedIn) {
            credentials.setActiveCredentials(
                ApiCredentials.create(
                    domain = me,
                    clientAccessToken = "test-token",
                    sharedSecret = SecureByteArray(ByteArray(16) { 1 }),
                )
            )
        }
        return Fixture(engine, credentials)
    }

    private fun okEngine(permission: String = "readwrite") = MockEngine {
        respond(
            content = contextJson(permission),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun ownPost_readsLocalContext_andIsAllowed() = runTest {
        val f = fixture(okEngine())
        assertEquals(CanReact.All, f.service.canReact(post(author = null)))
        assertEquals(1, f.requestCount, "the local security context must be fetched once")
    }

    @Test
    fun secondCallForSameDrive_doesNotRefetch() = runTest {
        val f = fixture(okEngine())
        f.service.canReact(post(author = null))
        f.service.canReact(post(author = null))
        f.service.canReact(post(author = null))
        assertEquals(1, f.requestCount, "the per-identity context must be cached across posts")
    }

    @Test
    fun reset_dropsCachedContext() = runTest {
        val f = fixture(okEngine())
        f.service.canReact(post(author = null))
        f.service.reset()
        f.service.canReact(post(author = null))
        assertEquals(2, f.requestCount)
    }

    @Test
    fun failedContextFetch_doesNotThrow() = runTest {
        val f = fixture(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        // Own post: the author short-circuit still applies with no context at all.
        assertEquals(CanReact.All, f.service.canReact(post(author = null)))
    }

    @Test
    fun failedContextFetch_isNotCached() = runTest {
        val f = fixture(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        f.service.canReact(post(author = null))
        f.service.canReact(post(author = null))
        assertEquals(2, f.requestCount, "a transient failure must not pin a verdict for the session")
    }

    @Test
    fun signedOut_isNotAuthenticated() = runTest {
        val f = fixture(okEngine(), signedIn = false)
        assertEquals(
            CanReact.Denied(DenyReason.NotAuthenticated),
            f.service.canReact(post(author = null)),
        )
        assertEquals(0, f.requestCount)
    }

    @Test
    fun peerAuthoredPost_degradesPermissive_withoutARequest() = runTest {
        val f = fixture(okEngine())
        assertEquals(CanReact.All, f.service.canReact(post(author = peer)))
        assertEquals(0, f.requestCount, "no v2 over-peer security-context route exists to call")
    }

    // The degrade is permissive about grants only — the post's own reactAccess still applies.
    @Test
    fun peerAuthoredPostWithReactAccessOff_isDisabledOnPost() = runTest {
        val f = fixture(okEngine())
        assertEquals(
            CanReact.Denied(DenyReason.DisabledOnPost),
            f.service.canReact(post(author = peer, reactAccess = ReactAccess.None)),
        )
    }
}
