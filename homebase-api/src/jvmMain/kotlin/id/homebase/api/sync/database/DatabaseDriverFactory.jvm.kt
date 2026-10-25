package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import id.homebase.api.file.JvmFileSystemUtil
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Properties

actual class DatabaseDriverFactory {
    actual fun createDriver(passphrase: String?): SqlDriver {
        val dbFile = resolveDbFile()
        return openKeyedSqliteDriver(dbFile, passphrase, SecureStorageRawKeyStore)
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

internal fun openKeyedSqliteDriver(
    dbFile: File,
    passphrase: String?,
    store: RawKeyStore,
    deriveIn: (() -> Unit) -> Unit = SqlCipherKeyMode::onDaemonThread,
): SqlDriver =
    SqlCipherKeyMode.openDriver(dbFile, passphrase, store, deriveIn) { key ->
        openSqliteDriver(if (key == null) "jdbc:sqlite:${dbFile.absolutePath}" else cipherUrl(dbFile, key))
    }

internal fun openSqliteDriver(jdbcUrl: String): SqlDriver = PooledJdbcSqliteDriver(jdbcUrl, tuningProperties())

// `legacy=4` must match SqlCipherKeyMode's KDF parameters, or a derived key won't be the one
// the file was encrypted with.
internal fun cipherUrl(dbFile: File, key: String): String {
    val encoded = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
    return "jdbc:sqlite:file:${dbFile.absolutePath}?cipher=sqlcipher&legacy=4&key=$encoded"
}

// Applied to every pooled connection.
internal fun tuningProperties(): Properties = Properties().apply {
    setProperty("journal_mode", SQLITE_JOURNAL_MODE)
    setProperty("journal_size_limit", SQLITE_WRITER_JOURNAL_SIZE_LIMIT.toString())
    SQLITE_CONNECTION_PRAGMAS.forEach { (key, value) -> setProperty(key, value) }
}
