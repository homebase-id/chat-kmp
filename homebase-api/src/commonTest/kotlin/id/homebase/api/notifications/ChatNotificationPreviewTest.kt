package id.homebase.api.notifications

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
    ): String {
        val keyHeader = KeyHeader.newRandom16()
        val encryptedKeyHeader = EncryptedKeyHeader.encryptKeyHeaderAes(
            keyHeader, ByteArray(16) { 9 }, SecureByteArray(encryptWith.copyOf())
        )
        val content = encryptedContent
            ?: Base64.encode(keyHeader.encryptDataAes(appContent.encodeToByteArray()))
        return """
            {
              "fileId": "${Uuid.random()}",
              "driveId": "${Uuid.random()}",
              "fileState": "$fileState",
              "fileSystemType": "standard",
              "sharedSecretEncryptedKeyHeader": ${OdinSystemSerializer.serialize(encryptedKeyHeader)},
              "fileMetadata": {
                "isEncrypted": true,
                "appData": {
                  "uniqueId": "$messageId",
                  "fileType": 7878,
                  "dataType": $dataType,
                  "groupId": "$groupId",
                  "content": ${OdinSystemSerializer.serialize(content)}
                }
              },
              "serverMetadata": {}
            }
        """.trimIndent()
    }

    private suspend fun preview(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        conversation: Uuid = conversationId,
        message: Uuid = messageId,
        requests: MutableList<String> = mutableListOf(),
    ): String? {
        val http = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            respond(body, status, headersOf("X-Odin-Version", "1.0"))
        })
        return fetchChatNotificationPreview(http, credentials, conversation.toString(), message.toString())
    }

    @Test
    fun plainTextMessageIsDecryptedFromItsChatDriveHeader() = runTest {
        val requests = mutableListOf<String>()
        val text = preview(header("""{"message":"Second breakfast?","version":1}"""), requests = requests)
        assertEquals("Second breakfast?", text)
        assertEquals(1, requests.size)
        assertTrue(requests.single().endsWith("/files/by-uid/$messageId/header"), requests.single())
    }

    @Test
    fun legacyRichTextMessageBecomesItsPlainText() = runTest {
        val rich = """{"message":[{"type":"p","children":[{"text":"Not all"},{"text":"who wander"}]}]}"""
        assertEquals("Not all who wander", preview(header(rich)))
    }

    @Test
    fun typedKindsKeepTheSendersLabel() = runTest {
        assertNull(preview(header("""{"question":"Mordor?"}""", dataType = 214)))
    }

    @Test
    fun statusMessagesKeepTheSendersBody() = runTest {
        assertNull(preview(header("""{"message":"x"}""", dataType = 202)))
    }

    @Test
    fun mediaOnlyMessageHasNoText() = runTest {
        assertNull(preview(header("""{"message":"","version":1}""")))
    }

    @Test
    fun aMessageFromAnotherConversationIsNotShown() = runTest {
        assertNull(preview(header("""{"message":"hi"}""", groupId = Uuid.random())))
    }

    @Test
    fun aDeletedMessageIsNotShown() = runTest {
        assertNull(preview(header("""{"message":"hi"}""", fileState = "deleted")))
    }

    @Test
    fun aKeyHeaderSealedWithAnotherSecretIsNotShown() = runTest {
        assertNull(preview(header("""{"message":"hi"}""", encryptWith = ByteArray(16) { 4 })))
    }

    @Test
    fun contentThatFailsToDecryptIsNotShownAsItsPlaceholder() = runTest {
        val undecryptable = Base64.encode(ByteArray(32) { 1 })
        assertNull(preview(header("", encryptedContent = undecryptable)))
    }

    @Test
    fun aMissingOrFailedFetchFallsBack() = runTest {
        assertNull(preview("""{"title":"Not Found","status":404}""", HttpStatusCode.NotFound))
        assertNull(preview("boom", HttpStatusCode.InternalServerError))
    }

    @Test
    fun aMomentsPostPushIsNeverFetched() = runTest {
        val requests = mutableListOf<String>()
        assertNull(preview("{}", conversation = messageId, message = messageId, requests = requests))
        assertTrue(requests.isEmpty())
    }
}
