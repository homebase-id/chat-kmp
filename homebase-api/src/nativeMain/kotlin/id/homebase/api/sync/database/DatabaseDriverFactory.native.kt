@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseConnection
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.getVersion
import co.touchlab.sqliter.longForQuery
import co.touchlab.sqliter.stringForQuery
import id.homebase.api.storage.SecureRead
import id.homebase.api.storage.SecureStorage
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Security.errSecItemNotFound

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        val driver = openKeyedNativeSqliteDriver(DB_FILE_NAME, passphrase, KeychainRawKeyStore)

        // Mark the SQLite files as ineligible for iCloud / Finder backup, like Android's
        // allowBackup=false. The file is encrypted by SQLCipher and its key is a
        // *ThisDeviceOnly* keychain item, so a restored backup couldn't be opened anyway;
        // excluding the files keeps them off the backup altogether.
        //
        // The directory exclusion handles WAL/SHM sidecars that SQLite creates
        // lazily after this call. The per-file calls are belt-and-suspenders
        // for the primary .db so the attribute is set even if a future iOS
        // build refuses to honour directory-level exclusion.
        excludeFromBackup(iosDatabasesDir())
        for (path in databaseFiles()) {
            if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
                excludeFromBackup(path)
            }
        }

        return driver
    }

    // sqliter (NativeSqliteDriver's engine) resolves a bare `name` to
    // "<NSApplicationSupportDirectory>/databases/<name>" — see
    // co.touchlab.sqliter.DatabaseFileContext.iosDirPath. Report that exact path so the
    // shared recovery (deleteOnDiskFiles) and backup-exclusion target the file the driver
    // actually opened. (A previous "${NSHomeDirectory()}/databases" assumption was wrong —
    // it pointed at a non-existent dir, so recovery and backup-exclusion silently no-op'd.)
    actual fun dbFilePath(): String = databasePath(DB_FILE_NAME)
}

/**
 * Opens [name] in SQLCipher's raw-key mode, like Android and desktop: a random 32-byte key cached
 * in [store]. iOS never shipped an encrypted file, so there is no passphrase-derived key to carry
 * over; an empty [passphrase] opens the file unencrypted, as on the other platforms.
 *
 * Which key is used, made or refused is [decideRawKey]'s call. A plaintext file from before iOS
 * linked SQLCipher is encrypted in place first ([encrypt]). If that fails, or the key can't be
 * read or stored, it is opened unencrypted as it is and the next launch tries again: the recovery
 * in DatabaseManager would otherwise wipe a file the key can't open.
 */
internal fun openKeyedNativeSqliteDriver(
    name: String,
    passphrase: String?,
    store: RawKeyStore,
    encrypt: (name: String, key: String) -> Unit = ::encryptPlaintextDatabase,
): SqlDriver {
    if (passphrase.isNullOrEmpty()) return openNativeSqliteDriver(name, key = null)
    requireSqlCipher()
    val file = databaseFileState(databasePath(name))
    val read = store.read()
    val hex = when (val decision = decideRawKey(read, file)) {
        is RawKeyDecision.Use -> decision.hex
        RawKeyDecision.Create -> createRawKey(store)
            ?: if (file == DatabaseFileState.Plaintext) return openNativeSqliteDriver(name, key = null)
            else throw DatabaseUnavailableException("The new database key couldn't be stored")
        RawKeyDecision.OpenUnencrypted -> {
            logger.w { "Database key unreadable ($read); opening the plaintext database unencrypted until the next launch" }
            return openNativeSqliteDriver(name, key = null)
        }
        RawKeyDecision.FailUnavailable ->
            throw DatabaseUnavailableException("Database file $file, key $read: can't open yet")
        RawKeyDecision.FailKeyLost ->
            throw IllegalStateException("The database is encrypted but its key is gone")
    }
    val key = SqlCipherRawKey.format(hex)
    if (file == DatabaseFileState.Plaintext) {
        try {
            encrypt(name, key)
        } catch (e: Exception) {
            logger.e(e) { "Encrypting the plaintext database failed; opening it unencrypted until the next launch" }
            runCatching { deleteDatabaseFiles(exportPath(name)) }
                .onFailure { logger.e(it) { "Couldn't delete the partial encrypted copy" } }
            return openNativeSqliteDriver(name, key = null)
        }
    }
    return openNativeSqliteDriver(name, key)
}

