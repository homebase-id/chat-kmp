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

    suspend fun selectDue(state: Long, now: Long, maxVideoBytes: Long?, limit: Long, newestFirst: Boolean): List<DeviceBackupAsset> =
        databaseManager.readValue("deviceBackup.selectDue") {
            val videos = maxVideoBytes ?: -1L
            if (newestFirst) {
                assets.selectDueNewestFirst(state, now, videos, limit).executeAsList()
            } else {
                assets.selectDueOldestFirst(state, now, videos, limit).executeAsList()
            }
        }

    suspend fun anyPhotoDue(states: Collection<Long>, now: Long): Boolean =
        databaseManager.readValue("deviceBackup.anyPhotoDue") { assets.anyPhotoDue(states, now).executeAsOne() }

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

    /** Rows whose lastError is in [keep] stay failed. */
    suspend fun requeueFailed(failedState: Long, newState: Long, hashedState: Long, keep: Collection<String>) {
        databaseManager.withWriteValue { assets.requeueFailed(newState, hashedState, failedState, keep) }
    }

    /** Returns how many rows went back. */
    suspend fun requeueFailedByErrorPrefix(failedState: Long, newState: Long, hashedState: Long, prefix: String): Long =
        databaseManager.withWriteValue { assets.requeueFailedByErrorPrefix(newState, hashedState, failedState, prefix).value }

    suspend fun recheckDone(doneState: Long, newState: Long, hashedState: Long) {
        databaseManager.withWriteValue { assets.recheckDone(newState, hashedState, doneState) }
    }

    suspend fun prepareStarts(uniqueId: String): Long =
        databaseManager.readValue("deviceBackup.prepareStarts") { assets.prepareStarts(uniqueId).executeAsOneOrNull() ?: 0 }

    suspend fun countPrepareStart(uniqueId: String) {
        databaseManager.withWriteValue { assets.countPrepareStart(uniqueId) }
    }

    suspend fun clearAllPrepareStarts() {
        databaseManager.withWriteValue { assets.clearAllPrepareStarts() }
    }

    suspend fun clearPrepareStart(uniqueId: String) {
        databaseManager.withWriteValue { assets.clearPrepareStart(uniqueId) }
    }

    suspend fun deleteAll() {
        databaseManager.withWriteTransaction {
            assets.deleteAll()
            assets.clearAllPrepareStarts()
            checkpoints.deleteAll()
        }
    }

    suspend fun checkpoints(): Map<String, String> =
        databaseManager.readValue("deviceBackup.checkpoints") {
            checkpoints.selectAll().executeAsList().associate { it.checkpointKey to it.checkpointValue }
        }

    suspend fun saveCheckpoints(values: Map<String, String>) {
        databaseManager.withWriteTransaction { values.forEach { (key, value) -> checkpoints.upsert(key, value) } }
    }
}
