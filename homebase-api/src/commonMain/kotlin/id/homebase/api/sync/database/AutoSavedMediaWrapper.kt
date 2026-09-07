package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

class AutoSavedMediaWrapper(
    driver: SqlDriver,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = AutoSavedMediaQueries(driver)

    suspend fun isSaved(fileId: String, payloadKey: String): Boolean =
        databaseManager.readValue("autoSavedMedia.isSaved") {
            (delegate.isSaved(fileId, payloadKey).executeAsOneOrNull() ?: 0L) > 0L
        }

    suspend fun markSaved(fileId: String, payloadKey: String) {
        databaseManager.withWriteValue { delegate.markSaved(fileId, payloadKey) }
    }

    suspend fun deleteAll() {
        databaseManager.withWriteValue { delegate.deleteAll() }
    }
}
