package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalAppdataContentOutboxRequest
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
 * Per-identity, cross-device moments user-state, stored as a single file on the
 * **moments drive** (one file per identity, never distributed). The carrier
 * holds two independent, private lanes:
 *
 *  - **`appData.content`** — the recipient most-recently-used list for the
 *    composer ([stableKeys]). Read-modify-written directly via
 *    `DriveUploadProvider` ([bump]/[forget]); best-effort, throws when offline.
 *
 *  - **`localAppData.content`** — the feed "last viewed" watermark
 *    ([lastViewedMs]) that drives the unseen-moments nav badge. Written through
 *    the **optimistic + outbox** path ([markViewed]) so it is offline-safe and
 *    retried, exactly mirroring chat's per-conversation `lastReadTime`.
 *
 * **Storage model.** Mirrors `id.homebase.core.sync.DriveRegistry`:
 *  - `driveId = momentsLabeledDrive.drive.alias`
 *  - `fileType = MomentsUserStateFileType` (7051)
 *  - `uniqueId = MomentsUserStateUniqueId` (well-known UUID, exactly one file
 *    per identity)
 *
 * **Never distributed.** Writes set `allowDistribution = false` and
 * `recipients = emptyList()`. No peer ever sees it.
 *
 * **Cross-device propagation.** [start] subscribes to
 * [BackendEvent.DataEvent.BatchReceived] for the moments drive. When a batch
 * touches this file, both lanes reload from the local index, so a change on
 * Device A re-orders the picker / advances the badge watermark on Device B
 * after the next moments-drive sync cycle.
 *
 * **Reads are offline-safe.** Cold load reads the local SQLDelight index. If
 * the file isn't there yet (fresh install), the MRU is empty and the watermark
 * is null (everything received reads as unseen until the first [markViewed]).
 */
