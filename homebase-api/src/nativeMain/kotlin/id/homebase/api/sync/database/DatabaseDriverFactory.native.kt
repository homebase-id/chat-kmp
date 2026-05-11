package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
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

    // NativeSqliteDriver resolves `name` to "${NSHomeDirectory()}/databases/<name>"
    // under the hood; report the same path here so the shared recovery deletes
    // the file the driver was looking at.
    actual fun dbFilePath(): String = "${NSHomeDirectory()}/databases/$DB_FILE_NAME"
}
