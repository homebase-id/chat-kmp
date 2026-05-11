package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.file.JvmFileSystemUtil
import java.io.File
import java.util.Properties

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        val dbFile = resolveDbFile()
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

    companion object {
        /**
         * The on-disk location of the SQLite database for the JVM/Desktop target.
         * Exposed as a companion-level helper so the corruption-recovery path in
         * `desktopApp/Main` can locate the same file (plus its WAL/SHM/journal
         * siblings) without re-stating the path and silently drifting from the
         * factory's view. Mirrors the role of
         * `Context.getDatabasePath("odin-2.db")` on Android.
         */
        fun resolveDbFile(): File {
            val userHome = JvmFileSystemUtil.getAppDataDirectory()
            val dbDir = File(userHome, "database")
            if (!dbDir.exists()) {
                dbDir.mkdirs()
            }
            return File(dbDir, "odin-2.db")
        }
    }
}
