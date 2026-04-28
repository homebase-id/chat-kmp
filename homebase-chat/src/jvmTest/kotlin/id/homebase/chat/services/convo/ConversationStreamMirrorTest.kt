package id.homebase.chat.services.convo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Focused tests for [mirrorLastReadIntoChatReadCount] — the helper that
 * keeps ChatReadCount in sync with each conversation file's
 * localAppData.lastReadTime when those files arrive via cold-load or
 * peer-device drive-sync.
 */
class ConversationStreamMirrorTest {

    private lateinit var dbm: DatabaseManager

    @BeforeTest
    fun setup() {
        dbm = DatabaseManager({
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            OdinDatabase.Schema.create(driver)
            driver
        })
    }

    @AfterTest
    fun teardown() {
        dbm.close()
    }

    @Test
    fun mirror_advancesChatReadCount_whenFileIsNewer() = runTest {
        val convoId = Uuid.random()
        dbm.chatReadCount.upsertLastReadTime(convoId, UnixTimeUtc(999L))

        mirrorLastReadIntoChatReadCount(dbm, listOf(stubFile(convoId, lastReadMs = 1_000L)))

        assertEquals(1_000L, dbm.chatReadCount.selectLastReadTimeMs(convoId))
    }

    @Test
    fun mirror_doesNothing_whenChatReadCountIsAlreadyAhead() = runTest {
        val convoId = Uuid.random()
        dbm.chatReadCount.upsertLastReadTime(convoId, UnixTimeUtc(2_000L))

        mirrorLastReadIntoChatReadCount(dbm, listOf(stubFile(convoId, lastReadMs = 1_000L)))

        assertEquals(2_000L, dbm.chatReadCount.selectLastReadTimeMs(convoId))
    }

    @Test
    fun mirror_isNoOp_whenAlreadyEqual() = runTest {
        val convoId = Uuid.random()
        dbm.chatReadCount.upsertLastReadTime(convoId, UnixTimeUtc(1_000L))

        mirrorLastReadIntoChatReadCount(dbm, listOf(stubFile(convoId, lastReadMs = 1_000L)))

        assertEquals(1_000L, dbm.chatReadCount.selectLastReadTimeMs(convoId))
    }

    @Test
    fun mirror_seedsFreshRow_whenNoChatReadCountYet() = runTest {
        val convoId = Uuid.random()
        assertNull(dbm.chatReadCount.selectLastReadTimeMs(convoId))

        mirrorLastReadIntoChatReadCount(dbm, listOf(stubFile(convoId, lastReadMs = 1_000L)))

        assertEquals(1_000L, dbm.chatReadCount.selectLastReadTimeMs(convoId))
    }

    @Test
    fun mirror_skipsFile_whenLocalAppDataLastReadTimeIsNull() = runTest {
        val convoId = Uuid.random()
        dbm.chatReadCount.upsertLastReadTime(convoId, UnixTimeUtc(500L))

        // localAppData.content present but lastReadTime field is null.
        mirrorLastReadIntoChatReadCount(dbm, listOf(stubFile(convoId, lastReadMs = null)))

        assertEquals(500L, dbm.chatReadCount.selectLastReadTimeMs(convoId))
    }

    @Test
    fun mirror_skipsFile_whenLocalAppDataMissing() = runTest {
        val convoId = Uuid.random()

        // No localAppData block at all on the file.
        mirrorLastReadIntoChatReadCount(dbm, listOf(stubFile(convoId, includeLocalAppData = false)))

        assertNull(dbm.chatReadCount.selectLastReadTimeMs(convoId))
    }

    @Test
    fun mirror_handlesBatchOfFiles() = runTest {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()
        dbm.chatReadCount.upsertLastReadTime(a, UnixTimeUtc(100L)) // behind file
        dbm.chatReadCount.upsertLastReadTime(b, UnixTimeUtc(999L)) // ahead of file

        mirrorLastReadIntoChatReadCount(
            dbm,
            listOf(
                stubFile(a, lastReadMs = 200L),
                stubFile(b, lastReadMs = 200L),
                stubFile(c, lastReadMs = 200L),
            )
        )

        assertEquals(200L, dbm.chatReadCount.selectLastReadTimeMs(a))
        assertEquals(999L, dbm.chatReadCount.selectLastReadTimeMs(b))
        assertEquals(200L, dbm.chatReadCount.selectLastReadTimeMs(c))
    }

    /**
     * Build a minimal conversation HomebaseFile with [uniqueId] and an
     * optional [lastReadMs] inside `localAppData`. Driven by JSON because
     * HomebaseFile + nested types have a long required-field list and
     * deserialising keeps the test surface small.
     */
    private fun stubFile(
        uniqueId: Uuid,
        lastReadMs: Long? = 0L,
        includeLocalAppData: Boolean = true,
    ): HomebaseFile {
        val localAppDataJson: String = if (!includeLocalAppData) {
            "null"
        } else {
            // ConversationLocalAppDataJson is @Serializable; serialise via
            // OdinSystemSerializer so the wire format is exactly what the
            // production deserialiser expects to parse back out.
            val ladModel = ConversationLocalAppDataJson(
                lastReadTime = lastReadMs?.let { UnixTimeUtc(it) }
            )
            val ladContent = OdinSystemSerializer.serialize(ladModel)
            // The localAppData wrapper itself is a LocalAppMetadata with `content`
            // carrying the JSON. We embed it as a JSON-escaped string.
            val escaped = ladContent.replace("\\", "\\\\").replace("\"", "\\\"")
            """{"tags": [], "iv": null, "content": "$escaped"}"""
        }
        val json = """{
          "fileId": "${Uuid.random()}",
          "driveId": "${Uuid.random()}",
          "fileState": "active",
          "fileSystemType": "standard",
          "serverFileIsEncrypted": "false",
          "keyHeader": {
            "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
            "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
          },
          "fileMetadata": {
            "globalTransitId": "${Uuid.random()}",
            "created": 0,
            "updated": 0,
            "isEncrypted": false,
            "senderOdinId": "test.sender",
            "originalAuthor": "test.sender",
            "appData": {
              "uniqueId": "$uniqueId",
              "tags": null,
              "fileType": 8888,
              "dataType": 0,
              "groupId": null,
              "userDate": 0,
              "content": null,
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
            "allowDistribution": false,
            "fileSystemType": "standard",
            "fileByteCount": 100,
            "originalRecipientCount": 0,
            "transferHistory": null
          },
          "priority": 100,
          "fileByteCount": 100
        }"""
        return OdinSystemSerializer.deserialize<HomebaseFile>(json)
    }
}
