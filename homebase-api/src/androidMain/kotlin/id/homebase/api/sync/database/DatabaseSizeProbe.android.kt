package id.homebase.api.sync.database

import android.content.Context
import java.io.File

class AndroidDatabaseSizeProbe(private val context: Context) : DatabaseSizeProbe {
    override fun sizeBytes(): Long {
        val dbFile = context.getDatabasePath("odin-2.db")
        val dir = dbFile.parentFile ?: return if (dbFile.exists()) dbFile.length() else 0L
        var total = 0L
        for (suffix in DB_SUFFIXES) {
            val f = File(dir, "odin-2.db$suffix")
            if (f.exists()) total += f.length()
        }
        return total
    }

    private companion object {
        val DB_SUFFIXES = arrayOf("", "-wal", "-shm", "-journal")
    }
}
