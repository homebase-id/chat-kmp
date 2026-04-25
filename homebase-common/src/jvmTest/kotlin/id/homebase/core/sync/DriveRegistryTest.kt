package id.homebase.core.sync

import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class DriveRegistryTest {

    // ---------- loadDrives (read path) ----------

    @Test
    fun loadDrivesReturnsEmptyWhenSingletonFileAbsent() = runTest {
        val db = createTestDatabaseManager()
        val registry = buildRegistry(db)
        assertTrue(registry.loadDrives().isEmpty())
        db.close()
    }

    @Test
    fun loadDrivesReturnsAllDrivesFromSingletonFileContent() = runTest {
        val db = createTestDatabaseManager()
        val vaultDrive = makeLabeledDrive("Vault")
        seedRegistryFile(db, listOf(feedLabeledDrive, vaultDrive))

        val registry = buildRegistry(db)
        val drives = registry.loadDrives()

        assertEquals(2, drives.size)
        assertTrue(drives.any { it.drive.alias == feedLabeledDrive.drive.alias && it.label == "Feed" })
        assertTrue(drives.any { it.drive.alias == vaultDrive.drive.alias && it.label == "Vault" })
        db.close()
    }

    @Test
    fun loadDrivesReturnsEmptyWhenContentIsNull() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFileWithRawContent(db, rawContent = null)
        val registry = buildRegistry(db)
        assertTrue(registry.loadDrives().isEmpty())
        db.close()
    }

    @Test
    fun loadDrivesReturnsEmptyOnCorruptContent() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFileWithRawContent(db, rawContent = "not valid json")
        val registry = buildRegistry(db)
        // Corrupt content logs a warning but must not throw.
        assertTrue(registry.loadDrives().isEmpty())
        db.close()
    }

    // ---------- hasDrive ----------

    @Test
    fun hasDriveReturnsTrueForRegisteredDrive() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFile(db, listOf(feedLabeledDrive))
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

    // ---------- addDrive / removeDrive (write path) ----------

    @Test
    fun addDriveCreatesSingletonFileWhenAbsent() = runTest {
        val db = createTestDatabaseManager()
        val recorder = WriteRecorder()
        val registry = buildRegistry(db, recorder = recorder)

        registry.addDrive(feedLabeledDrive)

        assertEquals(0, recorder.updates.size)
        assertEquals(1, recorder.uploads.size)
        val upload = recorder.uploads.single()
        assertEquals(SystemDriveConstants.chatDrive.alias, upload.driveId)
        assertEquals(REGISTRY_UNIQUE_ID, upload.metadata.appData.uniqueId)
        assertEquals(RegistryDriveFileType, upload.metadata.appData.fileType)
        assertNull(upload.metadata.versionTag)
        assertEquals(false, upload.metadata.allowDistribution)
        db.close()
    }

    @Test
    fun addDriveUpdatesSingletonFileWhenPresent() = runTest {
        val db = createTestDatabaseManager()
        val vaultDrive = makeLabeledDrive("Vault")
        val existing = buildRegistryFile(listOf(feedLabeledDrive))
        val recorder = WriteRecorder(existingServerFile = existing)
        val registry = buildRegistry(db, recorder = recorder)

        registry.addDrive(vaultDrive)

        assertEquals(0, recorder.uploads.size)
        assertEquals(1, recorder.updates.size)
        val update = recorder.updates.single()
        assertEquals(REGISTRY_UNIQUE_ID, update.uniqueId)
        assertEquals(existing.fileMetadata.versionTag, update.metadata.versionTag)
        db.close()
    }

    @Test
    fun addDriveIsIdempotentWhenDriveAlreadyInList() = runTest {
        val db = createTestDatabaseManager()
        val existing = buildRegistryFile(listOf(feedLabeledDrive))
        val recorder = WriteRecorder(existingServerFile = existing)
        val registry = buildRegistry(db, recorder = recorder)

        registry.addDrive(feedLabeledDrive)

        // No writes: mutate() returned an identical list.
        assertEquals(0, recorder.uploads.size)
        assertEquals(0, recorder.updates.size)
        db.close()
    }

    @Test
    fun addDriveRetriesOnVersionTagMismatch() = runTest {
        val db = createTestDatabaseManager()
        val vaultDrive = makeLabeledDrive("Vault")
        val staleFile = buildRegistryFile(listOf(feedLabeledDrive))
        // Second fetch returns a file where another device already added the community drive.
        val communityDrive = makeLabeledDrive("Community")
        val freshFile = buildRegistryFile(listOf(feedLabeledDrive, communityDrive))
        val fetchesToReturn = ArrayDeque(listOf(staleFile, freshFile))
        val updatesToThrow = ArrayDeque(listOf(OdinClientErrorCode.VersionTagMismatch))

        val recorder = WriteRecorder(
            fetchResolver = { fetchesToReturn.removeFirst() },
            updateErrorOnCall = { updatesToThrow.removeFirstOrNull() },
        )
        val registry = buildRegistry(db, recorder = recorder)

        registry.addDrive(vaultDrive)

        assertEquals(2, recorder.updates.size)
        // The retry read the FRESH file and appended our delta to its list —
        // final payload should hold feed + community + vault, not feed + vault.
        val aliases = decryptedAliases(recorder.updates.last())
        assertEquals(3, aliases.size)
        assertTrue(feedLabeledDrive.drive.alias in aliases)
        assertTrue(communityDrive.drive.alias in aliases)
        assertTrue(vaultDrive.drive.alias in aliases)
        db.close()
    }

    @Test
    fun addDriveRetriesOnExistingFileWithUniqueIdDuringCreate() = runTest {
        val db = createTestDatabaseManager()
        // First fetch returns null (no file yet); the initial upload races against
        // another device which wrote the file first — ExistingFileWithUniqueId.
        // Second fetch returns the file the other device just created.
        val otherDeviceFile = buildRegistryFile(listOf(feedLabeledDrive))
        val fetchesToReturn = ArrayDeque(listOf<HomebaseFile?>(null, otherDeviceFile))
        val uploadsToThrow = ArrayDeque(listOf(OdinClientErrorCode.ExistingFileWithUniqueId))

        val vaultDrive = makeLabeledDrive("Vault")
        val recorder = WriteRecorder(
            fetchResolver = { fetchesToReturn.removeFirst() },
            uploadErrorOnCall = { uploadsToThrow.removeFirstOrNull() },
        )
        val registry = buildRegistry(db, recorder = recorder)

        registry.addDrive(vaultDrive)

        assertEquals(1, recorder.uploads.size)
        assertEquals(1, recorder.updates.size)
        val aliases = decryptedAliases(recorder.updates.single())
        assertTrue(feedLabeledDrive.drive.alias in aliases)
        assertTrue(vaultDrive.drive.alias in aliases)
        db.close()
    }

    @Test
    fun addDriveThrowsAfterExhaustingRetries() = runTest {
        val db = createTestDatabaseManager()
        val existing = buildRegistryFile(listOf(feedLabeledDrive))
        val recorder = WriteRecorder(
            fetchResolver = { existing },  // always stale
            updateErrorOnCall = { OdinClientErrorCode.VersionTagMismatch },  // always conflict
        )
        val registry = buildRegistry(db, recorder = recorder)

        assertFailsWith<IllegalStateException> {
            registry.addDrive(makeLabeledDrive("Vault"))
        }
        db.close()
    }

    @Test
    fun addDrivePropagatesNonRetryableErrors() = runTest {
        val db = createTestDatabaseManager()
        val recorder = WriteRecorder(
            uploadErrorOnCall = { OdinClientErrorCode.UnhandledScenario },
        )
        val registry = buildRegistry(db, recorder = recorder)

        assertFailsWith<ClientException> {
            registry.addDrive(feedLabeledDrive)
        }
        db.close()
    }

    @Test
    fun removeDriveUpdatesSingletonFileRemovingDrive() = runTest {
        val db = createTestDatabaseManager()
        val vaultDrive = makeLabeledDrive("Vault")
        val existing = buildRegistryFile(listOf(feedLabeledDrive, vaultDrive))
        val recorder = WriteRecorder(existingServerFile = existing)
        val registry = buildRegistry(db, recorder = recorder)

        registry.removeDrive(feedLabeledDrive.drive.alias)

        assertEquals(1, recorder.updates.size)
        val aliases = decryptedAliases(recorder.updates.single())
        assertEquals(listOf(vaultDrive.drive.alias), aliases)
        db.close()
    }

    @Test
    fun removeDriveIsNoOpWhenDriveNotInList() = runTest {
        val db = createTestDatabaseManager()
        val existing = buildRegistryFile(listOf(feedLabeledDrive))
        val recorder = WriteRecorder(existingServerFile = existing)
        val registry = buildRegistry(db, recorder = recorder)

        registry.removeDrive(Uuid.random())

        assertEquals(0, recorder.uploads.size)
        assertEquals(0, recorder.updates.size)
        db.close()
    }

    @Test
    fun removeDriveIsNoOpWhenSingletonFileAbsent() = runTest {
        val db = createTestDatabaseManager()
        val recorder = WriteRecorder()  // no existing file
        val registry = buildRegistry(db, recorder = recorder)

        registry.removeDrive(feedLabeledDrive.drive.alias)

        assertEquals(0, recorder.uploads.size)
        assertEquals(0, recorder.updates.size)
        db.close()
    }

    // ---------- bootstrap ----------

    @Test
    fun bootstrapReturnsLocalDrivesWithoutFetchingServerWhenLocalAvailable() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFile(db, listOf(feedLabeledDrive))
        val recorder = WriteRecorder()
        val registry = buildRegistry(db, recorder = recorder)

        val drives = registry.bootstrap()

        assertEquals(listOf(feedLabeledDrive.drive.alias), drives.map { it.drive.alias })
        assertEquals(0, recorder.fetchCount, "bootstrap must not hit the server when local DB has the file")
        db.close()
    }

    @Test
    fun bootstrapFallsBackToServerWhenLocalEmpty() = runTest {
        val db = createTestDatabaseManager()
        val serverFile = buildRegistryFile(listOf(feedLabeledDrive))
        val recorder = WriteRecorder(existingServerFile = serverFile)
        val registry = buildRegistry(db, recorder = recorder)

        val drives = registry.bootstrap()

        assertEquals(listOf(feedLabeledDrive.drive.alias), drives.map { it.drive.alias })
        assertEquals(1, recorder.fetchCount)
        db.close()
    }

    @Test
    fun bootstrapReturnsEmptyWhenLocalEmptyAndServerHasNoFile() = runTest {
        val db = createTestDatabaseManager()
        val recorder = WriteRecorder()  // existingServerFile = null
        val registry = buildRegistry(db, recorder = recorder)

        val drives = registry.bootstrap()

        assertTrue(drives.isEmpty())
        assertEquals(1, recorder.fetchCount)
        db.close()
    }

    @Test
    fun bootstrapFallsBackToEmptyWhenServerFetchThrows() = runTest {
        val db = createTestDatabaseManager()
        val recorder = WriteRecorder(
            fetchResolver = { throw RuntimeException("simulated network failure") },
        )
        val registry = buildRegistry(db, recorder = recorder)

        // Must NOT throw — bootstrap is best-effort. Caller proceeds with empty;
        // observer picks up the file on the next chat-drive sync.
        val drives = registry.bootstrap()

        assertTrue(drives.isEmpty())
        assertEquals(1, recorder.fetchCount)
        db.close()
    }

    // ---------- observer ----------

    @Test
    fun startInitializesDiffBaselineWithoutEmittingOnMountForExistingDrives() = runTest {
        val db = createTestDatabaseManager()
        seedRegistryFile(db, listOf(feedLabeledDrive))
        val eventBus = EventBus()
        val registry = buildRegistry(db, eventBus = eventBus)

        val mounted = mutableListOf<LabeledDrive>()
        val unmounted = mutableListOf<Uuid>()

        registry.start(
            onMount = { mounted += it },
            onUnmount = { unmounted += it },
        )
        advanceUntilIdle()

        assertTrue(mounted.isEmpty(), "start() must not emit onMount for already-present drives")
        assertTrue(unmounted.isEmpty())

        registry.stop()
        db.close()
    }

    @Test
    fun startUsesExplicitInitialBaselineWhenProvided() = runTest {
        // Models the fresh-login flow: bootstrap fetched the registry from the server,
        // mounted the drives, but the local DB doesn't have the file yet. start() is
        // given the bootstrapped baseline. When the chat-drive sync later writes the
        // same file into the local index and emits BatchReceived, the diff against the
        // baseline is empty — no spurious onMount.
        val db = createTestDatabaseManager()
        val eventBus = EventBus()
        val registry = buildRegistry(db, eventBus = eventBus)

        val mounted = mutableListOf<LabeledDrive>()
        val unmounted = mutableListOf<Uuid>()
        registry.start(
            onMount = { mounted += it },
            onUnmount = { unmounted += it },
            initialBaseline = setOf(feedLabeledDrive.drive.alias),
        )
        advanceUntilIdle()

        // First chat-drive sync delivers the registry file into the local DB. Without
        // an explicit baseline this would be diff'd against an empty set and onMount
        // would fire — the regression we're guarding against.
        seedRegistryFile(db, listOf(feedLabeledDrive))
        val registryFile = buildRegistryFile(listOf(feedLabeledDrive))
        launch {
            eventBus.emit(
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = SystemDriveConstants.chatDrive.alias,
                    totalCount = 1,
                    batchCount = 1,
                    latestModified = null,
                    batchData = listOf(registryFile),
                )
            )
        }.join()
        advanceUntilIdle()

        assertTrue(mounted.isEmpty(), "onMount must not fire — feed was already in the explicit baseline")
        assertTrue(unmounted.isEmpty())

        registry.stop()
        db.close()
    }

    @Test
    fun observerEmitsMountWhenBatchCarriesRegistryFileWithNewDrive() = runTest {
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

        // Simulate another device activating Feed: chat-drive sync writes the registry
        // file to our local DB and emits BatchReceived.
        seedRegistryFile(db, listOf(feedLabeledDrive))
        val registryFile = buildRegistryFile(listOf(feedLabeledDrive))
        launch {
            eventBus.emit(
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = SystemDriveConstants.chatDrive.alias,
                    totalCount = 1,
                    batchCount = 1,
                    latestModified = null,
                    batchData = listOf(registryFile),
                )
            )
        }.join()
        advanceUntilIdle()

        assertEquals(listOf(feedLabeledDrive.drive.alias), mounted.map { it.drive.alias })
        assertTrue(unmounted.isEmpty())

        registry.stop()
        db.close()
    }

    @Test
    fun observerEmitsUnmountWhenBatchCarriesShrunkList() = runTest {
        val db = createTestDatabaseManager()
        val vaultDrive = makeLabeledDrive("Vault")
        seedRegistryFile(db, listOf(feedLabeledDrive, vaultDrive))
        val eventBus = EventBus()
        val registry = buildRegistry(db, eventBus = eventBus)

        val mounted = mutableListOf<LabeledDrive>()
        val unmounted = mutableListOf<Uuid>()
        registry.start(
            onMount = { mounted += it },
            onUnmount = { unmounted += it },
        )
        advanceUntilIdle()

        // Another device calls removeDrive(vault) — the registry file is updated in
        // the sync pipeline and a BatchReceived event is emitted with the shrunk list.
        seedRegistryFile(db, listOf(feedLabeledDrive))
        val updatedFile = buildRegistryFile(listOf(feedLabeledDrive))
        launch {
            eventBus.emit(
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = SystemDriveConstants.chatDrive.alias,
                    totalCount = 1,
                    batchCount = 1,
                    latestModified = null,
                    batchData = listOf(updatedFile),
                )
            )
        }.join()
        advanceUntilIdle()

        assertEquals(listOf(vaultDrive.drive.alias), unmounted)
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

        seedRegistryFile(db, listOf(feedLabeledDrive))
        val registryFile = buildRegistryFile(listOf(feedLabeledDrive))
        launch {
            eventBus.emit(
                BackendEvent.DriveEvent.BatchReceived(
                    driveId = Uuid.random(),  // not the chat drive
                    totalCount = 1,
                    batchCount = 1,
                    latestModified = null,
                    batchData = listOf(registryFile),
                )
            )
        }.join()
        advanceUntilIdle()

        assertTrue(mounted.isEmpty())
        assertTrue(unmounted.isEmpty())

        registry.stop()
        db.close()
    }

    @Test
    fun observerIgnoresChatDriveBatchesNotCarryingTheRegistryFile() = runTest {
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

        // Chat-drive batch with an unrelated file (e.g. a message) — short-circuit hit,
        // no local DB re-read.
        val otherFile = buildFile(
            fileType = 8888,
            uniqueId = Uuid.random(),
            content = "{}",
        )
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

    /**
     * Captures write-path calls and answers the read-before-write fetches.
     * All fields are mutable-by-lambda so tests can vary behavior across attempts
     * (see [addDriveRetriesOnVersionTagMismatch]).
     */
    private class WriteRecorder(
        existingServerFile: HomebaseFile? = null,
        // Lambda form lets a single recorder answer multiple fetches with different
        // files across retries. Default: always return the fixed [existingServerFile].
        val fetchResolver: suspend () -> HomebaseFile? = { existingServerFile },
        // Returning a non-null error code on a call makes that call throw a ClientException.
        // Returning null (or omitting) → success (the request is captured).
        val uploadErrorOnCall: (() -> OdinClientErrorCode?)? = null,
        val updateErrorOnCall: (() -> OdinClientErrorCode?)? = null,
    ) {
        val uploads = mutableListOf<UploadFileRequest>()
        val updates = mutableListOf<UpdateFileByUniqueIdRequest>()
        var fetchCount = 0
            private set

        suspend fun fetch(): HomebaseFile? {
            fetchCount++
            return fetchResolver()
        }
    }

    private fun TestScope.buildRegistry(
        db: DatabaseManager,
        eventBus: EventBus = EventBus(),
        recorder: WriteRecorder = WriteRecorder(),
    ): DriveRegistry {
        val credentialsManager = CredentialsManager()
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
            getFileHeaderByUid = { _, _ -> recorder.fetch() },
            uploadFile = { request ->
                // Record the attempt regardless of outcome so retries are observable.
                recorder.uploads += request
                val err = recorder.uploadErrorOnCall?.invoke()
                if (err != null) throw buildClientException(err)
            },
            updateFileByUniqueId = { request ->
                recorder.updates += request
                val err = recorder.updateErrorOnCall?.invoke()
                if (err != null) throw buildClientException(err)
            },
            eventBus = eventBus,
            scope = backgroundScope,
        )
    }

    private fun buildClientException(code: OdinClientErrorCode): ClientException =
        ClientException(
            status = 400,
            errorCode = code,
            message = "test-induced $code",
            correlationId = null,
            problem = ProblemDetails(
                status = 400,
                title = "test",
            ),
        )

    private fun makeLabeledDrive(label: String): LabeledDrive {
        val drive = feedLabeledDrive.drive.copy(alias = Uuid.random())
        return LabeledDrive(drive = drive, label = label)
    }

    private fun buildRegistryFile(drives: List<LabeledDrive>): HomebaseFile {
        val serialized = OdinSystemSerializer.serialize(drives)
        return buildFile(
            fileType = RegistryDriveFileType,
            uniqueId = REGISTRY_UNIQUE_ID,
            content = serialized,
        )
    }

    private fun buildFile(
        fileType: Int,
        uniqueId: Uuid,
        content: String?,
    ): HomebaseFile {
        val now = UnixTimeUtc.now().milliseconds
        val contentField = if (content == null) "null" else "\"${escape(content)}\""
        val json = """{
              "fileId": "${Uuid.random()}",
              "driveId": "${SystemDriveConstants.chatDrive.alias}",
              "fileState": "active",
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
                  "content": $contentField,
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

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private suspend fun seedRegistryFile(db: DatabaseManager, drives: List<LabeledDrive>) {
        seedFile(db, buildRegistryFile(drives))
    }

    private suspend fun seedRegistryFileWithRawContent(db: DatabaseManager, rawContent: String?) {
        seedFile(
            db,
            buildFile(
                fileType = RegistryDriveFileType,
                uniqueId = REGISTRY_UNIQUE_ID,
                content = rawContent,
            ),
        )
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

    private fun parseAliases(content: String): List<Uuid> =
        OdinSystemSerializer.deserialize<List<LabeledDrive>>(content).map { it.drive.alias }

    /** Production encrypts appData.content via metadata.encryptContent(keyHeader). To
     *  inspect the captured payload we have to round-trip through the same KeyHeader. */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun decryptedAliases(request: UploadFileRequest): List<Uuid> {
        val ciphertext = Base64.decode(request.metadata.appData.content!!)
        val plaintext = request.keyHeader.decrypt(ciphertext).decodeToString()
        return parseAliases(plaintext)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun decryptedAliases(request: UpdateFileByUniqueIdRequest): List<Uuid> {
        val ciphertext = Base64.decode(request.metadata.appData.content!!)
        val plaintext = request.keyHeader!!.decrypt(ciphertext).decodeToString()
        return parseAliases(plaintext)
    }
}