class MomentsUserStateStore(
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    // Function-type dependencies for the write path — same shape as DriveRegistry
    // so tests can wire fakes without standing up the HTTP stack.
    private val getFileHeaderByUid: suspend (driveId: Uuid, uniqueId: Uuid) -> HomebaseFile?,
    private val uploadFile: suspend (UploadFileRequest) -> Unit,
    private val updateFileByUniqueId: suspend (UpdateFileByUniqueIdRequest) -> Unit,
    // Optimistic + outbox path for the localAppData watermark (chat-style).
    // `stampLocalAppData` does the optimistic local write and returns the
    // outbox request (or null if the carrier file isn't present locally);
    // `enqueueOutbox` durably queues that request for server push.
    private val stampLocalAppData: suspend (uniqueId: Uuid, content: String) -> UpdateLocalAppdataContentOutboxRequest?,
    private val enqueueOutbox: suspend (UpdateLocalAppdataContentOutboxRequest) -> Boolean,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    companion object {
        const val MAX_ENTRIES = 20
        private const val MAX_CONFLICT_RETRIES = 5
        private const val TAG = "MomentsUserStateStore"
    }

    private val drive: Uuid = momentsLabeledDrive.drive.alias

    private val _stableKeys = MutableStateFlow<List<String>>(emptyList())
    val stableKeys: StateFlow<List<String>> = _stableKeys.asStateFlow()

    /**
     * Last-viewed watermark (ms since epoch, compared against each moment's
     * `createdMs`). Null until the user has viewed the feed at least once —
     * the badge treats null as "nothing seen yet".
     */
    private val _lastViewedMs = MutableStateFlow<Long?>(null)
    val lastViewedMs: StateFlow<Long?> = _lastViewedMs.asStateFlow()

    /** Serialises read-modify-write loops so two concurrent writes don't race. */
    private val writeMutex = Mutex()

    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            reloadFromLocal()
            eventBus.events.collect { event ->
                when (event) {
                    // Logout: drop the previous identity's state.
                    is BackendEvent.SessionEnded -> reset()
                    is BackendEvent.DataEvent.BatchReceived -> {
                        if (event.driveId != drive) return@collect
                        val touches = event.batchData.any {
                            it.fileMetadata.appData.uniqueId == MomentsProtocol.MomentsUserStateUniqueId
                        }
                        if (touches) reloadFromLocal()
                    }
                    else -> {}
                }
            }
        }
    }

    /** Logout: clear the in-memory state for the previous identity. */
    fun reset() {
        _stableKeys.value = emptyList()
        _lastViewedMs.value = null
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

    /**
     * Advance the feed "last viewed" watermark to [newestReceivedCreatedMs]
     * (the `createdMs` of the newest moment received from another identity that
     * the user has now seen). Monotonic — never regresses, and no-ops when
     * nothing newer has arrived since the last mark.
     *
     * Writes through the optimistic + outbox path so the value lands locally
     * immediately (badge clears at once) and syncs to the server / other
     * devices when the outbox drains. If the carrier singleton does not exist
     * yet (user has never used the composer), it is created so the *next* visit
     * can stamp + sync; the local advance below keeps the badge correct on this
     * device in the meantime.
     */
    suspend fun markViewed(newestReceivedCreatedMs: Long) {
        _lastViewedMs.value?.let { if (it >= newestReceivedCreatedMs) return }
        writeMutex.withLock {
            // Re-check under the lock — another mark/reload may have advanced it.
            _lastViewedMs.value?.let { if (it >= newestReceivedCreatedMs) return }

            val content = OdinSystemSerializer.serialize(
                MomentsFeedViewState(lastViewedMs = newestReceivedCreatedMs)
            )
            val request = stampLocalAppData(MomentsProtocol.MomentsUserStateUniqueId, content)
            if (request != null) {
                enqueueOutbox(request)
                Logger.d(tag = TAG) {
                    "markViewed: stamped + enqueued watermark=$newestReceivedCreatedMs"
                }
            } else {
                // No carrier file locally yet — create it (best-effort) so the
                // next markViewed can stamp the synced watermark.
                runCatching { ensureSingletonExists() }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Logger.d(tag = TAG) {
                        "markViewed: ensureSingletonExists failed (offline?): ${e.message}"
                    }
                }
            }
            _lastViewedMs.value = newestReceivedCreatedMs
        }
    }

    private suspend fun reloadFromLocal() {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        val file = databaseManager.driveMainIndex.selectHomebaseFileByUnique(
            identityId,
            drive,
            MomentsProtocol.MomentsUserStateUniqueId,
        )
        val keys = file?.let { parseKeys(it) } ?: emptyList()
        val watermark = file?.let { parseLastViewed(it) }
        Logger.d(tag = TAG) {
            "reloadFromLocal: ${keys.size} key(s), lastViewedMs=$watermark — fileExists=${file != null}"
        }
        _stableKeys.value = keys
        // Only adopt a synced watermark that is newer than what we already hold,
        // so a stale peer copy can't regress a local advance not yet pushed.
        if (watermark != null && (_lastViewedMs.value == null || watermark > _lastViewedMs.value!!)) {
            _lastViewedMs.value = watermark
        }
    }

    /**
     * Read-modify-write loop against the singleton file's `appData.content`
     * (the MRU lane). Mirrors `DriveRegistry.updateRegistry`: retries on
     * `ExistingFileWithUniqueId` (race on first create) and `VersionTagMismatch`
     * (concurrent edit from another device). Other errors propagate.
     */
    private suspend fun updateMru(mutate: (List<String>) -> List<String>) {
        repeat(MAX_CONFLICT_RETRIES) {
            val existing = getFileHeaderByUid(drive, MomentsProtocol.MomentsUserStateUniqueId)
            val current = existing?.let { parseKeys(it) } ?: emptyList()
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
            "MomentsUserStateStore: could not update MRU after $MAX_CONFLICT_RETRIES retries"
        )
    }

    /** Create the singleton with the current MRU keys if it doesn't exist yet. */
    private suspend fun ensureSingletonExists() {
        val existing = getFileHeaderByUid(drive, MomentsProtocol.MomentsUserStateUniqueId)
        if (existing == null) uploadNewFile(_stableKeys.value)
    }

    private suspend fun uploadNewFile(keys: List<String>) {
        val keyHeader = KeyHeader.newRandom16()
        val metadata = UploadFileMetadata(
            allowDistribution = false,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = MomentsProtocol.MomentsUserStateUniqueId,
                fileType = MomentsProtocol.MomentsUserStateFileType,
                content = OdinSystemSerializer.serialize(
                    MomentsUserState(
                        version = MomentsProtocol.MomentsUserStateVersionNumberOne,
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
                uniqueId = MomentsProtocol.MomentsUserStateUniqueId,
                fileType = MomentsProtocol.MomentsUserStateFileType,
                content = OdinSystemSerializer.serialize(
                    MomentsUserState(
                        version = MomentsProtocol.MomentsUserStateVersionNumberOne,
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
            uniqueId = MomentsProtocol.MomentsUserStateUniqueId,
            keyHeader = keyHeader,
            instructions = instructions,
            metadata = metadata.encryptContent(keyHeader),
        )
        updateFileByUniqueId(request)
    }

    /** The MRU keys, from the file's `appData.content`. */
    private fun parseKeys(file: HomebaseFile): List<String> {
        val content = file.fileMetadata.appData.content ?: return emptyList()
        return try {
            OdinSystemSerializer.deserialize<MomentsUserState>(content).keys
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) {
                "parseKeys: corrupt user-state file — returning empty list"
            }
            emptyList()
        }
    }

    /** The feed watermark, from the file's `localAppData.content`. */
    private fun parseLastViewed(file: HomebaseFile): Long? {
        val content = file.fileMetadata.localAppData?.content ?: return null
        return try {
            OdinSystemSerializer.deserialize<MomentsFeedViewState>(content).lastViewedMs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) {
                "parseLastViewed: corrupt watermark — treating as unset"
            }
            null
        }
    }
}

/**
 * On-disk shape of the moments recipient-MRU lane (`appData.content`). Wrapped
 * in a versioned envelope so the schema can grow without breaking older app
 * versions — unknown future fields deserialise into nothing here, and a bumped
 * `version` lets new clients add behavior conditionally.
 */
@Serializable
internal data class MomentsUserState(
    val version: Int = MomentsProtocol.MomentsUserStateVersionNumberOne,
    val keys: List<String> = emptyList(),
)

/**
 * On-disk shape of the feed "last viewed" watermark lane
 * (`localAppData.content`). Kept separate from [MomentsUserState] because the
 * two lanes are written by different mechanisms (MRU = direct upload; watermark
 * = optimistic + outbox) and must not clobber each other.
 */
@Serializable
internal data class MomentsFeedViewState(
    val version: Int = MomentsProtocol.MomentsUserStateVersionNumberOne,
    val lastViewedMs: Long? = null,
)
