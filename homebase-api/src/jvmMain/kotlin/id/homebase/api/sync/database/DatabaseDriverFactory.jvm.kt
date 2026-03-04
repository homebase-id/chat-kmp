package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        return if (passphrase.isNullOrEmpty()) {
            JdbcSqliteDriver("jdbc:sqlite:odin.db")
        } else {
            val properties = Properties().apply { setProperty("key", passphrase) }
            JdbcSqliteDriver("jdbc:sqlite:odin.db", properties)
        }
    }
}
