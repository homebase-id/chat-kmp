package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import id.homebase.api.file.JvmFileSystemUtil
import java.io.File

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        val dbFile = resolveDbFile()
        val dbFileName = dbFile.absolutePath

        val jdbcUrl = if (passphrase.isNullOrEmpty()) {
            "jdbc:sqlite:$dbFileName"
        } else {
            sqlCipherJdbcUrl(dbFile, passphrase)
        }

        return openSqliteDriver(jdbcUrl)
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

// Shared pragmas as JDBC connection properties, applied to every pooled connection.
internal fun openSqliteDriver(jdbcUrl: String): SqlDriver =
    PooledJdbcSqliteDriver(jdbcUrl, tuningProperties())
