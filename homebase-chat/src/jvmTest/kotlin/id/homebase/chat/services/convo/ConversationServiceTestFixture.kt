package id.homebase.chat.services.convo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.client.connections.IntroductionResult
import id.homebase.api.client.connections.IntroductionSender
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.buildTestUploadService
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadBundleEncryptor
import id.homebase.chat.services.SendMessageResult
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.outbox.OptimisticWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Shared setup for ConversationService tests.
 *
 *   - Real in-memory SQLite DB (same pattern as [AdminQueryTest]).
 *   - Real [OutboxSync] with `setOnline(false)` so enqueued rows land in the DB for
 *     inspection but are never actually uploaded. The stub [OutboxUploader] throws
 *     to catch accidental online paths in tests.
 *   - Fake collaborators ([FakeStatusMessageSender], [FakeIntroductionSender],
 *     [FakeConversationLoader], [FakePayloadBundleEncryptor]) record calls for
 *     assertion — no network, no crypto, no coroutines they can leak.
 *
 * Use via `ConversationServiceTestFixture().use { fixture -> ... }` so the
 * in-memory DB is closed at the end of each test.
 */
class ConversationServiceTestFixture : AutoCloseable {

    // Fixed identityId — matches the hardcoded value in ApiCredentials.getIdentityId()
    val testIdentityId: Uuid = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")

    // Chat drive alias from SystemDriveConstants.chatDrive
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
    lateinit var introductionSender: FakeIntroductionSender
        private set
    lateinit var statusMessageSender: FakeStatusMessageSender
        private set
    lateinit var conversationLoader: FakeConversationLoader
        private set
    lateinit var payloadEncryptor: FakePayloadBundleEncryptor
        private set
    lateinit var optimisticWriter: OptimisticWriter
        private set

    /**
     * In-memory participant lookup that the service's lastRead gate reads
     * from. Tests that exercise the gate (mark-read rejection, peer-device
     * advance) seed conversations via [FakeConversationParticipantLookup.setLastRead]
     * with an explicit baseline; tests that only run monotonically-increasing
     * advances can leave it empty — the gate then treats every candidate as
     * "no prior known" and accepts.
     */
    val participantLookup: id.homebase.chat.services.FakeConversationParticipantLookup =
        id.homebase.chat.services.FakeConversationParticipantLookup()

    /**
     * Build the service for tests.
     *
     * @param lastReadDebounceMs The lastRead-writeback debounce window. Defaults
     *   to one hour so the timer effectively never fires during a test —
     *   `runTest`'s scheduler can advance virtual time at points outside our
     *   control, so tests that need to assert on the debounce drain must call
     *   [ConversationService.flushLastReadNow] explicitly.
     */
    suspend fun build(
        scope: CoroutineScope = TestScope(),
        lastReadDebounceMs: Long = Long.MAX_VALUE / 2,
        driveFileProvider: id.homebase.api.client.drives.files.ResendPayloadByteSource? = null,
        fileOperationsProvider: id.homebase.api.file.FileOperationsProvider? = null,
    ): ConversationService {
        dbm = createInMemoryDbm()
        credentialsManager = createCredentialsManager(testDomain)
        eventBus = EventBus()
        outboxSync = OutboxSync(
            databaseManager = dbm,
            uploader = ThrowingOutboxUploader,
            eventBus = eventBus,
            scope = scope
        ).also { it.setOnline(false) } // never actually send

        introductionSender = FakeIntroductionSender()
        statusMessageSender = FakeStatusMessageSender()
        conversationLoader = FakeConversationLoader()
        payloadEncryptor = FakePayloadBundleEncryptor()

        optimisticWriter = OptimisticWriter(
            credentialsManager = credentialsManager,
            dbm = dbm,
            eventBus = eventBus,
            outboxSync = outboxSync,
        )

        return ConversationService(
            credentialsManager = credentialsManager,
            payloadBundleEncryptionService = payloadEncryptor,
            uploadService = buildTestUploadService(outboxSync, optimisticWriter, payloadEncryptor, credentialsManager),
            dbm = dbm,
            introductionProvider = introductionSender,
            scope = scope,
            outboxSync = outboxSync,
            chatMessageSenderService = statusMessageSender,
            optimisticWriter = optimisticWriter,
            conversationStream = conversationLoader,
            participantLookup = participantLookup,
            // Heal-payload-reuse helpers — null by default, but tests exercising
            // the heal redistribute path (image preservation) pass fakes. A null
            // here short-circuits reuseExistingPayloadsForResend to an empty
            // manifest.
            driveFileProvider = driveFileProvider,
            fileOperationsProvider = fileOperationsProvider,
            lastReadDebounceMs = lastReadDebounceMs,
        )
    }

