package id.homebase.photobase.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.CircleShareKeyProvider
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.crypto.ShareKeys
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.youauth.AppSignIn
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.PhotobaseRecord
import id.homebase.photobase.gallery.PhotoAlbums
import id.homebase.photobase.gallery.PhotoGalleryStore
import id.homebase.photobase.gallery.PhotoMover
import id.homebase.photobase.gallery.PhotoMoveServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import id.homebase.api.youauth.DrivePermission
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a shared album's changes put on the wire, through the real providers. */
class PhotoAlbumSharingTest {
    private val frodo = OdinId("frodo.test")
    private val appSecret = ByteArray(16) { 1 }
    private val photobaseSecret = ByteArray(16) { 2 }
    private val photobaseAppKey = ByteArray(16) { 3 }
    private val trip = Uuid.random()
    private val pets = Uuid.random()
    private val samsCircle = Uuid.random()
    private val pippinsCircle = Uuid.random()

    private class Call(val request: HttpRequestData, val body: ByteArray) {
        val path get() = request.url.encodedPath
        val asPhotobase get() = request.headers[HttpHeaders.Authorization] == "Bearer photobase-token"
    }

    private val calls = CopyOnWriteArrayList<Call>()
    // Each album record's live rows, by album.
    private val albumRows = mutableMapOf<Uuid, List<Uuid>>()
    @Volatile private var storedShareKey: String? = null
    @Volatile private var samsPending = "[]"
    @Volatile private var addManyKind = "skipped"
    private val photosCircle = Uuid.random()
    // What Photos' recipients circle grants on the drive; null leaves it unlisted.
    @Volatile private var photosCircleGrants: String? = "read, react, comment"
    @Volatile private var samsConnection = """{"odinId":"sam.test","status":"connected","created":1,"lastUpdated":1,"connectionRequestOrigin":"identityOwner","hasVerificationHash":true,"rku":false}"""

    private fun albumRecord(albumId: Uuid): String {
        val key = KeyHeader.newRandom16()
        val file = ServerFile(
            fileId = Uuid.random(),
            driveId = PhotobaseProtocol.DriveAlias,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            sharedSecretEncryptedKeyHeader = runBlocking { EncryptedKeyHeader.encryptKeyHeaderAes(key, ByteArray(16) { 9 }, SecureByteArray(photobaseSecret)) },
            fileMetadata = FileMetadata(appData = AppFileMetaData(uniqueId = albumId, fileType = PhotobaseProtocol.AlbumFileType, content = """{"name":"album"}""")),
            serverMetadata = ServerMetadata(),
        )
        val rows = albumRows[albumId].orEmpty().joinToString(prefix = "[", postfix = "]") { """{"circleId":"$it","permissions":"read,react,comment","created":1,"modified":1}""" }
        return OdinSystemSerializer.serialize(file).removeSuffix("}") + ""","shares":$rows}"""
    }

