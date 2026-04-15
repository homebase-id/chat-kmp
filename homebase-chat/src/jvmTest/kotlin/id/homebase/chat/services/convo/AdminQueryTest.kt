package id.homebase.chat.services.convo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.chat.services.ChatProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Tests for the admin query logic in [ConversationMapper.mapToConversationUi] (which calls
 * the private `queryAdmins()` method internally).
 *
 * These tests exist to lock down the current behavior before refactoring the duplicated
 * admin query logic between ConversationMapper and ConversationService.
 */
class AdminQueryTest {

    // Fixed identityId — matches the hardcoded value in ApiCredentials.getIdentityId()
    private val testIdentityId = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")

    // Chat drive alias from SystemDriveConstants.chatDrive
    private val chatDriveId = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")

    private val testDomain = "owner.test"
    private val alice = "alice.test"
    private val bob = "bob.test"

    // -- Helpers --

    private fun createTestDatabaseManager(): DatabaseManager {
        return DatabaseManager {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            OdinDatabase.Schema.create(driver)
            driver
        }
    }

    private suspend fun createTestCredentialsManager(): CredentialsManager {
        val cm = CredentialsManager()
        cm.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId(testDomain),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16))
            )
        )
        return cm
    }

    /**
     * Builds a JSON string representing a conversation HomebaseFile (fileType 8888).
     */
    private fun buildConversationFileJson(
        fileId: Uuid,
        uniqueId: Uuid,
        participants: List<String>,
        originalAuthor: String,
        isGroup: Boolean,
        adminDataJson: String? = null,
        title: String = ""
    ): String {
        val now = Clock.System.now().epochSeconds
        val recipientsJson = participants.joinToString(",") { "\"$it\"" }
        val tagsJson = if (isGroup) {
            "\"${ChatProtocol.ConversationGroupTag}\""
        } else {
            null
        }
        val tagsField = if (tagsJson != null) "[$tagsJson]" else "null"

        // Build appData content — the ConversationAppDataJson structure
        val adminDataField = if (adminDataJson != null) {
            """, "adminData": $adminDataJson"""
        } else {
            ""
        }
        val contentJson = """{"title":"$title","version":1,"recipients":[$recipientsJson]$adminDataField}"""

        // Escape the content for embedding in the outer JSON
        val escapedContent = contentJson.replace("\"", "\\\"")

        return """{
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
                "senderOdinId": "$originalAuthor",
                "originalAuthor": "$originalAuthor",
                "appData": {
                    "uniqueId": "$uniqueId",
                    "tags": $tagsField,
                    "fileType": ${ChatProtocol.ConversationFileType},
                    "dataType": 0,
                    "groupId": null,
                    "userDate": ${now}000,
                    "content": "$escapedContent",
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
                "originalRecipientCount": ${participants.size - 1},
                "transferHistory": null
            },
            "priority": 300,
            "fileByteCount": 100
        }"""
    }

    /**
     * Builds a JSON string representing an admin HomebaseFile (fileType 8890).
     */
    private fun buildAdminFileJson(
        fileId: Uuid,
        uniqueId: Uuid,
        groupId: Uuid,
        admins: List<String>,
        originalAuthor: String
    ): String {
        val now = Clock.System.now().epochSeconds
        val adminsJson = admins.joinToString(",") { "\"$it\"" }
        val contentJson = """{"admins":[$adminsJson]}"""
        val escapedContent = contentJson.replace("\"", "\\\"")

        return """{
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
                "transitCreated": 0,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "$originalAuthor",
                "originalAuthor": "$originalAuthor",
                "appData": {
                    "uniqueId": "$uniqueId",
                    "tags": null,
                    "fileType": ${ChatProtocol.ConversationAdminFileType},
                    "dataType": 0,
                    "groupId": "$groupId",
                    "userDate": ${now}000,
                    "content": "$escapedContent",
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
                "fileByteCount": 50,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 300,
            "fileByteCount": 50
        }"""
    }

    /**
     * Builds an admin file JSON with custom raw content (for testing corrupt/empty data).
     */
    private fun buildAdminFileJsonWithRawContent(
        fileId: Uuid,
        uniqueId: Uuid,
        groupId: Uuid,
        rawContent: String,
        originalAuthor: String
    ): String {
        val now = Clock.System.now().epochSeconds
        val escapedContent = rawContent.replace("\"", "\\\"")

        return """{
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
                "transitCreated": 0,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "$originalAuthor",
                "originalAuthor": "$originalAuthor",
                "appData": {
                    "uniqueId": "$uniqueId",
                    "tags": null,
                    "fileType": ${ChatProtocol.ConversationAdminFileType},
                    "dataType": 0,
                    "groupId": "$groupId",
                    "userDate": ${now}000,
                    "content": "$escapedContent",
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
                "fileByteCount": 50,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 300,
            "fileByteCount": 50
        }"""
    }

    private suspend fun insertFile(dbm: DatabaseManager, jsonHeader: String) {
        val header = OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
        val record = processor.convertFileHeaderToDriveMainIndexRecord(
            testIdentityId, chatDriveId, header
        )
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
    }

    // ---- Group 1: ConversationMapper.mapToConversationUi() — admin resolution ----

    @Test
    fun groupConversation_withAdminFile_returnsAdminsFromFile() = runTest {
        createTestDatabaseManager().use { dbm ->
            val cm = createTestCredentialsManager()
            val mapper = ConversationMapper(cm, dbm)

            val conversationId = Uuid.random()
            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)

            // Insert conversation file (group with 3 participants)
            val convoJson = buildConversationFileJson(
                fileId = Uuid.random(),
                uniqueId = conversationId,
                participants = listOf(testDomain, alice, bob),
                originalAuthor = testDomain,
                isGroup = true
            )
            insertFile(dbm, convoJson)

            // Insert admin file with alice and bob as admins
            val adminJson = buildAdminFileJson(
                fileId = Uuid.random(),
                uniqueId = adminUniqueId,
                groupId = conversationId,
                admins = listOf(alice, bob),
                originalAuthor = testDomain
            )
            insertFile(dbm, adminJson)

            // Map and verify
            val convoFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, conversationId
            )!!
            val result = mapper.mapToConversationUi(convoFile, null)

            assertEquals(
                setOf(OdinId(alice), OdinId(bob)),
                result.admins
            )
        }
    }

    @Test
    fun groupConversation_noAdminFile_fallsBackToAdminData() = runTest {
        createTestDatabaseManager().use { dbm ->
            val cm = createTestCredentialsManager()
            val mapper = ConversationMapper(cm, dbm)

            val conversationId = Uuid.random()

            // Insert conversation file with adminData in content, but NO admin file
            val convoJson = buildConversationFileJson(
                fileId = Uuid.random(),
                uniqueId = conversationId,
                participants = listOf(testDomain, alice, bob),
                originalAuthor = testDomain,
                isGroup = true,
                adminDataJson = """{"admins":["$alice"]}"""
            )
            insertFile(dbm, convoJson)

            val convoFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, conversationId
            )!!
            val result = mapper.mapToConversationUi(convoFile, null)

            assertEquals(
                setOf(OdinId(alice)),
                result.admins
            )
        }
    }

    @Test
    fun groupConversation_noAdminFile_noAdminData_fallsBackToOriginalAuthor() = runTest {
        createTestDatabaseManager().use { dbm ->
            val cm = createTestCredentialsManager()
            val mapper = ConversationMapper(cm, dbm)

            val conversationId = Uuid.random()

            // Insert conversation file without any admin info
            val convoJson = buildConversationFileJson(
                fileId = Uuid.random(),
                uniqueId = conversationId,
                participants = listOf(testDomain, alice, bob),
                originalAuthor = testDomain,
                isGroup = true
            )
            insertFile(dbm, convoJson)

            val convoFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, conversationId
            )!!
            val result = mapper.mapToConversationUi(convoFile, null)

            assertEquals(
                setOf(OdinId(testDomain)),
                result.admins
            )
        }
    }

    @Test
    fun oneOnOneConversation_returnsEmptyAdmins() = runTest {
        createTestDatabaseManager().use { dbm ->
            val cm = createTestCredentialsManager()
            val mapper = ConversationMapper(cm, dbm)

            val conversationId = Uuid.random()

            // Insert 1:1 conversation (no group tag, 2 participants)
            val convoJson = buildConversationFileJson(
                fileId = Uuid.random(),
                uniqueId = conversationId,
                participants = listOf(testDomain, alice),
                originalAuthor = testDomain,
                isGroup = false
            )
            insertFile(dbm, convoJson)

            val convoFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, conversationId
            )!!
            val result = mapper.mapToConversationUi(convoFile, null)

            assertTrue(result.admins.isEmpty())
        }
    }

    @Test
    fun groupConversation_corruptAdminFile_fallsBackToAdminData() = runTest {
        createTestDatabaseManager().use { dbm ->
            val cm = createTestCredentialsManager()
            val mapper = ConversationMapper(cm, dbm)

            val conversationId = Uuid.random()
            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)

            // Insert conversation file with adminData as fallback
            val convoJson = buildConversationFileJson(
                fileId = Uuid.random(),
                uniqueId = conversationId,
                participants = listOf(testDomain, alice, bob),
                originalAuthor = testDomain,
                isGroup = true,
                adminDataJson = """{"admins":["$bob"]}"""
            )
            insertFile(dbm, convoJson)

            // Insert admin file with corrupt content
            val adminJson = buildAdminFileJsonWithRawContent(
                fileId = Uuid.random(),
                uniqueId = adminUniqueId,
                groupId = conversationId,
                rawContent = "not valid json at all",
                originalAuthor = testDomain
            )
            insertFile(dbm, adminJson)

            val convoFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, conversationId
            )!!
            val result = mapper.mapToConversationUi(convoFile, null)

            // Should fall back to adminData from conversation content
            assertEquals(
                setOf(OdinId(bob)),
                result.admins
            )
        }
    }

    @Test
    fun groupConversation_emptyAdminFileContent_fallsBackToAdminData() = runTest {
        createTestDatabaseManager().use { dbm ->
            val cm = createTestCredentialsManager()
            val mapper = ConversationMapper(cm, dbm)

            val conversationId = Uuid.random()
            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)

            // Insert conversation file with adminData as fallback
            val convoJson = buildConversationFileJson(
                fileId = Uuid.random(),
                uniqueId = conversationId,
                participants = listOf(testDomain, alice, bob),
                originalAuthor = testDomain,
                isGroup = true,
                adminDataJson = """{"admins":["$alice"]}"""
            )
            insertFile(dbm, convoJson)

            // Insert admin file with empty content
            val adminJson = buildAdminFileJsonWithRawContent(
                fileId = Uuid.random(),
                uniqueId = adminUniqueId,
                groupId = conversationId,
                rawContent = "",
                originalAuthor = testDomain
            )
            insertFile(dbm, adminJson)

            val convoFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, conversationId
            )!!
            val result = mapper.mapToConversationUi(convoFile, null)

            // Empty content → queryAdmins returns null → falls back to adminData
            assertEquals(
                setOf(OdinId(alice)),
                result.admins
            )
        }
    }

    @Test
    fun groupConversation_adminFileOverridesAdminData() = runTest {
        createTestDatabaseManager().use { dbm ->
            val cm = createTestCredentialsManager()
            val mapper = ConversationMapper(cm, dbm)

            val conversationId = Uuid.random()
            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)

            // Conversation content says alice is admin
            val convoJson = buildConversationFileJson(
                fileId = Uuid.random(),
                uniqueId = conversationId,
                participants = listOf(testDomain, alice, bob),
                originalAuthor = testDomain,
                isGroup = true,
                adminDataJson = """{"admins":["$alice"]}"""
            )
            insertFile(dbm, convoJson)

            // But admin file says bob is admin (admin file takes priority)
            val adminJson = buildAdminFileJson(
                fileId = Uuid.random(),
                uniqueId = adminUniqueId,
                groupId = conversationId,
                admins = listOf(bob),
                originalAuthor = testDomain
            )
            insertFile(dbm, adminJson)

            val convoFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, conversationId
            )!!
            val result = mapper.mapToConversationUi(convoFile, null)

            // Admin file should take priority over adminData in conversation content
            assertEquals(
                setOf(OdinId(bob)),
                result.admins
            )
        }
    }

    // ---- Group 2: ConversationService.getAdmins() ----
    // ConversationService has 9 constructor dependencies. Since getAdmins() duplicates
    // the same logic as ConversationMapper.queryAdmins(), we'll cover it after the refactor
    // extracts the shared logic into a testable unit.

    // ---- Group 3: Additional mapping correctness ----

    @Test
    fun groupConversation_setsIsGroupTrue() = runTest {
        createTestDatabaseManager().use { dbm ->
            val cm = createTestCredentialsManager()
            val mapper = ConversationMapper(cm, dbm)

            val conversationId = Uuid.random()

            val convoJson = buildConversationFileJson(
                fileId = Uuid.random(),
                uniqueId = conversationId,
                participants = listOf(testDomain, alice, bob),
                originalAuthor = testDomain,
                isGroup = true
            )
            insertFile(dbm, convoJson)

            val convoFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, conversationId
            )!!
            val result = mapper.mapToConversationUi(convoFile, null)

            assertTrue(result.isGroup)
            assertTrue(result.isGroupConversation)
        }
    }

    @Test
    fun legacyGroup_moreThanTwoParticipants_noGroupTag_isDetected() = runTest {
        createTestDatabaseManager().use { dbm ->
            val cm = createTestCredentialsManager()
            val mapper = ConversationMapper(cm, dbm)

            val conversationId = Uuid.random()

            // 3 participants but NO group tag — legacy group
            val convoJson = buildConversationFileJson(
                fileId = Uuid.random(),
                uniqueId = conversationId,
                participants = listOf(testDomain, alice, bob),
                originalAuthor = testDomain,
                isGroup = false // no group tag
            )
            insertFile(dbm, convoJson)

            val convoFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
                testIdentityId, chatDriveId, conversationId
            )!!
            val result = mapper.mapToConversationUi(convoFile, null)

            assertTrue(result.isLegacyGroup)
            assertTrue(result.isGroupConversation)
        }
    }
}
