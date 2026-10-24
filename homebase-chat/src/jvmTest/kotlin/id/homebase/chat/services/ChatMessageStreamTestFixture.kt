@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.EventBus
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
import id.homebase.chat.viewonce.VO_OWNER
import id.homebase.chat.viewonce.ownerCredentials
import id.homebase.core.config.chatTargetDrive
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A real [ChatMessageStream] on an in-memory database, without DI. */
internal class ChatMessageStreamTestFixture private constructor(
    val dbm: DatabaseManager,
    val eventBus: EventBus,
    val credentialsManager: CredentialsManager,
    val outboxSync: OutboxSync,
    val optimisticWriter: OptimisticWriter,
    val stream: ChatMessageStream,
) {
    fun close() = dbm.close()

    fun awaitPostSyncRefresh(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { done ->
            stream.onPostSyncRefreshComplete = { done.complete(Unit) }
        }

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

    suspend fun commit(file: HomebaseFile) {
        val record = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
            .convertFileHeaderToDriveMainIndexRecord(testIdentityId, chatDriveId, file)
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
    }

    suspend fun seedMessage(userDateMs: Long): Uuid {
        val file = buildMessageFile(userDateMs)
        commit(file)
        return file.uniqueId()
    }

    companion object {
        val testIdentityId: Uuid = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
        val chatDriveId: Uuid = chatTargetDrive.alias
        const val testDomain: String = VO_OWNER
        val conversationId: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")

        suspend fun build(
            scope: TestScope,
            // The DB lanes and the stream's mapping on one dispatcher keep a test in virtual time.
            dispatcher: CoroutineDispatcher? = null,
            wrapDriver: (SqlDriver) -> SqlDriver = { it },
        ): ChatMessageStreamTestFixture {
            val inner = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            OdinDatabase.Schema.create(inner)
            val driver = wrapDriver(inner)
            val dbm = if (dispatcher == null) {
                DatabaseManager({ driver })
            } else {
                DatabaseManager({ driver }, dispatcher = dispatcher, readDispatcher = dispatcher)
            }
            val credentialsManager = ownerCredentials()
            val eventBus = EventBus()
            val outboxSync = OutboxSync(
                databaseManager = dbm,
                uploader = ThrowingOutboxUploader,
                eventBus = eventBus,
                scope = scope.backgroundScope,
            ).also { it.setOnline(false) }
            val optimisticWriter = OptimisticWriter(
                credentialsManager = credentialsManager,
                dbm = dbm,
                eventBus = eventBus,
                outboxSync = outboxSync,
            )
            val stream = testChatMessageStream(
                dbm, credentialsManager, eventBus, optimisticWriter, scope.backgroundScope, dispatcher ?: Dispatchers.Default,
            )
            return ChatMessageStreamTestFixture(dbm, eventBus, credentialsManager, outboxSync, optimisticWriter, stream)
        }
    }
}

internal fun HomebaseFile.uniqueId(): Uuid = fileMetadata.appData.uniqueId!!

/** A real [ChatMessageStream] without DI; its network calls fail. */
internal fun testChatMessageStream(
    dbm: DatabaseManager,
    credentialsManager: CredentialsManager,
    eventBus: EventBus,
    optimisticWriter: OptimisticWriter,
    scope: CoroutineScope,
    mapDispatcher: CoroutineDispatcher = Dispatchers.Default,
): ChatMessageStream {
    val httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
    return ChatMessageStream(
        credentialsManager = credentialsManager,
        contactService = ContactService(
            contactRepository = ContactRepository(
                contactsProvider = ContactsProvider(httpClient, credentialsManager) { _, _ -> null },
                contactPayloadReader = { _, _, _ -> null },
                databaseManager = dbm,
                credentialsManager = credentialsManager,
                eventBus = eventBus,
                scope = scope,
            ),
            connections = ConnectionService(
                provider = ConnectionNetworkProvider(httpClient, credentialsManager),
                eventBus = eventBus,
                scope = scope,
                cache = ConnectionCacheRepository(dbm, credentialsManager),
            ),
            scope = scope,
        ),
        ownerDisplayName = { null },
        dbm = dbm,
        eventBus = eventBus,
        scope = scope,
        driveFileProvider = DriveFileProvider(
            httpClient,
            credentialsManager,
            DriveFileProviderCached(httpClient, credentialsManager, NoopFileOperationsProvider()),
        ),
        optimisticWriter = optimisticWriter,
        serverHistory = ChatServerHistory(
            credentialsManager = credentialsManager,
            dbm = dbm,
            driveQueryProvider = DriveQueryProvider(httpClient, credentialsManager),
        ),
        mapDispatcher = mapDispatcher,
    )
}

internal object ThrowingOutboxUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        error("OutboxUploader.upload should never be called while the test outbox is offline")
    }
}
