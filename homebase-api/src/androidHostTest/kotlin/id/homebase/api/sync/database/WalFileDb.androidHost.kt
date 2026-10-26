package id.homebase.api.sync.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files

// Host JVM with android.jar stubs: plain JDBC; the Android WAL pool is covered on-device.
internal actual fun openWalFileDb(): WalFileDb {
    val dir = Files.createTempDirectory("dbm-lifecycle").toFile()
    val dbFile = File(dir, "t.db")
    return WalFileDb(
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}"),
        walBytes = { File(dbFile.path + "-wal").length() },
        cleanup = { dir.deleteRecursively() },
    )
}
