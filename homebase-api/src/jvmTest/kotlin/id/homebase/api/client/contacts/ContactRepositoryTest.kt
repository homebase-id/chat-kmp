@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

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
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.Md5
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Behavioural pins for [ContactRepository] — the source-of-truth layer that owns the live contact
 * list, the optimistic write-through, and the "resurrection guard" that keeps a deleted contact
 * from reappearing out of a stale drive batch.
 *
 * The repo is driven with real collaborators rather than mocks: a real in-memory [DatabaseManager]
 * (so [ContactRepository.loadAll] runs an actual QueryBatch), a real [ContactsProvider] over a Ktor
 * [MockEngine] (so writes exercise the true HTTP mapping), and a real [EventBus]. observeEvents()
 * runs on a [TestScope.backgroundScope] child with an [UnconfinedTestDispatcher] (see [repo]) so it
 * subscribes eagerly; [advanceUntilIdle] drains any pending work before each assertion.
 */
class ContactRepositoryTest {

    private val testDomain = OdinId("test.homebase.id")
    private val contactDriveId = SystemDriveConstants.contactDrive.alias

    // ApiCredentials.getIdentityId() is currently a fixed value; seeded rows must use the same one
    // or QueryBatch won't see them.
    private val identityId = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")

    private val tag = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private val jsonHeaders =
        headersOf("Content-Type" to listOf(ContentType.Application.Json.toString()))

    // ------------------------------------------------------------
    // Collaborator builders
    // ------------------------------------------------------------

    private suspend fun credentialsManager(): CredentialsManager = CredentialsManager().apply {
        val creds = ApiCredentials.create(
            domain = testDomain,
            clientAccessToken = "test-token",
            sharedSecret = SecureByteArray("0123456789abcdef".encodeToByteArray()),
        )
        storeCredentials(creds)
        setActiveCredentials(creds)
    }

