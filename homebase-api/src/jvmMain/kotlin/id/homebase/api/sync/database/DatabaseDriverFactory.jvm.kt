package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        val dbFileName = "odin.db"
        val jdbcUrl = if (passphrase.isNullOrEmpty()) {
            "jdbc:sqlite:$dbFileName"
        } else {
            val encodedPassword = java.net.URLEncoder.encode(
                passphrase, java.nio.charset.StandardCharsets.UTF_8.name()
            )
            "jdbc:sqlite:file:$dbFileName?cipher=sqlcipher&legacy=4&key=$encodedPassword"
        }

        return JdbcSqliteDriver(jdbcUrl, Properties())
    }
}
