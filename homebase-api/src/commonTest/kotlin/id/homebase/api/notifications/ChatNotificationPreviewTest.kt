package id.homebase.api.notifications

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    private suspend fun preview(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        conversation: Uuid = conversationId,
        message: Uuid = messageId,
        requests: MutableList<String> = mutableListOf(),
    ): ChatNotificationPreview {
        // Sealed with the shared secret and flagged X-SSE, as the identity answers a 200.
        val ok = status == HttpStatusCode.OK
        val sealed = if (ok) OdinSystemSerializer.serialize(CryptoHelper.encryptData(body, secret)) else body
        val headers = if (ok) headersOf("X-Odin-Version" to listOf("1.0"), "X-SSE" to listOf("1"))
        else headersOf("X-Odin-Version", "1.0")
        val http = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            respond(sealed, status, headers)
        })
        return fetchChatNotificationPreview(http, credentials, conversation.toString(), message.toString())
    }

    private fun assertMissed(expected: String, preview: ChatNotificationPreview) {
        assertNull(preview.text)
        assertTrue(preview.missReason.orEmpty().contains(expected), "missReason=${preview.missReason}")
    }

    @Test
    fun plainTextMessageIsDecryptedFromItsChatDriveHeader() = runTest {
        val requests = mutableListOf<String>()
        val result = preview(header("""{"message":"NSE 1091 check charlie"}"""), requests = requests)
        assertEquals(ChatNotificationPreview("NSE 1091 check charlie", null), result)
        assertEquals(1, requests.size)
        assertTrue(requests.single().endsWith("/files/by-uid/$messageId/header"), requests.single())
    }

    @Test
    fun legacyRichTextMessageBecomesItsPlainText() = runTest {
        val rich = """{"message":[{"type":"p","children":[{"text":"Not all"},{"text":"who wander"}]}]}"""
        assertEquals("Not all who wander", preview(header(rich)).text)
    }

    @Test
    fun typedKindsKeepTheSendersLabel() = runTest {
        assertMissed("dataType 214", preview(header("""{"question":"Mordor?"}""", dataType = 214)))
    }

    @Test
    fun statusMessagesKeepTheSendersBody() = runTest {
        assertMissed("dataType 202", preview(header("""{"message":"x"}""", dataType = 202)))
    }

    @Test
    fun mediaOnlyMessageHasNoText() = runTest {
        assertMissed("no message text", preview(header("""{"message":"","version":1}""")))
    }

    @Test
    fun aMessageFromAnotherConversationIsNotShown() = runTest {
        assertMissed("groupId", preview(header("""{"message":"hi"}""", groupId = Uuid.random())))
    }

    @Test
    fun aDeletedMessageIsNotShown() = runTest {
        assertMissed("deleted", preview(header("""{"message":"hi"}""", fileState = "deleted")))
    }

    @Test
    fun aKeyHeaderSealedWithAnotherSecretIsNotShown() = runTest {
        val result = preview(header("""{"message":"hi"}""", encryptWith = ByteArray(16) { 4 }))
        assertNull(result.text)
        // A wrong key still unpads cleanly about 1 time in 256, failing at the content instead.
        val reason = result.missReason.orEmpty()
        assertTrue("KeyHeaderDecryptionFailed" in reason || "content failed" in reason, reason)
    }

    @Test
    fun anEmptyKeyHeaderNamesTheFailingStep() = runTest {
        val empty = """{"encryptionVersion":1,"type":"aes","iv":"AAAAAAAAAAAAAAAAAAAAAA==","encryptedAesKey":"AAAAAAAAAAAAAAAAAAAAAA=="}"""
        assertMissed("header fetch threw", preview(header("""{"message":"hi"}""", keyHeaderJson = empty)))
    }

    @Test
    fun contentThatFailsToDecryptIsNotShownAsItsPlaceholder() = runTest {
        val undecryptable = Base64.encode(ByteArray(32) { 1 })
        assertMissed("content failed to decrypt", preview(header("", encryptedContent = undecryptable)))
    }

    @Test
    fun aMissingOrFailedFetchFallsBack() = runTest {
        assertMissed("404", preview("""{"title":"Not Found","status":404}""", HttpStatusCode.NotFound))
        assertMissed("header fetch threw", preview("boom", HttpStatusCode.InternalServerError))
    }

    @Test
    fun aMomentsPostPushIsNeverFetched() = runTest {
        val requests = mutableListOf<String>()
        assertMissed("moments", preview("{}", conversation = messageId, message = messageId, requests = requests))
        assertTrue(requests.isEmpty())
    }
}
