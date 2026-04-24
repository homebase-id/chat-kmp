package id.homebase.chat.services

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.LocalLastReadUpdater
import id.homebase.chat.services.convo.UnreadCountEnricher
import id.homebase.chat.services.convo.FakeConversationLoader
import id.homebase.chat.services.convo.FakeIntroductionSender
import id.homebase.chat.services.convo.FakePayloadBundleEncryptor
import id.homebase.chat.services.convo.FakeStatusMessageSender
import id.homebase.chat.services.outbox.OptimisticWriter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Integration-ish fixture for ChatMessageActionService. Uses:
 *   - Real in-memory SQLite DB (so the outbox and ChatReadCount live in a real schema).
 *   - Real OutboxSync with setOnline(false) — enqueued rows stay in the DB for
 *     inspection, never uploaded. The stub uploader throws if something tries.
 *   - FakeMessageLookup, FakeLocalLastReadUpdater, FakeUnreadCountEnricher — the
 *     three narrow seams the service under test depends on.
 *   - Real ConversationService is still passed to the ctor but markAsReadByFiles
 *     doesn't touch it.
 *
 * Use via `ChatMessageActionServiceTestFixture().use { fixture -> ... }` so the
 * in-memory DB is closed at the end of each test.
 */
class ChatMessageActionServiceTestFixture : AutoCloseable {

    val testIdentityId: Uuid = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
    val chatDriveId: Uuid = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
    val testDomain: String = "owner.test"

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
    lateinit var unreadCountEnricher: FakeUnreadCountEnricher
        private set

    suspend fun build(scope: CoroutineScope = TestScope()): ChatMessageActionService {
        dbm = createInMemoryDbm()
        credentialsManager = createCredentialsManager(testDomain)
        eventBus = EventBus()
        outboxSync = OutboxSync(
            databaseManager = dbm,
            uploader = ThrowingOutboxUploader,
            eventBus = eventBus,
            scope = scope,
        ).also { it.setOnline(false) }

        messageLookup = FakeMessageLookup()
        localLastReadUpdater = FakeLocalLastReadUpdater()
        unreadCountEnricher = FakeUnreadCountEnricher()

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
        )

        // reactionProvider and fileProvider are ctor-required but never invoked by
        // markAsReadByFiles. Build them with a MockEngine that 500s any request so
        // that if a future test reaches them, the failure is loud and clear rather
        // than silent or NPE.
        val httpClient = HttpClient(MockEngine { _ ->
            respondError(HttpStatusCode.InternalServerError)
        })
        val driveCache = DriveFileProviderCached(
            httpClient,
            credentialsManager,
            NoopFileOperationsProvider(),
        )

        return ChatMessageActionService(
            conversationService = conversationService,
            localLastReadUpdater = localLastReadUpdater,
            unreadCountEnricher = unreadCountEnricher,
            messageLookup = messageLookup,
            reactionProvider = DriveFileGroupReactionProvider(httpClient, credentialsManager),
            credentialsManager = credentialsManager,
            fileProvider = DriveFileProvider(httpClient, credentialsManager, driveCache),
            dbm = dbm,
            outboxSync = outboxSync,
            optimisticWriter = optimisticWriter,
        )
    }

    /**
     * Register a MessageUiModel with the fake lookup so getMessages([id]) returns it.
     * Returns the message's uniqueId so tests can pass it into markAsReadByFiles.
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
    ): Uuid {
        messageLookup.records += MessageUiModel(
            id = id,
            globalTransitId = null,
            fileId = fileId,
            conversationId = conversationId,
            content = "",
            userDate = Instant.fromEpochMilliseconds(userDateMs),
            modified = null,
            created = Instant.fromEpochMilliseconds(userDateMs),
            originalAuthor = OdinId(senderDomain),
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
        return id
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
        if (::dbm.isInitialized) dbm.close()
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
 */
private class NoopFileOperationsProvider : FileOperationsProvider {
    private fun nope(): Nothing =
        error("NoopFileOperationsProvider: no file IO expected in markAsReadByFiles tests")
    override fun openFileInput(path: String) = nope()
    override suspend fun readFileBytes(path: String) = nope()
    override fun deleteTempFile(path: String) = nope()
    override fun getCacheDirectory(): String = System.getProperty("java.io.tmpdir") ?: "/tmp"
    override fun getFileSize(path: String) = nope()
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String) = nope()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = nope()
}

// ---------- Test doubles ----------

class FakeMessageLookup : MessageLookup {
    val records = mutableListOf<MessageUiModel>()

    override suspend fun getMessage(messageId: Uuid): MessageUiModel? =
        records.firstOrNull { it.id == messageId }

    override suspend fun getMessages(messageIds: List<Uuid>): BatchResult<MessageUiModel> {
        val wanted = messageIds.toSet()
        val matched = records.filter { it.id in wanted }
        return BatchResult(
            records = matched,
            hasMoreRows = false,
            cursor = QueryBatchCursor(),
        )
    }
}

class FakeLocalLastReadUpdater : LocalLastReadUpdater {
    data class Call(val conversationId: Uuid, val newLastReadTime: UnixTimeUtc)
    val calls = mutableListOf<Call>()
    override suspend fun updateLocalLastReadTime(
        conversationId: Uuid,
        newLastReadTime: UnixTimeUtc,
    ) {
        calls += Call(conversationId, newLastReadTime)
    }
}

class FakeUnreadCountEnricher : UnreadCountEnricher {
    val calls = mutableListOf<Uuid>()
    override suspend fun enrichConversationWithUnreadCounts(conversationId: Uuid) {
        calls += conversationId
    }
}

private object ThrowingOutboxUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        error("OutboxUploader.upload should never be called in tests (setOnline(false))")
    }
}
