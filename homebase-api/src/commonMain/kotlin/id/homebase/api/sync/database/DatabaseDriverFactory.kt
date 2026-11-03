package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/**
 * The on-disk database file name used by every platform's [DatabaseDriverFactory].
 *
 * **This name is permanent — do not bump it.** The `-2` is historical: it was once
 * changed (from `odin.db`) to force a fresh database, before it was understood that the
 * `-- Version: N` comment on the DriveMainIndex CREATE statement already drives
 * [DatabaseManager.wipeAndRecreate]. The version bump is the *only* DB-reset mechanism;
 * renaming the file again would orphan every existing install's database and force a full
 * re-sync. To rebuild the schema, bump the version comment — never the filename.
 */
internal const val DB_FILE_NAME = "odin-2.db"

/** iOS's in-place encryption writes this copy beside the database; a killed first launch can leave it. */
internal const val ENCRYPTING_COPY_SUFFIX = "-encrypting"

private val SQLITE_SIDECAR_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

/**
 * The SQLite database file, the sidecar files it can spawn, and the [ENCRYPTING_COPY_SUFFIX] copy
 * with its sidecars — the single suffix list shared by everything that enumerates DB files
 * (recovery delete, size probe).
 */
internal val DB_FILE_SUFFIXES = listOf("", ENCRYPTING_COPY_SUFFIX).flatMap { copy ->
    SQLITE_SIDECAR_SUFFIXES.map { copy + it }
}

/**
 * Every on-disk file backing the database at [basePath] (the `.db` itself plus its
 * [DB_FILE_SUFFIXES] sidecars). The one place this list is built. Returns an empty list
 * when [basePath] is blank (platforms with no on-disk DB, e.g. wasmJs's in-memory sql.js).
 */
internal fun databaseFilePaths(basePath: String): List<String> =
    if (basePath.isEmpty()) emptyList() else DB_FILE_SUFFIXES.map { basePath + it }

/** All on-disk files backing this factory's database — see [databaseFilePaths]. */
internal fun DatabaseDriverFactory.databaseFiles(): List<String> =
    databaseFilePaths(dbFilePath())

/** Reader connections each driver serves beside its one writer; [DatabaseManager]'s read lane admits this many reads at once. */
internal const val SQLITE_READER_CONNECTIONS = 4

/**
 * Readers and the single writer don't block each other; without it a long read and a write
 * collide into `SQLITE_BUSY`. Persisted in the file, so each driver sets it once, its own way.
 */
internal const val SQLITE_JOURNAL_MODE = "WAL"

/**
 * Settings every connection carries, writer and readers alike. Shared so the
 * [DatabaseDriverFactory] actuals can't drift.
 *
 *  - `busy_timeout=5000` — a contended statement waits up to 5 s instead of throwing `SQLITE_BUSY`.
 *  - `synchronous=NORMAL` — WAL-safe durability; at most the last commit is lost on power loss.
 *  - `temp_store=MEMORY` — temp tables and indices in RAM.
 */
internal val SQLITE_CONNECTION_PRAGMAS: Map<String, String> = linkedMapOf(
    "busy_timeout" to "5000",
    "synchronous" to "NORMAL",
    "temp_store" to "MEMORY",
)

/** [SQLITE_CONNECTION_PRAGMAS] as `PRAGMA key=value` statements, for drivers that run raw SQL per connection. */
internal val SQLITE_CONNECTION_PRAGMA_STATEMENTS: List<String> =
    SQLITE_CONNECTION_PRAGMAS.entries.map { (key, value) -> "PRAGMA $key=$value" }

/**
 * `journal_size_limit` for the writer, the only connection that resets the `-wal`. Connections
 * stay open, so nothing deletes the file; this trims it after a write burst to about the
 * auto-checkpoint size (1000 pages of 4 KB).
 */
