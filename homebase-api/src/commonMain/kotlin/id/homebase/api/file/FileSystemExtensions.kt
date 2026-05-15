package id.homebase.api.file

import okio.FileSystem
import okio.Path

/**
 * Recursively sum the byte size of every regular file under [dir].
 *
 * Returns 0 when [dir] does not exist. Intended to be called on directories —
 * okio's [FileSystem.list] throws when given a regular file, so callers that
 * may hold either should branch on [okio.FileMetadata.isDirectory] first.
 *
 * Single implementation shared by [CacheAudit] and the Storage Settings screen.
 */
fun FileSystem.directorySizeBytes(dir: Path): Long {
    if (!exists(dir)) return 0L
    var total = 0L
    for (child in list(dir)) {
        val meta = metadata(child)
        total += if (meta.isDirectory) directorySizeBytes(child) else (meta.size ?: 0L)
    }
    return total
}
