package id.homebase.chat.services

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DeleteFileIdBatchResult
import id.homebase.api.client.drives.files.DeleteFileResult
import id.homebase.api.client.drives.files.DriveFileOperationsProvider
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.LocalLastReadUpdater
import id.homebase.chat.services.convo.FakeConversationLoader
import id.homebase.chat.services.convo.FakeIntroductionSender
import id.homebase.chat.services.convo.FakePayloadBundleEncryptor
import id.homebase.chat.services.convo.FakeStatusMessageSender
import id.homebase.chat.services.outbox.OptimisticWriter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Integration-ish fixture for ChatMessageActionService. Uses:
 *   - Real in-memory SQLite DB (so the outbox and ChatReadCount live in a real schema).
 *   - Real OutboxSync with setOnline(false) by default — enqueued rows stay in
 *     the DB for inspection, never uploaded. The stub uploader throws if
 *     something tries.
 *   - FakeMessageLookup, FakeLocalLastReadUpdater — the narrow seams the
 *     service under test depends on. The in-memory advance is the setter's job
 *     now (via [FakeConversationParticipantLookup.advancedLastRead]), verified
 *     in the ConversationService tests rather than here.
 *   - Real ConversationService is still passed to the ctor but markAsReadByFiles
 *     doesn't touch it.
 *
 * Set [captureHttp] = true to switch to a wire-level mode: real
 * [DriveOutboxUploader], capturing [MockEngine], `setOnline(true)`. The
 * captured requests show up in [capturedRequests], and bodies can be decrypted
 * via [sharedSecretBytes]. Use [drainOutboxAndAwaitHttp] to drive the outbox
 * through to its HTTP call deterministically.
 *
 * Use via `ChatMessageActionServiceTestFixture().use { fixture -> ... }` so the
 * in-memory DB is closed at the end of each test.
 */
