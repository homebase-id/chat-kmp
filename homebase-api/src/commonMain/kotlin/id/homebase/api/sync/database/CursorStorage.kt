package id.homebase.api.sync.database

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
     * Load QueryBatchCursor for the predefined cursor Guid
     * Returns null if no cursor is found in the database
     */
    fun loadCursor(): QueryBatchCursor? {
        return databaseManager.keyValue.selectByKey(driveId)?.let { QueryBatchCursor.fromJson(it.data_.decodeToString()) }
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