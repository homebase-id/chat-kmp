package id.homebase.chat.services.outbox

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.RecipientTransferHistory
import id.homebase.api.client.drives.TransferHistorySummary
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveWebSocketUpsertWorker
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.getDeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * transferHistory is server-owned: a local write that ties the server's next `modified` must not
 * pin a message on a stale (single-tick) history.
 */
class ServerOwnedTransferHistoryTest {

    private val identityId = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
    private val driveId = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
    private val domain = "owner.test"
    private val messageId = Uuid.random()
    private val fileId = Uuid.random()

    private val delivered = RecipientTransferHistory(TransferHistorySummary(0, 0, 1, 0))
    private val enqueued = RecipientTransferHistory(TransferHistorySummary(1, 0, 0, 0))

    private fun header(modified: Long, history: RecipientTransferHistory?, recipientCount: Int = 1): HomebaseFile {
        val base = OdinSystemSerializer.deserialize<HomebaseFile>(
            """{
              "fileId": "$fileId", "driveId": "$driveId", "fileState": "active",
              "fileSystemType": "standard", "serverFileIsEncrypted": "false",
              "keyHeader": {"iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0], "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}},
              "fileMetadata": {
                "globalTransitId": "${Uuid.random()}", "created": 1000, "updated": 1000,
                "transitCreated": 1000, "transitUpdated": 0, "isEncrypted": false,
                "senderOdinId": "$domain", "originalAuthor": "$domain",
                "appData": {"uniqueId": "$messageId", "tags": null, "fileType": ${ChatProtocol.MessageFileType},
                  "dataType": 0, "groupId": "${Uuid.random()}", "userDate": 1000, "content": "hi",
                  "previewThumbnail": null, "archivalStatus": 0},
                "localAppData": null, "referencedFile": null, "reactionPreview": null,
                "versionTag": "${Uuid.random()}", "payloads": [], "dataSource": null
              },
              "serverMetadata": {
                "accessControlList": {"requiredSecurityGroup": "owner", "circleIdList": null, "odinIdList": null},
                "allowDistribution": true, "fileSystemType": "standard", "fileByteCount": 50,
                "originalRecipientCount": 0, "transferHistory": null
              },
              "priority": 300, "fileByteCount": 50
            }"""
        )
        return base.copy(
            fileMetadata = base.fileMetadata.copy(updated = UnixTimeUtc(modified)),
            serverMetadata = base.serverMetadata.copy(
                originalRecipientCount = recipientCount,
                transferHistory = history,
            ),
        )
    }

    private class Env : AutoCloseable {
        val dbm = DatabaseManager({
            val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            OdinDatabase.Schema.create(raw)
            raw
        })
        val eventBus = EventBus()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        override fun close() {
            scope.cancel()
            dbm.close()
        }
    }

    private suspend fun Env.writer(): OptimisticWriter {
        val outboxSync = OutboxSync(
            databaseManager = dbm,
            uploader = object : OutboxUploader {
                override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) = error("unused")
            },
            eventBus = eventBus,
            scope = scope,
        ).also { it.setOnline(false) }
        val creds = CredentialsManager().also {
            it.setActiveCredentials(
                ApiCredentials.create(OdinId(domain), "t", SecureByteArray(ByteArray(16)))
            )
        }
        return OptimisticWriter(creds, dbm, eventBus, outboxSync)
    }

    private fun Env.processor() = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

    private suspend fun Env.seed(h: HomebaseFile) {
        processor().baseUpsertEntryZapZap(identityId, driveId, listOf(h), null)
    }

    private suspend fun Env.push(h: HomebaseFile) {
        val worker = DriveWebSocketUpsertWorker(identityId, driveId, dbm, eventBus, scope)
        worker.submit(h)
        // the drain runs on the worker scope; wait for it to settle
        delay(300)
    }

    private suspend fun Env.row(): HomebaseFile =
        dbm.driveMainIndex.selectHomebaseFileByUnique(identityId, driveId, messageId)!!

    private suspend fun Env.storedModified(): Long =
        dbm.driveMainIndex.selectByIdentityAndDriveAndUnique(identityId, driveId, messageId)!!.modified

    @Test
    fun localTagWriteTyingTheServersNextModified_doesNotPinAStaleHistory() = runBlocking {
        Env().use { env ->
            withTimeout(20_000) {
                env.seed(header(modified = 100, history = null))
                env.writer().updateLocalTags(driveId, messageId, listOf(Uuid.random()))
                assertEquals(101, env.storedModified())

                env.push(header(modified = 101, history = delivered))

                assertEquals(ChatDeliveryStatus.Delivered, getDeliveryStatus(env.row()))
                assertEquals(101, env.storedModified(), "modified stays the local stamp")
            }
        }
    }

    @Test
    fun pushFirstThenLocalTagWrite_keepsTheHistory() = runBlocking {
        Env().use { env ->
            withTimeout(20_000) {
                env.seed(header(modified = 100, history = null))
                env.push(header(modified = 101, history = delivered))
                env.writer().updateLocalTags(driveId, messageId, listOf(Uuid.random()))

                assertEquals(ChatDeliveryStatus.Delivered, getDeliveryStatus(env.row()))
                assertEquals(102, env.storedModified())
            }
        }
    }

    @Test
    fun driveSyncPathAtEqualModified_adoptsHistoryAndRecipientCount() = runBlocking {
        Env().use { env ->
            withTimeout(20_000) {
                env.seed(header(modified = 101, history = null, recipientCount = 0))
                env.processor().baseUpsertEntryZapZap(
                    identityId, driveId,
                    listOf(header(modified = 101, history = delivered, recipientCount = 1)),
                    QueryBatchCursor(),
                )

                val row = env.row()
                assertEquals(delivered, row.serverMetadata.transferHistory)
                assertEquals(1, row.serverMetadata.originalRecipientCount)
                assertEquals(ChatDeliveryStatus.Delivered, getDeliveryStatus(row))
                assertEquals(101, env.storedModified())
            }
        }
    }

    @Test
    fun stalePush_withADifferentHistory_isNotApplied() = runBlocking {
        Env().use { env ->
            withTimeout(20_000) {
                env.seed(header(modified = 105, history = delivered))
                env.push(header(modified = 104, history = enqueued))

                assertEquals(delivered, env.row().serverMetadata.transferHistory)
                assertEquals(105, env.storedModified())
            }
        }
    }

    @Test
    fun newerPush_goesThroughTheNormalPath() = runBlocking {
        Env().use { env ->
            withTimeout(20_000) {
                env.seed(header(modified = 100, history = null))
                env.writer().updateLocalTags(driveId, messageId, listOf(Uuid.random()))
                env.push(header(modified = 102, history = delivered))

                assertEquals(ChatDeliveryStatus.Delivered, getDeliveryStatus(env.row()))
                assertEquals(102, env.storedModified())
            }
        }
    }
}
