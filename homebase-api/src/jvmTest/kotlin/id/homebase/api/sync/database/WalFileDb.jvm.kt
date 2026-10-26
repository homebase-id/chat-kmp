package id.homebase.api.sync.database

import java.io.File
import java.nio.file.Files

internal actual fun openWalFileDb(): WalFileDb {
    val dir = Files.createTempDirectory("dbm-lifecycle").toFile()
    val dbFile = File(dir, "t.db")
    return WalFileDb(
        driver = openSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}"),
        walBytes = { File(dbFile.path + "-wal").length() },
        cleanup = { dir.deleteRecursively() },
    )
}
