package id.homebase.api.sync.database

import okio.FileSystem
import okio.Path.Companion.toPath

private const val WAL_TEST_DB = "dbm-lifecycle.db"

internal actual fun openWalFileDb(): WalFileDb {
    val path = "${iosDatabasesDir()}/$WAL_TEST_DB"
    deleteDatabaseFiles(path)
    val driver = openNativeSqliteDriver(WAL_TEST_DB, null)
    return WalFileDb(
        driver = driver,
        walBytes = { FileSystem.SYSTEM.metadataOrNull("$path-wal".toPath())?.size ?: 0L },
        cleanup = {
            driver.close()
            deleteDatabaseFiles(path)
        },
    )
}
