package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        return NativeSqliteDriver(
            schema = OdinDatabase.Schema, name = DB_FILE_NAME, onConfiguration = { config ->
                config.copy(
                    encryptionConfig = DatabaseConfiguration.Encryption(
                        key = passphrase ?: "", rekey = ""
                    )
                )
            })
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun deleteOnDiskFiles() {
        // NativeSqliteDriver resolves `name` to "${NSHomeDirectory()}/databases/<name>"
        // under the hood; mirror that path here so deletion matches the live file.
        val fileManager = NSFileManager.defaultManager
        val dbDir = "${NSHomeDirectory()}/databases"
        fileManager.removeItemAtPath("$dbDir/$DB_FILE_NAME", null)
        fileManager.removeItemAtPath("$dbDir/$DB_FILE_NAME-journal", null)
        fileManager.removeItemAtPath("$dbDir/$DB_FILE_NAME-wal", null)
        fileManager.removeItemAtPath("$dbDir/$DB_FILE_NAME-shm", null)
    }
}
