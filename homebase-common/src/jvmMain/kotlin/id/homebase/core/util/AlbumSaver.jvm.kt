package id.homebase.core.util

import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import id.homebase.api.util.freeFileName
import id.homebase.api.util.safeFileName
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    override suspend fun target(name: String): AlbumTarget {
        // A modal FileDialog must open from its own EDT event, never inside Compose's frame dispatch.
        val dest = suspendCancellableCoroutine { continuation ->
            SwingUtilities.invokeLater { continuation.resume(runCatching { chooseSaveTarget(name) }) }
        }.getOrThrow() ?: return AlbumTarget.Cancelled
        // Written beside it and moved over it when complete, so a failed save never costs a file the person replaced.
        val partial = File(dest.parentFile, ".${dest.name}.part")
        return AlbumTarget.InPlace(
            path = partial.absolutePath,
            publish = {
                withContext(Dispatchers.IO) {
                    Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
                reveal(dest)
                dest.absolutePath
            },
            discard = { withContext(Dispatchers.IO) { partial.delete() } },
        )
    }
}
