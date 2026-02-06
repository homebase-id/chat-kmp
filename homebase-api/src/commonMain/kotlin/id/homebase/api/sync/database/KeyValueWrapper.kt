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

    fun <T : Any> selectByKey(
        key: Uuid,
        mapper: (
            key: Uuid,
            data: ByteArray,
        ) -> T,
    ): T? = delegate.selectByKey(key, mapper).executeAsOneOrNull()

    fun selectByKey(
        key: Uuid,
    ): KeyValue? {
        try {
            return delegate.selectByKey(key).executeAsOneOrNull()
        } catch (e: Exception) {
            println { "executeReadQuery failed: ${e.message}\n" }
            throw e  // Rethrow if you want the caller to handle, or return a fallback QueryResult
        }
    }

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
}