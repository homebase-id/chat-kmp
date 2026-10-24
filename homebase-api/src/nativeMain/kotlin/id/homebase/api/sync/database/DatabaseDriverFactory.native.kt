@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        val driver = openNativeSqliteDriver(DB_FILE_NAME, passphrase)

        // Mark the SQLite files as ineligible for iCloud / Finder backup. Same
        // motivation as Android's allowBackup=false (AndroidManifest.xml): the
        // DB is content-encrypted by SQLCipher, but the keychain holding its
        // passphrase rides backup unless the keychain items use
        // *ThisDeviceOnly* — so a restored device could decrypt a restored DB.
        // Marking the files excluded breaks that cross-device chain at the
        // file-system layer.
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
    actual fun dbFilePath(): String = "${iosDatabasesDir()}/$DB_FILE_NAME"
}

/**
 * NOT ENCRYPTED TODAY. The iOS app links the system `libsqlite3`, which ignores `PRAGMA key`,
 * so [passphrase] protects nothing and the database file is plaintext on disk. This is a known
 * bug, deferred until existing installs can be migrated.
 *
 * When iOS links SQLCipher, this function must change with it:
 *
 *  1. **Swap to the shared raw-key code.** Desktop and Android hand SQLCipher a raw key
 *     (`x'<64 hex>'`) chosen by `SqlCipherKeyMode` (javaMain), because a passphrase costs a
 *     PBKDF2 derivation on *every* connection, and the writer plus [SQLITE_READER_CONNECTIONS]
 *     readers each pay it (measured in OPTIMIZE.md). Move
 *     `SqlCipherKeyMode` to commonMain (cryptography-kotlin has PBKDF2 on all targets) and use
 *     it here instead of the passphrase. Existing iOS files are plaintext, so iOS only needs the
 *     fresh-install path: a random raw key cached in SecureStorage.
 *  2. **Drop `rekey = ""`.** With a non-null rekey sqliter runs `PRAGMA rekey = ''` after the
 *     key on every connection. Harmless on the system SQLite; on SQLCipher it at best does
 *     nothing and at worst tries to change the key to empty.
 *  3. **Set the key before the pragmas.** sqliter runs `onCreateConnection` before it sets the
 *     key, and `PRAGMA synchronous` reads the schema, so on an encrypted file every connection
 *     would fail with "file is not a database" and recovery would wipe the database.
 *  4. **Migrate existing installs** from plaintext to encrypted (`sqlcipher_export`) before
 *     opening them with a key.
 *
 * DatabaseConnectionContractTest runs on the iOS simulator in CI and is the net for 3.
 */
internal fun openNativeSqliteDriver(name: String, passphrase: String?): SqlDriver = NativeSqliteDriver(
    schema = OdinDatabase.Schema,
    name = name,
    maxReaderConnections = SQLITE_READER_CONNECTIONS,
    onConfiguration = { config ->
        config.copy(
            journalMode = JournalMode.valueOf(SQLITE_JOURNAL_MODE),
            lifecycleConfig = config.lifecycleConfig.copy(
                onCreateConnection = { connection ->
                    config.lifecycleConfig.onCreateConnection(connection)
                    // Runs before sqliter sets the key: see point 3 above.
                    SQLITE_CONNECTION_PRAGMA_STATEMENTS.forEach(connection::rawExecSql)
                    connection.rawExecSql("PRAGMA journal_size_limit=$SQLITE_WRITER_JOURNAL_SIZE_LIMIT")
                },
            ),
            encryptionConfig = DatabaseConfiguration.Encryption(
                key = passphrase ?: "", rekey = ""
            )
        )
    },
)

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
