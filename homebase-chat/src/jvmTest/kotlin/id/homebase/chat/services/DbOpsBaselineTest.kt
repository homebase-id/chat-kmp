@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.chat.services

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.RecipientTransferHistory
import id.homebase.api.client.drives.TransferHistorySummary
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.DriveWebSocketUpsertWorker
import id.homebase.api.sync.database.CursorStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.debug.State
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * How many statements each chat flow runs, and from where, against checked-in baselines (see
 * [DbOpsBaseline]). Plain text messages only: the auto-pin and view-once hooks aren't wired,
 * and they read nothing for plain text.
 */
class DbOpsBaselineTest {

    private lateinit var counter: CountingSqlDriver
    private val conversationId = ChatMessageStreamTestFixture.conversationId

    private suspend fun TestScope.fixture(messages: Int = 100): Pair<ChatMessageStreamTestFixture, List<HomebaseFile>> {
        val fixture = ChatMessageStreamTestFixture.build(this, StandardTestDispatcher(testScheduler)) {
            CountingSqlDriver(it, ::issuingFrames).also { driver -> counter = driver }
        }
        val seeded = List(messages) { fixture.buildMessageFile(userDateMs = 1_000L + it) }
        seeded.forEach { fixture.commit(it) }
        settle()
        return fixture to seeded
    }

    private suspend fun TestScope.openConversation(): Pair<ChatMessageStreamTestFixture, List<HomebaseFile>> =
        fixture().also { (fixture, _) ->
            fixture.stream.loadConversation(conversationId)
            settle()
        }

    @Test
    fun openConversation() = runTest {
        val (fixture, _) = fixture()
        counter.startRecording()

        fixture.stream.loadConversation(conversationId)
        settle()

        DbOpsBaseline.assertMatches("open-conversation", "Open a conversation of 100 messages", counter.ops)
        fixture.close()
    }

    @Test
    fun openConversationAroundAMessage() = runTest {
        val (fixture, seeded) = fixture()
        counter.startRecording()

        fixture.stream.loadConversationAroundMessage(conversationId, seeded[50].uniqueId())
        settle()

        DbOpsBaseline.assertMatches(
            "open-conversation-around-message",
            "Open a conversation of 100 messages at a saved position (message 50)",
            counter.ops,
        )
        fixture.close()
    }

    @Test
    fun incomingMessageInAnOpenConversation() = runTest {
        val (fixture, _) = openConversation()
        counter.startRecording()

        val incoming = fixture.buildMessageFile(userDateMs = 5_000L)
        worker(fixture).submit(incoming)
        settleUntil { fixture.stream.isMessageInWindow(conversationId, incoming.uniqueId()) }

        DbOpsBaseline.assertMatches(
            "incoming-message",
            "A new message arrives over the WebSocket while its conversation is open",
            counter.ops,
        )
        fixture.close()
    }

    @Test
    fun readReceiptForASentMessage() = runTest {
        readReceipt(
            "read-receipt",
            "The recipient reads a sent message in the open conversation; the push carries a newer modified",
            modifiedDelta = 1,
        )
    }

    @Test
    fun readReceiptWithAnEqualModified() = runTest {
        readReceipt(
            "read-receipt-equal-modified",
            "The same read receipt, but its modified ties the stored row's (server-owned fields are adopted)",
            modifiedDelta = 0,
        )
    }

    private suspend fun TestScope.readReceipt(name: String, title: String, modifiedDelta: Long) {
        val (fixture, seeded) = openConversation()
        counter.startRecording()

        val sent = seeded.last()
        val before = fixture.stream.messageInWindow(conversationId, sent.uniqueId())
        worker(fixture).submit(
            sent.copy(
                fileMetadata = sent.fileMetadata.copy(
                    updated = UnixTimeUtc(sent.fileMetadata.updated.milliseconds + modifiedDelta),
                ),
                serverMetadata = sent.serverMetadata.copy(
                    originalRecipientCount = 1,
                    transferHistory = RecipientTransferHistory(TransferHistorySummary(0, 0, 0, 1)),
                ),
            )
        )
        settleUntil { fixture.stream.messageInWindow(conversationId, sent.uniqueId()) != before }

        DbOpsBaseline.assertMatches(name, title, counter.ops)
        fixture.close()
    }

    @Test
    fun sendAMessageInAnOpenConversation() = runTest {
        val (fixture, seeded) = openConversation()
        val sender = testChatMessageSenderService(
            scope = backgroundScope,
            outboxSync = fixture.outboxSync,
            optimisticWriter = fixture.optimisticWriter,
            credentialsManager = fixture.credentialsManager,
            conversations = SeedableConversationLookup(ChatMessageStreamTestFixture.testDomain).apply {
                register(testConversation(conversationId, ChatMessageStreamTestFixture.testDomain, listOf("frodo.test")))
            },
            messageLookup = fixture.stream,
        )
        counter.startRecording()

        val messageId = Uuid.random()
        sender.sendNewMessage(
            messageUniqueId = messageId,
            conversationId = conversationId,
            messageText = "hello",
            previousMessageUniqueId = seeded.last().uniqueId(),
            payloadBundle = null,
        )
        settleUntil { fixture.stream.isMessageInWindow(conversationId, messageId) }

        DbOpsBaseline.assertMatches(
            "send-message",
            "Send a text message in the open conversation (offline outbox: write and enqueue, no upload)",
            counter.ops,
        )
        fixture.close()
    }

