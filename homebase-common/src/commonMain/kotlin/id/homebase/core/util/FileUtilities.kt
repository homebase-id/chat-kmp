package id.homebase.core.util

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.safeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

/**
 * The [FileSystemHandler] for the current platform, remembered for the composition. Each method reports failure through
 * its `onError` callback and never throws; a method a platform cannot do (see [FileSystemHandler]) does nothing.
 */
@Composable
expect fun getUriHandler(): FileSystemHandler

interface FileSystemHandler {
    fun openUrl(url: String, onError: (Throwable) -> Unit = {})
    fun editFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit = {})
    /** Opens [file] in another app; web: downloads the file. iOS has no default app, so it always shows its Open In menu, whatever [showChooser]. */
    fun openFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit = {})
    fun openFileBrowser(file: Path, onError: (Throwable) -> Unit = {})
    /**
     * Hands [file] to another app. Android and iOS show the system share sheet (on iPad a popover anchored
     * to the window); desktop has no share target, so it opens a Save As dialog for a copy; the web does nothing.
     * Callers that already offer Save should offer Share on Android and iOS only.
     */
    fun shareFile(file: Path, onError: (Throwable) -> Unit = {})

    /**
     * Share a file alongside a caption (e.g. a Moment's media + its
     * description). Single-file + text is the one multi-part share the OS
     * delivers reliably across target apps. Defaults to a file-only share so
     * platforms that don't carry text (Desktop/Web) degrade gracefully; Android
     * and iOS override to attach [text] when it's non-blank.
     */
    fun shareFile(file: Path, text: String?, onError: (Throwable) -> Unit = {}) =
        shareFile(file, onError)

    fun shareText(text: String, onError: (Throwable) -> Unit = {})
    fun openAppStore(onError: (Throwable) -> Unit = {})

    /**
     * Save a file to the user's device storage (Downloads/Photos/etc), under [suggestedName] made [safeFileName].
     * [onSuccess] receives a human-readable location name (e.g. "Downloads", "Photos"). [onSettled] runs once [file] is
     * no longer needed, whatever the outcome, cancelled included, so the caller can delete it; not when it was handed to
     * the share sheet instead, which reads it later.
     */
    fun saveFile(
        file: Path,
        suggestedName: String,
        onSuccess: (String) -> Unit = {},
        onError: (Throwable) -> Unit = {},
        onSettled: () -> Unit = {},
    ) = shareFile(file, onError)
}

// App-lifetime: a save the person started finishes even if they leave the screen.
private val saveScope = supervisedScope("saveFile")

/**
 * [FileSystemHandler.saveFile] on every platform: [file] saved through [albumSaver] as a person's save, under
 * [suggestedName] made safe. [onRefused] gets the file when the album won't take it; by default that's an error saying
 * why. [onSettled] runs after every outcome but [onRefused], which settles it itself.
 */
internal fun saveThroughAlbum(
    albumSaver: AlbumSaver,
    fileOps: FileOperationsProvider,
    file: Path,
    suggestedName: String,
    onSuccess: (String) -> Unit,
    onError: (Throwable) -> Unit,
    onSettled: () -> Unit,
    onRefused: (Path, reason: String) -> Unit = { _, reason ->
        try {
            onError(AlbumAccessDeniedException(reason))
        } finally {
            onSettled()
        }
    },
) {
    saveScope.launch {
        // The callbacks run once the save has settled, so one that throws isn't taken for a failed save.
        val saved = try {
            albumSaver.saveFile(file.sourcePath(), safeFileName(suggestedName), asked = true, fileOps)
        } catch (e: CancellationException) {
            onSettled()
            throw e
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = "saveFile") { "Failed to save file: ${e.message}" }
            try {
                onError(e)
            } finally {
                onSettled()
            }
            return@launch
        }
        if (saved is AlbumSaveResult.Refused) return@launch onRefused(file, saved.reason)
        try {
            when (saved) {
                is AlbumSaveResult.Saved -> onSuccess(saved.location)
                AlbumSaveResult.Cancelled -> Unit
                AlbumSaveResult.WriteFailed -> onError(IllegalStateException("Couldn't write $suggestedName"))
                is AlbumSaveResult.Refused -> Unit
            }
        } finally {
            onSettled()
        }
    }
}

// kotlinx.io's Path collapses `content://` to `content:/`; the file providers need it back.
private fun Path.sourcePath(): String = toString().let { if (it.startsWith("content:/") && !it.startsWith("content://")) "content://${it.removePrefix("content:/")}" else it }
