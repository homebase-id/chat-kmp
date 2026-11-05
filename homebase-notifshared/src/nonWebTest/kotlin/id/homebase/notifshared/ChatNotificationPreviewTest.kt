package id.homebase.notifshared

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The NSE's decrypt path, run against the real crypto with only the transport mocked. */
class ChatNotificationPreviewTest {

    private val secret = ByteArray(16) { 3 }
    private val conversationId = Uuid.random()
    private val messageId = Uuid.random()
    private val credentials =
        ApiCredentials.create(OdinId("frodo.dotyou.cloud"), "token", SecureByteArray(secret.copyOf()))

    private suspend fun header(
        appContent: String,
        dataType: Int = 0,
        groupId: Uuid = conversationId,
        fileState: String = "active",
        encryptWith: ByteArray = secret,
        encryptedContent: String? = null,
        keyHeaderJson: String? = null,
        uniqueId: Uuid = messageId,
        fileType: Int = 7878,
        tags: String = "null",
    ): String {
        val keyHeader = KeyHeader.newRandom16()
        val encryptedKeyHeader = keyHeaderJson ?: OdinSystemSerializer.serialize(
            EncryptedKeyHeader.encryptKeyHeaderAes(
                keyHeader, ByteArray(16) { 9 }, SecureByteArray(encryptWith.copyOf())
            )
        )
        val content = encryptedContent
            ?: Base64.encode(keyHeader.encryptDataAes(appContent.encodeToByteArray()))
        return """
            {
              "fileId": "${Uuid.random()}",
              "driveId": "${Uuid.random()}",
              "fileState": "$fileState",
              "fileSystemType": "standard",
              "sharedSecretEncryptedKeyHeader": $encryptedKeyHeader,
              "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": 1761440502738,
                "updated": 1761440502738,
                "transitCreated": 1761440501938,
                "isEncrypted": true,
                "senderOdinId": "samwise.gamgee.demo.rocks",
                "originalAuthor": "samwise.gamgee.demo.rocks",
                "versionTag": "${Uuid.random()}",
                "payloads": [],
                "appData": {
                  "uniqueId": "$uniqueId",
                  "tags": $tags,
                  "fileType": $fileType,
                  "dataType": $dataType,
                  "groupId": "$groupId",
                  "userDate": 1761440501900,
                  "archivalStatus": 0,
                  "content": ${OdinSystemSerializer.serialize(content)}
                }
              },
              "serverMetadata": { "allowDistribution": true, "fileByteCount": 1200, "originalRecipientCount": 1 },
              "priority": 1,
              "fileByteCount": 1200
            }
        """.trimIndent()
    }

    private fun found(vararg headers: String) = """{"searchResults":[${headers.joinToString(",")}]}"""

    /** [body] answers the query; a null body never answers, as an identity that stopped responding. */
    private suspend fun preview(
        body: String?,
        status: HttpStatusCode = HttpStatusCode.OK,
        conversation: Uuid = conversationId,
        message: Uuid = messageId,
        requests: MutableList<String> = mutableListOf(),
        deadline: Duration = PREVIEW_DEADLINE,
        queryTimeout: Duration = QUERY_TIMEOUT,
    ): ChatNotificationPreview {
        val http = HttpClient(MockEngine.create {
            addHandler { request ->
                requests += request.url.encodedPath
                identity(body ?: awaitCancellation(), status)
            }
        })
        return fetchChatNotificationPreview(http, credentials, conversation.toString(), message.toString(), deadline, queryTimeout)
    }

    // Sealed with the shared secret and flagged X-SSE, as the identity answers a 200.
    private suspend fun MockRequestHandleScope.identity(body: String, status: HttpStatusCode): HttpResponseData {
        val ok = status == HttpStatusCode.OK
        val sealed = if (ok) OdinSystemSerializer.serialize(CryptoHelper.encryptData(body, secret)) else body
        val headers = if (ok) headersOf("X-Odin-Version" to listOf("1.0"), "X-SSE" to listOf("1"))
        else headersOf("X-Odin-Version", "1.0")
        return respond(sealed, status, headers)
    }