    @Test
    fun backgroundWakeSyncsNewMessages() = runTest {
        val (fixture, _) = fixture()
        val chatDriveId = ChatMessageStreamTestFixture.chatDriveId
        CursorStorage(fixture.dbm, chatDriveId).saveCursor(QueryBatchCursor.fromStartPoint(UnixTimeUtc(1_099L)))
        val incoming = List(3) { fixture.buildMessageFile(userDateMs = 5_000L + it) }
        val nextCursor = JsonPrimitive(QueryBatchCursor.fromStartPoint(UnixTimeUtc(5_002L)).toJson()).toString()
        var served = false
        val engine = MockEngine { request ->
            val results = if (served) "" else incoming.joinToString(",") { it.asServerFile() }
            served = true
            val batch = """"invalidDrive":false,"queryTime":0,"includeMetadataHeader":true,"cursorState":$nextCursor,""" +
                """"searchResults":[$results],"hasMoreRows":false"""
            val body = if (request.url.encodedPath == "/api/v2/drives/query-batch-collection") {
                """{"results":[{"name":"$chatDriveId","status":"ok",$batch}]}"""
            } else {
                """{"name":null,$batch}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val manager = DriveSyncManager(
            driveQueryProvider = DriveQueryProvider(HttpClient(engine), fixture.credentialsManager),
            credentialsManager = fixture.credentialsManager,
            eventBus = fixture.eventBus,
            scope = backgroundScope,
            databaseManager = fixture.dbm,
            mandatoryDrives = mapOf(chatDriveId to "chat"),
        )
        var synced = false
        backgroundScope.launch {
            fixture.eventBus.events.collect { if (it is BackendEvent.DriveEvent.Stopped && it.totalCount > 0) synced = true }
        }
        settle()
        counter.startRecording()

        manager.start()
        manager.syncAll()
        settleUntil { synced }

        DbOpsBaseline.assertMatches(
            "background-wake",
            "A background wake syncs the chat drive (cursor stored) and gets 3 new messages; no conversation is open",
            counter.ops,
        )
        fixture.close()
    }

    // advanceUntilIdle() stops once only background work is left, and the worker and the stream's
    // event collectors run in backgroundScope.
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
        advanceUntilIdle()
    }

    // processIncrementalBatch maps the batch on Dispatchers.Default, outside virtual time.
    private fun TestScope.settleUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            settle()
            if (done()) break
            check(System.nanoTime() < deadline) { "the flow didn't finish" }
            Thread.sleep(5)
        }
        settle()
    }

    private fun TestScope.worker(fixture: ChatMessageStreamTestFixture) = DriveWebSocketUpsertWorker(
        ChatMessageStreamTestFixture.testIdentityId,
        ChatMessageStreamTestFixture.chatDriveId,
        fixture.dbm,
        fixture.eventBus,
        backgroundScope,
    )

    private fun HomebaseFile.uniqueId() = fileMetadata.appData.uniqueId!!

    // The query endpoints return the server's shape: a shared-secret-encrypted key header in place of
    // keyHeader. These messages aren't encrypted, so the header is never decrypted.
    private fun HomebaseFile.asServerFile(): String {
        val client = OdinSystemSerializer.json.parseToJsonElement(OdinSystemSerializer.serialize(this)).jsonObject
        return JsonObject(
            client.filterKeys { it != "keyHeader" && it != "serverFileIsEncrypted" } +
                ("sharedSecretEncryptedKeyHeader" to OdinSystemSerializer.json.parseToJsonElement(
                    """{"encryptionVersion":1,"iv":"AAAAAAAAAAAAAAAAAAAAAA==","encryptedAesKey":"AAAA"}"""
                ))
        ).toString()
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun installProbes() = DebugProbes.install()

        @AfterClass
        @JvmStatic
        fun uninstallProbes() = DebugProbes.uninstall()

        /**
         * The frames of the continuation running on this thread, then its coroutine's callers:
         * after a resume the thread's stack ends at the resumed continuation. Matched by that
         * continuation's class, so a coroutine running elsewhere isn't credited.
         */
        private fun issuingFrames(): List<StackTraceElement> {
            val thread = Thread.currentThread().stackTrace.filterNot { it.className.startsWith(DbOpsBaselineTest::class.java.name) }
            val boundary = thread.indexOfFirst { it.className == "kotlin.coroutines.jvm.internal.BaseContinuationImpl" }
            if (boundary < 0) return thread
            val running = thread.subList(0, boundary)
            val resumed = running.lastOrNull { it.methodName == "invokeSuspend" }?.className ?: return running
            val callers = DebugProbes.dumpCoroutinesInfo()
                .filter { it.state == State.RUNNING }
                .map { it.lastObservedStackTrace() }
                .firstOrNull { it.firstOrNull()?.className == resumed }
                .orEmpty()
            return running + callers
        }
    }
}
