package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.CircleShareKeyProvider
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ShareKeys
import id.homebase.api.crypto.ZkSeal
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FilesyncSharingTest {

    private val frodo = OdinId("frodo.test")
    private val sam = OdinId("sam.test")
    private val appSecret = ByteArray(16) { 1 }
    private val filesyncSecret = ByteArray(16) { 2 }
    private val filesyncAppKey = ByteArray(16) { 3 }
    private val recipientsCircle = Uuid.random()
    private val samsCircle = Uuid.random()
    private val file = FilesyncHandle(Uuid.random(), null, FilesyncProtocol.FileFileType, KeyHeader.newRandom16(), JsonObject(emptyMap()), null)

    // Sam's Filesync drive's write-only key: the pair odin-core's ClientLockedKeyHeaderVectorTests uses
    private val samsJwk =
        """{"kty":"EC","crv":"P-384","x":"yjx33dKI7M4I3IUdOl1Tdqf4mqLGyXnYEBg_Oo3B9_QjOxI1ol2usFU1t3FhnQ18","y":"FkGMeDRw4PJSFlCwESYb59Nltf3qE7Q5IXaUK2OiplW8aWP3oTWZQ5YZYdzJ7ReN"}"""
    private val samsPrivateKey = Base64.decode(
        "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDCZAel29rv0BgBwa7LGp4+tmSi5g4LlRzkW9/ZLQNgeVdrB44imUOGeaKmn1eiPZsShZANiAATKPHfd0ojszgjchR06XVN2p/iaosbJedgQGD86jcH39CM7EjWiXa6wVTW3cWGdDXwWQYx4NHDg8lIWULARJhvn02W1/eoTtDkhdpQrY6KmVbxpY/ehNZlDlhlh3MntF40="
    )

    private class Call(val request: HttpRequestData, val body: ByteArray) {
        val asFilesync get() = request.headers[HttpHeaders.Authorization] == "Bearer filesync-token"
        val path get() = request.url.encodedPath
    }

    private val calls = mutableListOf<Call>()
    private var samReady = true
    private var storedShareKey: String? = null

    private val engine = MockEngine { request ->
        val call = Call(request, request.body.toByteArray()).also { calls += it }
        val path = call.path
        val body = when {
            path.endsWith("/apps/filesync/drives/filesync/public-key") ->
                if (samReady) """{"publicKeyJwk":${Json.encodeToString(samsJwk)},"publicKeyCrc32":0,"isZeroKnowledge":true,"sharingModel":"selectiveShare"}"""
                else return@MockEngine respond("""{"title":"no such drive","errorCode":"invalidRequest"}""", io.ktor.http.HttpStatusCode.BadRequest, json)
            path.endsWith("/connections/circles/create") ->
                if (decrypted(call, filesyncSecret).jsonObject["slug"]!!.jsonPrimitive.content == FilesyncProtocol.CircleSlug) "\"$recipientsCircle\"" else "\"$samsCircle\""
            path.endsWith("/connections/circles/add-many") -> """{"enrolled":1,"deposited":0,"skipped":0,"outcomes":[]}"""
            path.endsWith("/share-key/unsealed-members") -> """["sam.test"]"""
            path.endsWith("/share-key") && request.method == HttpMethod.Get -> """{"appKeyLockedShareKey":${storedShareKey?.let { "\"$it\"" } ?: "null"}}"""
            path.endsWith("/share-key") -> "{}".also { storedShareKey = decrypted(call, filesyncSecret).jsonObject["appKeyLockedShareKey"]!!.jsonPrimitive.content }
            path.endsWith("/share-key/members") || path.endsWith("/shares") -> "{}"
            path.endsWith("/files") -> """{"fileId":"${Uuid.random()}","driveId":"${SystemDriveConstants.transientTempDrive.alias}","newVersionTag":"${Uuid.random()}"}"""
            else -> error("unexpected ${request.method} $path")
        }
        respond(body, headers = json)
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val appCredentials = CredentialsManager()
    private val sharing = HttpClient(engine).let { http ->
        FilesyncSharing(
            http, appCredentials,
            ConnectionNetworkProvider(http, appCredentials), CircleShareKeyProvider(http, appCredentials),
            PeerDriveQueryProvider(http, appCredentials), DriveQueryProvider(http, appCredentials),
            DriveUploadProvider(http, appCredentials, NoFiles),
        )
    }

    @BeforeTest
    fun signIn() = runBlocking {
        appCredentials.setActiveCredentials(ApiCredentials.create(frodo, "app-token", SecureByteArray(appSecret)))
        FilesyncSession.use(ApiCredentials.create(frodo, "filesync-token", SecureByteArray(filesyncSecret)), filesyncAppKey)
    }

    @AfterTest
    fun signOut() = runBlocking { FilesyncSession.credentials.removeAllCredentials() }

    @Test
    fun sharingAFileGivesSamACircleOfHisOwnWhoseKeyHeAloneCanOpenAndLeavesHimAMarker() = runTest {
        sharing.share(listOf(file), sam, FilesyncAccess.View)

        // Circles, their keys and shares are written as Filesync; the public key and the marker as the app
        assertTrue(calls.filter { "/connections/" in it.path || it.path.endsWith("/shares") }.all { it.asFilesync })
        assertTrue(calls.filter { it.path.endsWith("/public-key") || it.path.endsWith("/files") }.none { it.asFilesync })

        val individual = calls.map { decryptedOrNull(it) }.single { it?.jsonObject?.get("designation")?.jsonPrimitive?.content == "individual" }!!.jsonObject
        assertEquals(FilesyncSharing.individualSlug(sam), individual["slug"]!!.jsonPrimitive.content)

        val circleKey = ShareKeys.openCircleKey(filesyncAppKey, Base64.decode(storedShareKey!!), samsCircle)
        val members = decrypted(calls.single { it.path.endsWith("/share-key/members") }, filesyncSecret).jsonObject
        assertEquals(storedShareKey, members["sealedFrom"]!!.jsonPrimitive.content)
        val samsCopy = Base64.decode(members["members"]!!.jsonArray.single().jsonObject["sealedKey"]!!.jsonPrimitive.content)
        assertContentEquals(circleKey, ShareKeys.unsealCircleKey(samsCopy, samsPrivateKey, samsCircle, sam))

        val share = decrypted(calls.single { it.path.endsWith("/shares") }, filesyncSecret).jsonObject["shares"]!!.jsonArray.single().jsonObject
        assertEquals(1 + 4 + 8, share["permissions"]!!.jsonPrimitive.int)
        val lockedFileKey = Base64.decode(share["lockedFileKey"]!!.jsonPrimitive.content)
        assertContentEquals(file.keyHeader.aesKey.toByteArray(), ShareKeys.openFileKey(circleKey, lockedFileKey, FilesyncProtocol.DriveAlias, file.fileId, samsCircle))

        val marker = calls.single { it.path.endsWith("/files") }
        assertEquals("/api/v2/drives/${SystemDriveConstants.transientTempDrive.alias}/files", marker.path)
        val parts = multipart(marker.body, marker.request)
        val instructions = Json.parseToJsonElement(parts.getValue("instructions").decodeToString()).jsonObject
        val transit = instructions["transitOptions"]!!.jsonObject
        assertEquals(listOf("sam.test"), transit["recipients"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, transit["isTransient"]!!.jsonPrimitive.content.toBoolean())
        val transferIv = Base64.decode(instructions["transferIv"]!!.jsonPrimitive.content)
        val descriptor = Json.parseToJsonElement(AesCbc.decrypt(parts.getValue("metadata"), appSecret, transferIv).decodeToString()).jsonObject
        val header = descriptor["encryptedKeyHeader"]!!.jsonObject
        assertEquals("sealed", header["type"]!!.jsonPrimitive.content)
        val opened = ZkSeal.open(Base64.decode(header["encryptedAesKey"]!!.jsonPrimitive.content), samsPrivateKey)
        val appData = descriptor["fileMetadata"]!!.jsonObject["appData"]!!.jsonObject
        assertEquals(FilesyncSharing.markerId(frodo, sam).toString(), appData["uniqueId"]!!.jsonPrimitive.content)
        assertEquals(FilesyncProtocol.MarkerFileType, appData["fileType"]!!.jsonPrimitive.int)
        val content = KeyHeader(opened.copyOf(16), SecureByteArray(opened.copyOfRange(16, 32))).decrypt(Base64.decode(appData["content"]!!.jsonPrimitive.content))
        assertEquals("frodo.test", Json.parseToJsonElement(content.decodeToString()).jsonObject["odinId"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSecondShareReusesTheCirclesKey() = runTest {
        sharing.share(listOf(file), sam, FilesyncAccess.View)
        sharing.share(listOf(file), sam, FilesyncAccess.Edit)

        assertEquals(1, calls.count { it.path.endsWith("/share-key") && it.request.method == HttpMethod.Put })
    }

    @Test
    fun someoneWhoHasntTurnedSharingOnCantBeSharedWith() = runTest {
        samReady = false

        assertFailsWith<FilesyncSharingException.RecipientNotReady> { sharing.share(listOf(file), sam, FilesyncAccess.View) }
        assertTrue(calls.none { it.path.endsWith("/shares") })
    }

    @Test
    fun theMarkerIdIsTheSameOnEveryDeviceAndDiffersByDirection() {
        assertEquals(FilesyncSharing.markerId(frodo, sam), FilesyncSharing.markerId(OdinId("Frodo.Test"), sam))
        assertTrue(FilesyncSharing.markerId(frodo, sam) != FilesyncSharing.markerId(sam, frodo))
        assertEquals(14, FilesyncSharing.individualSlug(sam).length)
    }

    private suspend fun decrypted(call: Call, secret: ByteArray): JsonElement =
        Json.parseToJsonElement(CryptoHelper.decryptContentAsString(call.body.decodeToString(), secret))

    private suspend fun decryptedOrNull(call: Call): JsonElement? =
        runCatching { decrypted(call, if (call.asFilesync) filesyncSecret else appSecret) }.getOrNull()
}
