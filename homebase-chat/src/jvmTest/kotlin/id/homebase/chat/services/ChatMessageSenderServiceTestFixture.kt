package id.homebase.chat.services

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.ConversationParticipantLookup
import id.homebase.chat.services.convo.FakePayloadBundleEncryptor
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.avatars.ConversationAvatarModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Fixture for [ChatMessageSenderService] focused on the static-shape contract
 * of `sendMessageInternal`'s outbox enqueue: the resulting `(uniqueId,
 * dependencyUniqueId)` row, given the caller's `previousMessageUniqueId` and
 * the in-memory per-conversation chain tracker the service maintains.
 *
 * Wiring choices:
 *   - Real in-memory SQLite DB + real [OutboxSync] with `setOnline(false)`,
 *     same pattern as [id.homebase.chat.services.convo.ConversationServiceTestFixture].
 *     Outbox rows are inspectable via [drainOutbox] / [drainOutboxInDependencyOrder].
 *   - Real [OptimisticWriter] (writes to the in-memory DB).
 *   - Fake [ConversationParticipantLookup] ([SeedableConversationLookup]) so
 *     tests can pre-register a conversation and its recipients without paying
 *     the cost of constructing a real [id.homebase.chat.services.convo.ConversationStream].
 *   - Fake [PayloadBundleEncryptor] returning empty bundles — the tests use
 *     plain text messages that fit in the file header (`buildMessageContentAndBundle`
 *     short-circuits before any payload work).
 *   - [FakeMessageLookup] for `chatMessageStream` — the new chain-related code path
 *     in `sendMessageInternal` does not call it, but the constructor requires
 *     a non-null instance.
 *   - Real [DriveFileProvider] backed by a [MockEngine] that returns 500 for
 *     every request. Construction requires it; the send path doesn't call it.
 *   - [SenderNoopFileOperationsProvider] — only invoked for over-budget messages,
 *     and the tests stay under the budget so it's never reached.
 *
 * Use via `ChatMessageSenderServiceTestFixture().use { fixture -> ... }` so
 * the in-memory DB is closed at the end of each test.
 */
class ChatMessageSenderServiceTestFixture : AutoCloseable {

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
    lateinit var conversationLookup: SeedableConversationLookup
        private set
    lateinit var payloadEncryptor: FakePayloadBundleEncryptor
        private set
    lateinit var optimisticWriter: OptimisticWriter
        private set

    /**
     * @param messageLookup override for tests that need `getMessage`/`getMessageFile`
     *   to return real data (e.g. the resend tests' DB-backed lookup).
     * @param engineHandler override for the [MockEngine] backing [DriveFileProvider] —
     *   the default 500s everything (send-path tests never reach it); resend tests
     *   script the by-uid server check with it.
     */
    suspend fun build(
        scope: CoroutineScope = TestScope(),
        messageLookup: ((DatabaseManager) -> MessageLookup)? = null,
        engineHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            respondError(HttpStatusCode.InternalServerError)
        },
    ): ChatMessageSenderService {
        dbm = createInMemoryDbm()
        credentialsManager = createCredentialsManager(testDomain)
        eventBus = EventBus()

        outboxSync = OutboxSync(
            databaseManager = dbm,
            uploader = SenderThrowingOutboxUploader,
            eventBus = eventBus,
            scope = scope,
        ).also { it.setOnline(false) }

        conversationLookup = SeedableConversationLookup(testDomain)
        payloadEncryptor = FakePayloadBundleEncryptor()

        optimisticWriter = OptimisticWriter(
            credentialsManager = credentialsManager,
            dbm = dbm,
            eventBus = eventBus,
            outboxSync = outboxSync,
        )

        // Real DriveFileProvider with a scriptable mock engine (default: 500s
        // every request — the send path doesn't call into it).
        val httpClient = HttpClient(MockEngine { request -> engineHandler(request) })
        val driveCache = DriveFileProviderCached(
            httpClient,
            credentialsManager,
            SenderNoopFileOperationsProvider(),
        )
        val driveFileProvider = DriveFileProvider(httpClient, credentialsManager, driveCache)

        return ChatMessageSenderService(
            outboxSync = outboxSync,
            conversationStream = conversationLookup,
            payloadBundleEncryptionService = payloadEncryptor,
            scope = scope,
            chatMessageStream = messageLookup?.invoke(dbm) ?: FakeMessageLookup(),
            optimisticWriter = optimisticWriter,
            fileOperationsProvider = SenderNoopFileOperationsProvider(),
            driveFileProvider = driveFileProvider,
            shareSuggestionDonor = ShareSuggestionDonor(),
        )
    }

    /**
     * Register a conversation so [ChatMessageSenderService] sees a non-null
     * [ConversationUiModel] when it queries `conversationStream.getConversationById`.
     * Recipients are everything except [testDomain].
     */
    fun seedConversation(
        conversationId: Uuid = Uuid.random(),
        others: List<String>,
        isGroup: Boolean = others.size > 1,
    ): Uuid {
        val participants = listOf(testDomain) + others
        conversationLookup.register(
            ConversationUiModel(
                id = conversationId,
                name = "test",
                lastMessage = "",
                latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
                admins = setOf(OdinId(testDomain)),
                unreadCount = 0,
                avatarTiny = null,
                avatarInitials = "",
                avatarUrl = "",
                participants = participants.map { OdinId(it) },
                lastRead = Instant.fromEpochMilliseconds(0),
                avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
                isGroup = isGroup,
                conversationState = ConversationState.Active,
            )
        )
        return conversationId
    }

    /**
     * Insert a synthetic outbox row with `uniqueId = conversationId` and no
     * dependency, simulating the conversation-file row that
     * `ConversationService.createConversation` would have enqueued. Tests use
     * this to verify "the message waits for the conversation row" without
     * standing up the full [ConversationService] graph.
     *
     * The row is inserted via the real [outboxSync.tryEnqueue] using a minimal
     * [DeleteFilesByGroupIdOutboxRequest] — type doesn't matter for the chain
     * test (the checkout SQL only cares about the uniqueId / dependencyUniqueId
     * pair), and this request doesn't require encryption or attached payloads.
     */
    suspend fun enqueuePendingConversationFileRow(conversationId: Uuid) {
        outboxSync.tryEnqueue(
            id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest(
                driveId = chatDriveId,
                groupIds = listOf(conversationId),
            ),
        )
    }

    suspend fun outboxRowCount(): Long = dbm.outbox.count()

    /**
     * Drain checked-out rows. Same caveat as the helper in
     * `ConversationServiceTestFixture`: rows whose `dependencyUniqueId` still
     * exists in the table won't surface — use [drainOutboxInDependencyOrder]
     * for chained enqueues.
     */
    suspend fun drainOutbox(): List<Outbox> {
        val drained = mutableListOf<Outbox>()
        while (true) {
            val row = dbm.outbox.checkout() ?: break
            drained += row
        }
        return drained
    }

    /**
     * Drain ALL rows by checkout-and-delete in a loop, surfacing rows in
     * dependency order (head first). Use to assert chain shape.
     */
    suspend fun drainOutboxInDependencyOrder(): List<Outbox> {
        val drained = mutableListOf<Outbox>()
        while (true) {
            val row = dbm.outbox.checkout() ?: break
            drained += row
            dbm.outbox.deleteByRowId(row.rowId)
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

// ---------- Test doubles (local to this fixture) ----------

class SeedableConversationLookup(private val testDomain: String) : ConversationParticipantLookup {
    private val conversations = mutableMapOf<Uuid, ConversationUiModel>()

    fun register(conversation: ConversationUiModel) {
        conversations[conversation.id] = conversation
    }

    override fun getConversationById(conversationId: Uuid): ConversationUiModel? =
        conversations[conversationId]

    override suspend fun getRecipients(
        conversationId: Uuid,
        additionalRecipients: List<OdinId>,
        recipientOverride: List<OdinId>?,
    ): List<OdinId> {
        val base = recipientOverride
            ?: conversations[conversationId]?.participants
            ?: return emptyList()
        return (base + additionalRecipients)
            .filter { it.domainName != testDomain }
            .distinct()
    }

    // lastRead dirty tracking — these chain-shape tests don't exercise the
    // writeback, but the interface requires them.
    override suspend fun advancedLastRead(
        conversationId: Uuid,
        candidate: kotlin.time.Instant,
    ) {
        val convo = conversations[conversationId] ?: return
        conversations[conversationId] = convo.advancedLastRead(candidate)
    }

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

private class SenderNoopFileOperationsProvider : FileOperationsProvider {
    // Per-instance unique dir so multiple test classes' Coil DiskCache instances
    // don't contend on the same on-disk journal.
    private val uniqueCacheDir: String =
        java.nio.file.Files.createTempDirectory("hb-chat-sender-test-cache").toString()
    private fun nope(): Nothing =
        error("SenderNoopFileOperationsProvider: no file IO expected in chain-shape tests")

    override fun openFileInput(path: String) = nope()
    override suspend fun readFileBytes(path: String) = nope()
    override fun deleteTempFile(path: String) = nope()
    override fun getCacheDirectory(): String = uniqueCacheDir
    override fun getFileSize(path: String) = nope()
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String) = nope()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String) = nope()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = nope()
}

private object SenderThrowingOutboxUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        error("OutboxUploader.upload should never be called in tests (setOnline(false))")
    }
}
