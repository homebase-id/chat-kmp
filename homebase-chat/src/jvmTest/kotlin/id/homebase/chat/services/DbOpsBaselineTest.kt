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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.ContinuationInterceptor
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

    // runTest's own dispatcher: withContext then runs in place, so each statement's callers stay on
    // the thread's stack, and nothing leaves virtual time.
    private val TestScope.dispatcher get() = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher

    private suspend fun TestScope.fixture(): Pair<ChatMessageStreamTestFixture, List<HomebaseFile>> {
        val fixture = ChatMessageStreamTestFixture.build(this, dispatcher) {
            CountingSqlDriver(it).also { driver -> counter = driver }
        }
        val seeded = List(100) { fixture.buildMessageFile(userDateMs = 1_000L + it) }
        seeded.forEach { fixture.commit(it) }
        settle()
        return fixture to seeded
    }

    private suspend fun TestScope.withOpenConversation(): Pair<ChatMessageStreamTestFixture, List<HomebaseFile>> =
        fixture().also { (fixture, _) ->
            fixture.stream.loadConversation(conversationId)
            settle()
        }

    private suspend fun TestScope.record(
        fixture: ChatMessageStreamTestFixture,
        name: String,
        title: String,
        flow: suspend () -> Unit,
        done: () -> Boolean = { true },
    ) {
        try {
            counter.startRecording()
            flow()
            settle()
            check(done()) { "the flow didn't finish" }
            DbOpsBaseline.assertMatches(name, title, counter.ops)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun openConversation() = runTest {
        val (fixture, _) = fixture()
        record(fixture, "open-conversation", "Open a conversation of 100 messages", {
            fixture.stream.loadConversation(conversationId)
        })
    }

    @Test
    fun openConversationAroundAMessage() = runTest {
        val (fixture, seeded) = fixture()
        record(fixture, "open-conversation-around-message", "Open a conversation of 100 messages at a saved position (message 50)", {
            fixture.stream.loadConversationAroundMessage(conversationId, seeded[50].uniqueId())
        })
    }

    @Test
    fun incomingMessageInAnOpenConversation() = runTest {
        val (fixture, _) = withOpenConversation()
        val incoming = fixture.buildMessageFile(userDateMs = 5_000L)
        record(
            fixture,
            "incoming-message",
            "A new message arrives over the WebSocket while its conversation is open",
            { worker(fixture).submit(incoming) },
            done = { fixture.stream.isMessageInWindow(conversationId, incoming.uniqueId()) },
        )
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
        val (fixture, seeded) = withOpenConversation()
        val sent = seeded.last()
        val before = fixture.stream.messageInWindow(conversationId, sent.uniqueId())
        val read = sent.copy(
            fileMetadata = sent.fileMetadata.copy(updated = UnixTimeUtc(sent.fileMetadata.updated.milliseconds + modifiedDelta)),
            serverMetadata = sent.serverMetadata.copy(
                originalRecipientCount = 1,
                transferHistory = RecipientTransferHistory(TransferHistorySummary(0, 0, 0, 1)),
            ),
        )
        record(
            fixture, name, title,
            { worker(fixture).submit(read) },
            done = { fixture.stream.messageInWindow(conversationId, sent.uniqueId()) != before },
        )
    }

    @Test
    fun sendAMessageInAnOpenConversation() = runTest {
        val (fixture, seeded) = withOpenConversation()
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
        val messageId = Uuid.random()
        record(
            fixture,
            "send-message",
            "Send a text message in the open conversation (offline outbox: write and enqueue, no upload)",
            {
                sender.sendNewMessage(
                    messageUniqueId = messageId,
                    conversationId = conversationId,
                    messageText = "hello",
                    previousMessageUniqueId = seeded.last().uniqueId(),
                    payloadBundle = null,
                )
            },
            done = { fixture.stream.isMessageInWindow(conversationId, messageId) },
        )
    }

    @Test
    fun backgroundWakeSyncsNewMessages() = runTest {
        val (fixture, _) = fixture()
        val chatDriveId = ChatMessageStreamTestFixture.chatDriveId
        CursorStorage(fixture.dbm, chatDriveId).saveCursor(QueryBatchCursor.fromStartPoint(UnixTimeUtc(1_099L)))
        val incoming = List(3) { fixture.buildMessageFile(userDateMs = 5_000L + it) }
        val nextCursor = JsonPrimitive(QueryBatchCursor.fromStartPoint(UnixTimeUtc(5_002L)).toJson()).toString()
        var served = false
        val httpClient = HttpClient(MockEngine) {
            engine {
                dispatcher = this@runTest.dispatcher
                addHandler { request ->
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
            }
        }
        val manager = DriveSyncManager(
            driveQueryProvider = DriveQueryProvider(httpClient, fixture.credentialsManager),
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
        record(
            fixture,
            "background-wake",
            "A background wake syncs the chat drive (cursor stored) and gets 3 new messages; no conversation is open",
            {
                manager.start()
                manager.syncAll()
            },
            done = { synced },
        )
    }

    // advanceUntilIdle() stops once only background work is left, and the worker and the stream's
    // event collectors run in backgroundScope.
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
        advanceUntilIdle()
    }

    private fun TestScope.worker(fixture: ChatMessageStreamTestFixture) = DriveWebSocketUpsertWorker(
        ChatMessageStreamTestFixture.testIdentityId,
        ChatMessageStreamTestFixture.chatDriveId,
        fixture.dbm,
        fixture.eventBus,
        backgroundScope,
    )

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
}
