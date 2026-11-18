package id.homebase.core.util

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.safeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
     * Save [file] to the person's device storage (Downloads, Photos, ...) under [suggestedName] made [safeFileName].
     * [onSuccess] gets a readable location ("Downloads", "Photos") and [onError] a failure; a cancel gets neither. On
     * Android 9 without storage access the share sheet opens instead, with the file under its name in `share_outbound/`
     * (swept when the app returns); [onSuccess] doesn't run, and [onError] does if the sheet can't be opened.
     *
     * [deleteAfter]: [file] is the caller's to give away, and the save deletes it once done, whatever the outcome (for
     * the share sheet it's moved rather than copied). Otherwise [file] is never touched.
     */
    fun saveFile(
        file: Path,
        suggestedName: String,
        deleteAfter: Boolean,
        onSuccess: (String) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    )

    /**
     * A save the album refused ([AlbumSaveResult.Refused]) offered another way without asking the album again, which
     * would only refuse it again: on Android 9 the share sheet, as for [saveFile]; elsewhere [onError] with the refusal.
     * [refused]'s file is the caller's to give away and is deleted once done.
     */
    fun offerRefused(refused: AlbumSaveResult.Refused, onError: (Throwable) -> Unit = {})
}

// App-lifetime: a save the person started finishes even if they leave the screen.
private val saveScope = supervisedScope("saveFile")

/**
 * What a save the album refused turns into, the one platform part of [FileSaver]: on Android 9 the share sheet
 * ([stageForShare]); elsewhere nothing can take the file ([ReportRefusal]). [giveAway]: [file] is the caller's to give
 * away. Nothing may read [file] where it is afterwards, since the caller may delete it.
 */
fun interface RefusedSaveFallback {
    suspend fun offer(file: String, name: String, reason: String, giveAway: Boolean, onError: (Throwable) -> Unit)
}

/** Reports the refusal through `onError`. */
object ReportRefusal : RefusedSaveFallback {
    override suspend fun offer(file: String, name: String, reason: String, giveAway: Boolean, onError: (Throwable) -> Unit) =
        onError(AlbumAccessDeniedException(reason))
}

/**
 * A person's save, the same on every platform: each [FileSystemHandler]'s [FileSystemHandler.saveFile] and
 * [FileSystemHandler.offerRefused] delegate here, and only [albumSaver] and [fallback] are the platform's.
 */
class FileSaver(
    private val albumSaver: AlbumSaver,
    private val fileOps: FileOperationsProvider,
    private val fallback: RefusedSaveFallback = ReportRefusal,
    private val scope: CoroutineScope = saveScope,
) {
    /** [FileSystemHandler.saveFile]. */
    fun save(file: Path, suggestedName: String, deleteAfter: Boolean, onSuccess: (String) -> Unit, onError: (Throwable) -> Unit) {
        // ATOMIC: once launched, the body (and so the delete in its finally) runs even if the scope is cancelled first.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                // The callbacks run once the save has settled, so one that throws isn't taken for a failed save.
                val saved = try {
                    albumSaver.saveFile(file.sourcePath(), safeFileName(suggestedName), asked = true, fileOps)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Error) {
                    // Reported, so the person hears the save failed, then rethrown: an Error isn't an ordinary failure.
                    onError(e)
                    throw e
                } catch (e: Throwable) {
                    // Throwable, not Exception: a failed JS call on the web throws a JsException, which isn't one.
                    Logger.e(throwable = e, tag = "saveFile") { "Failed to save file: ${e.message}" }
                    onError(e)
                    return@launch
                }
                when (saved) {
                    is AlbumSaveResult.Saved -> onSuccess(saved.location)
                    AlbumSaveResult.Cancelled -> Unit
                    AlbumSaveResult.WriteFailed -> onError(IllegalStateException("Couldn't write $suggestedName"))
                    is AlbumSaveResult.Refused -> fallback.offer(saved.file, saved.name, saved.reason, deleteAfter, onError)
                }
            } finally {
                if (deleteAfter) fileOps.deleteQuietly(file.sourcePath())
            }
        }
    }

    /** [FileSystemHandler.offerRefused]. */
    fun offerRefused(refused: AlbumSaveResult.Refused, onError: (Throwable) -> Unit) {
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                fallback.offer(refused.file, refused.name, refused.reason, giveAway = true, onError)
            } finally {
                fileOps.deleteQuietly(refused.file)
            }
        }
    }
}

/**
 * [source] as a file called [name] in `share_outbound/`, swept when the app returns, for an app that reads it later:
 * moved there when [giveAway], copied otherwise (also when it can't be moved). A failed copy leaves nothing behind.
 */
internal suspend fun FileOperationsProvider.stageForShare(source: String, name: String, giveAway: Boolean): String {
    val target = createNamedShareOutboundPath(name)
    if (giveAway && moveFile(source, target)) return target
    try {
        writeStream(target, readFileAsFlow(source, COPY_CHUNK))
    } catch (e: Throwable) {
        deleteTempFile(target)
        throw e
    }
    return target
}

// Logged, not thrown, so a failed delete can't hide what the save itself threw.
fun FileOperationsProvider.deleteQuietly(path: String) {
    runCatching { deleteTempFile(path) }.onFailure { Logger.e(throwable = it, tag = "saveFile") { "Deleting the saved file failed" } }
}

// kotlinx.io's Path collapses `content://` to `content:/`; the file providers need it back.
private fun Path.sourcePath(): String = toString().let { if (it.startsWith("content:/") && !it.startsWith("content://")) "content://${it.removePrefix("content:/")}" else it }
