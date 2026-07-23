@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.chat.services

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.chat.services.convo.contact.ConnectionCacheRepository
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.outbox.OptimisticWriter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Regression coverage for #1135 — a DriveSync/WS write that commits *between*
 * the start of `loadConversation`'s fetch and `setInitialWindow`.
 *
 * The fetch reads a pre-write SQLite snapshot, and both reconcilers
 * (`refreshCachedWindows` on `DriveEvent.Stopped`, `processIncrementalBatch` on
 * `DataEvent.BatchReceived`) skip a conversation that has no window yet — so the
 * message was dropped by all three paths and the stale window was cached and
 * reused on every subsequent open.
 *
 * The race is made deterministic with [GatedSqlDriver]: it lets the paging
 * SELECT run, then parks the reader thread *after* the snapshot has been taken.
 * The test writes the racing row and fires the sync event while the reader is
 * parked, then releases it.
 */
class ChatMessageStreamLoadRaceTest {

    private val testIdentityId: Uuid = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
    private val chatDriveId: Uuid = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
    private val testDomain: String = "owner.test"
    private val conversationId: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun loadConversation_rowCommittedMidFetch_isRecoveredViaDriveSyncStopped() = runTest {
        val fixture = buildFixture(this)
        val alreadySynced = fixture.seedMessage(userDateMs = 1_000L)
        val racingMessage = fixture.buildMessageFile(userDateMs = 2_000L)

        val fetchA = fixture.gate.armNextRead()
        val load = launch { fixture.stream.loadConversation(conversationId) }
        advanceUntilIdle()

        // Reader is now parked holding the pre-write snapshot.
        fetchA.reached.await()
        fixture.commit(racingMessage)
        fixture.eventBus.emit(
            BackendEvent.DriveEvent.Stopped(
                driveId = chatDriveId,
                totalCount = 1,
                result = BackendEvent.DriveResult.Completed,
            )
        )
        advanceUntilIdle()

        fetchA.release()
        load.join()

        val racingId = racingMessage.fileMetadata.appData.uniqueId!!
        assertTrue(
            fixture.stream.isMessageInWindow(conversationId, alreadySynced),
            "the pre-existing message must still be in the window",
        )
        assertTrue(
            fixture.stream.isMessageInWindow(conversationId, racingId),
            "a row committed while loadConversation was mid-fetch must end up in the window — " +
                "the fetch's snapshot predates it and both reconcilers skip a windowless conversation",
        )
        fixture.close()
    }

    @Test
    fun loadConversation_rowCommittedMidFetch_isRecoveredViaBatchReceived() = runTest {
        val fixture = buildFixture(this)
        fixture.seedMessage(userDateMs = 1_000L)
        val racingMessage = fixture.buildMessageFile(userDateMs = 2_000L)

        // processIncrementalBatch maps on Dispatchers.Default before reaching its
        // window gate, so "the event was emitted" is not "the gate was evaluated".
        // isConversationLeft is called immediately before that gate with no
        // suspension in between — use it as the barrier.
        val batchAtWindowGate = CompletableDeferred<Unit>()
        fixture.stream.isConversationLeft = { id ->
            if (id == conversationId) batchAtWindowGate.complete(Unit)
            false
        }

        val fetchA = fixture.gate.armNextRead()
        val load = launch { fixture.stream.loadConversation(conversationId) }
        advanceUntilIdle()

        fetchA.reached.await()
        fixture.commit(racingMessage)
        fixture.eventBus.emit(
            BackendEvent.DataEvent.BatchReceived(
                driveId = chatDriveId,
                batchData = listOf(racingMessage),
            )
        )
        batchAtWindowGate.await()

        fetchA.release()
        load.join()

        assertTrue(
            fixture.stream.isMessageInWindow(
                conversationId,
                racingMessage.fileMetadata.appData.uniqueId!!,
            ),
            "a WS-pushed batch for a conversation whose initial load is mid-fetch must not be dropped",
        )
        fixture.close()
    }