    // On wasm WebCrypto resolves on the JS event loop, and a virtual clock would jump past it into every timeout.
    private fun realTimeTest(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default, block) }

    private fun assertMissed(expected: String, preview: ChatNotificationPreview) {
        assertNull(preview.text)
        assertTrue(preview.missReason.orEmpty().contains(expected), "missReason=${preview.missReason}")
    }

    @Test
    fun plainTextMessageIsDecryptedFromOneChatDriveQuery() = realTimeTest {
        val requests = mutableListOf<String>()
        val result = preview(found(header("""{"message":"NSE 1091 check charlie"}""")), requests = requests)
        assertEquals(ChatNotificationPreview("NSE 1091 check charlie", null), result)
        assertEquals(listOf("/api/v2/drives/${SystemDriveConstants.chatDrive.alias}/files/query-batch"), requests)
    }

    @Test
    fun noResultsKeepsThePlaceholder() = realTimeTest {
        assertMissed("query returned 0 results", preview(found()))
    }

    @Test
    fun anInvalidDriveKeepsThePlaceholder() = realTimeTest {
        assertMissed("invalidDrive", preview("""{"invalidDrive":true}"""))
    }

    @Test
    fun aQueryThatNeverAnswersTimesOutOnItsOwn() = realTimeTest {
        assertMissed("query timed out after 200ms", preview(null, queryTimeout = 200.milliseconds))
    }

    @Test
    fun theWholeReadGivesUpAtTheDeadline() = realTimeTest {
        assertMissed("timed out after 1s", preview(null, deadline = 1.seconds))
    }

    @Test
    fun aFailedQueryKeepsThePlaceholder() = realTimeTest {
        assertMissed("query threw", preview("boom", HttpStatusCode.InternalServerError))
    }

    @Test
    fun legacyRichTextMessageBecomesItsPlainText() = realTimeTest {
        val rich = """{"message":[{"type":"p","children":[{"text":"Not all"},{"text":"who wander"}]}]}"""
        assertEquals("Not all who wander", preview(found(header(rich))).text)
    }

    @Test
    fun typedKindsKeepTheSendersLabel() = realTimeTest {
        assertMissed("dataType 214", preview(found(header("""{"question":"Mordor?"}""", dataType = 214))))
    }

    @Test
    fun statusMessagesKeepTheSendersBody() = realTimeTest {
        assertMissed("dataType 202", preview(found(header("""{"message":"x"}""", dataType = 202))))
    }

    @Test
    fun mediaOnlyMessageHasNoText() = realTimeTest {
        assertMissed("no message text", preview(found(header("""{"message":"","version":1}"""))))
    }

    @Test
    fun aMessageFromAnotherConversationIsNotShown() = realTimeTest {
        assertMissed("groupId", preview(found(header("""{"message":"hi"}""", groupId = Uuid.random()))))
    }

    @Test
    fun aDeletedMessageIsNotShown() = realTimeTest {
        assertMissed("deleted", preview(found(header("""{"message":"hi"}""", fileState = "deleted"))))
    }

    @Test
    fun aKeyHeaderSealedWithAnotherSecretIsNotShown() = realTimeTest {
        val result = preview(found(header("""{"message":"hi"}""", encryptWith = ByteArray(16) { 4 })))
        assertNull(result.text)
        // A wrong key still unpads cleanly about 1 time in 256, failing at the content instead.
        val reason = result.missReason.orEmpty()
        assertTrue("KeyHeaderDecryptionFailed" in reason || "content failed" in reason, reason)
    }

    @Test
    fun anEmptyKeyHeaderNamesTheFailingStep() = realTimeTest {
        val empty = """{"encryptionVersion":1,"type":"aes","iv":"AAAAAAAAAAAAAAAAAAAAAA==","encryptedAesKey":"AAAAAAAAAAAAAAAAAAAAAA=="}"""
        assertMissed("query threw", preview(found(header("""{"message":"hi"}""", keyHeaderJson = empty))))
    }

    @Test
    fun contentThatFailsToDecryptIsNotShownAsItsPlaceholder() = realTimeTest {
        // Not block-aligned: whole blocks under a random key still pass the PKCS7 check 1 run in 256.
        val undecryptable = Base64.encode(ByteArray(31) { 1 })
        assertMissed("content failed to decrypt", preview(found(header("", encryptedContent = undecryptable))))
    }

    @Test
    fun aMomentsPostPushIsNeverFetched() = realTimeTest {
        val requests = mutableListOf<String>()
        assertMissed("moments", preview("{}", conversation = messageId, message = messageId, requests = requests))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aMomentsCommentPushIsNeverFetched() = realTimeTest {
        val requests = mutableListOf<String>()
        val moment = Uuid.random()
        val preview = fetchChatNotificationPreview(
            HttpClient(MockEngine.create { addHandler { request -> requests += request.url.encodedPath; respond("", HttpStatusCode.NotFound) } }),
            credentials, moment.toString(), MOMENT_COMMENT_TAG_SENTINEL.uppercase(),
        )
        assertMissed("moments", preview)
        assertTrue(requests.isEmpty(), "requests=$requests")
    }

    private suspend fun conversationHeader(content: String, tags: String = "null") =
        header(content, uniqueId = conversationId, fileType = 8888, tags = tags)

    @Test
    fun aGroupsNameRidesInTheSameQuery() = realTimeTest {
        val requests = mutableListOf<String>()
        val result = preview(
            found(
                header("""{"message":"hi all"}"""),
                conversationHeader(
                    """{"title":"Fellowship","recipients":["frodo.dotyou.cloud","samwise.gamgee.demo.rocks"]}""",
                    tags = """["b4e3c2d1-7f6a-4e8b-9c5d-1a2b3c4d5e6f"]""",
                ),
            ),
            requests = requests,
        )
        assertEquals("hi all", result.text)
        assertEquals(
            ChatNotificationGroup("Fellowship", listOf("frodo.dotyou.cloud", "samwise.gamgee.demo.rocks")),
            result.group,
        )
        assertEquals(1, requests.size)
    }

    @Test
    fun anUntaggedConversationOfMoreThanTwoIsALegacyGroup() = realTimeTest {
        val result = preview(
            found(
                conversationHeader("""{"title":"","recipients":["a.example","b.example","c.example"]}"""),
                header("""{"message":"hi"}"""),
            ),
        )
        assertEquals("hi", result.text)
        assertEquals(ChatNotificationGroup("", listOf("a.example", "b.example", "c.example")), result.group)
    }

    @Test
    fun aOneToOneConversationIsNoGroup() = realTimeTest {
        val result = preview(
            found(
                header("""{"message":"hi"}"""),
                conversationHeader("""{"title":"Sam","recipients":["frodo.dotyou.cloud","samwise.gamgee.demo.rocks"]}"""),
            ),
        )
        assertEquals("hi", result.text)
        assertNull(result.group)
    }
}
