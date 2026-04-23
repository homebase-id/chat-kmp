package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import id.homebase.api.common.time.UnixTimeUtc
import kotlin.Long
import kotlin.uuid.Uuid
import kotlinx.atomicfu.atomic

class OutboxWrapper(
    driver: SqlDriver,
    outboxAdapter: Outbox.Adapter,
    private val databaseManager: DatabaseManager
) {
    private val delegate = OutboxQueries(driver, outboxAdapter)

    private val lastId = atomic(0L)

    // TEMP HACK - will make a different design
    fun getUniqueId(): Long {
        while (true) {
            val now = UnixTimeUtc.now().milliseconds
            val current = lastId.value
            val candidate = if (now > current) now else current + 1
            if (lastId.compareAndSet(current, candidate)) {
                return candidate
            }
        }
    }

    suspend fun checkout(): Outbox?
    {
        return databaseManager.withWriteValue { delegate.checkout(getUniqueId(), UnixTimeUtc.now().milliseconds).executeAsOneOrNull() }
    }

    fun nextScheduled(): UnixTimeUtc?
    {
        val n = delegate.nextScheduled().executeAsOneOrNull()

        return if (n == null) null else return UnixTimeUtc(n)
    }

    fun selectCheckedOut(
        checkOutStamp: Long,
    ): Outbox? = delegate.selectCheckedOut(checkOutStamp).executeAsOneOrNull()

    fun count(): Long = delegate.count().executeAsOne()

    suspend fun insert(
        driveId: Uuid,
        uniqueId: Uuid, //rename to uniqueId; be sure to update the c (and select by uniqueid)
        dependencyUniqueId: Uuid?,  // if i type 3 messages, ensure each one is depending on the previous message; same for reactions and conversations; use the uniqueId
        priority: Long,
        uploadType: Long,
        json: ByteArray,
        filePaths: ByteArray?,
    ): Long {
        return databaseManager.withWriteValue {
            delegate.insert(
                driveId,
                uniqueId,
                dependencyUniqueId,
                priority,
                0,
                0,
                0,
                null,
                uploadType,
                json,
                filePaths
            ).value
        }
    }

    suspend fun checkInFailed(
        checkOutStamp: Long,
        nextRunTime: Long,
    ): Long {
        return databaseManager.withWriteValue {
            delegate.checkInFailed(checkOutStamp, nextRunTime).value
        }
    }


    suspend fun clearCheckedOut(): Long
    {
        return databaseManager.withWriteValue {
            delegate.clearCheckedOut().value
        }
    }

    suspend fun deleteByRowId(
        rowId: Long,
    ): Long
    {
        return databaseManager.withWriteValue {
            delegate.deleteByRowId(rowId).value
        }
    }

    suspend fun deleteBy(
        driveId: Uuid,
        uniqueId: Uuid,
    ): Long {
        return databaseManager.withWriteValue {
            delegate.deleteBy(driveId, uniqueId).value
        }
    }

    suspend fun deleteAll(): Long {
        return databaseManager.withWriteValue { delegate.deleteAll().value }
    }
}
