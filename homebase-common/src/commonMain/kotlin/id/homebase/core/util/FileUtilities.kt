package id.homebase.core.util

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.safeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
     * [onSuccess] receives a human-readable location name (e.g. "Downloads", "Photos"). [onFileReleased] runs once
     * [file] is no longer needed, after any outcome, cancelled included, so the caller can delete it. It doesn't run when
     * the file went to the share sheet instead, which reads it later, so it isn't a completion signal.
     */
    fun saveFile(
        file: Path,
        suggestedName: String,
        onSuccess: (String) -> Unit = {},
        onError: (Throwable) -> Unit = {},
        onFileReleased: () -> Unit = {},
    ) = shareFile(file, onError)
}

// App-lifetime: a save the person started finishes even if they leave the screen.
private val saveScope = supervisedScope("saveFile")

/**
 * [FileSystemHandler.saveFile] on every platform: [file] saved through [albumSaver] as a person's save, under
 * [suggestedName] made safe. [onRefused] gets the file when the album won't take it, and says whether it handed the file
 * on to something that reads it later; by default it reports an error and doesn't. [onFileReleased] runs last, once,
 * unless the file was handed on.
 */
internal fun saveThroughAlbum(
    albumSaver: AlbumSaver,
    fileOps: FileOperationsProvider,
    file: Path,
    suggestedName: String,
    onSuccess: (String) -> Unit,
    onError: (Throwable) -> Unit,
    onFileReleased: () -> Unit,
    onRefused: (Path, reason: String) -> Boolean = { _, reason -> onError(AlbumAccessDeniedException(reason)); false },
    scope: CoroutineScope = saveScope,
) {
    scope.launch {
        var handedOn = false
        try {
            // The callbacks run once the save has settled, so one that throws isn't taken for a failed save.
            val saved = try {
                albumSaver.saveFile(file.sourcePath(), safeFileName(suggestedName), asked = true, fileOps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.e(throwable = e, tag = "saveFile") { "Failed to save file: ${e.message}" }
                onError(e)
                return@launch
            }
            when (saved) {
                is AlbumSaveResult.Saved -> onSuccess(saved.location)
                AlbumSaveResult.Cancelled -> Unit
                AlbumSaveResult.WriteFailed -> onError(IllegalStateException("Couldn't write $suggestedName"))
                is AlbumSaveResult.Refused -> handedOn = onRefused(file, saved.reason)
            }
        } finally {
            // Caught here, so a failed cleanup can't hide what the save itself threw.
            if (!handedOn) {
                try {
                    onFileReleased()
                } catch (e: Throwable) {
                    Logger.e(throwable = e, tag = "saveFile") { "Releasing the saved file failed: ${e.message}" }
                }
            }
        }
    }
}

// kotlinx.io's Path collapses `content://` to `content:/`; the file providers need it back.
private fun Path.sourcePath(): String = toString().let { if (it.startsWith("content:/") && !it.startsWith("content://")) "content://${it.removePrefix("content:/")}" else it }
