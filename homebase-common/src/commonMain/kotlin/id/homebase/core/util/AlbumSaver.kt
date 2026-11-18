package id.homebase.core.util

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.scratchDir
import id.homebase.api.video.playableContentType
import id.homebase.api.video.playableFileName
import id.homebase.api.video.writePlayable
import kotlin.uuid.Uuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Where a platform keeps what people save: its photo album, or the nearest thing it has. Each platform says only where a
 * new item goes ([target]); [saveFile] and [saveWritten] do the writing, publishing and clean-up the same way on all.
 * Reachable from a plain coroutine, so background callers (auto-save) use it as the screens do.
 */
interface AlbumSaver {
    /**
     * Where a new item goes, for [saveFile]/[saveWritten]. [name] is already a safe file name; the platform may change it
     * to avoid a clash (MediaStore and the browser number it; desktop's Save dialog asks first). [asked]: a person
     * tapped Save, so a platform that lets them choose the place asks; otherwise nothing prompts.
     * - Android 10+: [AlbumTarget.InPlace], a hidden MediaStore entry under Pictures, Movies or Downloads by type. It
     *   exists from this call, so [AlbumTarget.InPlace.discard] must run unless it's published.
     * - Android 9: [AlbumTarget.InPlace], a file in the same public folder; throws [AlbumAccessDeniedException] without
     *   the storage permission.
     * - Desktop: [AlbumTarget.InPlace], asked the file the Save dialog picks ([AlbumTarget.Cancelled] if the person
     *   cancels; the location is its full path), otherwise a new file in ~/Pictures (media) or ~/Downloads.
     * - iOS: [AlbumTarget.FinishedFile], imported by Photos (a photo or video, "Photos") or copied to the app's Files
     *   folder ("Files"); its take throws [AlbumAccessDeniedException] when Photos access is denied.
     * - Web: asked, [AlbumTarget.FinishedFile] downloaded by the browser; otherwise throws [AlbumAccessDeniedException].
     * An [AlbumTarget.InPlace] path is one [FileOperationsProvider] can `writeStream` and `overwrite`. The location
     * returned on success is for a message: a folder ("Movies/Homebase", "Photos", "Downloads") or, asked on desktop, the
     * file's full path.
     */
    suspend fun target(name: String, asked: Boolean): AlbumTarget
}

/** The album is not writable for us. Retrying or re-prompting won't change that. */
class AlbumAccessDeniedException(override val message: String) : Exception(message)

/** Where [saveFile]/[saveWritten] put a new item. */
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

    /** The album refused it ([AlbumAccessDeniedException], saying [reason]); [file], named [name], is finished and the caller's to share. */
    data class Refused(val file: String, val name: String, val reason: String) : AlbumSaveResult
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
    asked: Boolean = true,
    write: suspend (path: String) -> Boolean,
): AlbumSaveResult = when (val target = targetOrRefusal(name, asked)) {
    AlbumTarget.Cancelled -> AlbumSaveResult.Cancelled
    is AlbumTarget.InPlace -> target.fill(write)
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
                    AlbumSaveResult.Refused(scratchPath, name, e.message)
                }
            }
        } finally {
            if (!handedOver) withContext(NonCancellable) { fileOps.deleteTempFile(scratchPath) }
        }
    }
}

/**
 * Saves the finished [file] as a new item named [name] (a safe file name), the same way on every platform: copied
 * straight into the album where it's written in place, otherwise handed over as it is. Nothing is left behind unless
 * it's [AlbumSaveResult.Saved], even when the caller is cancelled; on [AlbumSaveResult.Refused] the caller still has
 * [file] to offer another way. Any other platform error is thrown.
 */
suspend fun AlbumSaver.saveFile(file: String, name: String, asked: Boolean, fileOps: FileOperationsProvider): AlbumSaveResult =
    when (val target = targetOrRefusal(name, asked)) {
        AlbumTarget.Cancelled -> AlbumSaveResult.Cancelled
        is AlbumTarget.InPlace -> target.fill { path -> fileOps.writeStream(path, fileOps.readFileAsFlow(file, COPY_CHUNK)); true }
        is AlbumTarget.FinishedFile -> try {
            AlbumSaveResult.Saved(withContext(NonCancellable) { target.take(file) })
        } catch (e: AlbumAccessDeniedException) {
            AlbumSaveResult.Refused(file, name, e.message)
        }
    }

// Once written, publishing finishes even if the caller has gone: it's the last step of a complete file.
private suspend fun AlbumTarget.InPlace.fill(write: suspend (path: String) -> Boolean): AlbumSaveResult = try {
    if (write(path)) {
        AlbumSaveResult.Saved(withContext(NonCancellable) { publish() })
    } else {
        withContext(NonCancellable) { discard() }
        AlbumSaveResult.WriteFailed
    }
} catch (e: Throwable) {
    withContext(NonCancellable) { discard() }
    throw e
}

// A refusal at the target is one at the hand-over: either way the caller ends up with a finished file it may share.
private suspend fun AlbumSaver.targetOrRefusal(name: String, asked: Boolean): AlbumTarget = try {
    target(name, asked)
} catch (e: AlbumAccessDeniedException) {
    AlbumTarget.FinishedFile { throw e }
}

private const val COPY_CHUNK = 256 * 1024

/**
 * A received payload saved through [saveWritten], named [name], as a file people can open ([writePlayable]): an HLS
 * video becomes an `.mp4`. The one save path for media people receive.
 */
suspend fun AlbumSaver.saveReceived(
    driveFileProvider: DriveFileProvider,
    fileOps: FileOperationsProvider,
    driveId: Uuid,
    fileId: Uuid,
    payload: PayloadDescriptor,
    keyHeader: KeyHeader,
    name: String = saveFileName(payload.playableFileName(payload.filename()), payload.key, payload.playableContentType),
    asked: Boolean = true,
): AlbumSaveResult {
    val scratch = "${fileOps.scratchDir(AppCacheDirs.DOWNLOADS)}/${Uuid.random().toString().take(8)}_$name"
    return saveWritten(name, scratch, fileOps, asked) { path ->
        driveFileProvider.writePlayable(driveId, fileId, payload, keyHeader, fileOps, path)
    }
}