class ChatMessageActionServiceTestFixture(
    private val captureHttp: Boolean = false,
) : AutoCloseable {

    val testIdentityId: Uuid = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
    val chatDriveId: Uuid = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
    val testDomain: String = "owner.test"

    /**
     * Outbound HTTP requests captured by the mock engine when `captureHttp = true`.
     * Empty otherwise.
     */
    val capturedRequests: MutableList<HttpRequestData> = mutableListOf()

    /**
     * Shared-secret bytes the fixture's credentials use. Re-exposed so tests
     * can decrypt request bodies via `CryptoHelper.decryptContent(...)`. Matches
     * what [createCredentialsManager] sets up below.
     */
    val sharedSecretBytes: ByteArray = ByteArray(16)

    lateinit var dbm: DatabaseManager
        private set
    lateinit var credentialsManager: CredentialsManager
        private set
    lateinit var eventBus: EventBus
        private set
    lateinit var outboxSync: OutboxSync
        private set
    lateinit var messageLookup: FakeMessageLookup
        private set
    lateinit var localLastReadUpdater: FakeLocalLastReadUpdater
        private set
    lateinit var participantLookup: FakeConversationParticipantLookup
        private set

    // Owned outbox scope. The uploader's post-`Completed` bookkeeping hops to
    // `Dispatchers.IO`; if we attached it to `runTest`'s `backgroundScope` the
    // hop survives `runTest` cancellation and can land DB writes after the
    // in-memory DB is closed (loaded Linux CI: SQLException → flake). By
    // owning the scope here we can `cancelAndJoin` it in [close] before
    // tearing down the DB — no late writers, no race.
    private val outboxJob = SupervisorJob()
    private val ownedOutboxScope = CoroutineScope(outboxJob + Dispatchers.IO)

    suspend fun build(
        scope: CoroutineScope = TestScope(),
    ): ChatMessageActionService {
        val outboxScope: CoroutineScope = ownedOutboxScope
        dbm = createInMemoryDbm()
        credentialsManager = createCredentialsManager(testDomain)
        eventBus = EventBus()

        // Two flavors of HTTP client:
        //   - captureHttp=false (default): every request 500s. The fixture is
        //     paired with ThrowingOutboxUploader + setOnline(false) so nothing
        //     should hit the wire — the 500 is a loud signal if it does.
        //   - captureHttp=true: requests are recorded into capturedRequests, and
        //     the chat delete-batch endpoint returns a valid response so the
        //     real DriveOutboxUploader's deleteFile path runs to completion.
        val httpClient = if (captureHttp) {
            HttpClient(MockEngine { request ->
                capturedRequests += request
                if (request.url.encodedPath.endsWith("/files/delete-batch/by-file-id")) {
                    val body = OdinSystemSerializer.serialize(
                        DeleteFileIdBatchResult(
                            results = listOf(
                                DeleteFileResult(
                                    fileId = Uuid.NIL,
                                    localFileDeleted = true,
                                    localFileNotFound = false,
                                )
                            )
                        )
                    )
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respondError(HttpStatusCode.InternalServerError)
                }
            })
        } else {
            HttpClient(MockEngine { _ ->
                respondError(HttpStatusCode.InternalServerError)
            })
        }

        val driveCache = DriveFileProviderCached(
            httpClient,
            credentialsManager,
            NoopFileOperationsProvider(),
        )
        val fileProvider = DriveFileProvider(httpClient, credentialsManager, driveCache)
        val reactionProvider = DriveFileGroupReactionProvider(httpClient, credentialsManager)

        // captureHttp=true wires the REAL DriveOutboxUploader so we exercise the
        // dispatcher (`when(uploadType) { DeleteFile -> deleteFile(...) }`) and
        // the real DriveFileProvider.deleteFiles serialization. The other three
        // providers are only on the constructor for the dispatcher's other
        // branches; the DeleteFile branch only touches `fileProvider`.
        val uploader: OutboxUploader = if (captureHttp) {
            DriveOutboxUploader(
                driveUploadProvider = DriveUploadProvider(
                    httpClient,
                    credentialsManager,
                    NoopFileOperationsProvider(),
                ),
                fileProvider = fileProvider,
                operationsProvider = DriveFileOperationsProvider(httpClient, credentialsManager),
                reactionProvider = reactionProvider,
            )
        } else {
            ThrowingOutboxUploader
        }

        outboxSync = OutboxSync(
            databaseManager = dbm,
            uploader = uploader,
            eventBus = eventBus,
            scope = outboxScope,
        ).also { it.setOnline(captureHttp) }

        messageLookup = FakeMessageLookup()
        localLastReadUpdater = FakeLocalLastReadUpdater(dbm)
        participantLookup = FakeConversationParticipantLookup()

        val optimisticWriter = OptimisticWriter(
            credentialsManager = credentialsManager,
            dbm = dbm,
            eventBus = eventBus,
        )

        // ConversationService is a ctor-required dep even though markAsReadByFiles
        // no longer calls it. Build it with the same fakes used in
        // ConversationServiceTestFixture so the instance is well-formed.
        val conversationService = ConversationService(
            credentialsManager = credentialsManager,
            payloadBundleEncryptionService = FakePayloadBundleEncryptor(),
            dbm = dbm,
            introductionProvider = FakeIntroductionSender(),
            scope = scope,
            outboxSync = outboxSync,
            chatMessageSenderService = FakeStatusMessageSender(),
            optimisticWriter = optimisticWriter,
            conversationStream = FakeConversationLoader(),
            participantLookup = participantLookup,
        )

        return ChatMessageActionService(
            conversationService = conversationService,
            participantLookup = participantLookup,
            localLastReadUpdater = localLastReadUpdater,
            messageLookup = messageLookup,
            reactionProvider = reactionProvider,
            credentialsManager = credentialsManager,
            fileProvider = fileProvider,
            dbm = dbm,
            outboxSync = outboxSync,
            optimisticWriter = optimisticWriter,
        )
    }

    /**
     * Drain the outbox via the real OutboxSync workers and wait for the
     * `Completed` event before returning. After this returns,
     * [capturedRequests] reflects every HTTP call the drained rows produced.
     *
     * Mirrors the pattern from `OutboxSyncTest` — subscribe BEFORE `send()` so
     * the test deterministically observes drain completion rather than relying
     * on `advanceUntilIdle` alone.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun drainOutboxAndAwaitHttp(testScope: TestScope) {
        check(captureHttp) {
            "drainOutboxAndAwaitHttp requires captureHttp = true; with the default fixture " +
                    "the outbox is offline and the uploader throws on use"
        }
        val completed = testScope.async {
            eventBus.events
                .filterIsInstance<BackendEvent.OutboxEvent.Completed>()
                .first()
        }
        testScope.testScheduler.runCurrent()
        outboxSync.send()
        testScope.advanceUntilIdle()
        completed.await()
    }

    /**
     * Register a [MessageUiModel] with the fake lookup so `getMessages` returns
     * it, and return the model itself. Tests pass the model into
     * `markAsReadByFiles` directly (mirroring the production code path, where
     * the in-memory window supplies the model and no DB round-trip is needed).
     * Use `.id` when a test needs the bare uniqueId.
     */
    fun seedMessage(
        conversationId: Uuid,
        senderDomain: String,
        userDateMs: Long,
        alreadyRead: Boolean = false,
        isDeleted: Boolean = false,
        isPendingSend: Boolean = false,
        id: Uuid = Uuid.random(),
        fileId: Uuid = Uuid.random(),
    ): MessageUiModel {
        val model = MessageUiModel(
            id = id,
            globalTransitId = null,
            fileId = fileId,
            conversationId = conversationId,
            content = "",
            userDate = Instant.fromEpochMilliseconds(userDateMs),
            modified = null,
            created = Instant.fromEpochMilliseconds(userDateMs),
            originalAuthor = OdinId(senderDomain),
            sender = OdinId(senderDomain),
            displayName = senderDomain,
            localReadTimestamp = if (alreadyRead) UnixTimeUtc(1L) else null,
            isDeleted = isDeleted,
            isPendingSend = isPendingSend,
            versionTag = Uuid.NIL,
            messageAppData = MessageAppData(),
            reactionPreview = null,
            previewThumbnail = null,
            payloads = null,
            keyHeader = KeyHeader.empty(),
            hasMore = false,
        )
        messageLookup.records += model
        return model
    }

    /**
     * Seed a 1:1 conversation between [testDomain] and [other] into the in-memory
     * DB so `conversationService.getConversation(convoId)` returns it with the
     * expected participants list. Used by deleteMessage tests that need a real
     * conversation row to compute recipients.
     */
    suspend fun seedOneOnOneConversation(
        other: String,
        conversationId: Uuid = Uuid.random(),
    ): Uuid {
        val fileId = Uuid.random()
        val now = Clock.System.now().epochSeconds
        val participants = listOf(testDomain, other)
        val recipientsJson = participants.joinToString(",") { "\"$it\"" }
        val contentJson = """{"title":"","version":1,"recipients":[$recipientsJson]}"""
        val escaped = contentJson.replace("\"", "\\\"")
        insertFile(
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
                "created": ${now}000,
                "updated": ${now}000,
                "transitCreated": ${now}000,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "$testDomain",
                "originalAuthor": "$testDomain",
                "appData": {
                  "uniqueId": "$conversationId",
                  "tags": null,
                  "fileType": ${ChatProtocol.ConversationFileType},
                  "dataType": 0,
                  "groupId": null,
                  "userDate": ${now}000,
                  "content": "$escaped",
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
                "originalRecipientCount": 1,
                "transferHistory": null
              },
              "priority": 300,
              "fileByteCount": 100
            }"""
        )
        return conversationId
    }

    /**
     * Seed a chat-message file row into DriveMainIndex AND register it with the
     * fake messageLookup. Returns the message uniqueId.
     *
     * Both pieces are required because `ChatMessageActionService.deleteMessage`
     * looks the message up via [MessageLookup] (for conversationId/authorship)
     * AND independently resolves its fileId via QueryBatch over DriveMainIndex
     * (`requireFileId(messageId)`).
     */
    suspend fun seedDeletableMessage(
        conversationId: Uuid,
        senderDomain: String = testDomain,
        userDateMs: Long = 100L,
        id: Uuid = Uuid.random(),
        fileId: Uuid = Uuid.random(),
        isPendingSend: Boolean = false,
    ): Uuid {
        // 1) FakeMessageLookup record (so getMessage(id) returns non-null).
        seedMessage(
            conversationId = conversationId,
            senderDomain = senderDomain,
            userDateMs = userDateMs,
            id = id,
            fileId = fileId,
            isPendingSend = isPendingSend,
        )

        // A still-pending message carries isPendingSendTag in localAppData (same as
        // the optimistic send path), so removeOptimisticFile recognises and drops it.
        val localAppDataJson =
            if (isPendingSend) """{"tags": ["${ChatProtocol.isPendingSendTag}"]}""" else "null"

        // 2) Real DriveMainIndex row keyed by uniqueId=id so requireFileId(id) finds it.
        val now = Clock.System.now().epochSeconds
        insertFile(
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
                "created": ${userDateMs}000,
                "updated": ${userDateMs}000,
                "transitCreated": ${userDateMs}000,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "$senderDomain",
                "originalAuthor": "$senderDomain",
                "appData": {
                  "uniqueId": "$id",
                  "tags": null,
                  "fileType": ${ChatProtocol.MessageFileType},
                  "dataType": 0,
                  "groupId": "$conversationId",
                  "userDate": ${userDateMs}000,
                  "content": "",
                  "previewThumbnail": null,
                  "archivalStatus": 0
                },
                "localAppData": $localAppDataJson,
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
        return id
    }

    private suspend fun insertFile(jsonHeader: String) {
        val header = OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
        val record = processor.convertFileHeaderToDriveMainIndexRecord(
            testIdentityId, chatDriveId, header
        )
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
    }

    /**
     * Drain the outbox (checkout everything eligible) so callers can inspect
     * what was enqueued. Mutates checkOutStamp — don't call twice per test.
     */
    suspend fun drainOutbox(): List<Outbox> {
        val drained = mutableListOf<Outbox>()
        while (true) {
            val row = dbm.outbox.checkout() ?: break
            drained += row
        }
        return drained
    }

    override fun close() {
        if (!::dbm.isInitialized) return
        // Drain the outbox scope before destroying the DB. The uploader's
        // post-`Completed` bookkeeping hops to `Dispatchers.IO`; without this
        // join those writes can land after `dbm.close()` and throw
        // `SQLException("database has been closed")` from an IO thread,
        // which `runTest` reports as a test failure on loaded Linux CI.
        runBlocking { outboxJob.cancelAndJoin() }
        dbm.close()
    }

    private fun createInMemoryDbm(): DatabaseManager = DatabaseManager({
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OdinDatabase.Schema.create(driver)
        driver
    })

    private suspend fun createCredentialsManager(domain: String): CredentialsManager {
        val cm = CredentialsManager()
        cm.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId(domain),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16)),
            )
        )
        return cm
    }

}

/**
 * Cheap FileOperationsProvider used only so DriveFileProviderCached's ctor can run.
 * Every method throws — if markAsReadByFiles (or any test path we expect NOT to hit
 * file I/O) actually reaches file ops, the test fails loudly instead of silently.
 *
 * Each instance gets its own unique cache subdirectory so multiple test classes'
 * Coil DiskCache instances don't contend on the same on-disk journal.
 */
private class NoopFileOperationsProvider : FileOperationsProvider {
    private val uniqueCacheDir: String =
        java.nio.file.Files.createTempDirectory("hb-chat-test-cache").toString()
    private fun nope(): Nothing =
        error("NoopFileOperationsProvider: no file IO expected in markAsReadByFiles tests")
    override fun openFileInput(path: String) = nope()
    override suspend fun readFileBytes(path: String) = nope()
    override fun deleteTempFile(path: String) = nope()
    override fun getCacheDirectory(): String = uniqueCacheDir
    override fun getFileSize(path: String) = nope()
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String) = nope()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String) = nope()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = nope()
}

// ---------- Test doubles ----------

class FakeMessageLookup : MessageLookup {
    val records = mutableListOf<MessageUiModel>()

    /** Number of `getMessages(ids, conversationId)` invocations against this
     *  fake. The MarkAsRead drop-the-DB-load test asserts this stays 0 after
     *  `markAsReadByFiles` — proving no by-IDs DB round-trip is happening on
     *  the scroll-visibility / per-message swipe paths. */
    var getMessagesCallCount = 0
        private set

    override suspend fun getMessage(messageId: Uuid): MessageUiModel? =
        records.firstOrNull { it.id == messageId }

    override suspend fun getMessages(
        messageIds: List<Uuid>,
        conversationId: Uuid,
    ): BatchResult<MessageUiModel> {
        getMessagesCallCount++
        val wanted = messageIds.toSet()
        // Mirror the real implementation: scope the lookup to the supplied
        // conversation so a test can register messages across multiple
        // conversations and still get the right slice back.
        val matched = records.filter { it.id in wanted && it.conversationId == conversationId }
        return BatchResult(
            records = matched,
            hasMoreRows = false,
            cursor = QueryBatchCursor(),
        )
    }

    override suspend fun getMessageFile(messageId: Uuid): id.homebase.api.client.drives.HomebaseFile? =
        error("FakeMessageLookup.getMessageFile not exercised by tests using this fixture")

    override suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String? =
        error("FakeMessageLookup.loadFullMessage not exercised by tests using this fixture")

    /** Mirrors the real implementation's contract: returns the cached fileId
     *  if the message has been seeded into [records], otherwise null so the
     *  caller falls through to its DB lookup. Existing requireFileId tests
     *  seed only the DriveMainIndex row (not [records]), so they exercise
     *  the DB fall-through; tests that want to assert the cache-hit path
     *  add the message to [records] explicitly. */
    override fun findCachedFileId(messageId: Uuid): Uuid? =
        records.firstOrNull { it.id == messageId }?.fileId
}

/**
 * Mirrors the production setter's only-increases + ChatReadCount-upsert
 * contract so tests asserting `dbm.chatReadCount.selectLastReadTimeMs(...)`
 * behave the same as production. The fake stops short of stamping the
 * conversation file / enqueueing an outbox writeback — those paths aren't
 * exercised by ChatMessageActionService tests.
 */
class FakeLocalLastReadUpdater(
    private val dbm: id.homebase.api.sync.database.DatabaseManager? = null,
) : LocalLastReadUpdater {
    data class Call(val conversationId: Uuid, val newLastReadTime: UnixTimeUtc)
    val calls = mutableListOf<Call>()
    override suspend fun updateLocalLastReadTime(
        conversationId: Uuid,
        newLastReadTime: UnixTimeUtc,
    ) {
        calls += Call(conversationId, newLastReadTime)
        // Mirror the production setter's eager ChatReadCount upsert so tests
        // that assert against selectLastReadTimeMs see the same row.
        dbm?.chatReadCount?.upsertLastReadTime(conversationId, newLastReadTime)
    }
}

/**
 * Default behaviour: returns null from `getConversationById`, so
 * `ChatMessageActionService.markAsReadByFiles` skips the in-memory `lastRead`
 * gate and exercises the full upsert + enrich path the rest of the suite
 * asserts. Tests that want to exercise the gate can stash a stub model via
 * [setLastRead] keyed by id.
 */
class FakeConversationParticipantLookup :
    id.homebase.chat.services.convo.ConversationParticipantLookup {
    private val conversations = mutableMapOf<Uuid, id.homebase.chat.data.ConversationUiModel>()

    /**
     * Stash a stub conversation whose `lastRead` and `latestMessageTimestamp`
     * are what `markAsReadByFiles`'s gate / `markAllAsRead` will read.
     * `latestMessageTimestamp` defaults to the same value as `lastRead`
     * (the "fully read" case); pass it explicitly to simulate a backlog.
     */
    fun setLastRead(
        conversationId: Uuid,
        lastRead: kotlin.time.Instant,
        latestMessageTimestamp: kotlin.time.Instant = lastRead,
        dirty: Boolean = false,
    ) {
        conversations[conversationId] = id.homebase.chat.data.ConversationUiModel(
            id = conversationId,
            name = "",
            lastMessage = "",
            latestMessageTimestamp = latestMessageTimestamp,
            avatarInitials = "",
            avatarTiny = null,
            lastRead = lastRead,
            dirty = dirty,
            avatarModel = id.homebase.core.avatars.ConversationAvatarModel(
                type = id.homebase.core.avatars.ConversationAvatarModel.Type.GroupFallback
            ),
            admins = emptySet(),
        )
    }

    /**
     * Production override: the list-level [ConversationParticipantLookup.advancedLastRead].
     * Runs the entity's single advance transition (gate → move lastRead → set
     * dirty) on the in-memory entry. Skips the unreadCount re-derive the real
     * ConversationStream does (no DB here) — the tests that use this assert on
     * lastRead/dirty, not the badge.
     */
    override suspend fun advancedLastRead(conversationId: Uuid, candidate: kotlin.time.Instant) {
        val convo = conversations[conversationId] ?: return
        conversations[conversationId] = convo.advancedLastRead(candidate)
    }

    override fun getConversationById(conversationId: Uuid):
            id.homebase.chat.data.ConversationUiModel? = conversations[conversationId]

    override suspend fun getRecipients(
        conversationId: Uuid,
        additionalRecipients: List<id.homebase.api.common.OdinId>,
        recipientOverride: List<id.homebase.api.common.OdinId>?,
    ): List<id.homebase.api.common.OdinId> = emptyList()

    // lastRead dirty tracking — delegates the transitions to the entity, same
    // as ConversationStream does in production. `dirty` is set via the entity's
    // advancedLastRead (through the [advancedLastRead] override / [setLastRead]);
    // this fake only reads + clears it.
    override fun getDirtyConversationIds(): List<Uuid> =
        conversations.values.filter { it.dirty }.map { it.id }

    override suspend fun clearLastReadDirtyIfUnchanged(
        conversationId: Uuid,
        pushed: id.homebase.api.common.time.UnixTimeUtc,
    ) {
        val convo = conversations[conversationId] ?: return
        conversations[conversationId] = convo.clearedDirtyIfUnchanged(pushed.milliseconds)
    }
}

private object ThrowingOutboxUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        error("OutboxUploader.upload should never be called in tests (setOnline(false))")
    }
}
