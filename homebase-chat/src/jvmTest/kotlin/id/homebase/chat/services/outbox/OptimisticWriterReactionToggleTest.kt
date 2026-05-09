package id.homebase.chat.services.outbox

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.chat.services.ChatProtocol
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Tests OptimisticWriter.writeReactionToggle.
 *
 * The optimistic writer must keep three pieces of state in sync on every toggle:
 *   - localAppData.localReactions  (per-user mirror; what ChatMessageStream
 *     decodes into MessageUiModel.ownReactions)
 *   - reactionPreview.reactions    (aggregate count, all participants)
 *   - The returned ToggleReactionResultType
 *
 * These tests pin all three. The most subtle case is the group scenario:
 * if another participant has already reacted with emoji X (preview count==1)
 * and the current user toggles X for the first time, the toggle MUST be
 * treated as an add — i.e. preview goes 1 -> 2, not 1 -> 0. The pre-fix code
 * read isAdding from preview count and got this wrong, briefly hiding the
 * other user's reaction in the optimistic UI until the server response
 * corrected it.
 */
class OptimisticWriterReactionToggleTest {

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

    // ---------- Tests ----------

    @Test
    fun add_writesLocalReactionsAndIncrementsPreview() = runTest {
        setUp()
        val messageId = seedMessage()
        val reaction = emojiJson("👍")

        val (resultType, original) = writer.writeReactionToggle(
            chatDriveId, messageId, reaction
        )

        assertEquals(ToggleReactionResultType.Added, resultType)
        assertNotNull(original, "original should be returned for rollback")

        val updated = readBack(messageId)
        assertEquals(
            listOf(reaction),
            updated.fileMetadata.localAppData?.localReactions,
            "localReactions should contain the new reaction JSON",
        )
        val previewMap = updated.fileMetadata.reactionPreview?.reactions.orEmpty()
        assertEquals(1, previewMap.size)
        assertEquals(1, previewMap.values.first().count)
        assertEquals(reaction, previewMap.values.first().reactionContent)
    }

    @Test
    fun remove_clearsLocalReactionsAndDecrementsPreview() = runTest {
        setUp()
        val reaction = emojiJson("🎉")
        val messageId = seedMessage(
            initialLocalReactions = listOf(reaction),
            initialReactionPreview = mapOf(reaction to 1),
        )

        val (resultType, _) = writer.writeReactionToggle(
            chatDriveId, messageId, reaction
        )

        assertEquals(ToggleReactionResultType.Deleted, resultType)
        val updated = readBack(messageId)
        assertEquals(
            emptyList(),
            updated.fileMetadata.localAppData?.localReactions,
            "localReactions should no longer contain the toggled emoji",
        )
        assertTrue(
            updated.fileMetadata.reactionPreview?.reactions.orEmpty().isEmpty(),
            "preview should drop the entry once count reaches 0",
        )
    }

    /**
     * Group case: another participant has already reacted with emoji X (preview
     * count==1), but the current user has NOT (their localReactions is empty).
     * Toggling X must be treated as an add: preview goes 1 -> 2, not 1 -> 0.
     * Pre-fix code read isAdding from the aggregate count and decremented;
     * this is the regression guard.
     */
    @Test
    fun add_inGroupWherePreviewAlreadyHasOtherUsersReaction_bumpsCountNotZeroes() = runTest {
        setUp()
        val reaction = emojiJson("❤️")
        val messageId = seedMessage(
            initialLocalReactions = null, // current user has nothing
            initialReactionPreview = mapOf(reaction to 1), // someone else's reaction
        )

        val (resultType, _) = writer.writeReactionToggle(
            chatDriveId, messageId, reaction
        )

        assertEquals(
            ToggleReactionResultType.Added, resultType,
            "current user toggling a reaction they don't own must be an add, " +
                    "regardless of others having it"
        )

        val updated = readBack(messageId)
        assertEquals(
            listOf(reaction),
            updated.fileMetadata.localAppData?.localReactions,
        )
        val previewMap = updated.fileMetadata.reactionPreview?.reactions.orEmpty()
        assertEquals(1, previewMap.size, "preview still has one entry")
        assertEquals(
            2, previewMap.values.first().count,
            "preview count should be the other user's 1 + the current user's 1 = 2"
        )
    }

