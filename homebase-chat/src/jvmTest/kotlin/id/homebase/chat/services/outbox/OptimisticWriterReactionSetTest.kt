package id.homebase.chat.services.outbox

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.reactions.SetReactionsOutboxRequest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.Md5
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.chat.services.ChatProtocol
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * OptimisticWriter.setReactions: one idempotent set-state outbox row per
 * (message, scope), and a local mirror that only counts reactions that actually
 * change, so re-applying the same state never double-counts.
 */
class OptimisticWriterReactionSetTest {

    private val testIdentityId: Uuid = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
    private val chatDriveId: Uuid = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
    private val testDomain: String = "owner.test"

    private lateinit var dbm: DatabaseManager
    private lateinit var credentialsManager: CredentialsManager
    private lateinit var eventBus: EventBus
    private lateinit var writer: OptimisticWriter

    @AfterTest
    fun tearDown() {
        if (::dbm.isInitialized) {
            try { dbm.close() } catch (_: java.sql.SQLException) { }
        }
    }

    private suspend fun setUp() {
        dbm = DatabaseManager({
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            OdinDatabase.Schema.create(driver)
            driver
        })
        credentialsManager = CredentialsManager().also {
            it.setActiveCredentials(
                ApiCredentials.create(
                    domain = OdinId(testDomain),
                    clientAccessToken = "test-token",
                    sharedSecret = SecureByteArray(ByteArray(16)),
                )
            )
        }
        eventBus = EventBus()
        writer = OptimisticWriter(
            credentialsManager = credentialsManager,
            dbm = dbm,
            eventBus = eventBus,
            outboxSync = OutboxSync(
                databaseManager = dbm,
                uploader = object : OutboxUploader {
                    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) =
                        error("no uploads in this test")
                },
                eventBus = eventBus,
            ),
        )
    }

    /**
     * Build the JSON for a single ReactionEntry block keyed by the JSON-form
     * reaction (matching what OptimisticWriter writes). Caller passes count.
     */
    private fun previewJson(reactions: Map<String, Int>): String {
        if (reactions.isEmpty()) return "null"
        val entries = reactions.entries.joinToString(",") { (json, count) ->
            // The map key is the JSON form, e.g. {"emoji":"😀"}.
            // We need to embed it as a JSON string key — escape its quotes.
            val keyEscaped = json.replace("\\", "\\\\").replace("\"", "\\\"")
            val contentEscaped = keyEscaped
            """"$keyEscaped":{"key":"$keyEscaped","count":$count,"reactionContent":"$contentEscaped"}"""
        }
        return """{"reactions":{$entries},"comments":[],"totalCommentCount":0}"""
    }

    private fun localReactionsJson(localReactions: List<String>?): String {
        if (localReactions == null) return "null"
        val items = localReactions.joinToString(",") {
            val escaped = it.replace("\\", "\\\\").replace("\"", "\\\"")
            "\"$escaped\""
        }
        return """{"localReactions":[$items]}"""
    }

    /**
     * Seed a chat-message file in DriveMainIndex with optional pre-existing
     * localReactions and reactionPreview. Returns the message uniqueId.
     */
    private suspend fun seedMessage(
        initialLocalReactions: List<String>? = null,
        initialReactionPreview: Map<String, Int>? = null,
        id: Uuid = Uuid.random(),
        fileId: Uuid = Uuid.random(),
        senderDomain: String = testDomain,
    ): Uuid {
        val now = Clock.System.now().epochSeconds
        val previewJson = previewJson(initialReactionPreview.orEmpty())
        val localAppDataJson = localReactionsJson(initialLocalReactions)

        val jsonHeader = """{
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
            "senderOdinId": "$senderDomain",
            "originalAuthor": "$senderDomain",
            "appData": {
              "uniqueId": "$id",
              "tags": null,
              "fileType": ${ChatProtocol.MessageFileType},
              "dataType": 0,
              "groupId": "${Uuid.random()}",
              "userDate": ${now}000,
              "content": "",
              "previewThumbnail": null,
              "archivalStatus": 0
            },
            "localAppData": $localAppDataJson,
            "referencedFile": null,
            "reactionPreview": $previewJson,
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

        val header = OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
        val record = processor.convertFileHeaderToDriveMainIndexRecord(
            testIdentityId, chatDriveId, header
        )
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
        return id
    }

    private suspend fun readBack(uniqueId: Uuid): HomebaseFile = assertNotNull(
        dbm.driveMainIndex.selectHomebaseFileByUnique(testIdentityId, chatDriveId, uniqueId),
        "expected row for $uniqueId",
    )

    private fun emojiJson(emoji: String): String =
        """{"emoji":"$emoji"}"""

    private val going = emojiJson("✅")
    private val maybe = emojiJson("🤔")
    private val notGoing = emojiJson("❌")

    private fun rowKey(messageId: Uuid) = Md5.toGuidId("reaction-set:$messageId:rsvp")

    private suspend fun pendingCount(): Long = dbm.outbox.count()

    private suspend fun pendingRow(rowKey: Uuid): Outbox =
        assertNotNull(dbm.outbox.selectByDriveAndUnique(chatDriveId, rowKey), "expected outbox row $rowKey")

    private fun HomebaseFile.previewCounts(): Map<String, Int> =
        fileMetadata.reactionPreview?.reactions.orEmpty().values
            .associate { it.reactionContent to it.count }

    @Test
    fun set_replaces_siblings_locally_and_queues_one_row() = runTest {
        setUp()
        val messageId = seedMessage(
            initialLocalReactions = listOf(maybe),
            initialReactionPreview = mapOf(maybe to 2, going to 1),
        )

        val outcome = writer.setReactions(
            chatDriveId, messageId, rowKey(messageId),
            add = setOf(notGoing), remove = setOf(going, maybe),
        ) { listOf(OdinId("frodo.test")) }

        assertEquals(MutationOutcome.Queued, outcome)
        val updated = readBack(messageId)
        assertEquals(listOf(notGoing), updated.fileMetadata.localAppData?.localReactions)
        assertEquals(mapOf(maybe to 1, going to 1, notGoing to 1), updated.previewCounts())

        assertEquals(1L, pendingCount())
        val row = pendingRow(rowKey(messageId))
        assertEquals(DriveOutboxUploader.SetReactions, row.uploadType)
        assertEquals(messageId, row.dependencyUniqueId)
        val request = OdinSystemSerializer.deserialize<SetReactionsOutboxRequest>(row.json.decodeToString())
        assertEquals(listOf(notGoing), request.add)
        assertEquals(setOf(going, maybe), request.remove.toSet())
        assertEquals(listOf(OdinId("frodo.test")), request.recipients)
    }

    @Test
    fun reapplying_the_same_state_is_a_local_noop() = runTest {
        setUp()
        val messageId = seedMessage(
            initialLocalReactions = listOf(going),
            initialReactionPreview = mapOf(going to 1),
        )

        writer.setReactions(
            chatDriveId, messageId, rowKey(messageId),
            add = setOf(going), remove = setOf(maybe, notGoing),
        ) { emptyList() }

        val updated = readBack(messageId)
        assertEquals(listOf(going), updated.fileMetadata.localAppData?.localReactions)
        assertEquals(mapOf(going to 1), updated.previewCounts())
    }

    @Test
    fun retract_clears_only_the_users_own_share_of_the_preview() = runTest {
        setUp()
        val messageId = seedMessage(
            initialLocalReactions = listOf(going),
            initialReactionPreview = mapOf(going to 3, maybe to 1),
        )

        writer.setReactions(
            chatDriveId, messageId, rowKey(messageId),
            add = emptySet(), remove = setOf(going, maybe, notGoing),
        ) { emptyList() }

        val updated = readBack(messageId)
        assertEquals(emptyList(), updated.fileMetadata.localAppData?.localReactions)
        assertEquals(mapOf(going to 2, maybe to 1), updated.previewCounts())
    }

    @Test
    fun a_newer_tap_replaces_the_pending_row_for_the_same_scope() = runTest {
        setUp()
        val messageId = seedMessage()

        writer.setReactions(
            chatDriveId, messageId, rowKey(messageId),
            add = setOf(maybe), remove = setOf(going, notGoing),
        ) { emptyList() }
        writer.setReactions(
            chatDriveId, messageId, rowKey(messageId),
            add = setOf(notGoing), remove = setOf(going, maybe),
        ) { emptyList() }

        assertEquals(1L, pendingCount(), "rapid taps must coalesce into one pending row")
        val request = OdinSystemSerializer.deserialize<SetReactionsOutboxRequest>(
            pendingRow(rowKey(messageId)).json.decodeToString()
        )
        assertEquals(listOf(notGoing), request.add)
        assertEquals(listOf(notGoing), readBack(messageId).fileMetadata.localAppData?.localReactions)
    }

    @Test
    fun different_scopes_keep_separate_rows() = runTest {
        setUp()
        val messageId = seedMessage()

        writer.setReactions(
            chatDriveId, messageId, Md5.toGuidId("reaction-set:$messageId:slot:1"),
            add = setOf(emojiJson("_1Y")), remove = setOf(emojiJson("_1N")),
        ) { emptyList() }
        writer.setReactions(
            chatDriveId, messageId, Md5.toGuidId("reaction-set:$messageId:slot:2"),
            add = setOf(emojiJson("_2N")), remove = setOf(emojiJson("_2Y")),
        ) { emptyList() }

        assertEquals(2L, pendingCount())
        assertEquals(
            listOf(emojiJson("_1Y"), emojiJson("_2N")),
            readBack(messageId).fileMetadata.localAppData?.localReactions,
        )
    }

    @Test
    fun missing_row_is_reported_and_nothing_is_queued() = runTest {
        setUp()
        val outcome = writer.setReactions(
            chatDriveId, Uuid.random(), Uuid.random(),
            add = setOf(going), remove = emptySet(),
        ) { emptyList() }

        assertEquals(MutationOutcome.NoRow, outcome)
        assertEquals(0L, pendingCount())
    }

    @Test
    fun recipient_resolution_failure_refuses_and_leaves_the_row_untouched() = runTest {
        setUp()
        val messageId = seedMessage(initialLocalReactions = listOf(maybe))

        val outcome = writer.setReactions(
            chatDriveId, messageId, rowKey(messageId),
            add = setOf(going), remove = setOf(maybe, notGoing),
        ) { error("no conversation") }

        assertIs<MutationOutcome.Refused>(outcome)
        assertEquals(listOf(maybe), readBack(messageId).fileMetadata.localAppData?.localReactions)
        assertEquals(0L, pendingCount())
        assertNull(dbm.outbox.selectByDriveAndUnique(chatDriveId, rowKey(messageId)))
    }
}
