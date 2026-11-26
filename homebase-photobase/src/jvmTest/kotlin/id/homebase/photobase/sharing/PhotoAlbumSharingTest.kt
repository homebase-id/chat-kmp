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
import kotlin.test.assertFails
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
    private val missingAlbums = mutableSetOf<Uuid>()
    private val revokeFails = mutableSetOf<String>()
    private val bilbo = OdinId("bilbo.test")
    // Each one's own connection, over samsConnection.
    private val statuses = mutableMapOf<String, String>()
    // Each one's own enrolment answer, over addManyKind.
    private val kinds = mutableMapOf<String, String>()
    private val enrolled = CopyOnWriteArrayList<Pair<String, List<String>>>()
    private val revoked = CopyOnWriteArrayList<String>()
    private val created = CopyOnWriteArrayList<String>()
    private val merry = OdinId("merry.test")
    private val rosie = OdinId("rosie.test")
    private val gandalf = OdinId("gandalf.test")
    private val pippin = OdinId("pippin.test")
    private val sam = OdinId("sam.test")
    private val editorsCircle = Uuid.random()
    private val newGroupCircle = Uuid.random()
    private val individualCircle = Uuid.random()
    private val groupCircle = Uuid.random()
    private val photosCircle = Uuid.random()
    // What Photos' recipients circle grants on the drive; null leaves it unlisted.
    @Volatile private var photosCircleGrants: String? = "read, react, comment"
    @Volatile private var samsConnection = """{"odinId":"sam.test","status":"connected","created":1,"lastUpdated":1,"connectionRequestOrigin":"identityOwner","hasVerificationHash":true,"rku":false}"""

    // Sharer records on this identity's drive: who the server says wrote each, and the sharer it keeps.
    private val sharerRecords = CopyOnWriteArrayList<Pair<OdinId?, OdinId>>()

    private fun sharerRecord(sender: OdinId?, sharer: OdinId): String = OdinSystemSerializer.serialize(
        ServerFile(
            fileId = Uuid.random(),
            driveId = PhotobaseProtocol.DriveAlias,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            sharedSecretEncryptedKeyHeader = runBlocking { EncryptedKeyHeader.encryptKeyHeaderAes(KeyHeader.newRandom16(), ByteArray(16) { 9 }, SecureByteArray(photobaseSecret)) },
            fileMetadata = FileMetadata(
                senderOdinId = sender,
                appData = AppFileMetaData(uniqueId = Uuid.random(), fileType = PhotobaseProtocol.SharerFileType, content = """{"odinId":"${sharer.domainName}"}"""),
            ),
            serverMetadata = ServerMetadata(),
        )
    )

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
                val params = decrypted(call).jsonObject["queryParams"]!!.jsonObject
                val asked = params["clientUniqueIdAtLeastOne"]?.jsonArray?.single()?.jsonPrimitive?.content
                val albumsAsked = params["fileType"]?.jsonArray?.any { it.jsonPrimitive.int == PhotobaseProtocol.AlbumFileType } == true
                val sharersAsked = params["fileType"]?.jsonArray?.any { it.jsonPrimitive.int == PhotobaseProtocol.SharerFileType } == true
                // One album by id, every album with rows, or (a photo query) nothing.
                val records = when {
                    asked != null && Uuid.parse(asked) in missingAlbums -> emptyList()
                    asked != null -> listOf(albumRecord(Uuid.parse(asked)))
                    albumsAsked -> albumRows.keys.map(::albumRecord)
                    sharersAsked -> sharerRecords.map { (sender, sharer) -> sharerRecord(sender, sharer) }
                    else -> emptyList()
                }
                """{"searchResults":${records.joinToString(prefix = "[", postfix = "]")},"hasMoreRows":false}"""
            }
            path.endsWith("/share-key/unsealed-members") -> "[]"
            path.endsWith("/share-key") && request.method == HttpMethod.Get -> """{"appKeyLockedShareKey":${storedShareKey?.let { "\"$it\"" } ?: "null"}}"""
            path.endsWith("/share-key") -> "{}".also { storedShareKey = decrypted(call).jsonObject["appKeyLockedShareKey"]!!.jsonPrimitive.content }
            path.endsWith("/shares") -> "{}"
            path.endsWith("/connections/status") -> {
                // The query string goes encrypted, as ss.
                val asked = request.url.parameters["ss"]?.let { CryptoHelper.decryptContentAsString(it, appSecret).substringAfter("odinId=") }
                statuses[asked] ?: samsConnection
            }
            // A collaborative drive can't take shares; everyone else has no Photos drive yet.
            // A server that's down for now.
            path.endsWith("/public-key") && "/peer/${bilbo.domainName}/" in path -> return@MockEngine respond("{}", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
            path.endsWith("/public-key") && "/peer/${merry.domainName}/" in path -> """{"publicKeyJwk":"{}","isZeroKnowledge":false}"""
            path.endsWith("/public-key") -> return@MockEngine respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            path.endsWith("/circles/create") -> {
                val slug = decrypted(call).jsonObject["slug"]?.jsonPrimitive?.content.orEmpty()
                created += slug
                when {
                    slug == PhotobaseProtocol.CircleSlug -> "\"$photosCircle\""
                    slug == PhotobaseProtocol.EditorsCircleSlug -> "\"$editorsCircle\""
                    slug.startsWith("g") -> "\"$newGroupCircle\""
                    else -> "\"$individualCircle\""
                }
            }
            path.endsWith("/circles/with-members") -> photosCircleGrants?.let { permission ->
                """[{"circle":{"id":"${photosCircle.toHexString()}","name":"Photos","driveGrants":[{"permissionedDrive":{"drive":{"alias":"${PhotobaseProtocol.DriveAlias.toHexString()}","type":"x"},"permission":"$permission"}}]},"members":[]}]"""
            } ?: "[]"
            path.endsWith("/circles/add-many") -> {
                val asked = decrypted(call).jsonObject
                val ids = asked["odinIds"]!!.jsonArray.map { it.jsonPrimitive.content }
                enrolled += asked["circleId"]!!.jsonPrimitive.content to ids
                ids.joinToString(prefix = """{"outcomes":[""", postfix = "]}") { """{"odinId":"$it","kind":"${kinds[it] ?: addManyKind}"}""" }
            }
            path.endsWith("/circles/revoke") -> {
                val who = decrypted(call).jsonObject["odinId"]!!.jsonPrimitive.content
                if (who in revokeFails) return@MockEngine respond("{}", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                "true".also { revoked += who }
            }
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

    private val recordUpdates = CopyOnWriteArrayList<Uuid?>()
    // An album's record as stored here, for an Add share's write of who may add.
    private val record = PhotobaseRecord(
        { _, uniqueId -> albumHeader(uniqueId) },
        { error("not used") },
        { recordUpdates += it.metadata.appData.uniqueId },
    ) { _, _ -> error("not used") }

    private fun albumHeader(albumId: Uuid) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = PhotobaseProtocol.DriveAlias,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader.newRandom16(),
        fileMetadata = FileMetadata(
            appData = AppFileMetaData(uniqueId = albumId, fileType = PhotobaseProtocol.AlbumFileType, content = """{"name":"album"}"""),
            versionTag = Uuid.random(),
        ),
        serverMetadata = ServerMetadata(),
    )
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
    fun aNewGroupIsntMadeWhenThePhotosCircleCantCarryAShare() = runTest {
        samsConnection = samsConnection.replace("\"rku\":false", "\"rku\":false,\"reviewedAt\":1")
        addManyKind = "enrolled"
        photosCircleGrants = "read"

        assertFailsWith<PhotoSharingException.CircleTooNarrow> {
            sharing.shareWithNewGroup(trip, "Trip", listOf(OdinId("sam.test")), PhotoAlbumAccess.View, expiresAt = null)
        }

        assertTrue(created.none { it.startsWith("g") }, "only the owner console widens it, so each retry would make and delete one: $created")
        assertTrue(calls.none { it.path.endsWith("/shares") || it.path.endsWith("/circles/delete") }, calls.map { it.path }.toString())
    }

    @Test
    fun aNewGroupWhoseShareFailsBeforeAnythingIsWrittenGoesAgain() = runTest {
        reviewed()
        addManyKind = "enrolled"
        missingAlbums += trip

        assertFailsWith<IllegalStateException> {
            sharing.shareWithNewGroup(trip, "Trip", listOf(sam), PhotoAlbumAccess.View, expiresAt = null)
        }

        assertTrue(calls.any { it.path.endsWith("/circles/delete") }, "a retry would otherwise make a second Trip")
        assertTrue(calls.none { it.path.endsWith("/shares") }, calls.map { it.path }.toString())
    }

    private fun reviewed() {
        samsConnection = samsConnection.replace("\"rku\":false", "\"rku\":false,\"reviewedAt\":1")
    }

    private fun addManyTo(circle: Uuid): List<List<String>> = enrolled.filter { it.first.replace("-", "") == circle.toHexString() }.map { it.second }

    @Test
    fun aGroupShareChecksNoConnectionsAndLeavesOutOneWhoseHomebaseCantTakeShares() = runTest {
        val group = AlbumAudience.Group(groupCircle, "Trip", listOf(merry, sam))

        val shared = sharing.share(trip, group, PhotoAlbumAccess.View, expiresAt = null)

        assertEquals(listOf(merry), shared.refused)
        assertEquals(trip, shared.albumId)
        assertTrue(calls.none { it.path.endsWith("/connections/status") }, "a group's members were checked as they joined")
        val rows = calls.filter { it.path.endsWith("/shares") && it.request.method != HttpMethod.Delete }
            .flatMap { decrypted(it).jsonObject["shares"]!!.jsonArray }.map { it.jsonObject["circleId"]!!.jsonPrimitive.content }
        assertTrue(rows.isNotEmpty() && rows.all { it.replace("-", "") == groupCircle.toHexString() }, rows.toString())
        assertEquals(listOf(listOf(sam.domainName)), addManyTo(photosCircle), "only the ones it can reach join the recipients")
    }

    @Test
    fun anAddShareWithAGroupMakesEditorsOfOnlyTheOnesItReached() = runTest {
        val group = AlbumAudience.Group(groupCircle, "Trip", listOf(merry, sam))

        sharing.share(trip, group, PhotoAlbumAccess.Add, expiresAt = null)

        assertEquals(listOf(listOf(sam.domainName)), addManyTo(editorsCircle), "merry was left out, so gets no Write")
    }

    @Test
    fun aGroupEditSaysWhoJoinedWhoIsPendingWhoWasRefusedAndRemovesThePendingUnticked() = runTest {
        reviewed()
        kinds[rosie.domainName] = "enrolled"
        kinds[gandalf.domainName] = "deposited"
        val group = AlbumAudience.Group(groupCircle, "Trip", listOf(sam), pending = listOf(pippin))

        val edit = sharing.setMembers(group, listOf(sam, rosie, gandalf, merry))

        assertEquals(listOf(rosie), edit.joined)
        assertEquals(listOf(gandalf), edit.pending)
        assertEquals(listOf(merry), edit.refused)
        assertEquals(listOf(pippin), edit.removed)
        assertEquals(listOf(pippin.domainName), revoked, "revoking takes a deposited grant too")
    }

    @Test
    fun aNewGroupsPendingMembersAreNamedAsPendingAndKeptOutOfItsMembers() = runTest {
        reviewed()
        kinds[rosie.domainName] = "enrolled"
        kinds[gandalf.domainName] = "deposited"

        val (group, shared) = sharing.shareWithNewGroup(trip, "Trip", listOf(rosie, gandalf), PhotoAlbumAccess.View, expiresAt = null)

        assertEquals(listOf(rosie), group.members)
        assertEquals(listOf(gandalf), group.pending)
        assertEquals(listOf(gandalf), shared.pending)
    }

    @Test
    fun aNewGroupRefusesOneWhoseHomebaseCantTakeSharesBeforeItsCircleIsMade() = runTest {
        reviewed()

        assertFailsWith<PhotoSharingException.RecipientNotAdded> {
            sharing.shareWithNewGroup(trip, "Trip", listOf(merry), PhotoAlbumAccess.View, expiresAt = null)
        }

        assertTrue(created.none { it.startsWith("g") }, created.toString())
    }

    @Test
    fun theAlbumsAGroupReachesEachCarryTheirOwnId() = runTest {
        albumRows[trip] = listOf(groupCircle)
        albumRows[pets] = listOf(groupCircle)
        albumRows[Uuid.random()] = listOf(samsCircle)

        val albums = sharing.albumsSharedWith(AlbumAudience.Group(groupCircle, "Trip", listOf(sam)))

        assertEquals(setOf(trip, pets), albums.map { it.first.albumId }.toSet())
    }

    @Test
    fun aSharerKeptByThisIdentityIsFoundWhetherTheServerNamesItAsSenderOrNoOne() = runTest {
        sharerRecords += frodo to sam
        sharerRecords += null to rosie
        sharerRecords += OdinId("eve.test") to gandalf

        assertEquals(listOf(sam, rosie), sharing.sharers(), "eve's record names gandalf, and isn't this identity's to keep")
    }

    @Test
    fun aGroupEditStillRemovesWhenSomeoneAddedCantJoinYetAndNamesThem() = runTest {
        reviewed()
        statuses[rosie.domainName] = samsConnection.replace(",\"reviewedAt\":1", "").replace("sam.test", rosie.domainName)
        statuses[merry.domainName] = samsConnection.replace("\"connected\"", "\"none\"").replace("sam.test", merry.domainName)
        val group = AlbumAudience.Group(groupCircle, "Trip", listOf(sam), pending = listOf(pippin))

        val edit = sharing.setMembers(group, listOf(sam, rosie, merry))

        assertEquals(listOf(pippin), edit.removed, "the removal went though the additions couldn't")
        assertEquals(listOf(rosie), edit.notReviewed)
        assertEquals(listOf(merry), edit.notConnected)
        assertEquals(emptyList(), edit.joined)
        assertEquals(listOf(pippin.domainName), revoked)
    }

    @Test
    fun aGroupsPendingMembersJoinTheRecipientsCircleWhenItsShared() = runTest {
        val group = AlbumAudience.Group(groupCircle, "Trip", listOf(sam), pending = listOf(pippin))

        sharing.share(trip, group, PhotoAlbumAccess.View, expiresAt = null)

        assertEquals(listOf(listOf(sam.domainName, pippin.domainName)), addManyTo(photosCircle))
    }

    @Test
    fun aGroupEditGoesOnWhenOneAddedsServerIsDown() = runTest {
        reviewed()
        kinds[bilbo.domainName] = "enrolled"
        val group = AlbumAudience.Group(groupCircle, "Trip", listOf(sam), pending = listOf(pippin))

        val edit = sharing.setMembers(group, listOf(sam, bilbo))

        assertEquals(listOf(pippin), edit.removed)
        assertEquals(listOf(bilbo), edit.joined, "their key is sealed by the upkeep once their server answers")
    }

    @Test
    fun aGroupEditWhoseRemovalFailsAddsNoOne() = runTest {
        reviewed()
        kinds[rosie.domainName] = "enrolled"
        revokeFails += pippin.domainName
        val group = AlbumAudience.Group(groupCircle, "Trip", listOf(sam), pending = listOf(pippin))

        assertFails { sharing.setMembers(group, listOf(sam, rosie)) }

        assertEquals(emptyList(), addManyTo(groupCircle), "no one joins to be left in, and untold, by a failed edit")
    }

    @Test
    fun aNewGroupWithOneWhoseServerIsDownIsMadeAllTheSame() = runTest {
        reviewed()
        kinds[bilbo.domainName] = "enrolled"
        kinds[sam.domainName] = "enrolled"

        val (group, _) = sharing.shareWithNewGroup(trip, "Trip", listOf(sam, bilbo), PhotoAlbumAccess.View, expiresAt = null)

        assertEquals(listOf(bilbo, sam), group.members)
    }

    @Test
    fun anEditThatWouldLeaveAGroupWithNoOneKeepsItsMembers() = runTest {
        reviewed()
        statuses[rosie.domainName] = samsConnection.replace(",\"reviewedAt\":1", "").replace("sam.test", rosie.domainName)
        val group = AlbumAudience.Group(groupCircle, "Trip", listOf(sam))

        val edit = sharing.setMembers(group, listOf(rosie))

        assertEquals(emptyList(), edit.removed, "rosie can't join yet, so sam stays")
        assertEquals(emptyList(), revoked)
        assertEquals(listOf(rosie), edit.notReviewed)
    }

    @Test
    fun anEditReplacingEveryoneAddsBeforeItRemoves() = runTest {
        reviewed()
        kinds[rosie.domainName] = "enrolled"
        val group = AlbumAudience.Group(groupCircle, "Trip", listOf(sam))

        val edit = sharing.setMembers(group, listOf(rosie))

        assertEquals(listOf(sam), edit.removed)
        assertEquals(listOf(rosie), edit.joined)
        val addedAt = calls.indexOfFirst { it.path.endsWith("/circles/add-many") && decrypted(it).jsonObject["circleId"]!!.jsonPrimitive.content.replace("-", "") == groupCircle.toHexString() }
        val removedAt = calls.indexOfFirst { it.path.endsWith("/circles/revoke") }
        assertTrue(addedAt in 0 until removedAt, "added at $addedAt, removed at $removedAt")
    }

    private suspend fun decrypted(call: Call) =
        Json.parseToJsonElement(CryptoHelper.decryptContentAsString(call.body.decodeToString(), if (call.asPhotobase) photobaseSecret else appSecret))
}
