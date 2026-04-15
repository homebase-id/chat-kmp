package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.uuid.Uuid

class ConnectionCacheWrapper(
    driver: SqlDriver,
    connectionCacheAdapter: ConnectionCache.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = ConnectionCacheQueries(driver, connectionCacheAdapter)

    fun selectByIdentity(identityId: Uuid): List<ConnectionCache> =
        delegate.selectByIdentity(identityId).executeAsList()

    fun selectByIdentityAndStatus(identityId: Uuid, status: String): List<ConnectionCache> =
        delegate.selectByIdentityAndStatus(identityId, status).executeAsList()

    suspend fun upsert(
        identityId: Uuid,
        odinId: String,
        status: String,
        lastRefresh: Long,
    ) {
        databaseManager.withWrite {
            delegate.upsert(identityId, odinId, status, lastRefresh)
        }
    }

    suspend fun replaceStatus(
        identityId: Uuid,
        status: String,
        odinIds: Collection<String>,
        lastRefresh: Long,
    ) {
        databaseManager.withWriteTransaction {
            delegate.deleteByIdentityAndStatus(identityId, status)
            odinIds.forEach { odinId ->
                delegate.upsert(identityId, odinId, status, lastRefresh)
            }
        }
    }

    suspend fun deleteByIdentityAndOdinId(identityId: Uuid, odinId: String) {
        databaseManager.withWrite {
            delegate.deleteByIdentityAndOdinId(identityId, odinId)
        }
    }

    suspend fun deleteAllByIdentity(identityId: Uuid) {
        databaseManager.withWrite {
            delegate.deleteAllByIdentity(identityId)
        }
    }
}