    @Test
    fun loadConversation_rowCommittedDuringTheReRead_survivesTheWriteBack() = runTest {
        // The re-read has its own snapshot boundary. By then the window exists, so a
        // batch landing mid-re-read IS upserted into it by processIncrementalBatch —
        // but the re-read's own write-back would replace that window with a page that
        // predates the upsert. Same permanent loss as #1135, one fetch later, and with
        // no reconciler left to heal it until the next DriveEvent.Stopped.
        val fixture = buildFixture(this)
        fixture.seedMessage(userDateMs = 1_000L)
        val duringFetchA = fixture.buildMessageFile(userDateMs = 2_000L)
        val duringReRead = fixture.buildMessageFile(userDateMs = 3_000L)

        val batchAtWindowGate = CompletableDeferred<Unit>()
        fixture.stream.isConversationLeft = { id ->
            if (id == conversationId) batchAtWindowGate.complete(Unit)
            false
        }

        val fetchA = fixture.gate.armNextRead()
        val load = launch { fixture.stream.loadConversation(conversationId) }
        advanceUntilIdle()

        // Commit during the initial fetch — this is what triggers the re-read.
        fetchA.reached.await()
        fixture.commit(duringFetchA)
        fixture.eventBus.emit(
            BackendEvent.DriveEvent.Stopped(
                driveId = chatDriveId,
                totalCount = 1,
                result = BackendEvent.DriveResult.Completed,
            )
        )
        advanceUntilIdle()

        // Arm the re-read's query before letting the initial fetch finish.
        val reRead = fixture.gate.armNextRead()
        fetchA.release()
        reRead.reached.await()

        // Commit during the re-read. The window exists now, so the WS path upserts it.
        fixture.commit(duringReRead)
        fixture.eventBus.emit(
            BackendEvent.DataEvent.BatchReceived(
                driveId = chatDriveId,
                batchData = listOf(duringReRead),
            )
        )
        batchAtWindowGate.await()

        reRead.release()
        load.join()

        assertTrue(
            fixture.stream.isMessageInWindow(
                conversationId,
                duringFetchA.fileMetadata.appData.uniqueId!!,
            ),
            "the re-read must still deliver the row that triggered it",
        )
        assertTrue(
            fixture.stream.isMessageInWindow(
                conversationId,
                duringReRead.fileMetadata.appData.uniqueId!!,
            ),
            "a row upserted into the window while the re-read was in flight must survive " +
                "its write-back — the re-read merges, it does not replace",
        )
        fixture.close()
    }

    @Test
    fun loadConversationAroundMessage_rowCommittedMidFetch_isRecovered() = runTest {
        // The scroll-anchored open — the common cold-open path — builds its window
        // with the same read-then-register shape and must be equally immune.
        val fixture = buildFixture(this)
        val anchor = fixture.seedMessage(userDateMs = 1_000L)
        val racingMessage = fixture.buildMessageFile(userDateMs = 2_000L)

        // loadAround issues anchor-lookup → older half (DESC) → newer half (ASC).
        // Park on the newer half: it is the last read, and the racing row sorts
        // into it, so the pre-park snapshot provably excludes it.
        val fetchA = fixture.gate.armNextRead { sql -> sql.contains("ORDER BY") && sql.contains("ASC") }
        val load = launch { fixture.stream.loadConversationAroundMessage(conversationId, anchor) }
        advanceUntilIdle()

        fetchA.reached.await()
        fixture.commit(racingMessage)
        fixture.eventBus.emit(
            BackendEvent.DriveEvent.Stopped(
                driveId = chatDriveId,
                totalCount = 1,
                result = BackendEvent.DriveResult.Completed,
            )
        )
        advanceUntilIdle()

        fetchA.release()
        load.join()

        assertTrue(
            fixture.stream.isMessageInWindow(
                conversationId,
                racingMessage.fileMetadata.appData.uniqueId!!,
            ),
            "a row committed while loadAround was mid-fetch must end up in the window",
        )
        fixture.close()
    }