/** Null when the key didn't read back: the Keychain write failed, and a file keyed with it would be lost. */
private fun createRawKey(store: RawKeyStore): String? {
    val hex = SqlCipherRawKey.newHex()
    store.put(hex)
    val readBack = store.read()
    if (readBack == SecureRead.Found(hex)) return hex
    logger.e { "Storing the new database key failed ($readBack)" }
    return null
}

internal object KeychainRawKeyStore : RawKeyStore by SecureStorageRawKeyStore {
    override fun read(): SecureRead {
        val result = SecureStorage.readKeychain(SecureStorageRawKeyStore.KEY_DB_RAW_KEY)
        return when {
            result.value != null -> SecureRead.Found(result.value)
            result.status == errSecItemNotFound -> SecureRead.Missing
            else -> SecureRead.Unavailable(IllegalStateException("OSStatus ${result.status}"))
        }
    }
}

/** [key] is what SQLCipher is handed: a raw `x'<hex>'` key, or null for no encryption. */
internal fun openNativeSqliteDriver(name: String, key: String?): SqlDriver = NativeSqliteDriver(
    schema = OdinDatabase.Schema,
    name = name,
    maxReaderConnections = SQLITE_READER_CONNECTIONS,
    onConfiguration = { config ->
        config.copy(
            journalMode = JournalMode.valueOf(SQLITE_JOURNAL_MODE),
            lifecycleConfig = config.lifecycleConfig.copy(
                onCreateConnection = { connection ->
                    // Keyed here rather than through sqliter's encryptionConfig, which keys only
                    // after onCreateConnection, and these pragmas already read the file.
                    key?.let { connection.applyKey(it) }
                    config.lifecycleConfig.onCreateConnection(connection)
                    SQLITE_CONNECTION_PRAGMA_STATEMENTS.forEach(connection::rawExecSql)
                    connection.rawExecSql("PRAGMA journal_size_limit=$SQLITE_WRITER_JOURNAL_SIZE_LIMIT")
                },
            ),
        )
    },
)

/**
 * Rewrites the plaintext database [name] as a copy encrypted with [key] and swaps it in. Until
 * the rename the plaintext file is untouched apart from a checkpoint, and a copy left by an
 * interrupted run is discarded, so a crash at any step leaves a database that opens.
 */
internal fun encryptPlaintextDatabase(name: String, key: String) {
    exportEncryptedCopy(name, key)
    val path = databasePath(name)
    // Checkpointed into the main file by the export, and a stale -wal would be replayed onto the encrypted file.
    deleteSqliteFileIfExists("$path-wal")
    deleteSqliteFileIfExists("$path-shm")
    FileSystem.SYSTEM.atomicMove(exportPath(name).toPath(), path.toPath())
    logger.i { "Encrypted the plaintext database in place" }
}

internal fun exportEncryptedCopy(name: String, key: String) {
    val export = exportPath(name)
    deleteDatabaseFiles(export)
    val (version, tables) = withConnection(name, key = null, JournalMode.WAL) { connection ->
        val busy = connection.longForQuery("PRAGMA wal_checkpoint(TRUNCATE)")
        check(busy == 0L) { "The plaintext database's WAL couldn't be checkpointed" }
        val version = connection.getVersion()
        val tables = connection.longForQuery("SELECT count(*) FROM sqlite_master")
        connection.rawExecSql("ATTACH DATABASE ${sqlString(export)} AS encrypted KEY ${sqlString(key)}")
        try {
            connection.rawExecSql("SELECT sqlcipher_export('encrypted')")
            // sqlcipher_export copies the schema and rows but not the header's user_version.
            connection.rawExecSql("PRAGMA encrypted.user_version = $version")
        } finally {
            connection.rawExecSql("DETACH DATABASE encrypted")
        }
        version to tables
    }
    withConnection(name + ENCRYPTING_COPY_SUFFIX, key, JournalMode.DELETE) { connection ->
        check(connection.getVersion() == version && connection.longForQuery("SELECT count(*) FROM sqlite_master") == tables) {
            "The encrypted copy doesn't match the plaintext database"
        }
    }
}

internal fun exportPath(name: String): String = databasePath(name) + ENCRYPTING_COPY_SUFFIX

internal fun databasePath(name: String): String = "${iosDatabasesDir()}/$name"

