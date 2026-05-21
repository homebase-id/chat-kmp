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
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        val driver = NativeSqliteDriver(
            schema = OdinDatabase.Schema, name = DB_FILE_NAME, onConfiguration = { config ->
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
                    busyTimeout = 5_000,
                    extendedConfig = config.extendedConfig.copy(
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
        excludeFromBackup("${NSHomeDirectory()}/databases")
        for (suffix in DB_SUFFIXES) {
            val path = "${NSHomeDirectory()}/databases/$DB_FILE_NAME$suffix"
            if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
                excludeFromBackup(path)
            }
        }

        return driver
    }

    // NativeSqliteDriver resolves `name` to "${NSHomeDirectory()}/databases/<name>"
    // under the hood; report the same path here so the shared recovery deletes
    // the file the driver was looking at.
    actual fun dbFilePath(): String = "${NSHomeDirectory()}/databases/$DB_FILE_NAME"

    private companion object {
        val DB_SUFFIXES = arrayOf("", "-wal", "-shm", "-journal")
    }
}

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
