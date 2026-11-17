package id.homebase.core.util

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.scratchDir
import id.homebase.api.util.safeFileName
import id.homebase.api.video.downloadHlsAsMp4
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

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
     * Where a new item a person asked to save goes, for [saveWritten]. [name] is already a safe file name; the platform
     * may change it to avoid a clash (MediaStore and the browser number it; desktop's Save dialog asks first).
     * - Android 10+: [AlbumTarget.InPlace], a hidden MediaStore entry under Pictures, Movies or Downloads by type. It
     *   exists from this call, so [AlbumTarget.InPlace.discard] must run unless it's published.
     * - Desktop: [AlbumTarget.InPlace], the file the Save dialog picks; [AlbumTarget.Cancelled] when the person cancels.
     * - iOS, and Android 9: this default, a finished file through [save] (Photos imports one; Android 9 checks its
     *   storage permission there).
     * - Web: [AlbumTarget.FinishedFile], downloaded by the browser.
     * An [AlbumTarget.InPlace] path is one [FileOperationsProvider] can `writeStream` and `overwrite`. The location
     * returned on success is for a message: a folder ("Movies/Homebase", "Photos", "Downloads") or desktop's full path.
     */
    suspend fun target(name: String): AlbumTarget = AlbumTarget.FinishedFile { file -> saveAwait(Path(file), name) }
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

    /** The album refused it ([AlbumAccessDeniedException]); [file], named [name], is finished and now the caller's to share. */
    data class Refused(val file: String, val name: String) : AlbumSaveResult
}

/**
 * Saves what [write] produces as a new item named [name] (a safe file name), the same way on every platform: straight
 * into the album where the platform allows writing in place (Android's MediaStore, a file on desktop), otherwise written
 * to [scratchPath] and handed over (iOS's Photos, a browser download). [write] writes the file at the path it's given and
 * returns false when it couldn't. Nothing is left behind unless it's [AlbumSaveResult.Saved] or
 * [AlbumSaveResult.Refused], even when the caller is cancelled; any other platform error is thrown.
 */
suspend fun AlbumSaver.saveWritten(
    name: String,
    scratchPath: String,
    fileOps: FileOperationsProvider,
    write: suspend (path: String) -> Boolean,
): AlbumSaveResult = when (val target = target(name)) {
    AlbumTarget.Cancelled -> AlbumSaveResult.Cancelled
    is AlbumTarget.InPlace -> try {
        // Once written, publishing finishes even if the caller has gone: it's the last step of a complete file.
        if (write(target.path)) AlbumSaveResult.Saved(withContext(NonCancellable) { target.publish() }) else {
            withContext(NonCancellable) { target.discard() }
            AlbumSaveResult.WriteFailed
        }
    } catch (e: Throwable) {
        withContext(NonCancellable) { target.discard() }
        throw e
    }
    is AlbumTarget.FinishedFile -> {
        var handedOver = false
        try {
            if (!write(scratchPath)) {
                AlbumSaveResult.WriteFailed
            } else {
                // The platform reads the file until it's done, so it isn't deleted underneath a cancelled caller.
                try {
                    AlbumSaveResult.Saved(withContext(NonCancellable) { target.take(scratchPath) })
                } catch (e: AlbumAccessDeniedException) {
                    handedOver = true
                    AlbumSaveResult.Refused(scratchPath, name)
                }
            }
        } finally {
            if (!handedOver) withContext(NonCancellable) { fileOps.deleteTempFile(scratchPath) }
        }
    }
}

/**
 * A received payload saved through [saveWritten], named for the person; an HLS video ([hls]) is remuxed into an `.mp4`
 * on the way. The one save path for media people receive, in chat and Moments.
 */
suspend fun AlbumSaver.saveReceived(
    driveFileProvider: DriveFileProvider,
    fileOps: FileOperationsProvider,
    driveId: Uuid,
    fileId: Uuid,
    payloadKey: String,
    keyHeader: KeyHeader,
    fileName: String?,
    contentType: String?,
    hls: Boolean,
): AlbumSaveResult {
    val name = if (hls) safeFileName(fileName?.substringBeforeLast('.'), "video", "mp4") else saveFileName(fileName, payloadKey, contentType)
    val scratch = "${fileOps.scratchDir(AppCacheDirs.DOWNLOADS)}/${Uuid.random().toString().take(8)}_$name"
    return saveWritten(name, scratch, fileOps) { path ->
        if (hls) {
            driveFileProvider.downloadHlsAsMp4(driveId, fileId, payloadKey, keyHeader, fileOps, path)
        } else {
            driveFileProvider.streamPayloadDecryptedToPath(driveId, fileId, payloadKey, keyHeader, path, fileOps)
        }
    }
}
