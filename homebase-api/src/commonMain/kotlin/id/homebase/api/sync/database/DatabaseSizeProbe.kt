package id.homebase.api.sync.database

interface DatabaseSizeProbe {
    fun sizeBytes(): Long
}

/**
 * The on-disk size of the database — the `.db` plus its WAL/SHM/journal sidecars — summed
 * over [DatabaseDriverFactory.databaseFiles]. Single implementation for every platform:
 * the only per-platform pieces are [DatabaseDriverFactory.dbFilePath] (the path) and
 * [fileSizeBytes] (reading a file's size). Returns 0 where there's no on-disk DB (wasmJs's
 * in-memory sql.js, where `dbFilePath()` is blank).
 */
class DefaultDatabaseSizeProbe(
    private val factory: DatabaseDriverFactory,
) : DatabaseSizeProbe {
    override fun sizeBytes(): Long = factory.databaseFiles().sumOf { fileSizeBytes(it) }
}
