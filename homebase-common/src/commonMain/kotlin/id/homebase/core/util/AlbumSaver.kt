package id.homebase.core.util

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The platform "save this file where the user keeps their photos" implementation, reachable from a
 * plain coroutine — [FileSystemHandler] is only obtainable from a Composable, so background callers
 * (auto-save) cannot use it.
 */
interface AlbumSaver {
    /** [onSuccess] receives a human-readable location name (e.g. "Downloads", "Photos"). */
    fun save(
        file: Path,
        suggestedName: String,
        onSuccess: (String) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    )
}

/** The album is not writable for us. Retrying or re-prompting won't change that. */
class AlbumAccessDeniedException(message: String) : Exception(message)

/** Suspends until the save completes, so the caller can delete its temp file afterwards. */
suspend fun AlbumSaver.saveAwait(file: Path, suggestedName: String): String =
    suspendCancellableCoroutine { continuation ->
        save(
            file = file,
            suggestedName = suggestedName,
            onSuccess = { continuation.resume(it) },
            onError = { continuation.resumeWithException(it) },
        )
    }