    @Test
    fun addThenRemove_returnsToOriginalState() = runTest {
        setUp()
        val reaction = emojiJson("🚀")
        val messageId = seedMessage()

        val (firstType, _) = writer.writeReactionToggle(
            chatDriveId, messageId, reaction
        )
        val (secondType, _) = writer.writeReactionToggle(
            chatDriveId, messageId, reaction
        )

        assertEquals(ToggleReactionResultType.Added, firstType)
        assertEquals(ToggleReactionResultType.Deleted, secondType)

        val updated = readBack(messageId)
        assertEquals(
            emptyList(),
            updated.fileMetadata.localAppData?.localReactions,
        )
        assertTrue(
            updated.fileMetadata.reactionPreview?.reactions.orEmpty().isEmpty(),
        )
    }

    @Test
    fun toggle_whenFileMissing_returnsNoneAndDoesNotInsert() = runTest {
        setUp()
        val nonExistent = Uuid.random()

        val (resultType, original) = writer.writeReactionToggle(
            chatDriveId, nonExistent, emojiJson("😀")
        )

        assertEquals(ToggleReactionResultType.None, resultType)
        assertNull(original)
        assertNull(
            dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, nonExistent
            ),
            "no row should have been created",
        )
    }

    /**
     * Add a second distinct emoji while the first is still present. localReactions
     * should grow to two entries; preview should have two distinct keys each at
     * count==1.
     */
    @Test
    fun add_secondDistinctEmoji_appendsAlongsideExisting() = runTest {
        setUp()
        val first = emojiJson("👍")
        val second = emojiJson("🎉")
        val messageId = seedMessage(
            initialLocalReactions = listOf(first),
            initialReactionPreview = mapOf(first to 1),
        )

        val (resultType, _) = writer.writeReactionToggle(
            chatDriveId, messageId, second
        )

        assertEquals(ToggleReactionResultType.Added, resultType)
        val updated = readBack(messageId)
        assertEquals(
            listOf(first, second),
            updated.fileMetadata.localAppData?.localReactions,
        )
        val previewMap = updated.fileMetadata.reactionPreview?.reactions.orEmpty()
        assertEquals(2, previewMap.size)
        assertTrue(previewMap.values.all { it.count == 1 })
    }

    /**
     * Diagnostic probe — does NOT directly correspond to a known bug yet.
     *
     * Reproduces the user-reported scenario in isolation: five concurrent
     * removes of the five own-reactions on a single message, going only
     * through OptimisticWriter (no outbox / server / sync in the loop).
     * Reads back the DB state to decide between two hypotheses for why
     * the chat bubble peels reactions off slowly:
     *
     *   - Hypothesis A: read-modify-write race in writeReactionToggle.
     *     If true, this test fails: the residue list is non-empty because
     *     last-writer-wins clobbered most of the removes.
     *
     *   - Hypothesis B: writer is race-safe under runTest's cooperative
     *     scheduling; the symptom is upstream (e.g. sync overwriting the
     *     correct optimistic state with stale server data).
     *     If true, this test passes; the residue is empty.
     *
     * runTest's StandardTestDispatcher yields at every suspension point
     * inside writeReactionToggle (DB read, DB write, eventBus.emit), so a
     * read-modify-write race would still surface here.
     *
     * The println surfaces the actual residue so we can read off the
     * hypothesis result either way.
     */
    @Test
    fun parallelRemoves_diagnostic_finalDbStateMustBeEmpty() = runTest {
        setUp()
        val emojis = listOf("👍", "🎉", "❤️", "🚀", "😀").map { emojiJson(it) }
        val messageId = seedMessage(
            initialLocalReactions = emojis,
            initialReactionPreview = emojis.associateWith { 1 },
        )

        coroutineScope {
            for (e in emojis) {
                launch { writer.writeReactionToggle(chatDriveId, messageId, e) }
            }
        }

        val updated = readBack(messageId)
        val residue = updated.fileMetadata.localAppData?.localReactions.orEmpty()
        val previewResidue = updated.fileMetadata.reactionPreview?.reactions.orEmpty()
        println("DIAG localReactionsResidue=$residue previewResidue=$previewResidue")
        assertEquals(
            emptyList(),
            residue,
            "If the optimistic writer is race-free, all five removes must land. " +
                    "Non-empty residue ⇒ Hypothesis A (race in writeReactionToggle).",
        )
        assertTrue(
            previewResidue.isEmpty(),
            "preview should also be empty after all five removes",
        )
    }
}
