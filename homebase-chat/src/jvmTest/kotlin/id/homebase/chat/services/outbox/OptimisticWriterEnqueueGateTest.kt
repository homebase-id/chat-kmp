package id.homebase.chat.services.outbox

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.DriveMainIndex
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.ChatProtocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * The outbox enqueue is the gate for an optimistic mutation: the writer reads the
 * row, hands it to the enqueue callback, and mutates only once that reports a
 * durable enqueue. A refused (or throwing) enqueue leaves the row untouched, so
 * there is no local state that the server will never learn about — and nothing
 * to roll back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticWriterEnqueueGateTest {

    @Test
    fun delete_refusedEnqueue_leavesTheRowUntouched() = runTest {
        Fixture().use { fx ->
            fx.build(this)
            val messageId = fx.seedMessage(updatedMs = 100_000L)
            advanceUntilIdle()
            val eventsBefore = fx.received.size

            fx.failOutboxInserts.set(true)
            val result = fx.optimisticWriter.writeDelete(fx.chatDriveId, messageId) { original ->
                fx.outboxSync.tryEnqueue(fx.deleteRequest(original)).enqueued
            }
            fx.failOutboxInserts.set(false)

            assertNull(result, "a refused enqueue must report nothing deleted")
            assertEquals(0L, fx.dbm.outbox.count(), "the refused enqueue left no outbox row")
            val row = fx.readRow(messageId)
            assertFalse(row.isSoftDeleted(), "the row must not be soft-deleted")
            assertEquals("hello world", row.fileMetadata.appData.content)
            assertEquals(100_000L, fx.readIndexRow(messageId).modified, "modified must be untouched")
            advanceUntilIdle()
            assertEquals(eventsBefore, fx.received.size, "nothing was written, so nothing is emitted")
        }
    }

    /** Recipient resolution failing inside the callback is the same as a refusal. */
    @Test
    fun delete_throwingEnqueue_propagatesAndLeavesTheRowUntouched() = runTest {
        Fixture().use { fx ->
            fx.build(this)
            val messageId = fx.seedMessage()

            assertFailsWith<IllegalStateException> {
                fx.optimisticWriter.writeDelete(fx.chatDriveId, messageId) {
                    error("no conversation found")
                }
            }

            assertFalse(fx.readRow(messageId).isSoftDeleted())
            assertEquals(0L, fx.dbm.outbox.count())
        }
    }

    @Test
    fun delete_enqueueSeesThePreDeleteRow_andTheWriteLandsAfterIt() = runTest {
        Fixture().use { fx ->
            fx.build(this)
            val messageId = fx.seedMessage(updatedMs = 100_000L)

            var rowInsideEnqueue: HomebaseFile? = null
            val result = fx.optimisticWriter.writeDelete(fx.chatDriveId, messageId) { original ->
                rowInsideEnqueue = fx.readRow(messageId)
                fx.outboxSync.tryEnqueue(fx.deleteRequest(original)).enqueued
            }

            assertNotNull(result, "an accepted enqueue must report the original")
            assertFalse(
                rowInsideEnqueue!!.isSoftDeleted(),
                "the local write must not land before the enqueue is durable",
            )
            assertEquals(1L, fx.dbm.outbox.count())
            assertTrue(fx.readRow(messageId).isSoftDeleted())
            assertEquals(100_001L, fx.readIndexRow(messageId).modified)
        }
    }

    @Test
    fun delete_missingRow_neverCallsEnqueue() = runTest {
        Fixture().use { fx ->
            fx.build(this)

            var called = false
            val result = fx.optimisticWriter.writeDelete(fx.chatDriveId, Uuid.random()) {
                called = true
                true
            }

            assertNull(result)
            assertFalse(called, "there is nothing to delete, so nothing may be queued")
        }
    }

    /**
     * A host write that lands between the read and the optimistic upsert owns the
     * row: the monotonic guard drops the optimistic copy, and observers must not
     * be shown it either.
     */
    @Test
    fun delete_hostWriteDuringEnqueue_winsAndIsNotEmittedOver() = runTest {
        Fixture().use { fx ->
            fx.build(this)
            val messageId = fx.seedMessage(updatedMs = 100_000L)
            advanceUntilIdle()
            val batchesBefore = fx.received.count { it is BackendEvent.DataEvent.BatchReceived }

            val result = fx.optimisticWriter.writeDelete(fx.chatDriveId, messageId) { original ->
                fx.seedMessage(
                    messageId = messageId,
                    fileId = original.fileId,
                    updatedMs = 200_000L,
                    content = "edited on another device",
                )
                fx.outboxSync.tryEnqueue(fx.deleteRequest(original)).enqueued
            }
            advanceUntilIdle()

            assertNotNull(result, "the delete was queued")
            val row = fx.readRow(messageId)
            assertFalse(row.isSoftDeleted(), "the newer host row must stand")
            assertEquals("edited on another device", row.fileMetadata.appData.content)
            assertEquals(200_000L, fx.readIndexRow(messageId).modified)
            assertEquals(
                batchesBefore,
                fx.received.count { it is BackendEvent.DataEvent.BatchReceived },
                "the dropped optimistic copy must not be emitted to observers",
            )
        }
    }

    @Test
    fun reaction_refusedEnqueue_leavesTheRowUntouched() = runTest {
        Fixture().use { fx ->
            fx.build(this)
            val messageId = fx.seedMessage(updatedMs = 100_000L)
            advanceUntilIdle()
            val eventsBefore = fx.received.size

            fx.failOutboxInserts.set(true)
            val (resultType, original) = fx.optimisticWriter.writeReactionToggle(
                fx.chatDriveId, messageId, fx.heart,
            ) { file -> fx.outboxSync.tryEnqueue(fx.reactionRequest(file)).enqueued }
            fx.failOutboxInserts.set(false)

            assertEquals(ToggleReactionResultType.None, resultType)
            assertNull(original)
            assertEquals(0L, fx.dbm.outbox.count())
            val row = fx.readRow(messageId)
            assertTrue(row.fileMetadata.localAppData?.localReactions.isNullOrEmpty())
            assertNull(row.fileMetadata.reactionPreview)
            assertEquals(100_000L, fx.readIndexRow(messageId).modified)
            advanceUntilIdle()
            assertEquals(eventsBefore, fx.received.size)
        }
    }

    @Test
    fun reaction_enqueueSeesThePreToggleRow_andTheWriteLandsAfterIt() = runTest {
        Fixture().use { fx ->
            fx.build(this)
            val messageId = fx.seedMessage()

            var reactionsInsideEnqueue: List<String>? = null
            val (resultType, original) = fx.optimisticWriter.writeReactionToggle(
                fx.chatDriveId, messageId, fx.heart,
            ) { file ->
                reactionsInsideEnqueue = fx.readRow(messageId).fileMetadata.localAppData?.localReactions
                fx.outboxSync.tryEnqueue(fx.reactionRequest(file)).enqueued
            }

            assertEquals(ToggleReactionResultType.Added, resultType)
            assertNotNull(original)
            assertTrue(reactionsInsideEnqueue.isNullOrEmpty(), "the toggle must not land before the enqueue")
            assertEquals(1L, fx.dbm.outbox.count())
            assertEquals(
                listOf(fx.heart),
                fx.readRow(messageId).fileMetadata.localAppData?.localReactions,
            )
        }
    }

    private class Fixture : AutoCloseable {
        val testIdentityId: Uuid = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
        val chatDriveId: Uuid = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
        val testDomain: String = "owner.test"

        val failOutboxInserts = AtomicBoolean(false)
        val received = mutableListOf<BackendEvent>()
        val heart = """{"emoji":"❤️"}"""

        fun deleteRequest(original: HomebaseFile) = DeleteLocalFilesByFileIdRequest(
            driveId = chatDriveId,
            fileIds = listOf(original.fileId),
            recipients = null,
            hardDelete = false,
        )

        fun reactionRequest(original: HomebaseFile) = ToggleReactionOutboxRequest(
            driveId = chatDriveId,
            fileId = original.fileId,
            reaction = heart,
            recipients = emptyList(),
        )

        lateinit var dbm: DatabaseManager
        lateinit var eventBus: EventBus
        lateinit var outboxSync: OutboxSync
        lateinit var optimisticWriter: OptimisticWriter

        suspend fun build(scope: TestScope) {
            dbm = DatabaseManager({
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                OdinDatabase.Schema.create(driver)
                RefusingOutboxInsertDriver(driver, failOutboxInserts)
            })
            eventBus = EventBus()
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                eventBus.events.collect { received += it }
            }
            outboxSync = OutboxSync(
                databaseManager = dbm,
                uploader = ThrowingUploader,
                eventBus = eventBus,
                scope = scope,
            ).also { it.setOnline(false) }

            val credentialsManager = CredentialsManager().also {
                it.setActiveCredentials(
                    ApiCredentials.create(
                        domain = OdinId(testDomain),
                        clientAccessToken = "test-token",
                        sharedSecret = SecureByteArray(ByteArray(16)),
                    )
                )
            }

            optimisticWriter = OptimisticWriter(
                credentialsManager = credentialsManager,
                dbm = dbm,
                eventBus = eventBus,
                outboxSync = outboxSync,
            )
        }

        suspend fun readIndexRow(messageId: Uuid): DriveMainIndex =
            dbm.driveMainIndex.selectByIdentityAndDriveAndUnique(
                identityId = testIdentityId,
                driveId = chatDriveId,
                uniqueId = messageId,
            ) ?: error("no DriveMainIndex row for $messageId")

        suspend fun readRow(messageId: Uuid): HomebaseFile =
            dbm.driveMainIndex.selectHomebaseFileByUnique(
                identityId = testIdentityId,
                driveId = chatDriveId,
                uniqueId = messageId,
            ) ?: error("no DriveMainIndex row for $messageId")

        /**
         * Seeds — or, when [messageId]/[fileId] name an existing row, host-writes over —
         * an active chat message through the real guarded upsert path.
         */
        suspend fun seedMessage(
            messageId: Uuid = Uuid.random(),
            fileId: Uuid = Uuid.random(),
            userDateMs: Long = 100_000L,
            updatedMs: Long = userDateMs,
            content: String = "hello world",
        ): Uuid {
            val header = OdinSystemSerializer.deserialize<HomebaseFile>(
                """{
                  "fileId": "$fileId",
                  "driveId": "$chatDriveId",
                  "fileState": "active",
                  "fileSystemType": "standard",
                  "serverFileIsEncrypted": "false",
                  "keyHeader": {
                    "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                    "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
                  },
                  "fileMetadata": {
                    "globalTransitId": "${Uuid.random()}",
                    "created": $userDateMs,
                    "updated": $updatedMs,
                    "transitCreated": $userDateMs,
                    "transitUpdated": 0,
                    "isEncrypted": false,
                    "senderOdinId": "$testDomain",
                    "originalAuthor": "$testDomain",
                    "appData": {
                      "uniqueId": "$messageId",
                      "tags": null,
                      "fileType": ${ChatProtocol.MessageFileType},
                      "dataType": 0,
                      "groupId": "${Uuid.random()}",
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
                    "fileByteCount": 50,
                    "originalRecipientCount": 1,
                    "transferHistory": null
                  },
                  "priority": 300,
                  "fileByteCount": 50
                }"""
            )
            MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
                .baseUpsertEntryZapZap(testIdentityId, chatDriveId, listOf(header), null)
            return messageId
        }

        override fun close() {
            if (::dbm.isInitialized) dbm.close()
        }
    }
}

private object ThrowingUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) =
        error("uploader must not run — the outbox is offline in this test")
}

/** Fails `INSERT INTO Outbox` while armed, so `tryEnqueue` reports a refusal. */
private class RefusingOutboxInsertDriver(
    private val delegate: SqlDriver,
    private val refusing: AtomicBoolean,
) : SqlDriver {

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (refusing.get() && sql.contains("INSERT INTO Outbox")) {
            throw IllegalStateException("simulated outbox INSERT failure")
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

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
