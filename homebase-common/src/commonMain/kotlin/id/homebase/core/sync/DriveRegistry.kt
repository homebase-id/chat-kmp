package id.homebase.core.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.crypto.ByteArrayUtil
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
 * activated.
 *
 * **Storage model.** A single file on the user's Chat drive:
 * - `driveId = SystemDriveConstants.chatDrive.alias`
 * - `fileType = RegistryDriveFileType` (4242)
 * - `uniqueId = REGISTRY_UNIQUE_ID` (a fixed well-known constant, one file per identity)
 * - `appData.content = OdinSystemSerializer.serialize(List<LabeledDrive>)`
 *
 * Add/remove mutate the list and write back the whole file via
 * `updateFileByUniqueId` with the current `versionTag` — the file itself is never
 * deleted. Concurrent edits from two devices land as `VersionTagMismatch`; the
 * loser re-fetches and retries, merging its delta into the winner's list.
 *
 * **Why a single list file instead of one file per drive.** The Homebase sync
 * engine propagates UPDATES to files via `BatchReceived`, but a hard-deleted file
 * simply vanishes on the server — other devices never see a tombstone. A per-drive
 * design means "unmount" doesn't propagate. Treating the registry as a single file
 * whose content mutates sidesteps that: every add/remove is a content update, and
 * updates propagate the same way a chat message does.
 *
 * **Reads are offline-safe.** [loadDrives] queries the local SQLDelight index, which
 * the Chat-drive sync pipeline populates decrypted (see
 * `ServerFile.decryptAppData`). No HTTP.
 *
 * **Cross-device propagation.** [start] launches an observer on the [EventBus] that
 * diffs the local index against an in-memory set whenever a Chat-drive batch
 * carrying the registry file arrives. Newly-appearing drives trigger [onMount];
 * disappearing ones trigger [onUnmount].
 *
 * Mandatory drives (chat, contacts, profile) are hardcoded in `mandatorySyncDrives`
 * and are never stored here. See `ADDING_ADDON_APPS.md §"Mandatory vs Optional Drives"`.
 */
