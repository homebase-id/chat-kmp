package id.homebase.core.sync

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.core.config.LabeledDrive
import id.homebase.core.config.feedLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class DriveRegistryTest {

    // ---------- loadDrives (read path) ----------

    @Test
    fun loadDrivesReturnsEmptyWhenLocalIndexHasNoRegistryFiles() = runTest {
        val db = createTestDatabaseManager()
        val registry = buildRegistry(db)
        assertTrue(registry.loadDrives().isEmpty())
        db.close()
    }

    @Test
    fun loadDrivesParsesAllRegistryFilesFromLocalIndex() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFile(db, feedLabeledDrive)
        val vaultDrive = makeLabeledDrive("Vault")
        seedRegistryFile(db, vaultDrive)

        val registry = buildRegistry(db)
        val drives = registry.loadDrives()

        assertEquals(2, drives.size)
        assertTrue(drives.any { it.drive.alias == feedLabeledDrive.drive.alias && it.label == "Feed" })
        assertTrue(drives.any { it.drive.alias == vaultDrive.drive.alias && it.label == "Vault" })
        db.close()
    }

    @Test
    fun loadDrivesIgnoresChatDriveFilesOfOtherTypes() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFile(db, feedLabeledDrive)
        // A chat-drive file with a different fileType (e.g. a conversation) must be skipped.
        seedOtherTypeFile(db, fileType = 8888)

        val registry = buildRegistry(db)
        val drives = registry.loadDrives()

        assertEquals(listOf(feedLabeledDrive.drive.alias), drives.map { it.drive.alias })
        db.close()
    }

    @Test
    fun loadDrivesIgnoresSoftDeletedRegistryFiles() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFile(db, feedLabeledDrive, softDeleted = true)
        val vaultDrive = makeLabeledDrive("Vault")
        seedRegistryFile(db, vaultDrive)

        val registry = buildRegistry(db)
        val drives = registry.loadDrives()

        assertEquals(listOf(vaultDrive.drive.alias), drives.map { it.drive.alias })
        db.close()
    }

    // ---------- hasDrive ----------

    @Test
    fun hasDriveReturnsTrueForRegisteredDrive() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFile(db, feedLabeledDrive)
        val registry = buildRegistry(db)
        assertTrue(registry.hasDrive(feedLabeledDrive.drive.alias))
        db.close()
    }

    @Test
    fun hasDriveReturnsFalseForUnknownDrive() = runTest {
        val db = createTestDatabaseManager()
        val registry = buildRegistry(db)
        assertFalse(registry.hasDrive(Uuid.random()))
        db.close()
    }

    // ---------- observer ----------

    @Test
    fun startInitializesDiffBaselineWithoutEmittingOnMountForExistingDrives() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFile(db, feedLabeledDrive)
        val eventBus = EventBus()
        val registry = buildRegistry(db, eventBus = eventBus)

        val mounted = mutableListOf<LabeledDrive>()
        val unmounted = mutableListOf<Uuid>()

        registry.start(
            onMount = { mounted += it },
            onUnmount = { unmounted += it },
        )
        advanceUntilIdle()

        // Registry drives already present in the local DB at start() are NOT re-emitted.
        // They form the diff baseline for subsequent BatchReceived events.
        assertTrue(mounted.isEmpty(), "start() must not emit onMount for already-present drives")
        assertTrue(unmounted.isEmpty())

        registry.stop()
        db.close()
    }

    @Test
    fun observerEmitsMountCallbackWhenBatchReceivedCarriesNewRegistryFile() = runTest {
        val db = createTestDatabaseManager()
        val eventBus = EventBus()
        val registry = buildRegistry(db, eventBus = eventBus)

        val mounted = mutableListOf<LabeledDrive>()
        val unmounted = mutableListOf<Uuid>()
        registry.start(
            onMount = { mounted += it },
            onUnmount = { unmounted += it },
        )
        advanceUntilIdle()

        // Simulate another device activating Feed: the chat-drive sync picks up the file
        // and emits a BatchReceived. We seed the row in the local DB first (sync's job),
        // then emit the event the observer listens on.
        seedRegistryFile(db, feedLabeledDrive)
        val file = buildRegistryFile(feedLabeledDrive)
        val emitJob = launch {
            eventBus.emit(
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = SystemDriveConstants.chatDrive.alias,
                    totalCount = 1,
                    batchCount = 1,
                    latestModified = null,
                    batchData = listOf(file),
                )
            )
        }
        emitJob.join()
        advanceUntilIdle()

        assertEquals(listOf(feedLabeledDrive.drive.alias), mounted.map { it.drive.alias })
        assertTrue(unmounted.isEmpty())

        registry.stop()
        db.close()
    }

    @Test
    fun observerEmitsUnmountCallbackWhenRegistryFileBecomesSoftDeleted() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFile(db, feedLabeledDrive)
        val eventBus = EventBus()
        val registry = buildRegistry(db, eventBus = eventBus)

        val mounted = mutableListOf<LabeledDrive>()
        val unmounted = mutableListOf<Uuid>()
        registry.start(
            onMount = { mounted += it },
            onUnmount = { unmounted += it },
        )
        advanceUntilIdle()

        // Mark the feed registry row as soft-deleted in the local DB, then emit a
        // BatchReceived carrying the tombstoned file. The observer should notice the
        // drive disappeared from loadDrives() and call onUnmount.
        seedRegistryFile(db, feedLabeledDrive, softDeleted = true)
        val tombstonedFile = buildRegistryFile(feedLabeledDrive, softDeleted = true)
        launch {
            eventBus.emit(
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = SystemDriveConstants.chatDrive.alias,
                    totalCount = 1,
                    batchCount = 1,
                    latestModified = null,
                    batchData = listOf(tombstonedFile),
                )
            )
        }.join()
        advanceUntilIdle()

        assertEquals(listOf(feedLabeledDrive.drive.alias), unmounted)
        assertTrue(mounted.isEmpty())

        registry.stop()
        db.close()
    }

    @Test
    fun observerIgnoresBatchesOnOtherDrives() = runTest {
        val db = createTestDatabaseManager()
        val eventBus = EventBus()
        val registry = buildRegistry(db, eventBus = eventBus)

        val mounted = mutableListOf<LabeledDrive>()
        val unmounted = mutableListOf<Uuid>()
        registry.start(
            onMount = { mounted += it },
            onUnmount = { unmounted += it },
        )
        advanceUntilIdle()

        // A batch on some OTHER drive — must be a no-op even if the batch has a
        // fileType=4242 file (the registry is scoped to the chat drive only).
        seedRegistryFile(db, feedLabeledDrive)
        val unrelatedFile = buildRegistryFile(feedLabeledDrive)
        launch {
            eventBus.emit(
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = Uuid.random(),
                    totalCount = 1,
                    batchCount = 1,
                    latestModified = null,
                    batchData = listOf(unrelatedFile),
                )
            )
        }.join()
        advanceUntilIdle()
        yield()

        assertTrue(mounted.isEmpty())
        assertTrue(unmounted.isEmpty())

        registry.stop()
        db.close()
    }

    @Test
    fun observerShortCircuitsBatchesWithoutRegistryFiles() = runTest {
        val db = createTestDatabaseManager()
        val eventBus = EventBus()
        val registry = buildRegistry(db, eventBus = eventBus)

        val mounted = mutableListOf<LabeledDrive>()
        val unmounted = mutableListOf<Uuid>()
        registry.start(
            onMount = { mounted += it },
            onUnmount = { unmounted += it },
        )
        advanceUntilIdle()

        // A chat-drive batch containing a different fileType (e.g. a message). The
        // observer must not trigger reconciliation because the registry file-type isn't
        // present in the batch — verifies the short-circuit in start().
        val otherFile = buildFile(fileType = 8888, uniqueId = Uuid.random(), content = "{}")
        launch {
            eventBus.emit(
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = SystemDriveConstants.chatDrive.alias,
                    totalCount = 1,
                    batchCount = 1,
                    latestModified = null,
                    batchData = listOf(otherFile),
                )
            )
        }.join()
        advanceUntilIdle()

        assertTrue(mounted.isEmpty())
        assertTrue(unmounted.isEmpty())

        registry.stop()
        db.close()
    }

    // ---------- test helpers ----------

    private fun TestScope.buildRegistry(
        db: DatabaseManager,
        eventBus: EventBus = EventBus(),
    ): DriveRegistry = buildRegistryIn(db, eventBus, backgroundScope)

    private fun buildRegistryIn(
        db: DatabaseManager,
        eventBus: EventBus,
        scope: CoroutineScope,
    ): DriveRegistry {
        val credentialsManager = CredentialsManager()
        // suspend set — deferred to runTest scope via a blocking runCatching
        kotlinx.coroutines.runBlocking {
            credentialsManager.setActiveCredentials(
                ApiCredentials.create(
                    domain = OdinId("test.homebase.id"),
                    clientAccessToken = "fake-token",
                    sharedSecret = SecureByteArray(ByteArray(16)),
                )
            )
        }
        return DriveRegistry(
            credentialsManager = credentialsManager,
            databaseManager = db,
            // Write-path lambdas aren't exercised by the tests in this suite — they cover
            // loadDrives, hasDrive, and the Chat-drive observer only. If a future test
            // exercises addDrive/removeDrive, replace these with recording lambdas.
            uploadFile = { throw UnsupportedOperationException("not exercised in tests") },
            hardDeleteFile = { _, _ -> throw UnsupportedOperationException("not exercised in tests") },
            eventBus = eventBus,
            scope = scope,
        )
    }

    private fun makeLabeledDrive(label: String): LabeledDrive {
        val drive = feedLabeledDrive.drive.copy(alias = Uuid.random())
        return LabeledDrive(drive = drive, label = label)
    }

    private fun buildRegistryFile(
        drive: LabeledDrive,
        softDeleted: Boolean = false,
    ): HomebaseFile {
        val serialized = OdinSystemSerializer.serialize(drive)
        return buildFile(
            fileType = RegistryDriveFileType,
            uniqueId = drive.drive.alias,
            content = serialized,
            softDeleted = softDeleted,
        )
    }

    private fun buildFile(
        fileType: Int,
        uniqueId: Uuid,
        content: String,
        softDeleted: Boolean = false,
    ): HomebaseFile {
        val now = UnixTimeUtc.now().milliseconds
        val fileState = if (softDeleted) "deleted" else "active"
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        val json = """{
              "fileId": "${Uuid.random()}",
              "driveId": "${SystemDriveConstants.chatDrive.alias}",
              "fileState": "$fileState",
              "fileSystemType": "standard",
              "serverFileIsEncrypted": "false",
              "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
              },
              "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": $now,
                "updated": $now,
                "transitCreated": 0,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": null,
                "originalAuthor": null,
                "appData": {
                  "uniqueId": "$uniqueId",
                  "tags": null,
                  "fileType": $fileType,
                  "dataType": 0,
                  "groupId": null,
                  "userDate": $now,
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
                "allowDistribution": false,
                "fileSystemType": "standard",
                "fileByteCount": 0,
                "originalRecipientCount": 0,
                "transferHistory": null
              },
              "priority": 0,
              "fileByteCount": 0
            }"""
        return OdinSystemSerializer.deserialize<HomebaseFile>(json)
    }

    private suspend fun seedRegistryFile(
        db: DatabaseManager,
        drive: LabeledDrive,
        softDeleted: Boolean = false,
    ) {
        val file = buildRegistryFile(drive, softDeleted = softDeleted)
        seedFile(db, file)
    }

    private suspend fun seedOtherTypeFile(db: DatabaseManager, fileType: Int) {
        val file = buildFile(fileType = fileType, uniqueId = Uuid.random(), content = "{}")
        seedFile(db, file)
    }

    private suspend fun seedFile(db: DatabaseManager, file: HomebaseFile) {
        val identityId = ApiCredentials.create(
            domain = OdinId("test.homebase.id"),
            clientAccessToken = "fake-token",
            sharedSecret = SecureByteArray(ByteArray(16)),
        ).getIdentityId()
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(db)
        val record = processor.convertFileHeaderToDriveMainIndexRecord(
            identityId, SystemDriveConstants.chatDrive.alias, file,
        )
        MainIndexMetaHelpers.upsertDriveMainIndex(db, record)
    }

}
