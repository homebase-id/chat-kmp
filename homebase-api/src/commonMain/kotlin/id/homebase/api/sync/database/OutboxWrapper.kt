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

    suspend fun nextScheduled(): UnixTimeUtc? {
        val n = databaseManager.readValue("outbox.nextScheduled") {
            delegate.nextScheduled().executeAsOneOrNull()
        }
        return if (n == null) null else UnixTimeUtc(n)
    }

    suspend fun selectCheckedOut(checkOutStamp: Long): Outbox? =
        databaseManager.readValue("outbox.selectCheckedOut") {
            delegate.selectCheckedOut(checkOutStamp).executeAsOneOrNull()
        }

    /** The single pending row for (driveId, uniqueId), or null if nothing is
     *  queued. Outbox is UNIQUE(driveId, uniqueId), so there is at most one. */
    suspend fun selectByDriveAndUnique(driveId: Uuid, uniqueId: Uuid): Outbox? =
        databaseManager.readValue("outbox.selectByDriveAndUnique") {
            delegate.selectByDriveAndUnique(driveId, uniqueId).executeAsOneOrNull()
        }

    /** True when any outbox row still exists for [uniqueId] (across drives) —
     *  i.e. a dependency a queued message is waiting on hasn't drained yet. */
    suspend fun existsByUniqueId(uniqueId: Uuid): Boolean =
        databaseManager.readValue("outbox.existsByUniqueId") {
            delegate.existsByUniqueId(uniqueId).executeAsOne() > 0L
        }

    suspend fun count(): Long =
        databaseManager.readValue("outbox.count") { delegate.count().executeAsOne() }

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
        // Named args are load-bearing: the generated query's parameter order is
        // (nextRunTime, checkOutStamp) — SQL appearance order, both Long. The
        // original positional call had them SWAPPED, so the WHERE never matched:
        // failed rows stayed checked out (zombies) until the next app start's
        // clearCheckedOut, and in-session retry/backoff never actually ran.
        return databaseManager.withWriteValue {
            delegate.checkInFailed(nextRunTime = nextRunTime, checkOutStamp = checkOutStamp).value
        }
    }

    /** Reset a queued row's next-attempt time (ms epoch). Returns the number of
     *  rows changed: 0 when the row is missing or currently checked out — an
     *  in-flight row's nextRunTime is owned by [checkInFailed]. */
    suspend fun setNextRunTime(
        driveId: Uuid,
        uniqueId: Uuid,
        nextRunTime: Long,
    ): Long {
        return databaseManager.withWriteValue {
            delegate.setNextRunTime(nextRunTime = nextRunTime, driveId = driveId, uniqueId = uniqueId).value
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

    /** Guarded cancel: deletes the row only when no worker holds it
     *  (checkOutStamp IS NULL). Returns rows deleted — 0 means the row is
     *  either gone or in flight; see [OutboxSync.cancelPending]. */
    suspend fun deleteByIfNotCheckedOut(
        driveId: Uuid,
        uniqueId: Uuid,
    ): Long {
        return databaseManager.withWriteValue {
            delegate.deleteByIfNotCheckedOut(driveId, uniqueId).value
        }
    }

    suspend fun deleteAll(): Long {
        return databaseManager.withWriteValue { delegate.deleteAll().value }
    }
}