class DriveRegistry(
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    // Function-type dependencies for the write path — kept narrow so tests can pass
    // in-memory fakes without standing up the full HTTP stack. The Koin factory wires
    // these to DriveFileProvider::getFileHeaderByUid, DriveUploadProvider::uploadFile,
    // and DriveUploadProvider::updateFileByUniqueId respectively.
    private val getFileHeaderByUid: suspend (driveId: Uuid, uniqueId: Uuid) -> HomebaseFile?,
    private val uploadFile: suspend (UploadFileRequest) -> Unit,
    private val updateFileByUniqueId: suspend (UpdateFileByUniqueIdRequest) -> Unit,
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
     * Alias-set of drives last surfaced to callers. The diff baseline for the observer.
     * Guarded by [stateMutex].
     */
    private var currentDriveAliases: Set<Uuid> = emptySet()

    /**
     * Optional drives currently registered by this identity. Pure local-DB read — safe
     * offline. Returns an empty list when no registry file exists yet (fresh install)
     * or when credentials aren't active.
     */
    suspend fun loadDrives(): List<LabeledDrive> {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return emptyList()
        val chatDriveId = SystemDriveConstants.chatDrive.alias
        val file = databaseManager.driveMainIndex.selectHomebaseFileByUnique(
            identityId, chatDriveId, REGISTRY_UNIQUE_ID,
        ) ?: return emptyList()
        return parseRegistryContent(file)
    }

    suspend fun hasDrive(driveId: Uuid): Boolean =
        loadDrives().any { it.drive.alias == driveId }

    /**
     * Resolve the registry on login: try the local index first (fast, offline-safe,
     * covers cold boot of returning users), then fall back to a single
     * `getFileHeaderByUid` HTTP call (covers fresh login on a new device or one whose
     * local DB was wiped). On both-empty or any fetch failure, returns an empty list
     * so the caller proceeds with mandatory drives only — the observer will pick the
     * file up on the next chat-drive sync.
     *
     * This eliminates the "first WS connect subscribes to mandatory only, then
     * reconnects after the first sync delivers the registry file" race on fresh
     * login. Callers should pass the result to both [AuthConnectionCoordinator.connect]
     * (so the WS subscribes to the full set on the first connect) and [start]'s
     * `initialBaseline` (so the observer doesn't fire spurious onMount when the
     * chat-drive sync later writes the same file into the local index).
     */
    suspend fun bootstrap(): List<LabeledDrive> {
        val local = loadDrives()
        if (local.isNotEmpty()) return local
        val chatDriveId = SystemDriveConstants.chatDrive.alias
        val server = runCatching { getFileHeaderByUid(chatDriveId, REGISTRY_UNIQUE_ID) }
            .getOrElse { e ->
                Logger.w(tag = TAG, throwable = e) {
                    "bootstrap: server fetch failed — falling back to empty (observer will pick up after first sync)"
                }
                return emptyList()
            } ?: return emptyList()
        return parseRegistryContent(server)
    }

    /**
     * Register [drive] in the cross-device registry. Idempotent: if [drive] is already
     * in the list this is a no-op with no network I/O.
     *
     * Callers typically go through `AuthConnectionCoordinator.mountDrive(...)` rather
     * than invoking this directly.
     */
    suspend fun addDrive(drive: LabeledDrive) {
        updateRegistry { current ->
            if (current.any { it.drive.alias == drive.drive.alias }) current
            else current + drive
        }
    }

    /**
     * Deregister the drive identified by [driveId]. No-op if the drive wasn't in the
     * list, or if no registry file exists yet.
     */
    suspend fun removeDrive(driveId: Uuid) {
        updateRegistry { current ->
            current.filter { it.drive.alias != driveId }
        }
    }

    /**
     * Start observing cross-device registry changes. Callers are expected to have
     * already mounted the drives in [initialBaseline] at connect time — [start] uses
     * that set as the diff baseline WITHOUT re-emitting [onMount] for any of them.
     * Callbacks fire only for SUBSEQUENT changes.
     *
     * Defaults to `loadDrives()` for the cold-boot case (returning user, registry
     * already in local DB). On fresh login the caller should pass the result of
     * [bootstrap] explicitly, since the local DB may not contain the registry file
     * yet (it was fetched directly from the server).
     *
     * Safe to call repeatedly — the observer is cancelled and restarted.
     */
    suspend fun start(
        onMount: suspend (LabeledDrive) -> Unit,
        onUnmount: suspend (Uuid) -> Unit,
        initialBaseline: Set<Uuid>? = null,
    ) {
        stop()
        val baseline = initialBaseline
            ?: loadDrives().mapTo(HashSet()) { it.drive.alias }
        stateMutex.withLock {
            currentDriveAliases = baseline
        }
        observerJob = scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DriveEvent.BatchReceived) return@collect
                if (event.driveId != SystemDriveConstants.chatDrive.alias) return@collect
                // Match on the well-known uniqueId — stronger than matching on fileType
                // alone. There is exactly one registry file per identity.
                val touchesRegistry = event.batchData.any {
                    it.fileMetadata.appData.uniqueId == REGISTRY_UNIQUE_ID
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

    /**
     * Read-modify-write loop against the singleton registry file. [mutate] receives
     * the current list (possibly empty if no file exists yet) and returns the new
     * list. If [mutate] returns an identical list, the write is skipped.
     *
     * Retries on `ExistingFileWithUniqueId` (a race on the very first create — another
     * device beat us to it) and `VersionTagMismatch` (another device updated between
     * our read and our write). Other errors propagate immediately.
     */
    private suspend fun updateRegistry(mutate: (List<LabeledDrive>) -> List<LabeledDrive>) {
        val chatDriveId = SystemDriveConstants.chatDrive.alias
        repeat(MAX_CONFLICT_RETRIES) {
            val existing = getFileHeaderByUid(chatDriveId, REGISTRY_UNIQUE_ID)
            val currentList = existing?.let { parseRegistryContent(it) } ?: emptyList()
            val newList = mutate(currentList)
            if (newList == currentList) {
                Logger.d(tag = TAG) { "updateRegistry: no-op (list unchanged)" }
                return
            }
            try {
                if (existing == null) uploadNewRegistryFile(newList)
                else updateRegistryFile(existing, newList)
                Logger.i(tag = TAG) {
                    "updateRegistry: wrote ${newList.size} drive(s): ${newList.map { it.label }}"
                }
                return
            } catch (e: ClientException) {
                val retryable = e.errorCode == OdinClientErrorCode.ExistingFileWithUniqueId
                    || e.errorCode == OdinClientErrorCode.VersionTagMismatch
                if (!retryable) throw e
                Logger.i(tag = TAG) {
                    "updateRegistry: ${e.errorCode} — re-fetching and retrying"
                }
            }
        }
        throw IllegalStateException(
            "DriveRegistry: could not update registry after $MAX_CONFLICT_RETRIES retries"
        )
    }

    private suspend fun uploadNewRegistryFile(drives: List<LabeledDrive>) {
        val keyHeader = KeyHeader.newRandom16()
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = REGISTRY_UNIQUE_ID,
                fileType = RegistryDriveFileType,
                content = OdinSystemSerializer.serialize(drives),
            ),
        )
        val request = UploadFileRequest(
            driveId = SystemDriveConstants.chatDrive.alias,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
        )
        uploadFile(request)
    }

    private suspend fun updateRegistryFile(existing: HomebaseFile, drives: List<LabeledDrive>) {
        // Preserve the existing aesKey — rotating it would break encrypted payloads
        // (we have none, but this matches the convention from ConversationService.updateAdminFile).
        // Fresh IV per revision prevents IV reuse with the same key.
        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existing.keyHeader.aesKey,
        )
        val metadata = UploadFileMetadata(
            allowDistribution = existing.serverMetadata.allowDistribution,
            isEncrypted = existing.serverFileIsEncrypted,
            versionTag = existing.fileMetadata.versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = REGISTRY_UNIQUE_ID,
                fileType = RegistryDriveFileType,
                content = OdinSystemSerializer.serialize(drives),
            ),
        )
        val instructions = FileUpdateInstructionSet(
            transferIv = ByteArrayUtil.getRndByteArray(16),
            locale = UpdateLocale.Local,
            recipients = emptyList(),
            manifest = UpdateManifest.build(
                payloads = null,
                toDeletePayloads = null,
                thumbnails = null,
                generatePayloadIv = false,
            ),
        )
        val request = UpdateFileByUniqueIdRequest(
            driveId = SystemDriveConstants.chatDrive.alias,
            uniqueId = REGISTRY_UNIQUE_ID,
            keyHeader = keyHeader,
            instructions = instructions,
            metadata = metadata.encryptContent(keyHeader),
        )
        updateFileByUniqueId(request)
    }

    private fun parseRegistryContent(file: HomebaseFile): List<LabeledDrive> {
        val content = file.fileMetadata.appData.content ?: return emptyList()
        return runCatching { OdinSystemSerializer.deserialize<List<LabeledDrive>>(content) }
            .getOrElse {
                Logger.w(tag = TAG, throwable = it) {
                    "parseRegistryContent: corrupt content in registry file — returning empty list"
                }
                emptyList()
            }
    }

    companion object {
        private const val TAG = "DriveRegistry"
        private const val MAX_CONFLICT_RETRIES = 5
    }
}

/**
 * File type of the [DriveRegistry] singleton on the Chat drive. Combined with
 * [REGISTRY_UNIQUE_ID] there is exactly one such file per identity.
 */
const val RegistryDriveFileType: Int = 4242

/**
 * Well-known `uniqueId` of the singleton registry file on the Chat drive. Stable
 * across builds — two devices writing to the registry resolve to the same file and
 * conflict cleanly via versionTag. (This UUID previously keyed the per-device KV
 * blob; reused here for recognizability.)
 */
val REGISTRY_UNIQUE_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000d71700")