    private val engine = MockEngine { request ->
        val call = Call(request, request.body.toByteArray()).also { calls += it }
        val path = call.path
        val body = when {
            path.endsWith("/query-batch") -> {
                val asked = decrypted(call).jsonObject["queryParams"]!!.jsonObject["clientUniqueIdAtLeastOne"]!!.jsonArray.single().jsonPrimitive.content
                """{"searchResults":[${albumRecord(Uuid.parse(asked))}],"hasMoreRows":false}"""
            }
            path.endsWith("/share-key") && request.method == HttpMethod.Get -> """{"appKeyLockedShareKey":${storedShareKey?.let { "\"$it\"" } ?: "null"}}"""
            path.endsWith("/share-key") -> "{}".also { storedShareKey = decrypted(call).jsonObject["appKeyLockedShareKey"]!!.jsonPrimitive.content }
            path.endsWith("/shares") -> "{}"
            path.endsWith("/connections/status") -> samsConnection
            path.endsWith("/public-key") -> return@MockEngine respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            path.endsWith("/circles/create") -> "\"$photosCircle\""
            path.endsWith("/circles/with-members") -> photosCircleGrants?.let { permission ->
                """[{"circle":{"id":"${photosCircle.toHexString()}","name":"Photos","driveGrants":[{"permissionedDrive":{"drive":{"alias":"${PhotobaseProtocol.DriveAlias.toHexString()}","type":"x"},"permission":"$permission"}}]},"members":[]}]"""
            } ?: "[]"
            path.endsWith("/circles/add-many") -> """{"outcomes":[{"odinId":"sam.test","kind":"$addManyKind"}]}"""
            path.endsWith("/circles/delete") -> "true"
            path.endsWith("/circles/pending") -> samsPending
            path.endsWith("/connections/circles") -> "[]"
            "/shares/" in path && request.method == HttpMethod.Delete -> "{}"
            else -> error("unexpected ${request.method} $path")
        }
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val http = HttpClient(engine)
    private val appCredentials = CredentialsManager()
    private val db = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val local = mutableMapOf<Uuid, HomebaseFile>()
    private val stored = mutableMapOf<String, String>()

    private val noServer = object : PhotoMoveServer {
        override suspend fun setArchivalStatus(status: id.homebase.api.client.drives.files.ArchivalStatus, files: List<id.homebase.api.client.drives.files.FileVersionTag>) = error("not used")
        override suspend fun softDelete(files: List<id.homebase.api.client.drives.files.FileVersionTag>) = error("not used")
        override suspend fun setLocalTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>) = error("not used")
        override suspend fun setAppTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>) = error("not used")
        override suspend fun header(fileId: Uuid): HomebaseFile? = error("not used")
    }

    private val record = PhotobaseRecord({ _, _ -> null }, { error("not used") }, { error("not used") }) { _, _ -> error("not used") }
    private val mover = PhotoMover(noServer, filesOf = { emptyList() }, binned = { emptyList() }, sync = {})
    private val albums = PhotoAlbums(PhotoGalleryStore(db, { Uuid.random() }), record, mover, { emptyList() }, emptyFlow(), scope, sync = {})

    private val sharing = PhotoAlbumSharing(
        http, appCredentials,
        ConnectionNetworkProvider(http, appCredentials), CircleShareKeyProvider(http, appCredentials),
        PeerDriveQueryProvider(http, appCredentials), DriveQueryProvider(http, appCredentials),
        record, albums, filesOf = { ids -> ids.mapNotNull { local[it] } },
    )

    @BeforeTest
    fun signIn() = runBlocking {
        PhotobaseSession.store = object : AppSignIn.Store {
            override fun get(key: String) = stored[key]
            override fun put(key: String, value: String) { stored[key] = value }
            override fun remove(key: String) { stored.remove(key) }
        }
        appCredentials.setActiveCredentials(ApiCredentials.create(frodo, "app-token", SecureByteArray(appSecret)))
        PhotobaseSession.use(ApiCredentials.create(frodo, "photobase-token", SecureByteArray(photobaseSecret)), photobaseAppKey)
    }

    @AfterTest
    fun close() = runBlocking {
        PhotobaseSession.forget()
        scope.cancel()
        db.close()
    }

    private fun photo(vararg albums: Uuid) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = PhotobaseProtocol.DriveAlias,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.newRandom16(),
        fileMetadata = FileMetadata(appData = AppFileMetaData(uniqueId = Uuid.random(), fileType = PhotobaseProtocol.PhotoFileType, tags = albums.toList())),
        serverMetadata = ServerMetadata(),
    ).also { local[it.fileId] = it }

    @Test
    fun aPhotoPutInASharedAlbumIsSharedWithEachOfItsCirclesUnderTheCirclesKey() = runTest {
        albumRows[trip] = listOf(samsCircle)
        val photo = photo(trip)

        sharing.albumTagChanged(trip, on = true, setOf(photo.fileId))

        val sent = calls.single { it.path.endsWith("/shares") }
        assertTrue(sent.asPhotobase, "shares are Photobase's to write")
        val share = decrypted(sent).jsonObject["shares"]!!.jsonArray.single().jsonObject
        assertEquals(photo.fileId.toString(), share["fileId"]!!.jsonPrimitive.content)
        assertEquals(samsCircle.toString(), share["circleId"]!!.jsonPrimitive.content)
        assertEquals(1 + 4 + 8, share["permissions"]!!.jsonPrimitive.int, "Read, React and Comment: never Write")
        val circleKey = ShareKeys.openCircleKey(photobaseAppKey, Base64.decode(storedShareKey!!), samsCircle)
        val locked = Base64.decode(share["lockedFileKey"]!!.jsonPrimitive.content)
        assertContentEquals(photo.keyHeader.aesKey.toByteArray(), ShareKeys.openFileKey(circleKey, locked, PhotobaseProtocol.DriveAlias, photo.fileId, null, samsCircle))
    }

    @Test
    fun aPhotoTakenOutStaysSharedWithACircleAnotherOfItsAlbumsIsSharedWith() = runTest {
        albumRows[trip] = listOf(samsCircle, pippinsCircle)
        albumRows[pets] = listOf(pippinsCircle)
        val photo = photo(pets)

        sharing.albumTagChanged(trip, on = false, setOf(photo.fileId))

        val withdrawn = calls.filter { it.request.method == HttpMethod.Delete }.map { it.path }
        assertEquals(1, withdrawn.size, "$withdrawn")
        assertTrue(withdrawn.single().endsWith("/drives/${PhotobaseProtocol.DriveAlias}/files/${photo.fileId}/shares/$samsCircle"), withdrawn.single())
    }

    @Test
    fun anAlbumSharedWithNoOneSharesNothing() = runTest {
        sharing.albumTagChanged(trip, on = true, setOf(photo(trip).fileId))

        assertTrue(calls.none { it.path.endsWith("/shares") || it.request.method == HttpMethod.Delete })
    }

    @Test
    fun aConnectionNotYetReviewedIsNamedBeforeAnythingIsWritten() = runTest {
        val sam = OdinId("sam.test")

        val refused = assertFailsWith<PhotoSharingException.NotReviewed> {
            sharing.share(trip, AlbumAudience.Person(sam), PhotoAlbumAccess.View, expiresAt = null)
        }

        assertEquals(listOf(sam), refused.recipients)
        assertTrue(calls.all { it.path.endsWith("/connections/status") }, calls.map { it.path }.toString())
    }

    @Test
    fun someoneNotConnectedIsNamedBeforeAnythingIsWritten() = runTest {
        samsConnection = samsConnection.replace("\"connected\"", "\"none\"")

        assertFailsWith<PhotoSharingException.NotConnected> {
            sharing.share(trip, AlbumAudience.Person(OdinId("sam.test")), PhotoAlbumAccess.View, expiresAt = null)
        }

        assertTrue(calls.all { it.path.endsWith("/connections/status") }, calls.map { it.path }.toString())
    }

    @Test
    fun someonePendingIsNamedAndNoPhotoIsTouchedOrShared() = runTest {
        samsConnection = samsConnection.replace("\"rku\":false", "\"rku\":false,\"reviewedAt\":1")
        samsPending = """[{"odinId":"sam.test","deposited":1}]"""

        val pending = assertFailsWith<PhotoSharingException.Pending> {
            sharing.share(trip, AlbumAudience.Person(OdinId("sam.test")), PhotoAlbumAccess.View, expiresAt = null)
        }

        assertEquals(listOf(OdinId("sam.test")), pending.recipients)
        assertTrue(calls.none { it.path.endsWith("/shares") || it.path.endsWith("/query-batch") || it.request.method == HttpMethod.Patch }, calls.map { it.path }.toString())
    }

    @Test
    fun someoneSkippedWhoIsNeitherInTheCircleNorPendingIsRefused() = runTest {
        samsConnection = samsConnection.replace("\"rku\":false", "\"rku\":false,\"reviewedAt\":1")

        assertFailsWith<PhotoSharingException.RecipientNotAdded> {
            sharing.share(trip, AlbumAudience.Person(OdinId("sam.test")), PhotoAlbumAccess.View, expiresAt = null)
        }
    }

    @Test
    fun aPhotosCircleThatGrantsOnlyReadStopsTheShareBeforeAnythingIsWritten() = runTest {
        samsConnection = samsConnection.replace("\"rku\":false", "\"rku\":false,\"reviewedAt\":1")
        photosCircleGrants = "read"

        val narrow = assertFailsWith<PhotoSharingException.CircleTooNarrow> {
            sharing.share(trip, AlbumAudience.Person(OdinId("sam.test")), PhotoAlbumAccess.View, expiresAt = null)
        }

        assertEquals(listOf(DrivePermission.React, DrivePermission.Comment), narrow.missing)
        assertTrue(calls.none { it.path.endsWith("/add-many") || it.path.endsWith("/shares") || it.path.endsWith("/query-batch") }, calls.map { it.path }.toString())
    }

    @Test
    fun aNewGroupWhoseShareIsRefusedBeforeAnythingIsWrittenGoesAgain() = runTest {
        samsConnection = samsConnection.replace("\"rku\":false", "\"rku\":false,\"reviewedAt\":1")
        addManyKind = "enrolled"
        photosCircleGrants = "read"

        assertFailsWith<PhotoSharingException.CircleTooNarrow> {
            sharing.shareWithNewGroup(trip, "Trip", listOf(OdinId("sam.test")), PhotoAlbumAccess.View, expiresAt = null)
        }

        assertTrue(calls.any { it.path.endsWith("/circles/delete") }, "a retry would otherwise make a second Trip")
        assertTrue(calls.none { it.path.endsWith("/shares") || it.path.endsWith("/share-key") }, calls.map { it.path }.toString())
    }

    private suspend fun decrypted(call: Call) =
        Json.parseToJsonElement(CryptoHelper.decryptContentAsString(call.body.decodeToString(), if (call.asPhotobase) photobaseSecret else appSecret))
}
