package id.homebase.api.client.peer

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.GlobalTransitIdFileIdentifier
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.commentedFileKeyHeader
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.keepFileKeyHeader
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ShareKeys
import id.homebase.api.file.JvmFileOperationsProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** A member's new file and edit as odin-core reads them (UploadFileDescriptor, V2DrivePeerUpdateController). */
class MemberWritesWireTest {

    private val secret = ByteArray(16) { 7 }
    private val frodo = OdinId("frodo.dotyou.cloud")
    private val credentials = CredentialsManager()
    private val drivePoint = ShareKeys.pointOfJwk(
        """{"kty":"EC","crv":"P-384","x":"yjx33dKI7M4I3IUdOl1Tdqf4mqLGyXnYEBg_Oo3B9_QjOxI1ol2usFU1t3FhnQ18","y":"FkGMeDRw4PJSFlCwESYb59Nltf3qE7Q5IXaUK2OiplW8aWP3oTWZQ5YZYdzJ7ReN"}"""
    )
    private lateinit var dir: File
    private lateinit var payload: File

    @BeforeTest
    fun setup() = runBlocking {
        credentials.setActiveCredentials(ApiCredentials.create(OdinId("sam.dotyou.cloud"), "app-token", SecureByteArray(secret)))
        dir = Files.createTempDirectory("member-writes").toFile()
        payload = File(dir, "payload").apply { writeBytes(ByteArray(64) { 1 }) }
        Unit
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aDepositCarriesItsSharesAndTheGlobalTransitIdItPickedNextToItsSealedKeyHeader() = runTest {
        val provider = DriveUploadProvider(HttpClient(MockEngine { error("no requests") }), credentials, JvmFileOperationsProvider())
        val gtid = Uuid.random()
        val circle = Uuid.random()
        val request = UploadFileRequest(
            driveId = SystemDriveConstants.transientTempDrive.alias,
            keyHeader = KeyHeader.newRandom16(),
            metadata = UploadFileMetadata(allowDistribution = true, isEncrypted = true, appData = UploadAppFileMetaData(uniqueId = Uuid.random())),
            payloads = listOf(PayloadFile(key = "sync_data", filePath = payload.path, contentType = "application/octet-stream", isPreEncrypted = true, iv = ByteArray(16))),
            transitOptions = TransitOptions(recipients = listOf(frodo), isTransient = true, remoteTargetDrive = TargetDrive(Uuid.random(), Uuid.random())),
            sealedTo = drivePoint,
            shares = listOf(ShareRequest(Uuid.NIL, circle, 3, lockedFileKey = ByteArray(45) { 3 })),
            globalTransitId = gtid,
        )
        val bodyFile = File(dir, "body")

        val prepared = provider.writeUploadBody(request, bodyFile.path)

        val parts = parts(bodyFile.readBytes(), prepared.contentType)
        val instructions = Json.parseToJsonElement(parts.getValue("instructions").decodeToString()).jsonObject
        assertEquals("true", instructions["transitOptions"]!!.jsonObject["isTransient"]!!.jsonPrimitive.content)
        val descriptor = descriptor(parts, instructions)
        assertEquals(gtid.toString(), descriptor["globalTransitId"]!!.jsonPrimitive.content)
        assertEquals("sealed", descriptor["encryptedKeyHeader"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val share = descriptor["shares"]!!.jsonArray.single().jsonObject
        assertEquals(Uuid.NIL.toString(), share["fileId"]!!.jsonPrimitive.content)
        assertEquals(circle.toString(), share["circleId"]!!.jsonPrimitive.content)
        assertEquals("3", share["permissions"]!!.jsonPrimitive.content)
        assertEquals(Base64.encode(ByteArray(45) { 3 }), share["lockedFileKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun anEditGoesToTheOwnersFileByGlobalTransitIdWithItsKeyHeaderAsItIs() = runTest {
        var method: HttpMethod? = null
        var path = ""
        var contentType = ""
        var body = ByteArray(0)
        val provider = DriveUploadProvider(
            HttpClient(MockEngine { request ->
                method = request.method
                path = request.url.encodedPath
                contentType = request.body.contentType.toString()
                body = request.body.toByteArray()
                respond(
                    """{"fileId":"${Uuid.NIL}","driveId":"${Uuid.NIL}","newVersionTag":"${Uuid.random()}"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
            credentials,
            JvmFileOperationsProvider(),
        )
        val drive = Uuid.random()
        val gtid = Uuid.random()
        val newIv = ByteArray(16) { 5 }

        provider.updateFileOverPeerByGlobalTransitId(
            frodo, drive, gtid, keepFileKeyHeader(newIv),
            UploadFileMetadata(allowDistribution = true, isEncrypted = true, appData = UploadAppFileMetaData(uniqueId = Uuid.random()), versionTag = Uuid.random()),
            listOf(PayloadFile(key = "sync_data", filePath = payload.path, contentType = "application/octet-stream", isPreEncrypted = true, iv = ByteArray(16) { 6 })),
        )

        assertEquals(HttpMethod.Patch, method)
        assertTrue(path.endsWith("/api/v2/peer/frodo.dotyou.cloud/drives/$drive/files/by-gtid/$gtid"), path)
        val parts = parts(body, contentType)
        val instructions = Json.parseToJsonElement(parts.getValue("instructions").decodeToString()).jsonObject
        assertEquals("peer", instructions["locale"]!!.jsonPrimitive.content.lowercase())
        val sent = checkNotNull(instructions["manifest"]!!.jsonObject["payloadDescriptors"]) { "$instructions" }.jsonArray.single().jsonObject
        assertEquals("appendOrOverwrite", sent["payloadUpdateOperationType"]!!.jsonPrimitive.content)
        val keyHeader = descriptor(parts, instructions)["encryptedKeyHeader"]!!.jsonObject
        assertEquals("share", keyHeader["type"]!!.jsonPrimitive.content)
        assertEquals(Base64.encode(newIv), keyHeader["iv"]!!.jsonPrimitive.content)
        assertEquals("", keyHeader["encryptedAesKey"]!!.jsonPrimitive.content, "the file's key is kept, not sent")
    }

    @Test
    fun aCommentEditOverPeerNamesTheCommentFileSystemAndKeepsThePhotosKey() = runTest {
        val sent = Captured()
        val provider = DriveUploadProvider(sent.client(updated), credentials, JvmFileOperationsProvider())
        val newIv = ByteArray(16) { 5 }

        provider.updateFileOverPeerByGlobalTransitId(
            frodo, Uuid.random(), Uuid.random(), commentedFileKeyHeader(newIv),
            UploadFileMetadata(allowDistribution = true, isEncrypted = true, appData = UploadAppFileMetaData(uniqueId = Uuid.random())),
            payloads = emptyList(),
            fileSystemType = FileSystemType.Comment,
        )

        assertEquals("Comment", sent.request.url.parameters["xfst"])
        val keyHeader = sent.descriptor()["encryptedKeyHeader"]!!.jsonObject
        assertEquals("fileKey", keyHeader["type"]!!.jsonPrimitive.content)
        assertEquals(Base64.encode(newIv), keyHeader["iv"]!!.jsonPrimitive.content)
    }

    @Test
    fun anOwnersCommentEditIsOnItsFilesKeyToo() = runTest {
        val sent = Captured()
        val provider = DriveUploadProvider(sent.client(updated), credentials, JvmFileOperationsProvider())
        val uniqueId = Uuid.random()
        val keyHeader = KeyHeader.newRandom16()

        provider.updateFileByUniqueId(
            UpdateFileByUniqueIdRequest(
                driveId = Uuid.random(),
                uniqueId = uniqueId,
                keyHeader = keyHeader,
                instructions = FileUpdateInstructionSet(ByteArray(16) { 2 }, UpdateLocale.Local, emptyList(), UpdateManifest.build()),
                metadata = UploadFileMetadata(allowDistribution = false, isEncrypted = true, appData = UploadAppFileMetaData(uniqueId = uniqueId)),
                fileSystemType = FileSystemType.Comment,
                withCommentedFileKey = true,
            )
        )

        assertTrue(sent.request.url.encodedPath.endsWith("/files/by-uid/$uniqueId"), sent.request.url.encodedPath)
        assertEquals("Comment", sent.request.url.parameters["xfst"])
        val locked = sent.descriptor()["encryptedKeyHeader"]!!.jsonObject
        assertEquals("fileKey", locked["type"]!!.jsonPrimitive.content)
        assertEquals(Base64.encode(keyHeader.iv), locked["iv"]!!.jsonPrimitive.content)
        assertEquals("", locked["encryptedAesKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun aGuestDeletesItsCommentByAskingTheOwnerByGlobalTransitId() = runTest {
        val sent = Captured()
        val provider = PeerDriveUploadProvider(sent.client { respondJson("""{"frodo.dotyou.cloud":"enqueued"}""") }, credentials, JvmFileOperationsProvider())
        val drive = TargetDrive(Uuid.random(), Uuid.random())
        val gtid = Uuid.random()

        provider.sendDeleteRequest(frodo, GlobalTransitIdFileIdentifier(gtid, drive), FileSystemType.Comment)

        assertEquals(HttpMethod.Post, sent.request.method)
        assertTrue(sent.request.url.encodedPath.endsWith("/api/v2/peer/frodo.dotyou.cloud/drives/${drive.alias}/files/senddeleterequest"), sent.request.url.encodedPath)
        val body = Json.parseToJsonElement(sent.body.decodeToString()).jsonObject
        assertEquals("comment", body["fileSystemType"]!!.jsonPrimitive.content)
        assertEquals(gtid.toString(), body["globalTransitIdFileIdentifier"]!!.jsonObject["globalTransitId"]!!.jsonPrimitive.content)
        assertEquals(listOf("frodo.dotyou.cloud"), body["recipients"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun aReactionOnACommentNamesTheCommentFileSystem() = runTest {
        val sent = Captured()
        val provider = DriveFileGroupReactionProvider(sent.client { respondJson("{}") }, credentials)

        provider.addReaction(Uuid.random(), Uuid.random(), "{}", recipients = emptyList(), fileSystemType = FileSystemType.Comment)

        assertEquals("Comment", sent.request.headers["X-ODIN-FILE-SYSTEM-TYPE"])
    }

    private val updated: suspend MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData = {
        respondJson("""{"fileId":"${Uuid.NIL}","driveId":"${Uuid.NIL}","newVersionTag":"${Uuid.random()}"}""")
    }

    private fun MockRequestHandleScope.respondJson(json: String) = respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    // The one request a test sends, and its body.
    private inner class Captured {
        lateinit var request: HttpRequestData
        var body = ByteArray(0)

        fun client(answer: suspend MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData) = HttpClient(MockEngine { request ->
            this@Captured.request = request
            body = request.body.toByteArray()
            answer()
        })

        suspend fun descriptor(): JsonObject {
            val parts = parts(body, request.body.contentType.toString())
            return descriptor(parts, Json.parseToJsonElement(parts.getValue("instructions").decodeToString()).jsonObject)
        }
    }

    private suspend fun descriptor(parts: Map<String, ByteArray>, instructions: JsonObject): JsonObject {
        val transferIv = Base64.decode(instructions["transferIv"]!!.jsonPrimitive.content)
        return Json.parseToJsonElement(AesCbc.decrypt(parts.getValue("metadata"), secret, transferIv).decodeToString()).jsonObject
    }

    // Each form part's bytes by its name.
    private fun parts(body: ByteArray, contentType: String): Map<String, ByteArray> {
        val boundary = "--" + contentType.substringAfter("boundary=").trim('"')
        val text = String(body, Charsets.ISO_8859_1)
        return text.split(boundary).drop(1).dropLast(1).associate { part ->
            val name = Regex("name=\"?([^\";\r\n]+)").find(part)!!.groupValues[1]
            val content = part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
            name to content.toByteArray(Charsets.ISO_8859_1)
        }
    }
}
