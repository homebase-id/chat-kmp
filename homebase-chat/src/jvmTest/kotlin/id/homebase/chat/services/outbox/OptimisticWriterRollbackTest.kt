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
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Rollback of an optimistic write must actually restore the local row.
 *
 * `writeDelete` stamps `updated + 1ms` so the mutated row wins the
 * `DriveMainIndex` monotonic guard (`WHERE excluded.modified >
 * DriveMainIndex.modified`). `rollbackWrite` then re-upserts the ORIGINAL file,
 * whose `updated` is 1ms older — the guard rejects it, `performBaseUpsert`
 * drops it silently, and the message stays deleted locally even though nothing
 * was ever queued for the server.
 */
class OptimisticWriterRollbackTest {

    @Test
    fun rollbackAfterDelete_restoresTheRow() = runTest {
        Fixture().use { fx ->
            fx.build(this)
            val messageId = fx.seedMessage()

            val original = fx.optimisticWriter.writeDelete(fx.chatDriveId, messageId)
                ?: error("writeDelete returned null — nothing to roll back")

            assertTrue(
                fx.readRow(messageId).isSoftDeleted(),
                "sanity: the optimistic delete should have landed",
            )

            fx.optimisticWriter.rollbackWrite(fx.chatDriveId, original)

            val afterRollback = fx.readRow(messageId)
            assertFalse(
                afterRollback.isSoftDeleted(),
                "rollback must restore the row: isSoftDeleted() is still true",
            )
            assertEquals(
                original.fileState,
                afterRollback.fileState,
                "rollback must restore fileState",
            )
            assertEquals(
                "hello world",
                afterRollback.fileMetadata.appData.content,
                "rollback must restore the message content",
            )
            assertEquals(0L, fx.dbm.outbox.count(), "nothing should be queued")
        }
    }

    /**
     * The same thing through the real refused-enqueue path the callers use:
     * the outbox INSERT fails, `tryEnqueue` returns a non-enqueued result, and
     * the caller rolls the optimistic write back.
     */
    @Test
    fun refusedEnqueue_rollsBackToAnUndeletedRow() = runTest {
        Fixture().use { fx ->
            fx.build(this)
            val messageId = fx.seedMessage()

            val original = fx.optimisticWriter.writeDelete(fx.chatDriveId, messageId)
                ?: error("writeDelete returned null — nothing to roll back")

            fx.failOutboxInserts.set(true)
            val result = fx.outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = fx.chatDriveId,
                    fileIds = listOf(original.fileId),
                    recipients = null,
                    hardDelete = false,
                ),
            )
            fx.failOutboxInserts.set(false)
            assertFalse(result.enqueued, "the outbox INSERT was supposed to be refused")

            fx.optimisticWriter.rollbackWrite(fx.chatDriveId, original)

            val afterRollback = fx.readRow(messageId)
            assertEquals(0L, fx.dbm.outbox.count(), "the refused enqueue left no outbox row")
            assertFalse(
                afterRollback.isSoftDeleted(),
                "refused enqueue + rollback left the message deleted locally with nothing queued",
            )
        }
    }

    private class Fixture : AutoCloseable {
        val testIdentityId: Uuid = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
        val chatDriveId: Uuid = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
        val testDomain: String = "owner.test"

        val failOutboxInserts = AtomicBoolean(false)

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

        suspend fun readRow(messageId: Uuid): HomebaseFile =
            dbm.driveMainIndex.selectHomebaseFileByUnique(
                identityId = testIdentityId,
                driveId = chatDriveId,
                uniqueId = messageId,
            ) ?: error("no DriveMainIndex row for $messageId")

        /** Seeds an active chat message through the real upsert path. */
        suspend fun seedMessage(
            messageId: Uuid = Uuid.random(),
            fileId: Uuid = Uuid.random(),
            userDateMs: Long = 100_000L,
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
                    "updated": $userDateMs,
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
                      "content": "hello world",
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
