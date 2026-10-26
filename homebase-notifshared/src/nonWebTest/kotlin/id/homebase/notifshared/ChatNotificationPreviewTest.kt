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
                  "uniqueId": "$messageId",
                  "tags": null,
                  "fileType": 7878,
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

    private val notFound = """{"title":"Not Found","status":404}"""
    private val inboxStatus = """{"totalItems":0,"poppedCount":1,"oldestItemTimestamp":0}"""

    /**
     * [body] answers the header read; once the inbox has been processed, [afterInbox] does (when
     * set). A null body never answers, as an identity that stopped responding.
     */
    private suspend fun preview(
        body: String?,
        status: HttpStatusCode = HttpStatusCode.OK,
        conversation: Uuid = conversationId,
        message: Uuid = messageId,
        requests: MutableList<String> = mutableListOf(),
        afterInbox: String? = null,
        inbox: String? = inboxStatus,
        inboxCode: HttpStatusCode = HttpStatusCode.OK,
        deadline: Duration = PREVIEW_DEADLINE,
        inboxTimeout: Duration = INBOX_TIMEOUT,
    ): ChatNotificationPreview {
        var inboxProcessed = false
        val http = HttpClient(MockEngine.create {
            addHandler { request ->
                val path = request.url.encodedPath
                requests += path
                if (path.endsWith("/inbox/process")) {
                    inboxProcessed = true
                    identity(inbox ?: awaitCancellation(), inboxCode)
                } else if (inboxProcessed && afterInbox != null) {
                    identity(afterInbox, HttpStatusCode.OK)
                } else {
                    identity(body ?: awaitCancellation(), status)
                }
            }
        })
        return fetchChatNotificationPreview(http, credentials, conversation.toString(), message.toString(), deadline, inboxTimeout)
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
    fun plainTextMessageIsDecryptedFromItsChatDriveHeader() = realTimeTest {
        val requests = mutableListOf<String>()
        val result = preview(header("""{"message":"NSE 1091 check charlie"}"""), requests = requests)
        assertEquals(ChatNotificationPreview("NSE 1091 check charlie", null), result)
        assertEquals(1, requests.size, "a header that is there needs no inbox processing: $requests")
        assertTrue(requests.single().endsWith("/files/by-uid/$messageId/header"), requests.single())
    }

    @Test
    fun aMessageStillInTheInboxIsReadAfterProcessingIt() = realTimeTest {
        val requests = mutableListOf<String>()
        val result = preview(
            notFound, HttpStatusCode.NotFound, requests = requests,
            afterInbox = header("""{"message":"Second breakfast?"}"""),
        )
        assertEquals(ChatNotificationPreview("Second breakfast?", null), result)
        assertEquals(3, requests.size, requests.toString())
        assertTrue(requests[0].endsWith("/header"), requests[0])
        assertTrue(requests[1].endsWith("/drives/${SystemDriveConstants.chatDrive.alias}/inbox/process"), requests[1])
        assertTrue(requests[2].endsWith("/header"), requests[2])
    }

    @Test
    fun aFailedInboxProcessKeepsThePlaceholder() = realTimeTest {
        val requests = mutableListOf<String>()
        val result = preview(
            notFound, HttpStatusCode.NotFound, requests = requests,
            inbox = "boom", inboxCode = HttpStatusCode.InternalServerError,
        )
        assertMissed("inbox process threw", result)
        assertEquals(2, requests.size, "no retry after the inbox failed: $requests")
    }

    @Test
    fun aMessageStillMissingAfterTheInboxKeepsThePlaceholder() = realTimeTest {
        assertMissed("after processing the inbox", preview(notFound, HttpStatusCode.NotFound))
    }

    @Test
    fun anInboxThatNeverAnswersTimesOutOnItsOwn() = realTimeTest {
        val requests = mutableListOf<String>()
        assertMissed("inbox process timed out", preview(notFound, HttpStatusCode.NotFound, requests = requests, inbox = null, inboxTimeout = 200.milliseconds))
        assertEquals(2, requests.size, requests.toString())
    }

    @Test
    fun theWholeReadGivesUpAtTheDeadline() = realTimeTest {
        assertMissed("timed out after 1s", preview(null, deadline = 1.seconds))
    }

    @Test
    fun legacyRichTextMessageBecomesItsPlainText() = realTimeTest {
        val rich = """{"message":[{"type":"p","children":[{"text":"Not all"},{"text":"who wander"}]}]}"""
        assertEquals("Not all who wander", preview(header(rich)).text)
    }

    @Test
    fun typedKindsKeepTheSendersLabel() = realTimeTest {
        assertMissed("dataType 214", preview(header("""{"question":"Mordor?"}""", dataType = 214)))
    }

    @Test
    fun statusMessagesKeepTheSendersBody() = realTimeTest {
        assertMissed("dataType 202", preview(header("""{"message":"x"}""", dataType = 202)))
    }

    @Test
    fun mediaOnlyMessageHasNoText() = realTimeTest {
        assertMissed("no message text", preview(header("""{"message":"","version":1}""")))
    }

    @Test
    fun aMessageFromAnotherConversationIsNotShown() = realTimeTest {
        assertMissed("groupId", preview(header("""{"message":"hi"}""", groupId = Uuid.random())))
    }

    @Test
    fun aDeletedMessageIsNotShown() = realTimeTest {
        assertMissed("deleted", preview(header("""{"message":"hi"}""", fileState = "deleted")))
    }

    @Test
    fun aKeyHeaderSealedWithAnotherSecretIsNotShown() = realTimeTest {
        val result = preview(header("""{"message":"hi"}""", encryptWith = ByteArray(16) { 4 }))
        assertNull(result.text)
        // A wrong key still unpads cleanly about 1 time in 256, failing at the content instead.
        val reason = result.missReason.orEmpty()
        assertTrue("KeyHeaderDecryptionFailed" in reason || "content failed" in reason, reason)
    }

    @Test
    fun anEmptyKeyHeaderNamesTheFailingStep() = realTimeTest {
        val empty = """{"encryptionVersion":1,"type":"aes","iv":"AAAAAAAAAAAAAAAAAAAAAA==","encryptedAesKey":"AAAAAAAAAAAAAAAAAAAAAA=="}"""
        assertMissed("header fetch threw", preview(header("""{"message":"hi"}""", keyHeaderJson = empty)))
    }

    @Test
    fun contentThatFailsToDecryptIsNotShownAsItsPlaceholder() = realTimeTest {
        // Not block-aligned: whole blocks under a random key still pass the PKCS7 check 1 run in 256.
        val undecryptable = Base64.encode(ByteArray(31) { 1 })
        assertMissed("content failed to decrypt", preview(header("", encryptedContent = undecryptable)))
    }

    @Test
    fun aMissingOrFailedFetchFallsBack() = realTimeTest {
        assertMissed("404", preview(notFound, HttpStatusCode.NotFound))
        assertMissed("header fetch threw", preview("boom", HttpStatusCode.InternalServerError))
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
}
