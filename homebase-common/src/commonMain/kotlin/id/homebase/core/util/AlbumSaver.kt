package id.homebase.core.util

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.scratchDir
import id.homebase.api.util.safeFileName
import id.homebase.api.video.downloadHlsAsMp4
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
     *   cancels), otherwise a new file in ~/Pictures (media) or ~/Downloads.
     * - iOS: [AlbumTarget.FinishedFile], imported by Photos (a photo or video) or copied to Files; its take throws
     *   [AlbumAccessDeniedException] when Photos access is denied.
     * - Web: asked, [AlbumTarget.FinishedFile] downloaded by the browser; otherwise throws [AlbumAccessDeniedException].
     * An [AlbumTarget.InPlace] path is one [FileOperationsProvider] can `writeStream` and `overwrite`. The location
     * returned on success is for a message: a folder ("Movies/Homebase", "Photos", "Downloads") or desktop's full path.
     */
    suspend fun target(name: String, asked: Boolean): AlbumTarget
}

/** The album is not writable for us. Retrying or re-prompting won't change that. */
class AlbumAccessDeniedException(message: String) : Exception(message)

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

    /** The album refused it ([AlbumAccessDeniedException], saying [reason]); [file], named [name], is finished and the caller's to share. */
    data class Refused(val file: String, val name: String, val reason: String? = null) : AlbumSaveResult
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
        is AlbumTarget.InPlace -> try {
            fileOps.writeStream(target.path, fileOps.readFileAsFlow(file, COPY_CHUNK))
            AlbumSaveResult.Saved(withContext(NonCancellable) { target.publish() })
        } catch (e: Throwable) {
            withContext(NonCancellable) { target.discard() }
            throw e
        }
        is AlbumTarget.FinishedFile -> try {
            AlbumSaveResult.Saved(withContext(NonCancellable) { target.take(file) })
        } catch (e: AlbumAccessDeniedException) {
            AlbumSaveResult.Refused(file, name, e.message)
        }
    }

// A refusal at the target is one at the hand-over: either way the caller ends up with a finished file it may share.
private suspend fun AlbumSaver.targetOrRefusal(name: String, asked: Boolean): AlbumTarget = try {
    target(name, asked)
} catch (e: AlbumAccessDeniedException) {
    AlbumTarget.FinishedFile { throw e }
}

private const val COPY_CHUNK = 256 * 1024

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
