@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.api.client.contacts

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.profile.FakeFileOperationsProvider
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ContactInfoGatewayAvatarTest {

    private val peer = OdinId("frodo.baggins.demo.rocks")
    private val contactDriveId = SystemDriveConstants.contactDrive.alias
    private val fileId = Uuid.parse("2c2c5b19-605a-e900-9349-c7ad2689ac38")
    private val uniqueId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val aesKey = ByteArray(16) { (it + 1).toByte() }
    private val photo = "real photo bytes, not initials".encodeToByteArray()
    private val initials = "server-generated initials png".encodeToByteArray()

    private val payloadRequests = mutableListOf<String?>()
    private var publicRequests = 0
    private var headerReads = 0

    @Test
    fun unencryptedPayloadIsReadPlainWithoutAKeyHeader() = runBlocking<Unit> {
        val gateway = gateway(
            synced = contactFile(isEncrypted = false, iv = null, lastModified = 1L),
            payload = { respond(photo, HttpStatusCode.OK, headersOf("payloadencrypted", "False")) },
        )

        assertContentEquals(photo, gateway.avatarBytes(peer))
        assertEquals<List<String?>>(listOf("1"), payloadRequests)
        assertEquals(0, publicRequests)
        assertEquals(0, headerReads)
    }

    @Test
    fun encryptedPayloadIsDecryptedWithTheDescriptorIv() = runBlocking<Unit> {
        val iv = ByteArray(16) { 0x11 }
        val cipher = encrypt(photo, iv)
        val gateway = gateway(
            synced = contactFile(isEncrypted = true, iv = iv, lastModified = 1L),
            payload = { respond(cipher, HttpStatusCode.OK, headersOf("payloadencrypted", "True")) },
        )

        assertContentEquals(photo, gateway.avatarBytes(peer))
        assertEquals(0, publicRequests)
        assertEquals(0, headerReads)
    }

    // What a synced contact looks like: decrypting the header content clears fileMetadata.isEncrypted.
    @Test
    fun encryptedPayloadIsDecryptedAfterTheHeaderWasDecrypted() = runBlocking<Unit> {
        val iv = ByteArray(16) { 0x11 }
        val cipher = encrypt(photo, iv)
        val gateway = gateway(
            synced = contactFile(isEncrypted = false, iv = iv, lastModified = 1L),
            payload = { respond(cipher, HttpStatusCode.OK, headersOf("payloadencrypted", "True")) },
        )

        assertContentEquals(photo, gateway.avatarBytes(peer))
        assertEquals(0, publicRequests)
    }

    @Test
    fun replacedVersionRereadsTheHeaderAndFetchesTheCurrentOne() = runBlocking<Unit> {
        val staleIv = ByteArray(16) { 0x11 }
        val currentIv = ByteArray(16) { 0x22 }
        val currentCipher = encrypt(photo, currentIv)
        val gateway = gateway(
            synced = contactFile(isEncrypted = true, iv = staleIv, lastModified = 1L),
            current = contactFile(isEncrypted = true, iv = currentIv, lastModified = 2L),
            payload = { request ->
                when (request.url.parameters["lastModified"]) {
                    "2" -> respond(currentCipher, HttpStatusCode.OK, headersOf("payloadencrypted", "True"))
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )

        assertContentEquals(photo, gateway.avatarBytes(peer))
        assertEquals<List<String?>>(listOf("1", "2"), payloadRequests)
        assertEquals(1, headerReads)
        assertEquals(0, publicRequests, "a replaced version must not fall back to /pub/image")
    }

    @Test
    fun failedRetryFallsBackToThePublicImage() = runBlocking<Unit> {
        val iv = ByteArray(16) { 0x11 }
        val synced = contactFile(isEncrypted = true, iv = iv, lastModified = 1L)
        val gateway = gateway(
            synced = synced,
            current = synced,
            payload = { respond("", HttpStatusCode.InternalServerError) },
        )

        assertContentEquals(initials, gateway.avatarBytes(peer))
        assertEquals(2, payloadRequests.size, "one read plus one retry, no more")
        assertEquals(1, headerReads)
        assertEquals(1, publicRequests)
    }

    private suspend fun encrypt(bytes: ByteArray, iv: ByteArray): ByteArray =
        KeyHeader(iv = iv, aesKey = SecureByteArray(aesKey.copyOf())).encryptDataAes(bytes)

    private fun contactFile(isEncrypted: Boolean, iv: ByteArray?, lastModified: Long) = HomebaseFile(
        fileId = fileId,
        driveId = contactDriveId,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(aesKey.copyOf())),
        fileMetadata = FileMetadata(
            isEncrypted = isEncrypted,
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                fileType = ContactsProvider.CONTACT_FILE_TYPE,
                content = OdinSystemSerializer.serialize(ContactContent(odinId = peer.domainName)),
            ),
            payloads = listOf(
                PayloadDescriptor(
                    key = ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY,
                    contentType = "image/webp",
                    iv = iv?.let { Base64.encode(it) },
                    lastModified = lastModified,
                ),
            ),
        ),
        serverMetadata = ServerMetadata(),
    )

    private suspend fun gateway(
        synced: HomebaseFile,
        current: HomebaseFile? = null,
        payload: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ContactInfoGateway {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/payload/${ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY}") -> {
                    payloadRequests += request.url.parameters["lastModified"]
                    payload(request)
                }
                path.contains("/pub/image") -> {
                    publicRequests++
                    respond(initials, HttpStatusCode.OK)
                }
                else -> error("unexpected request $path")
            }
        }
        val httpClient = HttpClient(engine)
        val cacheDir = Files.createTempDirectory("hb-gateway-avatar-test").toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val signedIn = CredentialsManager().apply {
            setActiveCredentials(
                ApiCredentials.create(
                    domain = OdinId("owner.demo.rocks"),
                    clientAccessToken = "test-token",
                    sharedSecret = SecureByteArray(ByteArray(16) { 0x01 }),
                )
            )
        }

        // No active credentials, so loadAll is a no-op and the contact arrives as a drive batch.
        val repoCredentials = CredentialsManager()
        val eventBus = EventBus()
        val repository = ContactRepository(
            contactsProvider = ContactsProvider(httpClient, repoCredentials) { _, _ -> null },
            contactPayloadReader = { _, _, _ -> null },
            databaseManager = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) }),
            credentialsManager = repoCredentials,
            eventBus = eventBus,
            scope = scope,
        )
        repository.ensureLoaded()
        eventBus.emit(BackendEvent.DataEvent.BatchReceived(driveId = contactDriveId, batchData = listOf(synced)))
        withTimeout(5_000) { repository.contacts.first { it.isNotEmpty() } }

        return ContactInfoGateway(
            contactRepository = { repository },
            publicProfiles = PublicProfileProviderCached(httpClient, FakeFileOperationsProvider(cacheDir), scope),
            driveFiles = DriveFileProviderCached(httpClient, signedIn, FakeFileOperationsProvider(cacheDir)),
            contactHeaders = { _, _ ->
                headerReads++
                current
            },
        )
    }
}

// No credentials, so loadAll is a no-op: a repository that knows no contacts.
internal fun emptyContactRepository(): ContactRepository {
    val credentials = CredentialsManager()
    return ContactRepository(
        contactsProvider = ContactsProvider(HttpClient(MockEngine { error("no network") }), credentials) { _, _ -> null },
        contactPayloadReader = { _, _, _ -> null },
        databaseManager = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) }),
        credentialsManager = credentials,
        eventBus = EventBus(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )
}

internal fun unusedDriveFiles(): DriveFileProviderCached = DriveFileProviderCached(
    HttpClient(MockEngine { error("no drive reads expected") }),
    CredentialsManager(),
    FakeFileOperationsProvider(Files.createTempDirectory("hb-gateway-unused-drive").toString()),
)