    @Test
    fun loadConversation_withoutAConcurrentWrite_readsTheDatabaseOnce() = runTest {
        // Guard on the other side of the fix: the re-read must be conditional,
        // not an unconditional second page fetch on every conversation open.
        val fixture = buildFixture(this)
        val onlyMessage = fixture.seedMessage(userDateMs = 1_000L)
        val readsBefore = fixture.gate.readCount

        fixture.stream.loadConversation(conversationId)
        advanceUntilIdle()

        assertTrue(fixture.stream.isMessageInWindow(conversationId, onlyMessage))
        assertEquals(
            1, fixture.gate.readCount - readsBefore,
            "an uncontended load must issue exactly one paging query",
        )
        fixture.close()
    }

    // ---------- fixture ----------

    private suspend fun buildFixture(scope: TestScope): Fixture {
        val inner = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OdinDatabase.Schema.create(inner)
        val gate = GatedSqlDriver(inner)
        val dbm = DatabaseManager({ gate })
        val credentialsManager = CredentialsManager().apply {
            setActiveCredentials(
                ApiCredentials.create(
                    domain = OdinId(testDomain),
                    clientAccessToken = "test-token",
                    sharedSecret = SecureByteArray(ByteArray(16)),
                )
            )
        }
        val eventBus = EventBus()
        val httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        val fileOps = NoopFileOperationsProvider()
        val outboxSync = OutboxSync(
            databaseManager = dbm,
            uploader = ThrowingUploader,
            eventBus = eventBus,
            scope = scope.backgroundScope,
        ).also { it.setOnline(false) }

        val stream = ChatMessageStream(
            credentialsManager = credentialsManager,
            contactService = ContactService(
                contactRepository = ContactRepository(
                    contactsProvider = ContactsProvider(httpClient, credentialsManager) { _, _ -> null },
                    contactPayloadReader = { _, _, _ -> null },
                    databaseManager = dbm,
                    credentialsManager = credentialsManager,
                    eventBus = eventBus,
                    scope = scope.backgroundScope,
                ),
                connections = ConnectionService(
                    provider = ConnectionNetworkProvider(httpClient, credentialsManager),
                    eventBus = eventBus,
                    scope = scope.backgroundScope,
                    cache = ConnectionCacheRepository(dbm, credentialsManager),
                ),
                scope = scope.backgroundScope,
            ),
            ownerSessionRepository = OwnerSessionRepository(
                publicIdentityRepository = PublicIdentityRepository(httpClient),
                publicProfileProviderCached = PublicProfileProviderCached(httpClient, fileOps),
                eventBus = eventBus,
                scope = scope.backgroundScope,
            ),
            dbm = dbm,
            eventBus = eventBus,
            scope = scope.backgroundScope,
            driveFileProvider = DriveFileProvider(
                httpClient,
                credentialsManager,
                DriveFileProviderCached(httpClient, credentialsManager, fileOps),
            ),
            optimisticWriter = OptimisticWriter(
                credentialsManager = credentialsManager,
                dbm = dbm,
                eventBus = eventBus,
                outboxSync = outboxSync,
            ),
        )
        return Fixture(dbm, eventBus, gate, stream)
    }