    private fun memoryDb(): DatabaseManager =
        DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })

    /**
     * One engine that covers every write the repo issues, routed by method/path:
     * DELETE → 204, POST `/sync/` → 202, POST `/contacts` (create) and PUT (update) → 200 okBody.
     * The okBody's uniqueId/versionTag are [okUniqueId]/[tag] so a `save()` resolves to a known id.
     */
    private fun writeEngine(okUniqueId: Uuid): MockEngine = MockEngine { req ->
        val path = req.url.encodedPath
        when {
            req.method == HttpMethod.Delete ->
                respond("", HttpStatusCode.NoContent, jsonHeaders)
            path.contains("/sync/") ->
                respond("", HttpStatusCode.Accepted, jsonHeaders)
            else ->
                respond(
                    ContactFixtures.okBody(okUniqueId.toString(), tag.toString()),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
        }
    }

    /**
     * Builds the repo on a scope that is a child of [TestScope.backgroundScope] (so its collector is
     * torn down with the test) but uses an [UnconfinedTestDispatcher] so `observeEvents()` subscribes
     * eagerly at construction. Without the eager dispatcher the collector wouldn't subscribe until a
     * later `advanceUntilIdle`, and a meanwhile-emitted first event would be swallowed by the
     * `drop(replayCache.size)` replay-skip in observeEvents.
     */
    private fun TestScope.repo(
        cm: CredentialsManager,
        dbm: DatabaseManager,
        eventBus: EventBus,
        engine: MockEngine,
    ): ContactRepository {
        // No image tests here, so the header reader is never invoked.
        val provider = ContactsProvider(HttpClient(engine), cm, { _, _ -> null })
        // No ext_data tests here, so the payload reader is never invoked.
        val payloadReader = ContactPayloadReader { _, _, _, _ -> null }
        val scope = backgroundScope + UnconfinedTestDispatcher(testScheduler)
        return ContactRepository(provider, payloadReader, dbm, cm, eventBus, scope)
    }

    // ------------------------------------------------------------
    // Contact-file builders
    // ------------------------------------------------------------

    /** A Contacts-drive [HomebaseFile] that [HomebaseFile.toContact] accepts, mirroring real rows. */
    private fun contactFile(
        uniqueId: Uuid,
        content: ContactContent,
        withImagePayload: Boolean = false,
    ): HomebaseFile = HomebaseFile(
        fileId = Uuid.parse("99999999-9999-9999-9999-999999999999"),
        driveId = contactDriveId,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        fileMetadata = FileMetadata(
            isEncrypted = true,
            versionTag = tag,
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                fileType = ContactsProvider.CONTACT_FILE_TYPE,
                content = OdinSystemSerializer.serialize(content),
            ),
            payloads = if (withImagePayload) {
                listOf(
                    PayloadDescriptor(
                        key = ContactsProvider.CONTACT_IMAGE_PAYLOAD_KEY,
                        contentType = "image/jpeg",
                        bytesWritten = 1024L,
                    ),
                )
            } else {
                null
            },
        ),
        serverMetadata = ServerMetadata(),
    )

    /** Seed a contact row into the Contacts drive so [ContactRepository.loadAll] returns it. */
    private suspend fun seedContact(
        dbm: DatabaseManager,
        uniqueId: Uuid,
        content: ContactContent,
        created: Long,
    ) {
        val fileId = Uuid.fromLongs(0L, created) // distinct per row
        val file = contactFile(uniqueId, content).copy(fileId = fileId)
        dbm.driveMainIndex.upsertDriveMainIndex(
            identityId = identityId,
            driveId = contactDriveId,
            fileId = fileId,
            uniqueId = uniqueId,
            globalTransitId = null,
            groupId = null,
            senderId = null,
            originalAuthor = null,
            fileType = ContactsProvider.CONTACT_FILE_TYPE.toLong(),
            dataType = 0L,
            archivalStatus = 0L,
            fileState = 1L,
            historyStatus = 0L,
            userDate = created,
            created = created,
            modified = created,
            fileSystemType = 0L,
            jsonHeader = OdinSystemSerializer.serialize(file),
        )
    }

    private fun batch(vararg files: HomebaseFile) =
        BackendEvent.DataEvent.BatchReceived(driveId = contactDriveId, batchData = files.toList())

    private fun named(displayName: String) =
        ContactContent(name = ContactName(displayName = displayName))

    // ------------------------------------------------------------
    // Event-driven read path
    // ------------------------------------------------------------

    @Test
    fun batchReceived_addsThenDedupesByUniqueId() = runTest {
        val cm = credentialsManager()
        val eventBus = EventBus()
        val repo = repo(cm, memoryDb(), eventBus, writeEngine(Uuid.random()))
        advanceUntilIdle() // let observeEvents() subscribe before we emit

        val uid = Uuid.parse("11111111-1111-1111-1111-111111111111")
        eventBus.emit(batch(contactFile(uid, named("Sam"))))
        advanceUntilIdle()
        assertEquals(listOf("Sam"), repo.contacts.value.map { it.content.name?.displayName })

        // Same uniqueId again -> replaces in place, not appended.
        eventBus.emit(batch(contactFile(uid, named("Samuel"))))
        advanceUntilIdle()
        assertEquals(1, repo.contacts.value.size)
        assertEquals("Samuel", repo.contacts.value.single().content.name?.displayName)
    }

    @Test
    fun reset_clearsContactsAndLoadedState() = runTest {
        val cm = credentialsManager()
        val eventBus = EventBus()
        val repo = repo(cm, memoryDb(), eventBus, writeEngine(Uuid.random()))
        advanceUntilIdle()

        val uid = Uuid.parse("11111111-1111-1111-1111-111111111111")
        eventBus.emit(batch(contactFile(uid, named("Sam"))))
        advanceUntilIdle()
        assertTrue(repo.contacts.value.isNotEmpty())

        repo.reset()
        assertTrue(repo.contacts.value.isEmpty())
        assertFalse(repo.isLoaded.value)
    }

    @Test
    fun delete_suppressesLaterBatchForSameId() = runTest {
        // Resurrection guard: a stale batch must not re-add a contact we just deleted.
        val cm = credentialsManager()
        val eventBus = EventBus()
        val uid = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val repo = repo(cm, memoryDb(), eventBus, writeEngine(uid))
        advanceUntilIdle()

        assertTrue(repo.delete(uid))

        eventBus.emit(batch(contactFile(uid, named("Ghost"))))
        advanceUntilIdle()
        assertTrue(repo.contacts.value.isEmpty(), "deleted id must stay suppressed")
    }

    // ------------------------------------------------------------
    // Write-through (optimistic)
    // ------------------------------------------------------------

    @Test
    fun save_upsertsOptimisticallyAndLiftsDeleteGuard() = runTest {
        val cm = credentialsManager()
        val eventBus = EventBus()
        val uid = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val repo = repo(cm, memoryDb(), eventBus, writeEngine(uid))
        advanceUntilIdle()

        // Delete first so the id sits in the resurrection guard...
        assertTrue(repo.delete(uid))
        // ...then save it back. save() resolves to `uid` via the okBody.
        val response = repo.save(named("Sam"), knownUniqueId = uid, knownVersionTag = tag)
        assertNotNull(response)
        assertEquals(uid, repo.contacts.value.single().uniqueId)

        // Guard lifted: a subsequent drive batch for the same id now reconciles instead of being
        // dropped.
        eventBus.emit(batch(contactFile(uid, named("Sam (synced)"))))
        advanceUntilIdle()
        assertEquals("Sam (synced)", repo.contacts.value.single().content.name?.displayName)
    }

    @Test
    fun save_preservesExistingImageOnContentEdit() = runTest {
        val cm = credentialsManager()
        val eventBus = EventBus()
        val uid = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val repo = repo(cm, memoryDb(), eventBus, writeEngine(uid))
        advanceUntilIdle()

        // A contact that already carries an avatar payload arrives via sync.
        eventBus.emit(batch(contactFile(uid, named("Sam"), withImagePayload = true)))
        advanceUntilIdle()
        assertNotNull(repo.contacts.value.single().image)

        // Editing the content (which has no image) must not drop the known avatar reference.
        repo.save(named("Samuel"), knownUniqueId = uid, knownVersionTag = tag)
        val after = repo.contacts.value.single()
        assertEquals("Samuel", after.content.name?.displayName)
        assertNotNull(after.image, "content edit must preserve the existing image ref")
    }

    @Test
    fun sync_liftsDeleteGuardSoReSyncReappears() = runTest {
        // #4: sync(odinId) clears the md5(odinId) id from the guard so reconnecting a previously
        // deleted identity isn't suppressed.
        val cm = credentialsManager()
        val eventBus = EventBus()
        val odinId = OdinId("sam.dotyou.cloud")
        val uid = Md5.toGuidId(odinId.domainName) // the id the server derives for this identity
        val repo = repo(cm, memoryDb(), eventBus, writeEngine(uid))
        advanceUntilIdle()

        assertTrue(repo.delete(uid))
        eventBus.emit(batch(contactFile(uid, named("Sam"))))
        advanceUntilIdle()
        assertTrue(repo.contacts.value.isEmpty(), "still suppressed before sync")

        repo.sync(odinId)
        eventBus.emit(batch(contactFile(uid, named("Sam"))))
        advanceUntilIdle()
        assertEquals(1, repo.contacts.value.size, "sync must lift the guard for md5(odinId)")
    }

    // ------------------------------------------------------------
    // loadAll (real DB)
    // ------------------------------------------------------------

    @Test
    fun loadAll_loadsSeededContacts() = runTest {
        val cm = credentialsManager()
        val dbm = memoryDb()
        val a = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
        val b = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002")
        seedContact(dbm, a, named("Alice"), created = 100L)
        seedContact(dbm, b, named("Bob"), created = 200L)

        val repo = repo(cm, dbm, EventBus(), writeEngine(Uuid.random()))
        repo.loadAll()

        assertTrue(repo.isLoaded.value)
        assertContentEquals(
            listOf(a, b).sortedBy { it.toString() },
            repo.contacts.value.map { it.uniqueId }.sortedBy { it.toString() },
        )
    }

    @Test
    fun loadAll_forgetsConfirmedDeletedButKeepsStillPresent() = runTest {
        // #5: the query is authoritative. A deleted id the server no longer returns is forgotten
        // (its guard is dropped); a deleted id the server STILL returns keeps its guard.
        val cm = credentialsManager()
        val dbm = memoryDb()
        val gone = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001") // never seeded → "delete synced"
        val present = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002") // seeded → delete not yet honored
        seedContact(dbm, present, named("Bob"), created = 200L)

        val eventBus = EventBus()
        val repo = repo(cm, dbm, eventBus, writeEngine(gone))
        advanceUntilIdle()

        // Both ids enter the resurrection guard.
        assertTrue(repo.delete(gone))
        assertTrue(repo.delete(present))

        repo.loadAll() // authoritative: returns only `present`
        assertTrue(repo.contacts.value.isEmpty(), "present is still guarded, so list is empty")

        // `gone` was pruned from the guard -> a batch now reconciles it...
        eventBus.emit(batch(contactFile(gone, named("Back"))))
        advanceUntilIdle()
        assertEquals(listOf(gone), repo.contacts.value.map { it.uniqueId })

        // ...but `present` is still guarded -> a batch for it stays suppressed.
        eventBus.emit(batch(contactFile(present, named("Still gone"))))
        advanceUntilIdle()
        assertEquals(listOf(gone), repo.contacts.value.map { it.uniqueId })
    }

    @Test
    fun loadAll_failureLeavesIsLoadedFalseSoEnsureLoadedRetries() = runTest {
        // #6: a transient query failure must not latch isLoaded=true with an empty list.
        val cm = credentialsManager()
        val dbm = memoryDb()
        val repo = repo(cm, dbm, EventBus(), writeEngine(Uuid.random()))
        advanceUntilIdle()

        dbm.close() // break the driver so the QueryBatch throws
        repo.loadAll()

        assertFalse(repo.isLoaded.value, "failed load must leave isLoaded false for a retry")
        assertTrue(repo.contacts.value.isEmpty())
    }
}