    /**
     * Build a [GroupHealService] wired against the fixture's deps and the given
     * [conversationService] (cast as [GroupHealConversationOps]). Call after
     * [build] — the heal service shares the fixture's `outboxSync`,
     * `optimisticWriter`, `statusMessageSender`, etc.
     */
    fun buildHealService(conversationService: ConversationService): GroupHealService =
        GroupHealService(
            credentialsManager = credentialsManager,
            dbm = dbm,
            outboxSync = outboxSync,
            optimisticWriter = optimisticWriter,
            chatMessageSenderService = statusMessageSender,
            convoOps = conversationService,
        )

    // ---------- DB seed helpers ----------

    /**
     * Seed a group conversation where [testDomain] is the admin. Participants include
     * the caller ([testDomain]) plus every entry in [others].
     *
     * @param localTags tags written to localAppData — drive Left/Archived/Pinned state.
     * @param archivalStatus 0=Active, 2=Removed. See [ArchivalStatus].
     */
    suspend fun seedGroup(
        conversationId: Uuid = Uuid.random(),
        others: List<String>,
        adminDomains: List<String> = listOf(testDomain),
        title: String = "",
        localTags: List<Uuid> = emptyList(),
        archivalStatus: Int = 0,
        seedAdminFile: Boolean = true,
    ): Uuid {
        val participants = listOf(testDomain) + others
        val adminDataJson =
            """{"admins":[${adminDomains.joinToString(",") { "\"$it\"" }}]}"""
        insertConversationFile(
            fileId = Uuid.random(),
            uniqueId = conversationId,
            participants = participants,
            originalAuthor = testDomain,
            isGroup = true,
            adminDataJson = adminDataJson,
            title = title,
            localTags = localTags,
            archivalStatus = archivalStatus,
        )

        if (seedAdminFile) {
            // Separate admin file (takes priority over adminData when read by
            // ConversationAdminInfo.queryFromDb — see AdminQueryTest for details).
            insertAdminFile(
                fileId = Uuid.random(),
                uniqueId = ChatProtocol.getAdminFileUniqueId(conversationId),
                groupId = conversationId,
                admins = adminDomains,
                originalAuthor = testDomain
            )
        }
        return conversationId
    }

    /** Seed a 1:1 conversation between [testDomain] and [other]. */
    suspend fun seedOneOnOne(
        other: String,
        conversationId: Uuid = Uuid.random(),
        localTags: List<Uuid> = emptyList(),
        archivalStatus: Int = 0,
    ): Uuid {
        insertConversationFile(
            fileId = Uuid.random(),
            uniqueId = conversationId,
            participants = listOf(testDomain, other),
            originalAuthor = testDomain,
            isGroup = false,
            title = "",
            localTags = localTags,
            archivalStatus = archivalStatus,
        )
        return conversationId
    }

    /**
     * Seed an orphaned 1:1 conversation file: recipients contains ONLY the
     * test domain (the other party is gone — e.g. participant removed,
     * corrupt/migrated content). Reproduces the
     * `participants.first { it != domain }` crash in `ConversationMapper.mapToBasic`
     * line 151 that motivated the read-path orphan-recovery wiring.
     */
    suspend fun seedOrphanedOneOnOneSelfOnly(
        conversationId: Uuid = Uuid.random(),
        originalAuthor: String = testDomain,
    ): Uuid {
        insertConversationFile(
            fileId = Uuid.random(),
            uniqueId = conversationId,
            participants = listOf(testDomain),
            originalAuthor = originalAuthor,
            isGroup = false,
            title = "",
        )
        return conversationId
    }

    /**
     * Seed a chat message (fileType=MessageFileType, dataType=0) in [groupId] authored
     * by [author]. Used to exercise recoverConversation's peer-message guard — only
     * groupId / fileType / originalAuthor are read there, so the content is a stub.
     */
    suspend fun seedMessage(
        groupId: Uuid,
        author: String,
        fileId: Uuid = Uuid.random(),
        archivalStatus: Int = 0,
    ) {
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
                "created": ${now}000,
                "updated": ${now}000,
                "transitCreated": ${now}000,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "$author",
                "originalAuthor": "$author",
                "appData": {
                  "uniqueId": "${Uuid.random()}",
                  "tags": null,
                  "fileType": ${ChatProtocol.MessageFileType},
                  "dataType": 0,
                  "groupId": "$groupId",
                  "userDate": ${now}000,
                  "content": null,
                  "previewThumbnail": null,
                  "archivalStatus": $archivalStatus
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
        )
    }

