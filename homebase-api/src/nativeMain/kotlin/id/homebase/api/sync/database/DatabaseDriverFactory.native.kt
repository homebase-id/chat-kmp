package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        return NativeSqliteDriver(
            schema = OdinDatabase.Schema, name = "odin-2.db", onConfiguration = { config ->
                config.copy(
                    encryptionConfig = DatabaseConfiguration.Encryption(
                        key = passphrase ?: "", rekey = ""
                    )
                )
            })
    }
}
