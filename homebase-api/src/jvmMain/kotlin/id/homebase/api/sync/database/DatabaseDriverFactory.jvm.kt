package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.file.JvmFileSystemUtil
import java.io.File

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        val dbFile = resolveDbFile()
        val dbFileName = dbFile.absolutePath

        val jdbcUrl = if (passphrase.isNullOrEmpty()) {
            "jdbc:sqlite:$dbFileName"
        } else {
            SqlCipherKeyMode.jdbcUrl(dbFile, passphrase)
        }

        // Apply the shared SQLITE_TUNING_PRAGMAS as JDBC connection properties — the
        // Willena/xerial driver applies these to every connection it opens (reads and
        // writes use different connections; see DatabaseManager). These are the same
        // key/value pairs SQLiteConfig.toProperties() would emit, just sourced from the
        // one cross-platform definition so desktop/Android/iOS can't drift.
        return JdbcSqliteDriver(jdbcUrl, tuningProperties())
    }

    actual fun dbFilePath(): String = resolveDbFile().absolutePath

    companion object {
        /**
         * The on-disk location of the SQLite database for the JVM/Desktop target.
         * Single source of truth shared by [createDriver] and [dbFilePath].
         * Mirrors the role of `Context.getDatabasePath(DB_FILE_NAME)` on Android.
         */
        fun resolveDbFile(): File {
            val userHome = JvmFileSystemUtil.getAppDataDirectory()
            val dbDir = File(userHome, "database")
            if (!dbDir.exists()) {
                dbDir.mkdirs()
            }
            return File(dbDir, DB_FILE_NAME)
        }
    }
}
