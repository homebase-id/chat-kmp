package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.query.QueryBatchCursor
import kotlin.uuid.Uuid

/**
 * Handles cursor storage operations using KeyValue database API.
 * Provides functionality to load and save QueryBatchCursor for efficient data synchronization
 * between app open / close.
 */
class CursorStorage(
    private val databaseManager: DatabaseManager,
    private val driveId: Uuid
) {
    // TODO: We should XOR the driveId with some constant GUID to create a KV key that
    // won't conflict - in case someone else uses the DriveId to store data. The KV key
    // should be calculated on init() here

    /**
     * Load QueryBatchCursor for the predefined cursor Guid.
     * Returns null if no cursor is found in the database.
     *
     * @param expectFresh set by callers who know this is the first load after logout.
     *   If a cursor is found anyway, log an error (logout should have wiped it) and
     *   discard it so the next sync starts from scratch instead of silently resuming
     *   from a stale position. The underlying row is not deleted here; the next
     *   saveCursor() will overwrite it, or DriveSyncManager.clearStorage() already
     *   wipes the whole KeyValue table.
     */
    fun loadCursor(expectFresh: Boolean = false): QueryBatchCursor? {
        // Bootstrap-only sync read — called from DriveSync.init / MainIndexMeta
        // outside a coroutine context. See KeyValueWrapper.selectByKeyBootstrapSync
        // for why we can't be suspend here (commonMain has no runBlocking on
        // wasmJs). The follow-up to async-init these bootstrap paths is tracked
        // separately.
        val cursor = databaseManager.keyValue.selectByKeyBootstrapSync(driveId)?.let {
            QueryBatchCursor.fromJson(it.data_.decodeToString())
        }
        if (expectFresh && cursor != null) {
            Logger.e(
                "Stale cursor survived logout for drive=$driveId cursor=${cursor.toJson().take(200)} — discarding"
            )
            return null
        }
        return cursor
    }
    
    /**
     * Save QueryBatchCursor for the predefined cursor Guid
     */
    suspend fun saveCursor(cursor: QueryBatchCursor) {
        databaseManager.keyValue.upsertValue(
            key = driveId,
            data = cursor.toJson().encodeToByteArray()
        )
    }

    /**
     * Save QueryBatchCursor for the predefined cursor Guid
     */
    fun saveCursor(db : OdinDatabase, cursor: QueryBatchCursor) {
        db.keyValueQueries.upsertValue(
            key = driveId,
            data_ = cursor.toJson().encodeToByteArray()
        )
    }

    /**
     * Delete cursor position for the predefined cursor Guid
     */
    suspend fun deleteCursor() {
        databaseManager.keyValue.deleteByKey(driveId)
    }
}