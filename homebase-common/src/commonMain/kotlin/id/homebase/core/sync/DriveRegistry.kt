package id.homebase.core.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.config.LabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Cross-device registry of optional drives (feed, vault, …) that this identity has
 * activated. Persisted as one file per drive on the user's Chat drive
 * (`fileType = RegistryDriveFileType`, `uniqueId = drive.alias`,
 * `appData.content = serialized LabeledDrive`).
 *
 * Reads are served from the **local** synced drive index, so they are offline-safe
 * and require no HTTP round-trip. Writes are uploaded to the Chat drive (add = new
 * file, remove = hard-delete). The standard Chat-drive sync machinery brings changes
 * from other devices back into the local index.
 *
 * [start] launches an observer on [EventBus] that diffs the local index against an
 * in-memory set whenever a Chat-drive batch arrives. Newly-appearing registry files
 * trigger [onMount]; disappearing ones trigger [onUnmount]. This is how a drive
 * activated on Device A propagates to Device B without any explicit action.
 *
 * Mandatory drives (chat, contacts, profile) are hardcoded in `mandatorySyncDrives`
 * and are never stored here. See `ADDING_ADDON_APPS.md §"Mandatory vs Optional Drives"`.
 */
class DriveRegistry(
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    // Function-type dependencies for the write path — kept narrow (not the whole provider
    // classes) so tests can pass in-memory fakes without standing up the full HTTP stack.
    // The Koin factory wires these to `driveUploadProvider::uploadFile` and
    // `driveFileProvider::hardDeleteFile` respectively.
    private val uploadFile: suspend (UploadFileRequest) -> Unit,
    private val hardDeleteFile: suspend (driveId: Uuid, fileId: Uuid) -> Unit,
    private val eventBus: EventBus,
    // Scope for the BatchReceived observer. Defaults to a fresh Default-dispatched scope
    // with a SupervisorJob, so an observer crash doesn't take down the process. Tests pass
    // `backgroundScope` from `runTest` so virtual-time helpers (advanceUntilIdle) see the
    // observer coroutine.
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private val stateMutex = Mutex()
    private var observerJob: Job? = null

    /**
     * Alias-set of drives last surfaced to callers via [onMount] — the diff baseline
     * for the observer. Guarded by [stateMutex].
     */
    private var currentDriveAliases: Set<Uuid> = emptySet()

    /** All registry drives currently present (and not soft-deleted) in the local Chat-drive index. */
    suspend fun loadDrives(): List<LabeledDrive> {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return emptyList()
        val chatDriveId = SystemDriveConstants.chatDrive.alias
        val files = databaseManager.driveMainIndex.selectHomebaseFilesByFileType(
            identityId, chatDriveId, RegistryDriveFileType.toLong(),
        )
        val drives = ArrayList<LabeledDrive>(files.size)
        for (file in files) {
            if (file.isSoftDeleted()) continue
            val content = file.fileMetadata.appData.content ?: continue
            try {
                drives += OdinSystemSerializer.deserialize<LabeledDrive>(content)
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) {
                    "loadDrives: failed to deserialize content for fileId=${file.fileId} " +
                        "uniqueId=${file.fileMetadata.appData.uniqueId}"
                }
            }
        }
        return drives
    }

    suspend fun hasDrive(driveId: Uuid): Boolean = loadDrives().any { it.drive.alias == driveId }

    /**
     * Activate [drive] by uploading a new marker file to the Chat drive.
     * Idempotent: an [OdinClientErrorCode.ExistingFileWithUniqueId] response
     * (server already has this drive registered) is treated as success.
     *
     * Callers typically go through `AuthConnectionCoordinator.mountDrive(...)` rather
     * than invoking this directly.
     */
    suspend fun addDrive(drive: LabeledDrive) {
        val keyHeader = KeyHeader.newRandom16()
        val content = OdinSystemSerializer.serialize(drive)
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = drive.drive.alias,
                fileType = RegistryDriveFileType,
                content = content,
            ),
        )
        val request = UploadFileRequest(
            driveId = SystemDriveConstants.chatDrive.alias,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
        )
        try {
            uploadFile(request)
            Logger.i(tag = TAG) { "addDrive: uploaded registry file for '${drive.label}' (${drive.drive.alias})" }
        } catch (e: ClientException) {
            if (e.errorCode == OdinClientErrorCode.ExistingFileWithUniqueId) {
                Logger.i(tag = TAG) {
                    "addDrive: registry file already exists for '${drive.label}' (${drive.drive.alias}) — idempotent no-op"
                }
            } else throw e
        }
    }

    /**
     * Deactivate the drive identified by [driveId]. Hard-deletes the registry marker
     * file from the Chat drive; a missing file is treated as success.
     */
    suspend fun removeDrive(driveId: Uuid) {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        val chatDriveId = SystemDriveConstants.chatDrive.alias
        val existing = databaseManager.driveMainIndex.selectHomebaseFileByUnique(
            identityId, chatDriveId, driveId,
        )
        if (existing == null || existing.isSoftDeleted()) {
            Logger.i(tag = TAG) { "removeDrive: no registry file for $driveId — nothing to delete" }
            return
        }
        hardDeleteFile(chatDriveId, existing.fileId)
        Logger.i(tag = TAG) { "removeDrive: hard-deleted registry file for $driveId (fileId=${existing.fileId})" }
    }

    /**
     * Start observing Chat-drive sync events and surface cross-device changes.
     * Callers are expected to have already mounted the drives returned by
     * [loadDrives] at connect time — [start] sets the diff baseline from the
     * current local-DB state WITHOUT re-emitting [onMount] for those. Callbacks
     * fire only for SUBSEQUENT changes (drives added/removed on another device).
     *
     * Safe to call repeatedly — the observer is cancelled and restarted.
     */
    suspend fun start(
        onMount: suspend (LabeledDrive) -> Unit,
        onUnmount: suspend (Uuid) -> Unit,
    ) {
        stop()
        val initial = loadDrives()
        stateMutex.withLock {
            currentDriveAliases = initial.mapTo(HashSet()) { it.drive.alias }
        }
        observerJob = scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DriveEvent.BatchReceived) return@collect
                if (event.driveId != SystemDriveConstants.chatDrive.alias) return@collect
                val touchesRegistry = event.batchData.any {
                    it.fileMetadata.appData.fileType == RegistryDriveFileType
                }
                if (!touchesRegistry) return@collect
                reconcile(onMount, onUnmount)
            }
        }
    }

    suspend fun stop() {
        observerJob?.cancel()
        observerJob = null
        stateMutex.withLock {
            currentDriveAliases = emptySet()
        }
    }

    private suspend fun reconcile(
        onMount: suspend (LabeledDrive) -> Unit,
        onUnmount: suspend (Uuid) -> Unit,
    ) {
        val fresh = loadDrives()
        val freshAliases = fresh.mapTo(HashSet()) { it.drive.alias }
        val added: List<LabeledDrive>
        val removed: List<Uuid>
        stateMutex.withLock {
            added = fresh.filter { it.drive.alias !in currentDriveAliases }
            removed = (currentDriveAliases - freshAliases).toList()
            currentDriveAliases = freshAliases
        }
        for (drive in added) onMount(drive)
        for (alias in removed) onUnmount(alias)
    }

    companion object {
        private const val TAG = "DriveRegistry"
    }
}

/**
 * Marker file type for [DriveRegistry] entries on the Chat drive. Each file is a
 * record that a specific optional drive (feed, vault, …) has been activated by this
 * identity. The file's `appData.uniqueId` equals the optional drive's
 * `TargetDrive.alias`; `appData.content` holds a serialized `LabeledDrive`.
 */
const val RegistryDriveFileType: Int = 4242

