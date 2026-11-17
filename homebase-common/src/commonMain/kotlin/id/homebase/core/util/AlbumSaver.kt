package id.homebase.core.util

import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.safeFileName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.coroutines.resume
import kotlin.uuid.Uuid
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

    /**
     * Where a new item named [name] goes, for [saveWritten]. [asked]: a person tapped Save, so a platform that lets them
     * choose the place asks (desktop's Save dialog) and may hand the file over itself (a browser download); otherwise
     * nothing prompts. This default takes a finished file through [save], as iOS's Photos needs.
     */
    suspend fun target(name: String, asked: Boolean): AlbumTarget = AlbumTarget.FinishedFile { file -> saveAwait(Path(file), name) }
}

/** Where [saveWritten] puts a new item. */
sealed interface AlbumTarget {
    /** Written in place at [path], a file or Android's `content://` entry; [publish] shows it and says where, [discard] removes it. */
    class InPlace(val path: String, val publish: suspend () -> String, val discard: suspend () -> Unit) : AlbumTarget

    /** The platform only takes a finished file: [take] hands it over and says where it went. */
    class FinishedFile(val take: suspend (file: String) -> String) : AlbumTarget

    /** The person cancelled. */
    data object Cancelled : AlbumTarget
}

sealed interface AlbumSaveResult {
    data class Saved(val location: String) : AlbumSaveResult
    data object Cancelled : AlbumSaveResult
    data object WriteFailed : AlbumSaveResult
}

/**
 * Saves what [write] produces as a new item named [name], the same way on every platform: straight into the album
 * where the platform allows writing in place (Android's MediaStore, a file on desktop), otherwise written to
 * [scratchPath] and handed over (iOS's Photos, a browser download). [write] writes the file at the path it's given and
 * returns false when it couldn't. Nothing is left behind unless it's [AlbumSaveResult.Saved]; a platform error is thrown.
 */
suspend fun AlbumSaver.saveWritten(
    name: String,
    asked: Boolean,
    scratchPath: String,
    fileOps: FileOperationsProvider,
    write: suspend (path: String) -> Boolean,
): AlbumSaveResult = when (val target = target(name, asked)) {
    AlbumTarget.Cancelled -> AlbumSaveResult.Cancelled
    is AlbumTarget.InPlace -> {
        val written = try {
            write(target.path)
        } catch (e: Throwable) {
            withContext(NonCancellable) { target.discard() }
            throw e
        }
        if (written) {
            AlbumSaveResult.Saved(target.publish())
        } else {
            target.discard()
            AlbumSaveResult.WriteFailed
        }
    }
    is AlbumTarget.FinishedFile -> try {
        if (write(scratchPath)) AlbumSaveResult.Saved(target.take(scratchPath)) else AlbumSaveResult.WriteFailed
    } finally {
        fileOps.deleteTempFile(scratchPath)
    }
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

/**
 * [saveWritten] for a video a person asked to save: named after [fileName] as an `.mp4`, with a scratch file in the
 * media work folder where the platform needs one.
 */
suspend fun AlbumSaver.saveVideo(
    fileOps: FileOperationsProvider,
    fileName: String?,
    write: suspend (path: String) -> Boolean,
): AlbumSaveResult {
    val scratch = "${AppCacheDirs.scratchPath(fileOps.getCacheDirectory(), AppCacheDirs.MEDIA_WORK)}/save_${Uuid.random().toString().take(8)}.mp4"
    return saveWritten(safeFileName(fileName?.substringBeforeLast('.'), "video", "mp4"), asked = true, scratch, fileOps, write)
}
