package id.homebase.core.sync

import co.touchlab.kermit.Logger
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.config.LabeledDrive
import id.homebase.core.config.feedLabeledDrive
import kotlin.uuid.Uuid

/**
 * Persisted registry of optional drives mounted by the sync engine.
 *
 * Mandatory drives (chat, contacts, profile) are hardcoded in [mandatorySyncDrives] and are
 * never stored here. This registry covers only add-on drives such as Feed, Vault, etc.
 *
 * Storage: a JSON blob in the encrypted [DatabaseManager.keyValue] store keyed by [REGISTRY_KEY].
 *
 * First-startup migration: when no entry exists (fresh install or first upgrade), the registry
 * seeds itself with [feedLabeledDrive] so existing users keep feed sync without any action.
 *
 * See ADDING_ADDON_APPS.md §"Mandatory vs Optional Drives" for the full usage pattern.
 */
class DriveRegistry(private val databaseManager: DatabaseManager) {

    /**
     * Returns all optional drives that should be mounted at startup.
     * Reads synchronously from the local DB — safe to call from non-suspend contexts.
     */
    fun loadDrives(): List<LabeledDrive> {
        val entry = runCatching {
            databaseManager.keyValue.selectByKey(REGISTRY_KEY)
        }.getOrNull()

        if (entry == null) {
            // Key not found → first launch. Return the seed list without persisting;
            // the first addDrive/removeDrive call will write the real state.
            Logger.i(tag = TAG) { "DriveRegistry: no entry found — returning default seed [Feed]" }
            return listOf(feedLabeledDrive)
        }

        val bytes = entry.data_
        if (bytes.isEmpty()) return emptyList()

        return runCatching {
            OdinSystemSerializer.deserialize<List<LabeledDrive>>(bytes.decodeToString())
        }.onFailure { e ->
            Logger.e(e, tag = TAG) { "DriveRegistry: failed to deserialize registry — returning empty" }
        }.getOrDefault(emptyList())
    }

    /** Returns true if [driveId] (alias UUID) is in the optional-drive registry. */
    fun hasDrive(driveId: Uuid): Boolean = loadDrives().any { it.drive.alias == driveId }

    /**
     * Adds [drive] to the registry (idempotent). Persists immediately.
     * Call this when a user activates an add-on.
     */
    suspend fun addDrive(drive: LabeledDrive) {
        val current = loadDrives().toMutableList()
        if (current.none { it.drive.alias == drive.drive.alias }) {
            current += drive
            persist(current)
            Logger.i(tag = TAG) { "DriveRegistry: added drive '${drive.label}' (${drive.drive.alias})" }
        }
    }

    /**
     * Removes the drive identified by [driveId] from the registry. Persists immediately.
     * After this call, the drive will not be mounted on next startup.
     */
    suspend fun removeDrive(driveId: Uuid) {
        val updated = loadDrives().filter { it.drive.alias != driveId }
        persist(updated)
        Logger.i(tag = TAG) { "DriveRegistry: removed drive $driveId" }
    }

    private suspend fun persist(drives: List<LabeledDrive>) {
        val json = OdinSystemSerializer.serialize(drives).encodeToByteArray()
        databaseManager.keyValue.upsertValue(REGISTRY_KEY, json)
    }

    companion object {
        private const val TAG = "DriveRegistry"
        val REGISTRY_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-000000d71700")
    }
}