internal const val SQLITE_WRITER_JOURNAL_SIZE_LIMIT = 4_194_304

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class DatabaseDriverFactory {
    /**
     * Serves one writer plus [SQLITE_READER_CONNECTIONS] concurrent readers in
     * [SQLITE_JOURNAL_MODE], all kept open: reads never wait for an open write transaction, and
     * the page cache and parsed schema survive between statements. Every connection carries
     * [SQLITE_CONNECTION_PRAGMAS], and the writer [SQLITE_WRITER_JOURNAL_SIZE_LIMIT].
     * DatabaseConnectionContractTest holds each platform to this.
     *
     * wasmJs is the exception: sql.js is one in-memory connection on the browser's single
     * thread, with no journal and no pragmas.
     *
     * Encryption at rest: a non-empty [passphrase] means SQLCipher encrypts the file with a raw
     * key cached in SecureStorage, and after the first launch only that raw key reaches
     * SQLCipher. A new file gets a random key. Android and desktop derive an existing file's raw
     * key from [passphrase] (SqlCipherKeyMode); iOS uses [passphrase] only as encryption on/off
     * and encrypts its old plaintext file in place (decideRawKey). A null or empty [passphrase]
     * opens a plaintext database and stores no key. wasmJs has no file to encrypt.
     *
     * Unreadable key or file (iOS before the first unlock): throws [DatabaseUnavailableException]
     * and nothing is written or wiped. Wrong key: when the file exists and no key decrypts it,
     * this call throws and leaves nothing open; [DatabaseManager.initializeWithRecovery] then
     * deletes the files and starts fresh. wasmJs never rejects a key. Blocking: runs on the
     * caller's thread and may do file I/O and, once per install, a slow key derivation or the
     * in-place encryption.
     */
    fun createDriver(passphrase: String? = null): SqlDriver

    /**
     * Absolute path to the on-disk database file on this platform. Used by
     * [deleteOnDiskFiles] to clean up after a failed open in
     * [DatabaseManager.initializeWithRecovery]. Each platform reports the
     * exact path its `createDriver` opens (or would open) so the recovery
     * deletes the file the driver was looking at.
     *
     * Platforms without an on-disk database (e.g. wasmJs's in-memory sql.js)
     * return an empty string — [deleteOnDiskFiles] becomes a no-op.
     */
    fun dbFilePath(): String
}

/**
 * Delete a single SQLite side file at [path] if it exists. Per-platform so
 * commonMain doesn't have to reference `kotlinx.io.files.SystemFileSystem`,
 * which is unavailable on wasmJs. Missing files are treated as a no-op
 * (matches `SystemFileSystem.delete(..., mustExist = false)`).
 *
 * On wasmJs this is a no-op because sql.js is in-memory and has no on-disk
 * file to delete.
 */
internal expect fun deleteSqliteFileIfExists(path: String)

/**
 * Size in bytes of a single file at [path], or 0 if it doesn't exist. Per-platform for
 * the same reason as [deleteSqliteFileIfExists] (no shared filesystem API across wasmJs).
 */
internal expect fun fileSizeBytes(path: String): Long

/**
 * Delete the on-disk database file plus its WAL/SHM/journal siblings. The
 * suffix list lives in commonMain ([DB_FILE_SUFFIXES]) because it isn't
 * platform-specific — only [DatabaseDriverFactory.dbFilePath] is. Missing
 * files are treated as a no-op.
 */
internal fun DatabaseDriverFactory.deleteOnDiskFiles() {
    deleteDatabaseFiles(dbFilePath())
}

/**
 * Delete the database file at [dbFilePath] plus its [DB_FILE_SUFFIXES] siblings.
 * Extracted from [DatabaseDriverFactory.deleteOnDiskFiles] so unit tests can exercise the
 * suffix loop against a temp-directory path without instantiating a real factory (which
 * would target the user's actual data directory).
 */
internal fun deleteDatabaseFiles(dbFilePath: String) {
    databaseFilePaths(dbFilePath).forEach { deleteSqliteFileIfExists(it) }
}
