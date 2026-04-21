package id.homebase.api.sync.database

import id.homebase.api.file.JvmFileSystemUtil
import java.io.File

class JvmDatabaseSizeProbe : DatabaseSizeProbe {
    override fun sizeBytes(): Long {
        val dbDir = File(JvmFileSystemUtil.getAppDataDirectory(), "database")
        var total = 0L
        for (suffix in DB_SUFFIXES) {
            val f = File(dbDir, "odin-2.db$suffix")
            if (f.exists()) total += f.length()
        }
        return total
    }

    private companion object {
        val DB_SUFFIXES = arrayOf("", "-wal", "-shm", "-journal")
    }
}
