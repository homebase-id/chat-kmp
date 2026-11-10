package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.UnauthorizedException
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.CircleShareKeyProvider
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.crypto.ShareKeys
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionsSerializer
import id.homebase.core.config.filesyncAppRegistration
import id.homebase.core.filesync.FilesyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import id.homebase.api.youauth.ZkKeyStore
import id.homebase.api.client.drives.shares.ShareRow
import id.homebase.api.youauth.DrivePermissionSet
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FilesyncSharingTest {

    private val frodo = OdinId("frodo.test")
    private val sam = OdinId("sam.test")
    private val toSam = FilesyncAudience.Person(sam)
    private val appSecret = ByteArray(16) { 1 }
    private val filesyncSecret = ByteArray(16) { 2 }
    private val filesyncAppKey = ByteArray(16) { 3 }
    private val recipientsCircle = Uuid.random()
    private val samsCircle = Uuid.random()
    private val editorsCircle = Uuid.random()
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

    private val sharedPages = mutableListOf<String>()

    private fun sharedFile(
        name: String,
        sharedWith: Uuid?,
        permissions: String,
        uniqueId: Uuid = Uuid.random(),
        fileId: Uuid = Uuid.random(),
        expiresAt: Long? = null,
    ): String {
        val key = KeyHeader.newRandom16()
        val file = ServerFile(
            fileId = fileId,
            driveId = FilesyncProtocol.DriveAlias,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            sharedSecretEncryptedKeyHeader = runBlocking { EncryptedKeyHeader.encryptKeyHeaderAes(key, ByteArray(16) { 9 }, SecureByteArray(filesyncSecret)) },
            fileMetadata = FileMetadata(
                isEncrypted = true,
                appData = AppFileMetaData(
                    uniqueId = uniqueId,
                    groupId = FilesyncProtocol.RootNodeId,
                    fileType = FilesyncProtocol.FileFileType,
                    content = Base64.encode(runBlocking { key.encryptDataAes("""{"v":1,"name":"$name","size":5}""".encodeToByteArray()) }),
                ),
                payloads = listOf(PayloadDescriptor(key = FilesyncProtocol.PayloadKey, iv = Base64Bytes(ByteArray(16)), hash = PayloadHash("blake3", Base64Bytes(byteArrayOf(1)), Base64Bytes(byteArrayOf(2))))),
            ),
            serverMetadata = ServerMetadata(),
        )
        val shares = if (sharedWith == null) "[]" else """[{"circleId":"$sharedWith","permissions":"$permissions","expiresAt":${expiresAt ?: "null"},"created":1,"modified":1}]"""
        return OdinSystemSerializer.serialize(file).removeSuffix("}") + ""","shares":$shares}"""
    }

    private val calls = mutableListOf<Call>()
    private var samReady = true
    private var notReady = emptySet<String>()
    private var samGrantsWrite = true
    private var samHasFilesync = true
    private var frodoReviewedUs = true
    private var downPeers = emptySet<String>()
    private var collaborative = false
    private var filesyncRefused = false
    private var storedShareKey: String? = null
    private val merry = OdinId("merry.test")
    private val groupCircle = Uuid.random()
    private var unsealed = listOf("sam.test")
    // What a sharer sealed to this identity, by circle.
    private val sealedForUs = mutableMapOf<Uuid, ByteArray>()
    private var refusedByAddMany = emptyList<String>()
    private var depositedByAddMany = emptyList<String>()
    private var circles = """[{"circle":{"id":"$samsCircle","name":"sam.test","designation":"individual","appId":"${Uuid.parseHex(filesyncAppRegistration.appId)}"},"members":["sam.test"]}]"""

    private val engine = MockEngine { request ->
        val call = Call(request, request.body.toByteArray()).also { calls += it }
        val path = call.path
        val body = when {
            path.endsWith("/apps/filesync/drives/filesync/public-key") ->
                if (!samGrantsWrite) return@MockEngine respond("""{"title":"Write access is required"}""", io.ktor.http.HttpStatusCode.Forbidden, json)
                else if (!samHasFilesync) return@MockEngine respond("""{"title":"Not found"}""", io.ktor.http.HttpStatusCode.NotFound, json)
                else if (downPeers.any { "/peer/$it/" in path }) return@MockEngine respond("""{"title":"down"}""", io.ktor.http.HttpStatusCode.ServiceUnavailable, json)
                else if (collaborative) """{"publicKeyJwk":${Json.encodeToString(samsJwk)},"publicKeyCrc32":0,"isZeroKnowledge":true,"sharingModel":"collaborative"}"""
                else if (samReady && notReady.none { "/peer/$it/" in path }) """{"publicKeyJwk":${Json.encodeToString(samsJwk)},"publicKeyCrc32":0,"isZeroKnowledge":true,"sharingModel":"selectiveShare"}"""
                else return@MockEngine respond("""{"title":"no such drive","errorCode":"invalidRequest"}""", io.ktor.http.HttpStatusCode.BadRequest, json)
            path.endsWith("/connections/circles/create") ->
                when (decrypted(call, filesyncSecret).jsonObject["slug"]!!.jsonPrimitive.content) {
                    FilesyncProtocol.CircleSlug -> "\"$recipientsCircle\""
                    FilesyncSharing.individualSlug(sam) -> "\"$samsCircle\""
                    FilesyncProtocol.EditorsCircleSlug -> "\"$editorsCircle\""
                    else -> "\"$groupCircle\""
                }
            path.endsWith("/connections/circles/add-many") ->
                """{"enrolled":1,"deposited":0,"skipped":0,"outcomes":[${(refusedByAddMany.map { it to "skipped" } + depositedByAddMany.map { it to "deposited" }).joinToString { (id, kind) -> """{"odinId":"$id","kind":"$kind"}""" }}]}"""
            "/connections/circles/delete" in path || path.endsWith("/connections/circles/revoke") -> "{}"
            "/peer/" in path && path.endsWith("/share-key") ->
                if (frodoReviewedUs) """{"sealedKey":${sealedForUs.entries.firstOrNull { "/circles/${it.key}/" in path }?.let { "\"${Base64.encode(it.value)}\"" } ?: "null"}}"""
                else return@MockEngine respond("""{"title":"Not reviewed","errorCode":"notReviewedByOwner"}""", io.ktor.http.HttpStatusCode.Forbidden, json)
            path.endsWith("/share-key/unsealed-members") -> unsealed.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
            path.endsWith("/share-key") && request.method == HttpMethod.Get -> """{"appKeyLockedShareKey":${storedShareKey?.let { "\"$it\"" } ?: "null"}}"""
            path.endsWith("/share-key") -> "{}".also { storedShareKey = decrypted(call, filesyncSecret).jsonObject["appKeyLockedShareKey"]!!.jsonPrimitive.content }
            path.endsWith("/share-key/members") || path.endsWith("/shares") -> "{}"
            "/shares/" in path && request.method == HttpMethod.Delete -> "{}"
            path.endsWith("/circles/with-members") ->
                if (filesyncRefused && call.asFilesync) return@MockEngine respond("""{"title":"Unauthorized"}""", io.ktor.http.HttpStatusCode.Unauthorized, json)
                else circles
            path.endsWith("/query-batch") -> sharedPages.removeFirstOrNull() ?: """{"searchResults":[],"hasMoreRows":false}"""
            path.endsWith("/files") -> """{"fileId":"${Uuid.random()}","driveId":"${SystemDriveConstants.transientTempDrive.alias}","newVersionTag":"${Uuid.random()}"}"""
            else -> error("unexpected ${request.method} $path")
        }
        respond(body, headers = json)
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val appCredentials = CredentialsManager()
    private val http = HttpClient(engine)
    private val upload = DriveUploadProvider(http, appCredentials, NoFiles)

    private val sharing = FilesyncSharing(
        http, appCredentials,
        ConnectionNetworkProvider(http, appCredentials), CircleShareKeyProvider(http, appCredentials),
        PeerDriveQueryProvider(http, appCredentials), DriveQueryProvider(http, appCredentials),
        upload,
    )

    // Never the machine's real secure storage: a dev app's Filesync sign-in lives there.
    private val stored = mutableMapOf<String, String>()

    @BeforeTest
    fun keepTheSignInInMemory() {
        FilesyncSession.store = object : FilesyncSession.Store {
            override fun get(key: String) = stored[key]
            override fun put(key: String, value: String) { stored[key] = value }
            override fun remove(key: String) { stored.remove(key) }
        }
    }

    @BeforeTest
    fun signIn() = runBlocking {
        appCredentials.setActiveCredentials(ApiCredentials.create(frodo, "app-token", SecureByteArray(appSecret)))
        FilesyncSession.use(ApiCredentials.create(frodo, "filesync-token", SecureByteArray(filesyncSecret)), filesyncAppKey)
    }

    @AfterTest
    fun signOut() = runBlocking { FilesyncSession.forget() }

    @Test
    fun sharingAFileGivesSamACircleOfHisOwnWhoseKeyHeAloneCanOpenAndWritesNothingOnHisDrive() = runTest {
        assertEquals(FilesyncShared(samsCircle, waiting = emptyList()), sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null))

        // Circles, their keys and shares are written as Filesync; the public key and the circle list are read as the app
        assertTrue(calls.filter { ("/connections/" in it.path && !it.path.endsWith("/with-members")) || it.path.endsWith("/shares") }.all { it.asFilesync })
        assertTrue(calls.filter { it.path.endsWith("/public-key") }.none { it.asFilesync })
        assertTrue(calls.none { it.path.endsWith("/files") }, "Sam's app keeps his marker, once he opens the chat message")

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
        assertContentEquals(file.keyHeader.aesKey.toByteArray(), ShareKeys.openFileKey(circleKey, lockedFileKey, FilesyncProtocol.DriveAlias, file.fileId, null, samsCircle))
    }


    private val hiking get() = FilesyncAudience.Group(groupCircle, "Hiking", listOf(sam))
    private val pippin = OdinId("pippin.test")

    private suspend fun Call.addedTo(): Pair<String, List<String>> = decrypted(this, filesyncSecret).jsonObject.let { body ->
        body["circleId"]!!.jsonPrimitive.content to body["odinIds"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun addingToAGroupLetsTheNewMemberInAndNamesThemForTelling() = runTest {
        unsealed = listOf("merry.test")

        assertEquals(listOf(merry), sharing.setGroupMembers(hiking, listOf(sam, merry)))

        val added = calls.filter { it.path.endsWith("/add-many") }.map { it.addedTo() }
        assertEquals(listOf(recipientsCircle.toString() to listOf("merry.test"), groupCircle.toString() to listOf("merry.test")), added)
        val sealed = decrypted(calls.single { it.path.endsWith("/share-key/members") }, filesyncSecret).jsonObject["members"]!!.jsonArray
        assertEquals(listOf("merry.test"), sealed.map { it.jsonObject["odinId"]!!.jsonPrimitive.content })
        assertTrue(calls.none { it.path.endsWith("/revoke") })
    }

    @Test
    fun removingFromAGroupRevokesOnlyThem() = runTest {
        sharing.setGroupMembers(hiking.copy(members = listOf(merry, sam)), listOf(merry))

        val revoked = calls.filter { it.path.endsWith("/revoke") }.map { decrypted(it, filesyncSecret).jsonObject }
        assertEquals(listOf("sam.test"), revoked.map { it["odinId"]!!.jsonPrimitive.content })
        assertEquals(listOf(groupCircle.toString()), revoked.map { it["circleId"]!!.jsonPrimitive.content })
        assertTrue(calls.none { it.path.endsWith("/add-many") || it.path.endsWith("/files") })
    }

    @Test
    fun someoneAddedWithoutFilesyncJoinsAndOnlyTheReadyAreSealedTo() = runTest {
        unsealed = listOf("merry.test", "pippin.test")
        notReady = setOf("pippin.test")

        assertEquals(listOf(merry, pippin), sharing.setGroupMembers(hiking, listOf(sam, merry, pippin)))

        assertTrue(calls.filter { it.path.endsWith("/add-many") }.all { it.addedTo().second == listOf("merry.test", "pippin.test") })
        val sealed = decrypted(calls.single { it.path.endsWith("/share-key/members") }, filesyncSecret).jsonObject["members"]!!.jsonArray
        assertEquals(listOf("merry.test"), sealed.map { it.jsonObject["odinId"]!!.jsonPrimitive.content })
    }

    @Test
    fun someoneTheGroupRefusesIsNamed() = runTest {
        refusedByAddMany = listOf("pippin.test")

        val e = assertFailsWith<FilesyncSharingException.RecipientNotAdded> { sharing.setGroupMembers(hiking, listOf(sam, pippin)) }

        assertEquals(listOf(pippin), e.recipients)
    }

    @Test
    fun aSecondShareReusesTheCirclesKey() = runTest {
        sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null)
        sharing.share(listOf(file), toSam, FilesyncAccess.Edit, expiresAt = null)

        assertEquals(1, calls.count { it.path.endsWith("/share-key") && it.request.method == HttpMethod.Put })
    }

    @Test
    fun anEditShareLetsSamWriteThroughTheEditorsCircleAndAViewShareDoesnt() = runTest {
        sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null)
        assertTrue(calls.filter { it.path.endsWith("/add-many") }.none { it.addedTo().first == editorsCircle.toString() })

        sharing.share(listOf(file), toSam, FilesyncAccess.Edit, expiresAt = null)

        assertEquals(editorsCircle.toString() to listOf("sam.test"), calls.filter { it.path.endsWith("/add-many") }.last().addedTo())
        val created = calls.filter { it.path.endsWith("/circles/create") }.map { decrypted(it, filesyncSecret).jsonObject }
            .single { it["slug"]!!.jsonPrimitive.content == FilesyncProtocol.EditorsCircleSlug }
        val granted = created["driveGrants"]!!.jsonArray.single().jsonObject["permissionedDrive"]!!.jsonObject["permission"]!!
        assertEquals(setOf(DrivePermission.Write), Json.decodeFromJsonElement(DrivePermissionsSerializer, granted).values.toSet())
        assertTrue(created["grantOn"].let { it == null || it.jsonPrimitive.content == "none" })
    }

    private fun editorsAre(vararg members: String) {
        circles = """[
            {"circle":{"id":"$samsCircle","name":"sam.test","designation":"individual","appId":"$appId"},"members":["sam.test"]},
            {"circle":{"id":"$groupCircle","name":"Hiking","slug":"g0123456789abc","appId":"$appId"},"members":["merry.test","sam.test"]},
            {"circle":{"id":"$editorsCircle","name":"Filesync editors","slug":"${FilesyncProtocol.EditorsCircleSlug}","appId":"$appId"},"members":[${members.joinToString { "\"$it\"" }}]}
        ]"""
    }

    @Test
    fun theEditorsAreThePeopleALiveEditShareReachesThroughAGroupToo() = runTest {
        editorsAre("sam.test", "pippin.test")
        sharedPages += """{"searchResults":[${sharedFile("plan.pdf", samsCircle, "read")}],"cursorState":"next","hasMoreRows":true}"""
        sharedPages += """{"searchResults":[${sharedFile("map.png", groupCircle, "readWrite")}],"hasMoreRows":false}"""

        sharing.syncEditors()

        val asked = decrypted(calls.first { it.path.endsWith("/query-batch") }, filesyncSecret).jsonObject["queryParams"]!!.jsonObject
        assertEquals(setOf(samsCircle.toString(), groupCircle.toString()), asked["sharedWith"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(listOf(editorsCircle.toString() to listOf("merry.test")), calls.filter { it.path.endsWith("/add-many") }.map { it.addedTo() })
        val revoked = calls.filter { it.path.endsWith("/revoke") }.map { decrypted(it, filesyncSecret).jsonObject }
        assertEquals(listOf(editorsCircle.toString() to "pippin.test"), revoked.map { it["circleId"]!!.jsonPrimitive.content to it["odinId"]!!.jsonPrimitive.content })
    }

    @Test
    fun withNoEditSharesLeftTheEditorsCircleEmptiesAndNoneIsMadeWhenThereWasNone() = runTest {
        editorsAre("sam.test")
        sharedPages += """{"searchResults":[${sharedFile("plan.pdf", samsCircle, "read")}],"hasMoreRows":false}"""
        sharing.syncEditors()
        assertEquals(listOf("sam.test"), calls.filter { it.path.endsWith("/revoke") }.map { decrypted(it, filesyncSecret).jsonObject["odinId"]!!.jsonPrimitive.content })

        calls.clear()
        editorsAre()
        sharedPages += """{"searchResults":[${sharedFile("plan.pdf", samsCircle, "read")}],"hasMoreRows":false}"""
        sharing.syncEditors()
        assertTrue(calls.none { it.path.endsWith("/circles/create") || it.path.endsWith("/add-many") || it.path.endsWith("/revoke") })
    }

    @Test
    fun aViewShareWithNoEditorsReadsNoMore() = runTest {
        sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null)

        assertTrue(calls.none { it.path.endsWith("/query-batch") })
    }

    @Test
    fun anEditorStillJoiningIsNeitherAddedAgainNorLeftIn() = runTest {
        circles = """[
            {"circle":{"id":"$samsCircle","name":"sam.test","designation":"individual","appId":"$appId"},"members":["sam.test"]},
            {"circle":{"id":"$editorsCircle","name":"Filesync editors","slug":"${FilesyncProtocol.EditorsCircleSlug}","appId":"$appId"},"members":[],"pendingMembers":[{"odinId":"sam.test"}]}
        ]"""
        sharedPages += """{"searchResults":[${sharedFile("plan.pdf", samsCircle, "readWrite")}],"hasMoreRows":false}"""
        sharing.syncEditors()
        assertTrue(calls.none { it.path.endsWith("/add-many") || it.path.endsWith("/revoke") })

        sharedPages += """{"searchResults":[${sharedFile("plan.pdf", samsCircle, "read")}],"hasMoreRows":false}"""
        sharing.syncEditors()
        assertEquals(listOf("sam.test"), calls.filter { it.path.endsWith("/revoke") }.map { decrypted(it, filesyncSecret).jsonObject["odinId"]!!.jsonPrimitive.content })
    }

    @Test
    fun aShareCarriesItsExpiry() = runTest {
        sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = 1_900_000_000_000)

        val share = decrypted(calls.single { it.path.endsWith("/shares") }, filesyncSecret).jsonObject["shares"]!!.jsonArray.single().jsonObject
        assertEquals("1900000000000", share["expiresAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun someoneWithoutAFilesyncDriveIsSharedWithAndWaitsForTheKey() = runTest {
        samHasFilesync = false

        assertEquals(FilesyncShared(samsCircle, waiting = listOf(sam)), sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null))
        assertEquals(1, calls.count { it.path.endsWith("/shares") })
        assertTrue(calls.none { it.path.endsWith("/share-key/members") })
    }

    @Test
    fun comingOnlineSealsToWhoeverIsReadyThenWaitsBeforeAskingAgain() = runTest {
        val upkeep = FilesyncShareUpkeep(sharing, appCredentials, MutableStateFlow(true), EventBus(), backgroundScope)
        sharedPages += """{"searchResults":[],"hasMoreRows":false}"""

        upkeep.upkeep()
        assertEquals(listOf("sam.test"), decrypted(calls.single { it.path.endsWith("/share-key/members") }, filesyncSecret).jsonObject["members"]!!.jsonArray.map { it.jsonObject["odinId"]!!.jsonPrimitive.content })

        calls.clear()
        upkeep.upkeep()
        assertTrue(calls.isEmpty(), "a phone back in the foreground a minute later asks nothing")
    }

    @Test
    fun aDriveWhoseKeyOthersHoldIsRefusedRatherThanLeftWaiting() = runTest {
        collaborative = true

        val e = assertFailsWith<FilesyncSharingException.RecipientNotAdded> { sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null) }
        assertEquals(listOf(sam), e.recipients)
        assertTrue(calls.none { it.path.endsWith("/shares") })
    }

    @Test
    fun oneUnreachableServerLeavesOnlyItsPersonWaitingForTheKey() = runTest {
        circles = """[{"circle":{"id":"$groupCircle","name":"Hiking","slug":"g0123456789abc","appId":"$appId"},"members":["merry.test","sam.test"]}]"""
        unsealed = listOf("merry.test", "sam.test")
        downPeers = setOf("merry.test")

        sharing.sealPending()

        val sealed = decrypted(calls.single { it.path.endsWith("/share-key/members") }, filesyncSecret).jsonObject["members"]!!.jsonArray
        assertEquals(listOf("sam.test"), sealed.map { it.jsonObject["odinId"]!!.jsonPrimitive.content })
    }

    @Test
    fun aRefusalOfTheAppsOwnTokenLeavesFilesyncSignedIn() = runTest {
        assertEquals(false, sharing.signOutIfRefused(UnauthorizedException()))
        assertTrue(FilesyncSession.signedIn.value)

        filesyncRefused = true
        assertEquals(true, sharing.signOutIfRefused(UnauthorizedException()))
        assertEquals(false, FilesyncSession.signedIn.value)
    }

    @Test
    fun anOlderServersRefusalsAlsoMeanNoDriveYet() = runTest {
        samReady = false
        assertEquals(listOf(sam), sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null).waiting)
        samReady = true
        samGrantsWrite = false
        assertEquals(listOf(sam), sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null).waiting)
    }

    @Test
    fun onceSamHasADriveTheKeyIsSealedToHimNextTime() = runTest {
        samHasFilesync = false
        sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null)
        assertTrue(calls.none { it.path.endsWith("/share-key/members") })

        samHasFilesync = true
        sharing.sealPending()

        val sealed = decrypted(calls.single { it.path.endsWith("/share-key/members") }, filesyncSecret).jsonObject
        assertEquals(listOf("sam.test"), sealed["members"]!!.jsonArray.map { it.jsonObject["odinId"]!!.jsonPrimitive.content })
    }

    @Test
    fun theFilesSharedWithSamComeFromTheFilterPageByPageWithTheAccessTheirShareGives() = runTest {
        sharedPages += """{"searchResults":[${sharedFile("plan.pdf", samsCircle, "readWrite")}],"cursorState":"next","hasMoreRows":true}"""
        sharedPages += """{"searchResults":[${sharedFile("notes.txt", samsCircle, "read")}, ${sharedFile("other.txt", recipientsCircle, "read")}],"hasMoreRows":false}"""

        val shared = sharing.sharedWithAudience(toSam)

        assertEquals(listOf("plan.pdf" to FilesyncAccess.Edit, "notes.txt" to FilesyncAccess.View), shared.map { it.node.name to it.access })
        val queries = calls.filter { it.path.endsWith("/query-batch") }
        assertTrue(queries.all { it.asFilesync })
        val asked = queries.map { decrypted(it, filesyncSecret).jsonObject }
        assertTrue(asked.all { q -> q["queryParams"]!!.jsonObject["sharedWith"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf(samsCircle.toString()) })
        assertTrue(asked.all { it["resultOptionsRequest"]!!.jsonObject["includeShares"]!!.jsonPrimitive.content.toBoolean() })
        assertEquals("next", asked[1]["resultOptionsRequest"]!!.jsonObject["cursorState"]!!.jsonPrimitive.content)
        // Shared folders are listed too, and nothing in Recently deleted.
        val params = asked.first()["queryParams"]!!.jsonObject
        assertEquals(setOf(FilesyncProtocol.FolderFileType, FilesyncProtocol.FileFileType), params["fileType"]!!.jsonArray.map { it.jsonPrimitive.int }.toSet())
        assertEquals(FilesyncStatus.Live.archivalStatus.toSet(), params["archivalStatus"]!!.jsonArray.map { it.jsonPrimitive.int }.toSet())
    }

    private fun marker(names: String, sender: OdinId?): String {
        val key = KeyHeader.newRandom16()
        return OdinSystemSerializer.serialize(
            ServerFile(
                fileId = Uuid.random(),
                driveId = FilesyncProtocol.DriveAlias,
                fileState = FileState.Active,
                fileSystemType = FileSystemType.Standard,
                sharedSecretEncryptedKeyHeader = runBlocking { EncryptedKeyHeader.encryptKeyHeaderAes(key, ByteArray(16) { 9 }, SecureByteArray(appSecret)) },
                fileMetadata = FileMetadata(
                    isEncrypted = true,
                    senderOdinId = sender,
                    appData = AppFileMetaData(
                        uniqueId = Uuid.random(),
                        fileType = FilesyncProtocol.MarkerFileType,
                        content = Base64.encode(runBlocking { key.encryptDataAes("""{"odinId":"$names","driveAlias":"${FilesyncProtocol.DriveAlias}"}""".encodeToByteArray()) }),
                    ),
                ),
                serverMetadata = ServerMetadata(),
            )
        )
    }

    @Test
    fun theSharersAreTheMarkersKeptHereAndOnesLeftByOthersOnlyWhenTheyNameTheirSender() = runTest {
        sharedPages += """{"searchResults":[${marker("merry.test", frodo)}, ${marker("sam.test", sam)}, ${marker("pippin.test", sam)}, ${marker("gandalf.test", null)}],"hasMoreRows":false}"""

        assertEquals(listOf(merry, sam, OdinId("gandalf.test")), sharing.sharers())
    }

    @Test
    fun openingAShareKeepsItsSharerOnThisDriveOnce() = runTest {
        sharedPages += """{"searchResults":[],"hasMoreRows":false}"""
        sharing.keepSharer(sam)

        val kept = calls.single { it.path.endsWith("/files") }
        assertEquals("/api/v2/drives/${FilesyncProtocol.DriveAlias}/files", kept.path)
        assertTrue(!kept.asFilesync)
        val parts = multipart(kept.body, kept.request)
        val instructions = Json.parseToJsonElement(parts.getValue("instructions").decodeToString()).jsonObject
        val transferIv = Base64.decode(instructions["transferIv"]!!.jsonPrimitive.content)
        val descriptor = Json.parseToJsonElement(AesCbc.decrypt(parts.getValue("metadata"), appSecret, transferIv).decodeToString()).jsonObject
        val appData = descriptor["fileMetadata"]!!.jsonObject["appData"]!!.jsonObject
        assertEquals(FilesyncSharing.markerId(sam, frodo).toString(), appData["uniqueId"]!!.jsonPrimitive.content)
        assertEquals(FilesyncProtocol.MarkerFileType, appData["fileType"]!!.jsonPrimitive.int)

        calls.clear()
        sharedPages += """{"searchResults":[${marker("sam.test", frodo)}],"hasMoreRows":false}"""
        sharing.keepSharer(sam)
        assertTrue(calls.none { it.path.endsWith("/files") })
    }

    // A file of Frodo's shared with us through [circleId], its key locked with the circle's.
    private fun fromFrodo(circleId: Uuid) = """
        {
          "fileId": "${Uuid.random()}", "driveId": "${FilesyncProtocol.DriveAlias}", "fileState": "active", "fileSystemType": "standard",
          "sharedSecretEncryptedKeyHeader": { "encryptionVersion": 1, "type": "share", "iv": "${Base64.encode(ByteArray(16))}", "encryptedAesKey": "AAAA" },
          "fileMetadata": { "isEncrypted": true, "versionTag": "${Uuid.random()}", "appData": { "uniqueId": "${Uuid.random()}", "fileType": ${FilesyncProtocol.FileFileType}, "content": "AAAA" } },
          "serverMetadata": {},
          "shares": [{ "circleId": "$circleId", "permissions": "read", "created": 1, "modified": 1 }]
        }
    """

    @Test
    fun filesFromAnOwnerWhoHasntReviewedUsSaySoInsteadOfLookingEmpty() = runTest {
        frodoReviewedUs = false
        sharedPages += """{"searchResults":[${fromFrodo(samsCircle)}],"hasMoreRows":false}"""

        val e = assertFailsWith<FilesyncSharingException.NotReviewedByOwner> { sharing.sharedBy(frodo) }
        assertEquals(frodo, e.owner)
    }

    @Test
    fun filesWhoseKeyTheOwnerHasntSealedHereYetSaySo() = runTest {
        sharedPages += """{"searchResults":[${fromFrodo(samsCircle)}],"hasMoreRows":false}"""

        assertFailsWith<FilesyncSharingException.NotSealedYet> { sharing.sharedBy(frodo) }
    }

    @Test
    fun aFileAddedToAFolderSharedWithUsIsLockedForEachOfOurCirclesOnItBoundToTheGlobalTransitIdWePicked() = runTest {
        val stored = mutableMapOf("zk_drive_write_only_keys" to """{"${FilesyncProtocol.DriveAlias}":"${Base64.encode(samsPrivateKey)}"}""")
        ZkKeyStore.storage = object : ZkKeyStore.Storage {
            override fun get(key: String) = stored[key]
            override fun put(key: String, value: String) { stored[key] = value }
            override fun remove(key: String) { stored.remove(key) }
        }
        try {
            val circleKeys = mapOf(samsCircle to ShareKeys.newCircleKey(), groupCircle to ShareKeys.newCircleKey())
            circleKeys.forEach { (circle, key) -> sealedForUs[circle] = ShareKeys.sealCircleKey(key, ShareKeys.pointOfJwk(samsJwk), circle, frodo) }
            val folder = FilesyncHandle(
                Uuid.random(), null, FilesyncProtocol.FolderFileType, KeyHeader.newRandom16(), JsonObject(emptyMap()), null,
                globalTransitId = Uuid.random(),
                shares = listOf(
                    ShareRow(samsCircle, DrivePermissionSet(listOf(DrivePermission.Read, DrivePermission.Write)), expiresAt = 99),
                    ShareRow(groupCircle, DrivePermissionSet(listOf(DrivePermission.Read))),
                    ShareRow(Uuid.random(), DrivePermissionSet(emptyList())),
                ),
            )
            val fileKey = KeyHeader.newRandom16()
            val gtid = Uuid.random()

            val shares = sharing.sharesOfNewFileIn(sam, folder, fileKey, gtid)

            assertEquals(listOf(samsCircle, groupCircle), shares.map { it.circleId }, "a withdrawn row gets nothing")
            assertEquals(listOf(3, 1), shares.map { it.permissions }, "as the folder is shared with each")
            assertEquals(listOf(99L, null), shares.map { it.expiresAt })
            assertTrue(shares.all { it.fileId == Uuid.NIL })
            shares.forEach { share ->
                val opened = ShareKeys.openFileKey(circleKeys.getValue(share.circleId), share.lockedFileKey!!, FilesyncProtocol.DriveAlias, Uuid.random(), gtid, share.circleId)
                assertContentEquals(fileKey.aesKey.toByteArray(), opened)
            }
        } finally {
            ZkKeyStore.clear()
        }
    }

    @Test
    fun loggingOutSignsFilesyncOutAndForgetsTheCircleKeys() = runTest {
        val events = EventBus()
        FilesyncShareUpkeep(sharing, appCredentials, MutableStateFlow(false), events, backgroundScope)
        sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null)

        events.emit(BackendEvent.SessionEnded)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (FilesyncSession.signedIn.value) delay(10) } }
        signIn()
        sharing.share(listOf(file), toSam, FilesyncAccess.View, expiresAt = null)

        assertEquals(2, calls.count { "/connections/circles/" in it.path && it.path.endsWith("/share-key") && it.request.method == HttpMethod.Get })
    }

    @Test
    fun whoeverSharedLastIsAskedForFirst() = runTest {
        sharedPages += """{"searchResults":[],"hasMoreRows":false}"""

        sharing.sharers()

        val options = decrypted(calls.single { it.path.endsWith("/query-batch") }, appSecret).jsonObject["resultOptionsRequest"]!!.jsonObject
        assertEquals("newestFirst", options["ordering"]!!.jsonPrimitive.content)
        assertEquals("anyChangeDate", options["sorting"]!!.jsonPrimitive.content)
    }

    @Test
    fun aNodeMadeInASharedFolderIsSharedLikeIt() = runTest {
        val folder = Uuid.random()
        val node = Uuid.random()
        val newFileId = Uuid.random()
        val circleKey = ShareKeys.newCircleKey()
        storedShareKey = Base64.encode(ShareKeys.lockCircleKey(filesyncAppKey, circleKey, samsCircle))
        sharedPages += """{"searchResults":[${sharedFile("Photos", samsCircle, "readWrite", uniqueId = folder, expiresAt = 1_900_000_000_000)}, ${sharedFile("new.jpg", null, "", uniqueId = node, fileId = newFileId)}],"hasMoreRows":false}"""
        val key = KeyHeader.newRandom16()

        sharing.inherit(folder, node, key)

        val asked = decrypted(calls.single { it.path.endsWith("/query-batch") }, filesyncSecret).jsonObject
        assertEquals(setOf(folder.toString(), node.toString()), asked["queryParams"]!!.jsonObject["clientUniqueIdAtLeastOne"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val share = decrypted(calls.single { it.path.endsWith("/shares") }, filesyncSecret).jsonObject["shares"]!!.jsonArray.single().jsonObject
        assertEquals(newFileId.toString(), share["fileId"]!!.jsonPrimitive.content)
        assertEquals(samsCircle.toString(), share["circleId"]!!.jsonPrimitive.content)
        assertEquals(1 + 2 + 4 + 8, share["permissions"]!!.jsonPrimitive.int)
        assertEquals("1900000000000", share["expiresAt"]!!.jsonPrimitive.content)
        val opened = ShareKeys.openFileKey(circleKey, Base64.decode(share["lockedFileKey"]!!.jsonPrimitive.content), FilesyncProtocol.DriveAlias, newFileId, null, samsCircle)
        assertContentEquals(key.aesKey.toByteArray(), opened)
    }

    @Test
    fun aNodeMadeInAnUnsharedFolderStaysPrivate() = runTest {
        val folder = Uuid.random()
        val node = Uuid.random()
        sharedPages += """{"searchResults":[${sharedFile("Photos", null, "", uniqueId = folder)}, ${sharedFile("new.jpg", null, "", uniqueId = node)}],"hasMoreRows":false}"""

        sharing.inherit(folder, node, KeyHeader.newRandom16())
        sharing.inherit(FilesyncProtocol.RootNodeId, node, KeyHeader.newRandom16())

        assertEquals(1, calls.count { it.path.endsWith("/query-batch") }, "the root is never shared")
        assertTrue(calls.none { it.path.endsWith("/shares") })
    }

    private val oldFolder = Uuid.random()
    private val newFolder = Uuid.random()
    private val moved = FilesyncNode(Uuid.random(), oldFolder, false, "emilie.jpg", modifiedMs = null, sizeBytes = 1)
    private val movedFileId = Uuid.random()
    private val movedHandle = FilesyncHandle(movedFileId, null, FilesyncProtocol.FileFileType, KeyHeader.newRandom16(), JsonObject(emptyMap()), null)

    private fun folders(old: Boolean, new: Boolean) =
        """{"searchResults":[${sharedFile("Old", samsCircle.takeIf { old }, "read", uniqueId = oldFolder)}, ${sharedFile("New", samsCircle.takeIf { new }, "read", uniqueId = newFolder, expiresAt = 1_900_000_000_000)}],"hasMoreRows":false}"""

    private fun movedFile(shared: Boolean) =
        """{"searchResults":[${sharedFile("emilie.jpg", samsCircle.takeIf { shared }, "read", uniqueId = moved.nodeId, fileId = movedFileId)}],"hasMoreRows":false}"""

    private suspend fun planMove() = sharing.planMove(listOf(moved), newFolder) { mapOf(it.nodeId to movedHandle) }

    @Test
    fun stoppingSharingAFolderWithSamReachesWhatsInsideItSharedWithHim() = runTest {
        val handle = { FilesyncHandle(Uuid.random(), null, FilesyncProtocol.FileFileType, KeyHeader.newRandom16(), JsonObject(emptyMap()), null) }
        val folder = Uuid.random() to handle()
        val inside = Uuid.random() to handle()
        val othersOnly = Uuid.random() to handle()
        val unshared = Uuid.random() to handle()
        sharedPages += """{"searchResults":[${sharedFile("Photos", samsCircle, "read", uniqueId = folder.first, fileId = folder.second.fileId)}, ${sharedFile("a.jpg", samsCircle, "read", uniqueId = inside.first, fileId = inside.second.fileId)}, ${sharedFile("b.jpg", recipientsCircle, "read", uniqueId = othersOnly.first, fileId = othersOnly.second.fileId)}, ${sharedFile("c.jpg", null, "", uniqueId = unshared.first, fileId = unshared.second.fileId)}],"hasMoreRows":false}"""

        sharing.withdraw(mapOf(folder, inside, othersOnly, unshared), toSam)

        assertEquals(
            setOf("/files/${folder.second.fileId}/shares/$samsCircle", "/files/${inside.second.fileId}/shares/$samsCircle"),
            calls.filter { it.request.method == HttpMethod.Delete }.map { it.path.substringAfter(FilesyncProtocol.DriveAlias.toString()) }.toSet(),
        )
    }

    @Test
    fun stoppingSamsLastEditShareTakesHisWriteAwayThere() = runTest {
        editorsAre("sam.test")
        val handle = FilesyncHandle(Uuid.random(), null, FilesyncProtocol.FileFileType, KeyHeader.newRandom16(), JsonObject(emptyMap()), null)
        val node = Uuid.random()
        sharedPages += """{"searchResults":[${sharedFile("plan.pdf", samsCircle, "readWrite", uniqueId = node, fileId = handle.fileId)}],"hasMoreRows":false}"""

        sharing.withdraw(mapOf(node to handle), toSam)

        val revoked = calls.filter { it.path.endsWith("/revoke") }.map { decrypted(it, filesyncSecret).jsonObject }
        assertEquals(listOf(editorsCircle.toString() to "sam.test"), revoked.map { it["circleId"]!!.jsonPrimitive.content to it["odinId"]!!.jsonPrimitive.content })
    }

    @Test
    fun movingOutOfAFolderSharedWithSamStopsSharingItWithHim() = runTest {
        sharedPages += folders(old = true, new = false)
        sharedPages += movedFile(shared = true)

        val plan = planMove()
        assertEquals(listOf(sam), plan.losingAccess)

        sharing.applyMove(plan, emptyList())
        assertTrue(calls.none { it.request.method == HttpMethod.Delete }, "a node that didn't move keeps its shares")

        sharing.applyMove(plan, listOf(moved.nodeId))
        assertEquals(listOf("/files/$movedFileId/shares/$samsCircle"), calls.filter { it.request.method == HttpMethod.Delete }.map { it.path.substringAfter(FilesyncProtocol.DriveAlias.toString()) })
        assertTrue(calls.none { it.path.endsWith("/shares") })
    }

    @Test
    fun movingIntoAFolderSharedWithSamSharesItLikeTheFolderWithoutAsking() = runTest {
        val circleKey = ShareKeys.newCircleKey()
        storedShareKey = Base64.encode(ShareKeys.lockCircleKey(filesyncAppKey, circleKey, samsCircle))
        sharedPages += folders(old = false, new = true)
        sharedPages += movedFile(shared = false)

        val plan = planMove()
        assertEquals(emptyList(), plan.losingAccess)
        sharing.applyMove(plan, listOf(moved.nodeId))

        val share = decrypted(calls.single { it.path.endsWith("/shares") }, filesyncSecret).jsonObject["shares"]!!.jsonArray.single().jsonObject
        assertEquals(movedFileId.toString(), share["fileId"]!!.jsonPrimitive.content)
        assertEquals("1900000000000", share["expiresAt"]!!.jsonPrimitive.content)
        val opened = ShareKeys.openFileKey(circleKey, Base64.decode(share["lockedFileKey"]!!.jsonPrimitive.content), FilesyncProtocol.DriveAlias, movedFileId, null, samsCircle)
        assertContentEquals(movedHandle.keyHeader.aesKey.toByteArray(), opened)
        assertTrue(calls.none { it.request.method == HttpMethod.Delete })
    }

    @Test
    fun movingBetweenTwoFoldersSharedWithSamChangesNothing() = runTest {
        sharedPages += folders(old = true, new = true)
        sharedPages += movedFile(shared = true)

        val plan = planMove()
        sharing.applyMove(plan, listOf(moved.nodeId))

        assertEquals(emptyList(), plan.losingAccess)
        assertTrue(calls.none { it.path.endsWith("/shares") || it.request.method == HttpMethod.Delete })
    }

    @Test
    fun movingBetweenUnsharedFoldersDoesntWalkWhatsMoved() = runTest {
        sharedPages += folders(old = false, new = false)

        val plan = sharing.planMove(listOf(moved), newFolder) { error("walked") }

        assertEquals(emptyList(), plan.losingAccess)
        assertEquals(1, calls.count { it.path.endsWith("/query-batch") })
    }

    private val appId = Uuid.parseHex(filesyncAppRegistration.appId)

    @Test
    fun aGroupIsACircleOfTheAppsGrantingWhatRecipientsAreGrantedWithItsMembersInIt() = runTest {
        val group = sharing.createGroup("Hiking", listOf(sam, merry))

        assertEquals(FilesyncAudience.Group(groupCircle, "Hiking", listOf(merry, sam)), group)
        val created = decrypted(calls.single { it.path.endsWith("/circles/create") }, filesyncSecret).jsonObject
        assertEquals("Hiking", created["name"]!!.jsonPrimitive.content)
        assertTrue(Regex("g[0-9a-f]{13}").matches(created["slug"]!!.jsonPrimitive.content))
        val declared = filesyncAppRegistration.circles.single { it.slug == FilesyncProtocol.CircleSlug }.drives.single().permissions
        val granted = created["driveGrants"]!!.jsonArray.single().jsonObject["permissionedDrive"]!!.jsonObject["permission"]!!
        assertEquals(declared.toSet(), Json.decodeFromJsonElement(DrivePermissionsSerializer, granted).values.toSet())
        assertTrue(DrivePermission.Write !in declared, "Write would let every member add files anywhere on the drive")
        assertTrue(created["grantOn"].let { it == null || it.jsonPrimitive.content == "none" })
        val added = decrypted(calls.single { it.path.endsWith("/add-many") }, filesyncSecret).jsonObject
        assertEquals(groupCircle.toString(), added["circleId"]!!.jsonPrimitive.content)
        assertEquals(setOf("sam.test", "merry.test"), added["odinIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun aGroupIsMadeEvenWithMembersWhoHaventSetUpFilesync() = runTest {
        samReady = false

        assertEquals(listOf(merry, sam), sharing.createGroup("Hiking", listOf(sam, merry)).members)
    }

    @Test
    fun aMemberWhoJoinsAtTheirNextCallKeepsTheGroup() = runTest {
        depositedByAddMany = listOf("merry.test")

        val group = sharing.createGroup("Hiking", listOf(sam, merry))

        assertEquals(listOf(merry, sam), group.members)
        assertTrue(calls.none { "/connections/circles/delete" in it.path })
    }

    @Test
    fun aGroupSomeoneCouldntJoinIsDeletedAgain() = runTest {
        refusedByAddMany = listOf("merry.test")

        val e = assertFailsWith<FilesyncSharingException.RecipientNotAdded> { sharing.createGroup("Hiking", listOf(sam, merry)) }

        assertEquals(listOf(merry), e.recipients)
        assertEquals(1, calls.count { "/connections/circles/delete" in it.path })
    }

    @Test
    fun sharingWithAGroupWritesOneRowForItsCircleAndSealsItsKeyToEveryMember() = runTest {
        unsealed = listOf("sam.test", "merry.test")

        sharing.share(listOf(file), FilesyncAudience.Group(groupCircle, "Hiking", listOf(merry, sam)), FilesyncAccess.View, expiresAt = null)

        val share = decrypted(calls.single { it.path.endsWith("/shares") }, filesyncSecret).jsonObject["shares"]!!.jsonArray.single().jsonObject
        assertEquals(groupCircle.toString(), share["circleId"]!!.jsonPrimitive.content)
        val members = decrypted(calls.single { it.path.endsWith("/share-key/members") }, filesyncSecret).jsonObject["members"]!!.jsonArray
        assertEquals(setOf("sam.test", "merry.test"), members.map { it.jsonObject["odinId"]!!.jsonPrimitive.content }.toSet())
        assertTrue(calls.none { decryptedOrNull(it)?.jsonObject?.get("designation")?.jsonPrimitive?.content == "individual" }, "no individual circle for a group")
    }

    @Test
    fun groupsComeFirstByNameThenPeopleAndTheAppsOtherCirclesAreLeftOut() = runTest {
        circles = """[
            {"circle":{"id":"$samsCircle","name":"sam.test","designation":"individual","appId":"$appId"},"members":["sam.test"]},
            {"circle":{"id":"$groupCircle","name":"Hiking","slug":"g0123456789abc","appId":"$appId"},"members":["sam.test","merry.test"]},
            {"circle":{"id":"${Uuid.random()}","name":"Old","slug":"g0123456789abd","disabled":true,"appId":"$appId"},"members":["sam.test"]},
            {"circle":{"id":"$recipientsCircle","name":"Filesync","slug":"${FilesyncProtocol.CircleSlug}","appId":"$appId"},"members":["sam.test"]},
            {"circle":{"id":"${Uuid.random()}","name":"Family","slug":"g0123456789abe"},"members":["sam.test"]}
        ]"""

        assertEquals(listOf(FilesyncAudience.Group(groupCircle, "Hiking", listOf(merry, sam)), FilesyncAudience.Person(sam)), sharing.audiences())
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
