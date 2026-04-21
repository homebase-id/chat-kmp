package id.homebase.api.sync.database

import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHomeDirectory

class NativeDatabaseSizeProbe : DatabaseSizeProbe {
    override fun sizeBytes(): Long {
        val dir = "${NSHomeDirectory()}/databases"
        val fileManager = NSFileManager.defaultManager
        var total = 0L
        for (suffix in DB_SUFFIXES) {
            val path = "$dir/odin-2.db$suffix"
            if (!fileManager.fileExistsAtPath(path)) continue
            val attrs = fileManager.attributesOfItemAtPath(path, null) ?: continue
            val size = (attrs[NSFileSize] as? Number)?.toLong() ?: 0L
            total += size
        }
        return total
    }

    private companion object {
        val DB_SUFFIXES = arrayOf("", "-wal", "-shm", "-journal")
    }
}