/** Unreadable is a file whose header can't be read, e.g. under data protection before the first unlock. */
internal fun databaseFileState(path: String): DatabaseFileState {
    if ((FileSystem.SYSTEM.metadataOrNull(path.toPath())?.size ?: 0L) == 0L) return DatabaseFileState.Absent
    return try {
        if (isPlaintextDatabase(path)) DatabaseFileState.Plaintext else DatabaseFileState.Encrypted
    } catch (e: IOException) {
        logger.w(e) { "Couldn't read the database header" }
        DatabaseFileState.Unreadable
    }
}

/** True for a file in SQLite's plaintext format; an encrypted file starts with its random salt. */
internal fun isPlaintextDatabase(path: String): Boolean {
    val file = path.toPath()
    if ((FileSystem.SYSTEM.metadataOrNull(file)?.size ?: 0L) < PLAINTEXT_HEADER.size) return false
    return FileSystem.SYSTEM.read(file) { readByteArray(PLAINTEXT_HEADER.size.toLong()) }.contentEquals(PLAINTEXT_HEADER)
}

private val PLAINTEXT_HEADER = "SQLite format 3\u0000".encodeToByteArray()

private val logger = Logger.withTag("DatabaseDriverFactory")

private fun sqlString(value: String) = "'${value.replace("'", "''")}'"

private fun DatabaseConnection.applyKey(key: String) = rawExecSql("PRAGMA key = \"$key\"")

internal fun <T> withConnection(name: String, key: String?, journalMode: JournalMode, block: (DatabaseConnection) -> T): T {
    val manager = createDatabaseManager(
        DatabaseConfiguration(
            name = name,
            version = NO_VERSION_CHECK,
            create = {},
            journalMode = journalMode,
            lifecycleConfig = DatabaseConfiguration.Lifecycle(onCreateConnection = { c -> key?.let { c.applyKey(it) } }),
        ),
    )
    val connection = manager.createMultiThreadedConnection()
    try {
        return block(connection)
    } finally {
        connection.close()
    }
}

private val sqlCipherVersion: String by lazy {
    val connection = createDatabaseManager(
        DatabaseConfiguration(name = null, inMemory = true, version = NO_VERSION_CHECK, create = {}),
    ).createMultiThreadedConnection()
    try {
        connection.stringForQuery("PRAGMA cipher_version")
    } finally {
        connection.close()
    }
}

/**
 * The system libsqlite3 ignores `PRAGMA key` and writes plaintext without an error. An Error,
 * not an Exception, so DatabaseManager's recovery doesn't wipe the database over a build fault.
 */
internal fun requireSqlCipher() {
    if (sqlCipherVersion.isBlank()) throw AssertionError("SQLCipher isn't linked: PRAGMA cipher_version is empty")
}

/**
 * The directory sqliter stores databases in on Apple targets:
 * `<NSApplicationSupportDirectory>/databases`. Mirrors
 * `co.touchlab.sqliter.DatabaseFileContext.iosDirPath("databases")` so our delete /
 * backup-exclusion / size logic targets exactly where the driver puts the file.
 */
internal fun iosDatabasesDir(): String =
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .first() as String) + "/databases"

/**
 * Set `NSURLIsExcludedFromBackupKey = true` on [path] so iCloud / Finder /
 * encrypted backups skip it. Stored as the extended attribute
 * `com.apple.metadata:com_apple_backup_excludeItem` on the inode — survives
 * the lifetime of the file but is lost if the file is deleted and recreated
 * (relevant for WAL/SHM, which is why we also flag the parent directory).
 *
 * Errors are logged at warn — refusing to start the database because backup
 * exclusion failed would be a worse failure mode than carrying a backup
 * surface we'd rather not have. A persistent failure here would show up in
 * `homebase.log` as a recurring `excludeFromBackup` warning, which is what we
 * want for diagnosability.
 */
private fun excludeFromBackup(path: String) {
    runCatching {
        val url = NSURL.fileURLWithPath(path)
        memScoped {
            // alloc() zero-initialises, so errorVar.value is already null.
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            val ok = url.setResourceValue(
                value = NSNumber(bool = true),
                forKey = NSURLIsExcludedFromBackupKey,
                error = errorVar.ptr,
            )
            if (!ok) {
                Logger.withTag("DatabaseDriverFactory").w {
                    "excludeFromBackup($path): setResourceValue returned false: " +
                        (errorVar.value?.localizedDescription ?: "no NSError")
                }
            }
        }
    }.onFailure { e ->
        Logger.withTag("DatabaseDriverFactory").w(throwable = e) {
            "excludeFromBackup($path): unexpected throw"
        }
    }
}
