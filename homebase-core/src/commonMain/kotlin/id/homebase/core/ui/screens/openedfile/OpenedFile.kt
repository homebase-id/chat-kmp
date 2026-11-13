package id.homebase.core.ui.screens.openedfile

import id.homebase.api.file.createStagingPathIn
import id.homebase.api.file.systemFileSystem
import id.homebase.core.util.safeFileName
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import okio.FileSystem
import okio.Path.Companion.toPath

/** A document another app handed to Homebase, already copied to [path] (its sanitized [name] is the file's own name). */
data class OpenedFileRequest(val path: String, val name: String, val mimeType: String?, val sizeBytes: Long)

// iOSApp.swift stages into the same "open-with" folder under Caches.
fun openedFileStagingRoot(cacheDir: String): String = cacheDir.trimEnd('/') + "/open-with"

// Long enough for an entry point to finish copying a file it has reserved but not yet offered.
private const val STAGING_GRACE_MS = 60 * 60 * 1000L

/** Only another app's document: a URI one of our own providers serves would read our sandbox with our identity. */
fun isForeignContentUri(scheme: String?, authority: String?, isOwnAuthority: (String) -> Boolean): Boolean {
    // "user@authority" reaches the same provider for another user, so the user part is not part of the check.
    val provider = authority?.substringAfterLast('@').orEmpty()
    return scheme == "content" && provider.isNotEmpty() && !isOwnAuthority(provider)
}

/** An empty folder of its own under [root], so the upload is named by the file and two files with one name never share a path. */
fun reserveStagedPath(root: String, rawName: String?, fileSystem: FileSystem = systemFileSystem): String {
    val dir = createStagingPathIn(root, "open-", "", fileSystem).toPath()
    fileSystem.createDirectories(dir)
    return (dir / safeFileName(rawName)).toString()
}

fun stageFileCopy(source: String, root: String, rawName: String?, fileSystem: FileSystem = systemFileSystem): String {
    val target = reserveStagedPath(root, rawName, fileSystem)
    fileSystem.copy(source.toPath(), target.toPath())
    return target
}

fun discardStaged(path: String, fileSystem: FileSystem = systemFileSystem) {
    val file = path.toPath()
    runCatching { fileSystem.delete(file, mustExist = false) }
    file.parent?.let { dir -> runCatching { fileSystem.delete(dir, mustExist = false) } }
}

/** Deletes staged copies no screen can still show: a copy in [keep], or one reserved within the grace period, stays. */
fun sweepStaged(root: String, keep: Collection<String>, nowMs: Long, fileSystem: FileSystem = systemFileSystem) {
    val entries = fileSystem.listOrNull(root.toPath()) ?: return
    val kept = keep.mapNotNull { it.toPath().parent?.name }.toSet()
    for (entry in entries) {
        if (entry.name in kept) continue
        val modified = fileSystem.metadataOrNull(entry)?.lastModifiedAtMillis ?: continue
        if (nowMs - modified >= STAGING_GRACE_MS) runCatching { fileSystem.deleteRecursively(entry) }
    }
}

/** [waitingForSignIn]: "Sign in to save" sent the user to Login, and the file comes back once they are signed in. */
data class PendingOpenedFile(val request: OpenedFileRequest, val waitingForSignIn: Boolean = false)

/** Where an entry point leaves a document for the app to show once it is signed in. */
object OpenedFileInbox {
    private val _pending = MutableStateFlow<PendingOpenedFile?>(null)
    val pending: StateFlow<PendingOpenedFile?> = _pending.asStateFlow()

    private val _failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val failures: SharedFlow<Unit> = _failures.asSharedFlow()

    fun reportFailure() {
        _failures.tryEmit(Unit)
    }

    fun offer(request: OpenedFileRequest, discard: (String) -> Unit = ::discardStaged) {
        val old = _pending.getAndUpdate { PendingOpenedFile(request) }
        // A file waiting for sign-in has no screen left to delete its copy.
        if (old != null && old.waitingForSignIn && old.request.path != request.path) discard(old.request.path)
    }

    fun waitForSignIn(request: OpenedFileRequest) {
        _pending.value = PendingOpenedFile(request, waitingForSignIn = true)
    }

    fun clear(path: String) {
        _pending.update { if (it?.request?.path == path) null else it }
    }
}
