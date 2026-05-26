package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.uuid.Uuid

class KeyValueWrapper(
    driver: SqlDriver,
    keyValueAdapter: KeyValue.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = KeyValueQueries(driver, keyValueAdapter)

    suspend fun <T : Any> selectByKey(
        key: Uuid,
        mapper: (
            key: Uuid,
            data: ByteArray,
        ) -> T,
    ): T? = databaseManager.readValue("keyValue.selectByKey(mapper)") {
        delegate.selectByKey(key, mapper).executeAsOneOrNull()
    }

    suspend fun selectByKey(key: Uuid): KeyValue? =
        databaseManager.readValue("keyValue.selectByKey") {
            delegate.selectByKey(key).executeAsOneOrNull()
        }

    /**
     * Synchronous read for cold-start bootstrap **only** — used by classes that
     * seed `StateFlow`s in their constructor (`VaultPreferences`,
     * `MomentsPreferences`, `DiceRollPreferences`) and by `CursorStorage.init`.
     * These can't `suspend` because the constructor is invoked from non-suspend
     * DI / sync code paths, and `runBlocking` isn't available in commonMain
     * (wasmJs has no real blocking).
     *
     * Bypasses both lanes — runs on the caller's thread. **Do not use** for any
     * non-bootstrap path; prefer the suspend [selectByKey] variants which route
     * through `readValue` and show up in `SlowDbRead`. Audited callers:
     *  - `VaultPreferences.readBoolean` (icon-visible, biometrics — 2 reads/cold-start)
     *  - `MomentsPreferences.readBoolean / readViewMode` (3 reads/cold-start)
     *  - `DiceRollPreferences.readInt / readMode` (3 reads/cold-start)
     *  - `CursorStorage.loadCursor` (called from `DriveSync.init`, `MainIndexMeta`)
     *
     * If you find yourself wanting this for anything else, refactor the caller
     * to async-init instead.
     */
    fun <T : Any> selectByKeyBootstrapSync(
        key: Uuid,
        mapper: (
            key: Uuid,
            data: ByteArray,
        ) -> T,
    ): T? = delegate.selectByKey(key, mapper).executeAsOneOrNull()

    /** @see selectByKeyBootstrapSync — bootstrap-only synchronous variant. */
    fun selectByKeyBootstrapSync(key: Uuid): KeyValue? =
        delegate.selectByKey(key).executeAsOneOrNull()

    suspend fun upsertValue(
        key: Uuid,
        data: ByteArray,
    ): Boolean {
        return databaseManager.withWriteValue { delegate.upsertValue(key, data).value > 0 }
    }

    suspend fun deleteByKey(
        key: Uuid,
    ): Boolean {
        return databaseManager.withWriteValue { delegate.deleteByKey(key).value > 0 }
    }

    suspend fun deleteAll(): Long {
        return databaseManager.withWriteValue { delegate.deleteAll().value }
    }
}
