package id.homebase.core.util

import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import id.homebase.api.util.freeFileName
import id.homebase.api.util.safeFileName
import java.io.File
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path

/** Desktop has no photo library; the user's Pictures / Downloads folder is the closest thing. */
class JvmAlbumSaver(
    private val chooseSaveTarget: (suggestedName: String) -> File? = ::showSaveDialog,
    private val reveal: (File) -> Unit = ::revealInFileManager,
) : AlbumSaver {

    override fun save(
        file: Path,
        suggestedName: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        try {
            val safeName = safeFileName(suggestedName)
            val mimeType = detectContentTypeFromExtensionOrHint(safeName)
            val folder = if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                "Pictures"
            } else {
                "Downloads"
            }
            val destDir = File(System.getProperty("user.home"), folder).apply { mkdirs() }
            File(file.toString()).copyTo(File(destDir, freeFileName(safeName) { File(destDir, it).exists() }))
            onSuccess(folder)
        } catch (e: Exception) {
            onError(e)
        }
    }

    // Asked: the Save dialog picks the file, as the save button always has; otherwise ~/Pictures, as [save] does.
    override suspend fun target(name: String, asked: Boolean): AlbumTarget {
        val safeName = safeFileName(name)
        val dest = if (asked) {
            // A modal FileDialog must open from its own EDT event, never inside Compose's frame dispatch.
            suspendCancellableCoroutine { continuation ->
                SwingUtilities.invokeLater { continuation.resume(runCatching { chooseSaveTarget(safeName) }) }
            }.getOrThrow() ?: return AlbumTarget.Cancelled
        } else {
            val dir = File(System.getProperty("user.home"), "Pictures").apply { mkdirs() }
            File(dir, freeFileName(safeName) { File(dir, it).exists() })
        }
        return AlbumTarget.InPlace(
            path = dest.absolutePath,
            publish = { if (asked) { reveal(dest); dest.absolutePath } else "Pictures" },
            discard = { dest.delete() },
        )
    }
}
