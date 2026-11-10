package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/** The photo backup ledger and checkpoints; the state machine over them lives in homebase-photobase. */
class DeviceBackupWrapper(
    driver: SqlDriver,
    private val databaseManager: DatabaseManager,
) {
    private val assets = DeviceBackupAssetQueries(driver)
    private val checkpoints = BackupCheckpointQueries(driver)

    /** Atomic: the block's writes all land or none do. */
    suspend fun inTransaction(block: DeviceBackupAssetQueries.() -> Unit) {
        databaseManager.withWriteTransaction { assets.block() }
    }

    suspend fun selectAll(): List<DeviceBackupAsset> =
        databaseManager.readValue("deviceBackup.selectAll") { assets.selectAll().executeAsList() }

    suspend fun selectById(id: String): DeviceBackupAsset? =
        databaseManager.readValue("deviceBackup.selectById") { assets.selectById(id).executeAsOneOrNull() }

    suspend fun selectAllIdsExcept(state: Long): List<String> =
        databaseManager.readValue("deviceBackup.selectAllIds") { assets.selectAllIdsExcept(state).executeAsList() }

    suspend fun selectDue(state: Long, now: Long, includeVideos: Boolean, limit: Long, newestFirst: Boolean): List<DeviceBackupAsset> =
        databaseManager.readValue("deviceBackup.selectDue") {
            val videos = if (includeVideos) 1L else 0L
            if (newestFirst) {
                assets.selectDueNewestFirst(state, now, videos, limit).executeAsList()
            } else {
                assets.selectDueOldestFirst(state, now, videos, limit).executeAsList()
            }
        }

    suspend fun selectByUnique(uniqueId: String): List<DeviceBackupAsset> =
        databaseManager.readValue("deviceBackup.selectByUnique") { assets.selectByUnique(uniqueId).executeAsList() }

    suspend fun selectInStates(states: Collection<Long>): List<DeviceBackupAsset> =
        databaseManager.readValue("deviceBackup.selectInStates") { assets.selectInStates(states).executeAsList() }

    suspend fun countByState(): Map<Long, Long> =
        databaseManager.readValue("deviceBackup.countByState") {
            assets.countByState().executeAsList().associate { it.state to it.COUNT }
        }

    suspend fun countInStates(states: List<Long>): Long =
        databaseManager.readValue("deviceBackup.countInStates") { assets.countInStates(states).executeAsOne() }

    suspend fun countSending(states: List<Long>, retrying: String): Long =
        databaseManager.readValue("deviceBackup.countSending") { assets.countSending(states, retrying).executeAsOne() }

    suspend fun bytesByState(): Map<Long, Long> =
        databaseManager.readValue("deviceBackup.bytesByState") {
            assets.bytesByState().executeAsList().associate { it.state to it.bytes }
        }

    suspend fun unknownSizeByState(): Map<Long, Long> =
        databaseManager.readValue("deviceBackup.unknownSizeByState") {
            assets.unknownSizeByState().executeAsList().associate { it.state to it.COUNT }
        }

    suspend fun selectFailures(state: Long, limit: Long): List<SelectFailures> =
        databaseManager.readValue("deviceBackup.selectFailures") { assets.selectFailures(state, limit).executeAsList() }

    suspend fun markHashed(id: String, contentHash: String, uniqueId: String, state: Long, size: Long) {
        databaseManager.withWriteValue { assets.markHashed(contentHash, uniqueId, state, size, id) }
    }

    suspend fun clearBackoffForReason(reason: String) {
        databaseManager.withWriteValue { assets.clearBackoffForReason(reason) }
    }

    suspend fun setState(id: String, state: Long, lastError: String?, attempts: Long, nextAttemptAt: Long) {
        databaseManager.withWriteValue { assets.setState(state, lastError, attempts, nextAttemptAt, id) }
    }

    suspend fun setStateOfUnique(
        uniqueId: String,
        fromStates: Collection<Long>,
        state: Long,
        lastError: String?,
        nextAttemptAt: Long,
        addAttempts: Long,
    ) {
        databaseManager.withWriteValue { assets.setStateOfUnique(state, lastError, nextAttemptAt, addAttempts, uniqueId, fromStates) }
    }

    suspend fun setUploadTask(uniqueId: String, state: Long, uploadTaskId: Long?) {
        databaseManager.withWriteValue { assets.setUploadTask(state, uploadTaskId, uniqueId) }
    }

    suspend fun requeueFailed(failedState: Long, newState: Long, hashedState: Long) {
        databaseManager.withWriteValue { assets.requeueFailed(newState, hashedState, failedState) }
    }

    suspend fun recheckDone(doneState: Long, newState: Long, hashedState: Long) {
        databaseManager.withWriteValue { assets.recheckDone(newState, hashedState, doneState) }
    }

    suspend fun checkpoints(): Map<String, String> =
        databaseManager.readValue("deviceBackup.checkpoints") {
            checkpoints.selectAll().executeAsList().associate { it.checkpointKey to it.checkpointValue }
        }

    suspend fun saveCheckpoints(values: Map<String, String>) {
        databaseManager.withWriteTransaction { values.forEach { (key, value) -> checkpoints.upsert(key, value) } }
    }
}
