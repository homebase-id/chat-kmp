package id.homebase.core.util

import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import id.homebase.api.util.freeFileName
import java.awt.Desktop
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Desktop has no photo library; the user's Pictures / Downloads folder is the closest thing. */
class JvmAlbumSaver(
    private val chooseSaveTarget: (suggestedName: String) -> File? = ::showSaveDialog,
    private val reveal: (File) -> Unit = ::revealInFileManager,
) : AlbumSaver {

    override suspend fun target(name: String, asked: Boolean): AlbumTarget {
        val dest = if (asked) {
            // A modal FileDialog must open from its own EDT event, never inside Compose's frame dispatch.
            suspendCancellableCoroutine { continuation ->
                SwingUtilities.invokeLater { continuation.resume(runCatching { chooseSaveTarget(name) }) }
            }.getOrThrow() ?: return AlbumTarget.Cancelled
        } else {
            val folder = if (detectContentTypeFromExtensionOrHint(name).let { it.startsWith("image/") || it.startsWith("video/") }) "Pictures" else "Downloads"
            withContext(Dispatchers.IO) {
                val dir = File(System.getProperty("user.home"), folder).apply { mkdirs() }
                File(dir, freeFileName(name) { File(dir, it).exists() })
            }
        }
        // Written beside it and moved over it when complete, so a failed save never costs a file the person replaced.
        val partial = File(dest.parentFile, ".${dest.name}.part")
        return AlbumTarget.InPlace(
            path = partial.absolutePath,
            publish = {
                withContext(Dispatchers.IO) {
                    try {
                        Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: AtomicMoveNotSupportedException) {
                        // A network or FUSE folder may have no atomic rename; replacing is still better than failing.
                        Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                if (asked) {
                    // Showing it is a courtesy: the file is saved either way.
                    runCatching { reveal(dest) }
                    dest.absolutePath
                } else {
                    dest.parentFile.name
                }
            },
            discard = { withContext(Dispatchers.IO) { partial.delete() } },
        )
    }
}

private fun showSaveDialog(suggestedName: String): java.io.File? {
    val parent = java.awt.Frame.getFrames().firstOrNull { it.isShowing }
    val dialog = java.awt.FileDialog(parent, "Save File", java.awt.FileDialog.SAVE)
    dialog.file = suggestedName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return java.io.File(dir, name)
}

private fun revealInFileManager(dest: java.io.File) {
    if (Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            desktop.browseFileDirectory(dest)
        }
    }
}
