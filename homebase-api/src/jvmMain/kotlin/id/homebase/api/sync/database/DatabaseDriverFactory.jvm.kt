package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.file.JvmFileSystemUtil
import java.io.File
import java.util.Properties

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        val userHome = JvmFileSystemUtil.getAppDataDirectory()
        val dbDir = File(userHome, "database")
        if (!dbDir.exists()) {
            dbDir.mkdirs()
        }
        val dbFile = File(dbDir, "odin-2.db")
        val dbFileName = dbFile.absolutePath

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