    /** A legacy (pre-tag) group — >2 participants but no ConversationGroupTag. */
    suspend fun seedLegacyGroup(
        others: List<String>,
        conversationId: Uuid = Uuid.random(),
    ): Uuid {
        insertConversationFile(
            fileId = Uuid.random(),
            uniqueId = conversationId,
            participants = listOf(testDomain) + others,
            originalAuthor = testDomain,
            isGroup = false, // no ConversationGroupTag → legacy
            title = "",
        )
        return conversationId
    }

    suspend fun outboxRowCount(): Long = dbm.outbox.count()

    /**
     * Drain the outbox (checkout everything eligible at real `now`) so callers can
     * inspect what was enqueued. NOTE: this mutates `checkOutStamp`. Don't call it
     * if you plan to run further service operations that might re-enqueue the same
     * uniqueId, and don't rely on [outboxRowCount] afterwards — the rows are still
     * there, just checked out.
     *
     * **Caveat for dependency-chained enqueues**: the `checkout` SQL hides any row
     * whose `dependencyUniqueId` still exists in the table, so this only returns
     * the chain-head when callers enqueue with deps. Use
     * [drainOutboxInDependencyOrder] when you need every row of a chain.
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
     * Drain ALL outbox rows by repeatedly checking out the next eligible row and
     * then deleting it, which unblocks any rows that depended on it. Returns the
     * rows in dependency order (chain head first). Use this when asserting the
     * `(uniqueId, dependencyUniqueId)` shape of a chained enqueue (e.g. group
     * creation, admin update + status messages, member-change flows).
     *
     * Like [drainOutbox], this is destructive — the rows are removed from the
     * table after the call returns.
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

    /** Load the current conversation HomebaseFile from the in-memory DB. */
    suspend fun getConversationFile(conversationId: Uuid) =
        dbm.driveMainIndex.selectHomebaseFileByUnique(
            testIdentityId, chatDriveId, conversationId
        )

    override fun close() {
        if (::dbm.isInitialized) dbm.close()
    }

