package id.homebase.api.client.peer

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.keepFileKeyHeader
import id.homebase.api.client.drives.shares.ShareRequest
import id.homebase.api.client.drives.upload.DriveUploadProvider
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
