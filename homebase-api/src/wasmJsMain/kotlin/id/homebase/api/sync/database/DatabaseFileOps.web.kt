package id.homebase.api.sync.database

// sql.js is in-memory only — there is no on-disk SQLite file to delete on
// wasmJs. The recovery path in `DatabaseManager.initializeWithRecovery`
// becomes a no-op; restarting the worker effectively wipes the database.
internal actual fun deleteSqliteFileIfExists(path: String) {
    // intentionally empty
}
