package id.homebase.api.client.cache

import coil3.disk.DiskCache

private val JOURNAL_FILES = setOf("journal", "journal.tmp", "journal.bkp")

/**
 * [DiskCache.clear] only removes entries the journal tracks; files written without a committed
 * entry (a killed write, a rebuilt journal) stay on disk forever. After the clear, everything in
 * the cache directory except the live journal is such a file.
 */
internal fun DiskCache.clearIncludingOrphans() {
    clear()
    if (!fileSystem.exists(directory)) return
    for (child in fileSystem.list(directory)) {
        if (child.name !in JOURNAL_FILES) fileSystem.deleteRecursively(child)
    }
}
