package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
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
import id.homebase.core.config.momentsLabeledDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Cross-device most-recently-used list of recipient stable keys for the
 * moments composer.
 *
 * **Storage model.** A single file on the **moments drive**, mirroring the
 * pattern in `id.homebase.core.sync.DriveRegistry`:
 *  - `driveId = momentsLabeledDrive.drive.alias`
 *  - `fileType = MomentsRecipientMruFileType` (7051)
 *  - `uniqueId = MomentsRecipientMruUniqueId` (well-known UUID, exactly one
 *    file per identity)
 *  - `appData.content = OdinSystemSerializer.serialize(MomentsRecipientMruContent)`
 *
 * **Never distributed.** Writes set `allowDistribution = false` and
 * `recipients = emptyList()`. The MRU is a private device-sync convenience —
 * no peer ever sees it.
 *
 * **Cross-device propagation.** [start] subscribes to
 * [BackendEvent.DriveEvent.BatchReceived] for the moments drive. When a batch
 * touches the MRU file (uniqueId match), the in-memory list reloads from the
 * local index, so a bump on Device A re-orders the picker on Device B after
 * the next moments-drive sync cycle.
 *
 * **Reads are offline-safe.** Cold load reads the local SQLDelight index. If
 * the file isn't there yet (fresh install), the list is empty and the picker
 * just falls back to alphabetical.
 *
 * **Writes throw when offline.** `bump` / `forget` go directly through
 * `DriveUploadProvider`. Callers should treat MRU writes as best-effort
 * (they're convenience UX, not core functionality) — see the swallow in
 * `MomentsRecipientLookupService.recordUsed`.
 */
class MomentsRecipientMruStore(
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    // Function-type dependencies for the write path — same shape as DriveRegistry
    // so tests can wire fakes without standing up the HTTP stack.
    private val getFileHeaderByUid: suspend (driveId: Uuid, uniqueId: Uuid) -> HomebaseFile?,
    private val uploadFile: suspend (UploadFileRequest) -> Unit,
    private val updateFileByUniqueId: suspend (UpdateFileByUniqueIdRequest) -> Unit,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    companion object {
        const val MAX_ENTRIES = 20
        private const val MAX_CONFLICT_RETRIES = 5
        private const val TAG = "MomentsRecipientMruStore"
    }

    private val drive: Uuid = momentsLabeledDrive.drive.alias

    private val _stableKeys = MutableStateFlow<List<String>>(emptyList())
    val stableKeys: StateFlow<List<String>> = _stableKeys.asStateFlow()

    /** Serialises read-modify-write loops so two concurrent bumps don't race. */
    private val writeMutex = Mutex()

    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            reloadFromLocal()
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DriveEvent.BatchReceived) return@collect
                if (event.driveId != drive) return@collect
                val touches = event.batchData.any {
                    it.fileMetadata.appData.uniqueId == MomentsProtocol.MomentsRecipientMruUniqueId
                }
                if (touches) reloadFromLocal()
            }
        }
    }

    /**
     * Move [stableKeys] to the front of the MRU list, preserving the order in
     * which they appear in the parameter — first key ends up at index 0,
     * second at index 1, etc. No-op for keys that would change nothing.
     */
    suspend fun bump(stableKeys: List<String>) {
        if (stableKeys.isEmpty()) return
        writeMutex.withLock {
            updateMru { current ->
                // Remove any of the bumped keys from the existing list (they may
                // already be there in a different position), then prepend in the
                // order requested. Cap at MAX_ENTRIES.
                val rest = current.filterNot { it in stableKeys }
                (stableKeys + rest).take(MAX_ENTRIES)
            }
        }
    }

    /** Drop a stale key (e.g. recipient source removed). */
    suspend fun forget(stableKey: String) {
        writeMutex.withLock {
            updateMru { current ->
                if (stableKey !in current) current
                else current.filterNot { it == stableKey }
            }
        }
    }

    private suspend fun reloadFromLocal() {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        val file = databaseManager.driveMainIndex.selectHomebaseFileByUnique(
            identityId,
            drive,
            MomentsProtocol.MomentsRecipientMruUniqueId,
        )
        val keys = file?.let { parseContent(it) } ?: emptyList()
        Logger.d(tag = TAG) {
            "reloadFromLocal: ${keys.size} key(s) — fileExists=${file != null}"
        }
        _stableKeys.value = keys
    }

    /**
     * Read-modify-write loop against the singleton MRU file. Mirrors
     * `DriveRegistry.updateRegistry`: retries on `ExistingFileWithUniqueId`
     * (race on first create) and `VersionTagMismatch` (concurrent edit from
     * another device). Other errors propagate.
     *
     * Optimistically updates the in-memory `_stableKeys` after a successful
     * write so the picker reorders without waiting for the BatchReceived
     * roundtrip. The observer's reload later confirms (or corrects, if a
     * concurrent write from another device beat us).
     */
    private suspend fun updateMru(mutate: (List<String>) -> List<String>) {
        repeat(MAX_CONFLICT_RETRIES) {
            val existing = getFileHeaderByUid(drive, MomentsProtocol.MomentsRecipientMruUniqueId)
            val current = existing?.let { parseContent(it) } ?: emptyList()
            val next = mutate(current)
            if (next == current) {
                Logger.d(tag = TAG) { "updateMru: no-op (list unchanged)" }
                return
            }
            try {
                if (existing == null) uploadNewFile(next) else updateFile(existing, next)
                _stableKeys.value = next
                Logger.d(tag = TAG) {
                    "updateMru: wrote ${next.size} key(s) — was=${current.size} (created=${existing == null})"
                }
                return
            } catch (e: ClientException) {
                val retryable =
                    e.errorCode == OdinClientErrorCode.ExistingFileWithUniqueId ||
                            e.errorCode == OdinClientErrorCode.VersionTagMismatch
                if (!retryable) throw e
                Logger.i(tag = TAG) {
                    "updateMru: ${e.errorCode} — re-fetching and retrying"
                }
            }
        }
        throw IllegalStateException(
            "MomentsRecipientMruStore: could not update MRU after $MAX_CONFLICT_RETRIES retries"
        )
    }

    private suspend fun uploadNewFile(keys: List<String>) {
        val keyHeader = KeyHeader.newRandom16()
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = MomentsProtocol.MomentsRecipientMruUniqueId,
                fileType = MomentsProtocol.MomentsRecipientMruFileType,
                content = OdinSystemSerializer.serialize(
                    MomentsRecipientMruContent(
                        version = MomentsProtocol.MomentsRecipientMruVersionNumberOne,
                        keys = keys,
                    )
                ),
            ),
        )
        val request = UploadFileRequest(
            driveId = drive,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
        )
        uploadFile(request)
    }

    private suspend fun updateFile(existing: HomebaseFile, keys: List<String>) {
        // Preserve aesKey; rotate IV per revision.
        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existing.keyHeader.aesKey,
        )
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = existing.serverFileIsEncrypted,
            versionTag = existing.fileMetadata.versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = MomentsProtocol.MomentsRecipientMruUniqueId,
                fileType = MomentsProtocol.MomentsRecipientMruFileType,
                content = OdinSystemSerializer.serialize(
                    MomentsRecipientMruContent(
                        version = MomentsProtocol.MomentsRecipientMruVersionNumberOne,
                        keys = keys,
                    )
                ),
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
            driveId = drive,
            uniqueId = MomentsProtocol.MomentsRecipientMruUniqueId,
            keyHeader = keyHeader,
            instructions = instructions,
            metadata = metadata.encryptContent(keyHeader),
        )
        updateFileByUniqueId(request)
    }

    private fun parseContent(file: HomebaseFile): List<String> {
        val content = file.fileMetadata.appData.content ?: return emptyList()
        return try {
            OdinSystemSerializer.deserialize<MomentsRecipientMruContent>(content).keys
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) {
                "parseContent: corrupt MRU file — returning empty list"
            }
            emptyList()
        }
    }
}

/**
 * On-disk shape of the moments recipient-MRU file. Wrapped in a versioned
 * envelope so the schema can grow without breaking older app versions —
 * unknown future fields would deserialise into nothing here, and a bumped
 * `version` lets new clients add behavior conditionally.
 */
@Serializable
internal data class MomentsRecipientMruContent(
    val version: Int = MomentsProtocol.MomentsRecipientMruVersionNumberOne,
    val keys: List<String>,
)
