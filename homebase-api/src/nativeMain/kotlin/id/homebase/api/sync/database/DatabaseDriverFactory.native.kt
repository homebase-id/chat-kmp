@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.SynchronousFlag
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
        val driver = NativeSqliteDriver(
            schema = OdinDatabase.Schema,
            name = DB_FILE_NAME,
            // Open a pool of reader connections (default is 1 = writer only) so reads
            // dispatched off DatabaseManager's single write dispatcher run concurrently
            // with the writer under WAL (configured below). This is the iOS half of the
            // "reads don't queue behind writes" change — it's the same driver's internal
            // pool, not a second managed SqlDriver. Kept in step with
            // DatabaseManager.READ_PARALLELISM.
            maxReaderConnections = 4,
            onConfiguration = { config ->
                // Configure via sqliter's typed DatabaseConfiguration fields — the idiomatic
                // path for NativeSqliteDriver/SQLiter, not raw PRAGMA strings. This mirrors the
                // shared SQLITE_TUNING_PRAGMAS values (desktop/Android use that map directly):
                //   - journalMode=WAL  (already sqliter's default; set explicitly so it can't
                //     drift) so reads don't block the writer.
                //   - busyTimeout=5s   so contention waits instead of throwing SQLITE_BUSY.
                //   - synchronousFlag=NORMAL  faster, WAL-safe durability.
                // temp_store has no sqliter config field; it's the least-important tuning pragma
                // so we omit it on iOS rather than reach for rawExecSql (which on Android we saw
                // is finicky for result-returning pragmas).
                //
                // NOTE: native targets don't compile on Windows — verify on macOS/CI against the
                // `DB pragmas: journal_mode=wal …` audit line.
                config.copy(
                    journalMode = JournalMode.WAL,
                    extendedConfig = config.extendedConfig.copy(
                        busyTimeout = 5_000,
                        synchronousFlag = SynchronousFlag.NORMAL,
                    ),
                    encryptionConfig = DatabaseConfiguration.Encryption(
                        key = passphrase ?: "", rekey = ""
                    )
                )
            })

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
