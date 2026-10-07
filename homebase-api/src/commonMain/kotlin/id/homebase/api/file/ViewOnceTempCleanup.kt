package id.homebase.api.file

import co.touchlab.kermit.Logger
import okio.FileSystem

/** Reaps decrypted view-once temps a process death left behind; a viewer deletes its own on close. */
fun sweepViewOnceTemps(
    cacheDirPath: String,
    fileSystem: FileSystem = systemFileSystem,
): Boolean {
    val scratchRoot = AppCacheDirs.scratchRoot(cacheDirPath)
    val deleted = safeDeleteRecursively(scratchRoot, AppCacheDirs.VIEW_ONCE, fileSystem)
    if (deleted) Logger.i(tag = "ViewOnceTemp") { "swept $scratchRoot/${AppCacheDirs.VIEW_ONCE} (cleartext view-once temps)" }
    return deleted
}