    private inner class Fixture(
        val dbm: DatabaseManager,
        val eventBus: EventBus,
        val gate: GatedSqlDriver,
        val stream: ChatMessageStream,
    ) {
        fun close() = dbm.close()

        /** Build a chat-message header without writing it to `DriveMainIndex`. */
        fun buildMessageFile(userDateMs: Long, uniqueId: Uuid = Uuid.random()): HomebaseFile {
            val content =
                """{"message":"msg-${uniqueId.toString().take(8)}","deliveryStatus":20,"isEdited":false,"version":1}"""
                    .replace("\"", "\\\"")
            return OdinSystemSerializer.deserialize<HomebaseFile>(
                """{
                    "fileId": "${Uuid.random()}",
                    "driveId": "$chatDriveId",
                    "fileState": "active",
                    "fileSystemType": "standard",
                    "serverFileIsEncrypted": false,
                    "keyHeader": {
                        "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                        "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
                    },
                    "fileMetadata": {
                        "globalTransitId": "${Uuid.random()}",
                        "created": $userDateMs,
                        "updated": $userDateMs,
                        "transitCreated": $userDateMs,
                        "transitUpdated": 0,
                        "isEncrypted": false,
                        "senderOdinId": "$testDomain",
                        "originalAuthor": "$testDomain",
                        "appData": {
                            "uniqueId": "$uniqueId",
                            "tags": null,
                            "fileType": ${ChatProtocol.MessageFileType},
                            "dataType": 0,
                            "groupId": "$conversationId",
                            "userDate": $userDateMs,
                            "content": "$content",
                            "previewThumbnail": null,
                            "archivalStatus": 0
                        },
                        "localAppData": null,
                        "referencedFile": null,
                        "reactionPreview": null,
                        "versionTag": "${Uuid.random()}",
                        "payloads": [],
                        "dataSource": null
                    },
                    "serverMetadata": {
                        "accessControlList": {
                            "requiredSecurityGroup": "owner",
                            "circleIdList": null,
                            "odinIdList": null
                        },
                        "doNotIndex": false,
                        "allowDistribution": true,
                        "fileSystemType": "standard",
                        "fileByteCount": 100,
                        "originalRecipientCount": 0,
                        "transferHistory": null
                    },
                    "priority": 300,
                    "fileByteCount": 100
                }"""
            )
        }

        /** Write [file] into `DriveMainIndex` — the DriveSync commit being raced. */
        suspend fun commit(file: HomebaseFile) {
            val record = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
                .convertFileHeaderToDriveMainIndexRecord(testIdentityId, chatDriveId, file)
            MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
        }

        suspend fun seedMessage(userDateMs: Long): Uuid {
            val file = buildMessageFile(userDateMs)
            commit(file)
            return file.fileMetadata.appData.uniqueId!!
        }
    }
}

/**
 * `SqlDriver` decorator that parks the reader thread immediately AFTER an armed
 * `executeQuery` has run — the query's result set is already materialised, so the
 * caller provably holds a pre-park snapshot of the database while the test mutates
 * it from another thread.
 *
 * One stop is armed at a time; a test that needs to park twice (initial fetch and
 * re-read) arms the second before releasing the first.
 *
 * Blocking here is intentional and safe: reads run on `DatabaseManager`'s IO
 * read dispatcher, never on the test scheduler.
 */
private class GatedSqlDriver(private val delegate: SqlDriver) : SqlDriver {

    class ReadStop internal constructor(internal val matches: (String) -> Boolean) {
        internal val gate = CountDownLatch(1)

        /** Completes once the parked query has produced its snapshot. */
        val reached = CompletableDeferred<Unit>()

        fun release() = gate.countDown()
    }

    private val armed = AtomicReference<ReadStop?>(null)
    private val reads = AtomicInteger(0)

    /** Total `executeQuery` calls seen; tests read deltas around a single call. */
    val readCount: Int get() = reads.get()

    /** Park on the next query, optionally only one whose SQL satisfies [matching]. */
    fun armNextRead(matching: (String) -> Boolean = { true }): ReadStop =
        ReadStop(matching).also { armed.set(it) }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val candidate = armed.get()
        val stop = candidate
            ?.takeIf { it.matches(sql) && armed.compareAndSet(it, null) }
        reads.incrementAndGet()
        val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        if (stop != null) {
            stop.reached.complete(Unit)
            stop.gate.await()
        }
        return result
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = delegate.execute(identifier, sql, parameters, binders)

    override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()

    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.addListener(queryKeys = queryKeys, listener = listener)

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.removeListener(queryKeys = queryKeys, listener = listener)

    override fun notifyListeners(vararg queryKeys: String) =
        delegate.notifyListeners(queryKeys = queryKeys)

    override fun close() = delegate.close()
}

private object ThrowingUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        error("OutboxUploader.upload should never be called in ChatMessageStreamLoadRaceTest")
    }
}
