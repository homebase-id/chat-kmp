package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FilesyncDiscussionsTest {

    private val sharedSecret = ByteArray(16)
    private val fileKey = KeyHeader.newRandom16()
    private val globalTransitId = Uuid.random()
    private val handle = FilesyncHandle(Uuid.random(), null, FilesyncProtocol.FileFileType, fileKey, JsonObject(emptyMap()), null, globalTransitId = globalTransitId)

    private val requests = mutableListOf<Pair<HttpRequestData, ByteArray>>()
    private var reactionsJson = """{"reactions":[]}"""
    private var commentsJson = "[]"

    private val engine = MockEngine { request ->
        requests += request to request.body.toByteArray()
        val body = when {
            request.url.encodedPath.endsWith("/group-reactions") -> reactionsJson
            request.url.encodedPath.endsWith("/query-batch") -> """{"queryTime":0,"includeMetadataHeader":true,"searchResults":$commentsJson,"hasMoreRows":false}"""
            request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/files") ->
                """{"fileId":"${Uuid.random()}","driveId":"${FilesyncProtocol.DriveAlias}","newVersionTag":"${Uuid.random()}"}"""
            else -> error("unexpected ${request.method} ${request.url}")
        }
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val credentialsManager = CredentialsManager()
    private val discussions = HttpClient(engine).let { http ->
        FilesyncDiscussions(
            DriveFileGroupReactionProvider(http, credentialsManager),
            DriveQueryProvider(http, credentialsManager),
            DriveUploadProvider(http, credentialsManager, NoFiles),
            credentialsManager,
        )
    }

    @BeforeTest
    fun signIn() = runBlocking {
        credentialsManager.setActiveCredentials(ApiCredentials.create(OdinId("frodo.test"), "token", SecureByteArray(sharedSecret)))
    }

    @Test
    fun theFilesReactionsAreCountedAndTheUsersOwnAreKnown() = runTest {
        reactionsJson = """{"reactions":[
            ${reaction("frodo.test", "👍", 1)}, ${reaction("sam.test", "👍", 2)}, ${reaction("frodo.test", "❤️", 3)},
            {"reactionContent":"not json","odinId":"sam.test","fileId":{"driveId":"${Uuid.random()}","fileId":"${Uuid.random()}"},"created":4}
        ]}"""

        val loaded = discussions.load(Uuid.random(), handle)

        assertEquals(listOf(FilesyncReactionCount("👍", 2), FilesyncReactionCount("❤️", 1)), loaded.reactions)
        assertEquals(listOf("👍", "❤️"), loaded.mine)
    }

    @Test
    fun commentsAreQueriedOnTheCommentFileSystemByTheFilesGroupAndOpenWithItsKey() = runTest {
        commentsJson = "[${comment("sam.test", "Lovely", 2000)}, ${comment("frodo.test", "Thanks", 1000)}]"

        val comments = discussions.load(Uuid.random(), handle).comments

        assertEquals(listOf("Thanks" to true, "Lovely" to false), comments.map { it.body to it.mine })
        val (query, body) = requests.single { it.first.url.encodedPath.endsWith("/query-batch") }
        assertEquals("Comment", query.headers["X-ODIN-FILE-SYSTEM-TYPE"])
        val params = Json.parseToJsonElement(CryptoHelper.decryptContentAsString(body.decodeToString(), sharedSecret)).jsonObject["queryParams"]!!.jsonObject
        assertEquals("[\"$globalTransitId\"]", params["groupId"].toString())
    }

    @Test
    fun aCommentIsUploadedToTheCommentFileSystemWithTheFilesKeyUnderItsOwnIv() = runTest {
        discussions.comment(handle, "Looks good")

        val (upload, body) = requests.single { it.first.url.encodedPath.endsWith("/files") }
        assertEquals("Comment", upload.url.parameters["xfst"])
        val parts = multipart(body, upload)
        val transferIv = Base64.decode(Json.parseToJsonElement(parts.getValue("instructions").decodeToString()).jsonObject["transferIv"]!!.jsonPrimitive.content)
        val descriptor = Json.parseToJsonElement(AesCbc.decrypt(parts.getValue("metadata"), sharedSecret, transferIv).decodeToString()).jsonObject

        val keyHeader = descriptor["encryptedKeyHeader"]!!.jsonObject
        assertEquals("fileKey", keyHeader["type"]!!.jsonPrimitive.content)
        assertEquals("", keyHeader["encryptedAesKey"]!!.jsonPrimitive.content)
        val iv = Base64.decode(keyHeader["iv"]!!.jsonPrimitive.content)
        assertFalse(iv.contentEquals(fileKey.iv), "a comment's IV is its own")

        val metadata = descriptor["fileMetadata"]!!.jsonObject
        assertEquals(globalTransitId.toString(), metadata["referencedFile"]!!.jsonObject["globalTransitId"]!!.jsonPrimitive.content)
        val appData = metadata["appData"]!!.jsonObject
        assertEquals(801, appData["fileType"]!!.jsonPrimitive.int)
        assertTrue("groupId" !in appData || appData["groupId"].toString() == "null", "the server sets a comment's group")
        val content = KeyHeader(iv, fileKey.aesKey).decrypt(Base64.decode(appData["content"]!!.jsonPrimitive.content)).decodeToString()
        assertEquals("Looks good", Json.parseToJsonElement(content).jsonObject["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun toggling_adds_or_takes_away_one_of_the_users_reactions() {
        val start = FilesyncDiscussion(Uuid.random(), loading = false, reactions = listOf(FilesyncReactionCount("👍", 2)), mine = listOf("👍"))

        assertEquals(listOf(FilesyncReactionCount("👍", 1)), start.toggled("👍").reactions)
        assertEquals(emptyList(), start.toggled("👍").mine)
        assertEquals(listOf(FilesyncReactionCount("👍", 2), FilesyncReactionCount("🎉", 1)), start.toggled("🎉").reactions)
        assertEquals(emptyList(), FilesyncDiscussion(Uuid.random(), reactions = listOf(FilesyncReactionCount("👍", 1)), mine = listOf("👍")).toggled("👍").reactions)
    }

    @Test
    fun aUserMayLeaveFiveReactions() {
        val four = FilesyncDiscussion(Uuid.random(), mine = listOf("1", "2", "3", "4"))
        assertTrue(four.canAddReaction)
        assertFalse(four.toggled("5").canAddReaction)
    }

    private fun reaction(odinId: String, emoji: String, created: Long) =
        """{"reactionContent":"{\"emoji\":\"$emoji\"}","odinId":"$odinId","fileId":{"driveId":"${Uuid.random()}","fileId":"${Uuid.random()}"},"created":$created}"""

    private suspend fun comment(author: String, body: String, createdMs: Long): String {
        val iv = ByteArrayUtil.getRndByteArray(16)
        val content = Base64.encode(KeyHeader(iv, fileKey.aesKey).encryptDataAes("""{"body":"$body"}""".encodeToByteArray()))
        return """{
            "fileId": "${Uuid.random()}", "driveId": "${FilesyncProtocol.DriveAlias}", "fileState": "active", "fileSystemType": "comment",
            "sharedSecretEncryptedKeyHeader": { "encryptionVersion": 1, "type": "fileKey", "iv": "${Base64.encode(iv)}", "encryptedAesKey": "" },
            "fileMetadata": { "isEncrypted": true, "created": $createdMs, "originalAuthor": "$author", "versionTag": "${Uuid.random()}",
              "appData": { "uniqueId": "${Uuid.random()}", "fileType": 801, "groupId": "$globalTransitId", "content": "$content" } },
            "serverMetadata": {}
        }"""
    }

    // Byte for byte: ISO-8859-1 maps every byte to one char and back.
    private fun multipart(body: ByteArray, request: HttpRequestData): Map<String, ByteArray> {
        val boundary = request.body.contentType!!.parameter("boundary")!!
        return String(body, Charsets.ISO_8859_1).split("--$boundary").mapNotNull { part ->
            val name = Regex("name=\"([^\"]+)\"").find(part.substringBefore("\r\n\r\n"))?.groupValues?.get(1) ?: return@mapNotNull null
            name to part.substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
        }.toMap()
    }

    private object NoFiles : FileOperationsProvider {
        override fun getCacheDirectory() = ""
        override fun openFileInput(path: String): InputProvider = error("not used")
        override suspend fun readFileBytes(path: String): ByteArray = error("not used")
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    }
}