    // ---------- internals ----------

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
                sharedSecret = SecureByteArray(ByteArray(16))
            )
        )
        return cm
    }

    private suspend fun insertConversationFile(
        fileId: Uuid,
        uniqueId: Uuid,
        participants: List<String>,
        originalAuthor: String,
        isGroup: Boolean,
        adminDataJson: String? = null,
        title: String = "",
        archivalStatus: Int = 0,
        localTags: List<Uuid> = emptyList(),
        payloadsJson: String = "[]",
    ) {
        val now = Clock.System.now().epochSeconds
        val recipientsJson = participants.joinToString(",") { "\"$it\"" }
        val tagsField = if (isGroup) "[\"${ChatProtocol.ConversationGroupTag}\"]" else "null"
        val adminDataField = if (adminDataJson != null) """, "adminData": $adminDataJson""" else ""
        val contentJson =
            """{"title":"$title","version":1,"recipients":[$recipientsJson]$adminDataField}"""
        val escaped = contentJson.replace("\"", "\\\"")
        val localAppDataField = if (localTags.isEmpty()) {
            "null"
        } else {
            val tagList = localTags.joinToString(",") { "\"$it\"" }
            """{"tags":[$tagList],"versionTag":"${Uuid.random()}","iv":null,"content":null,"readTime":null,"localReactions":null}"""
        }

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
                "senderOdinId": "$originalAuthor",
                "originalAuthor": "$originalAuthor",
                "appData": {
                  "uniqueId": "$uniqueId",
                  "tags": $tagsField,
                  "fileType": ${ChatProtocol.ConversationFileType},
                  "dataType": 0,
                  "groupId": null,
                  "userDate": ${now}000,
                  "content": "$escaped",
                  "previewThumbnail": null,
                  "archivalStatus": $archivalStatus
                },
                "localAppData": $localAppDataField,
                "referencedFile": null,
                "reactionPreview": null,
                "versionTag": "${Uuid.random()}",
                "payloads": $payloadsJson,
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
        )
    }

    /**
     * Seed a group conversation whose conversation file carries a `convo_img`
     * payload descriptor WITH a set of thumbnails — the shape the group-heal
     * redistribute path reuses. Used to assert that the resend re-attaches those
     * thumbnails (otherwise the AppendOrOverwrite wipes them and the avatar 404s).
     */
    suspend fun seedGroupWithImagePayload(
        conversationId: Uuid = Uuid.random(),
        others: List<String>,
        thumbnailSizes: List<Int> = listOf(72, 320),
        imagePayloadIvBase64: String = "AAAAAAAAAAAAAAAAAAAAAA==", // 16 zero bytes
        title: String = "",
    ): Uuid {
        val participants = listOf(testDomain) + others
        val thumbsJson = thumbnailSizes.joinToString(",") { s ->
            """{"pixelWidth":$s,"pixelHeight":$s,"contentType":"image/webp"}"""
        }
        val payloadsJson = """[
            {
              "key": "${ChatProtocol.ConversationImageKey}",
              "contentType": "image/jpeg",
              "iv": "$imagePayloadIvBase64",
              "lastModified": 1779276955816,
              "thumbnails": [$thumbsJson]
            }
        ]"""
        insertConversationFile(
            fileId = Uuid.random(),
            uniqueId = conversationId,
            participants = participants,
            originalAuthor = testDomain,
            isGroup = true,
            adminDataJson = """{"admins":["$testDomain"]}""",
            title = title,
            payloadsJson = payloadsJson,
        )
        return conversationId
    }

    private suspend fun insertAdminFile(
        fileId: Uuid,
        uniqueId: Uuid,
        groupId: Uuid,
        admins: List<String>,
        originalAuthor: String,
    ) {
        val now = Clock.System.now().epochSeconds
        val adminsJson = admins.joinToString(",") { "\"$it\"" }
        val contentJson = """{"admins":[$adminsJson]}"""
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
                "fileByteCount": 50,
                "originalRecipientCount": 0,
                "transferHistory": null
              },
              "priority": 300,
              "fileByteCount": 50
            }"""
        )
    }

    private suspend fun insertFile(jsonHeader: String) {
        val header = OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
        insertHomebaseFile(header)
    }

    /** Insert a pre-built [HomebaseFile] into the local DriveMainIndex. Used by
     *  heal tests that need the heal status message itself present in the DB so
     *  the self-destruct's local hard-delete has something to remove. */
    suspend fun insertHomebaseFile(file: HomebaseFile) {
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
        val record = processor.convertFileHeaderToDriveMainIndexRecord(
            testIdentityId, chatDriveId, file
        )
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
    }
}

// ---------- Shared outbox assertions ----------

/**
 * Decode a drained [Outbox] row as a hard-delete request, or null if it isn't one.
 * Shared by the heal tests ([ConversationServiceHealTest]) and the recovery tests
 * ([ConversationServiceRecoveryTest]).
 */
fun Outbox.asHardDeleteRequestOrNull(): DeleteLocalFilesByFileIdRequest? {
    if (this.uploadType != DriveOutboxUploader.DeleteFile) return null
    return try {
        OdinSystemSerializer.deserialize<DeleteLocalFilesByFileIdRequest>(this.json.decodeToString())
    } catch (_: Throwable) {
        null
    }
}

fun Outbox.isHardDeleteRequest(): Boolean = asHardDeleteRequestOrNull() != null

// ---------- Test doubles ----------

class FakeIntroductionSender : IntroductionSender {
    val calls = mutableListOf<IntroductionGroup>()
    val preflightCalls = mutableListOf<IntroductionGroup>()
    /** Override to drive preflight outcomes from individual tests; default returns
     *  every recipient as Ready so tests that don't care about preflight semantics
     *  proceed unchanged. */
    var preflightResultProvider: (IntroductionGroup) -> id.homebase.api.client.connections.IntroductionPreflightResult =
        { group ->
            id.homebase.api.client.connections.IntroductionPreflightResult(
                recipients = group.recipients.map { rcpt ->
                    id.homebase.api.client.connections.RecipientPreflightStatus(
                        recipient = rcpt,
                        status = id.homebase.api.client.connections.IntroductionPreflightStatus.Ready,
                    )
                }
            )
        }

    override suspend fun sendIntroductions(group: IntroductionGroup): IntroductionResult {
        calls += group
        return IntroductionResult()
    }

    override suspend fun preflightIntroductions(
        group: IntroductionGroup
    ): id.homebase.api.client.connections.IntroductionPreflightResult {
        preflightCalls += group
        return preflightResultProvider(group)
    }
}

class FakeStatusMessageSender : StatusMessageSender {
    data class Call(
        val messageUniqueId: Uuid,
        val conversationId: Uuid,
        val statusMessage: StatusMessageData,
        val previousMessageUniqueId: Uuid?,
        val additionalRecipients: List<OdinId>,
        val recipientOverride: List<OdinId>?,
    )
    val calls = mutableListOf<Call>()
    override suspend fun sendStatusMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        statusMessage: StatusMessageData,
        previousMessageUniqueId: Uuid?,
        payloadBundle: PayloadBundle?,
        additionalRecipients: List<OdinId>,
        recipientOverride: List<OdinId>?,
    ): SendMessageResult {
        calls += Call(
            messageUniqueId = messageUniqueId,
            conversationId = conversationId,
            statusMessage = statusMessage,
            previousMessageUniqueId = previousMessageUniqueId,
            additionalRecipients = additionalRecipients,
            recipientOverride = recipientOverride,
        )
        return SendMessageResult(messageUniqueId)
    }
}

/**
 * Fake [id.homebase.api.client.drives.files.ResendPayloadByteSource] for the heal
 * redistribute path. Returns fixed non-empty encrypted bytes for any payload /
 * thumbnail request and records what was asked for so tests can assert the
 * resend pulled the payload's thumbnails (not just the payload bytes).
 */
class FakeResendPayloadByteSource(
    private val payloadBytes: ByteArray = ByteArray(2048) { 1 },
    private val thumbBytes: ByteArray = ByteArray(256) { 2 },
) : id.homebase.api.client.drives.files.ResendPayloadByteSource {
    val payloadRequests = mutableListOf<String>()
    val thumbRequests = mutableListOf<Triple<String, Int, Int>>()

    override suspend fun getPayloadBytesEncrypted(driveId: Uuid, fileId: Uuid, key: String): ByteArray {
        payloadRequests += key
        return payloadBytes
    }

    override suspend fun getThumbBytesEncrypted(
        driveId: Uuid,
        fileId: Uuid,
        payloadKey: String,
        width: Int,
        height: Int,
        lastModified: Long?,
    ): ByteArray {
        thumbRequests += Triple(payloadKey, width, height)
        return thumbBytes
    }
}

/**
 * Minimal [id.homebase.api.file.FileOperationsProvider]. Only [writeBytesToTempFile]
 * is exercised by the heal resend path (it spills encrypted payload bytes to a
 * temp file); everything else errors so an unexpected call is caught.
 */
class FakeFileOperationsProvider : id.homebase.api.file.FileOperationsProvider {
    private var counter = 0
    val writtenPaths = mutableListOf<String>()

    override fun openFileInput(path: String): io.ktor.client.request.forms.InputProvider =
        error("FakeFileOperationsProvider.openFileInput should not be called in tests")
    override suspend fun readFileBytes(path: String): ByteArray =
        error("FakeFileOperationsProvider.readFileBytes should not be called in tests")
    override fun deleteTempFile(path: String): Boolean = true
    override fun getCacheDirectory(): String = "/tmp"
    override fun getFileSize(path: String): Long = 0L
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String {
        val path = "/tmp/${prefix}${counter++}$suffix"
        writtenPaths += path
        return path
    }
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        error("FakeFileOperationsProvider.writeBytesToShareOutboundFile should not be called in tests")
    override suspend fun writeStream(path: String, data: kotlinx.coroutines.flow.Flow<ByteArray>) =
        error("FakeFileOperationsProvider.writeStream should not be called in tests")
}

class FakeConversationLoader : ConversationLoader {
    val loaded = mutableListOf<Uuid>()
    val removed = mutableListOf<Uuid>()
    override suspend fun loadConversation(conversationId: Uuid) {
        loaded += conversationId
    }
    override suspend fun removeConversation(conversationId: Uuid) {
        removed += conversationId
    }
}

class FakePayloadBundleEncryptor : PayloadBundleEncryptor {
    val calls = mutableListOf<Uuid>()
    override suspend fun encryptBundle(
        uniqueId: Uuid,
        bundle: PayloadBundle?,
        aesKey: SecureByteArray,
        scope: CoroutineScope,
    ): PayloadBundle {
        calls += uniqueId
        // Empty bundle regardless of input. Tests that need to assert payload contents
        // should use a more specific fake or the real service.
        return PayloadBundle(
            payloads = emptyList(),
            thumbnails = emptyList(),
            previewThumbs = emptyList(),
        )
    }
}

/** Fails if the outbox ever tries to send — ensures tests stay offline. */
private object ThrowingOutboxUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        error("OutboxUploader.upload should never be called in tests (setOnline(false))")
    }
}
