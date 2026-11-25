package id.homebase.api.client.cache

import coil3.disk.DiskCache

private val JOURNAL_FILES = setOf("journal", "journal.tmp", "journal.bkp")

/**
 * [DiskCache.clear] only removes entries the journal tracks; files written without a committed
 * entry (a killed write, a rebuilt journal) stay on disk forever. The files present before the
 * clear, other than the journal, are deleted after it. Taking the listing first means an entry
 * committed concurrently with the clear is never swept. Rethrows the first delete failure after
 * attempting every file.
 */
internal fun DiskCache.clearIncludingOrphans() {
    val before = if (fileSystem.exists(directory)) {
        fileSystem.list(directory).filter { it.name !in JOURNAL_FILES }
    } else {
        emptyList()
    }
    clear()
    var failure: Throwable? = null
    for (child in before) {
        try {
            fileSystem.deleteRecursively(child, mustExist = false)
        } catch (e: Exception) {
            if (failure == null) failure = e
        }
    }
    failure?.let { throw it }
}
