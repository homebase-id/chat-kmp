package id.homebase.core.util

import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import id.homebase.api.util.freeFileName
import id.homebase.api.util.safeFileName
import kotlinx.io.files.Path
import java.io.File

/** Desktop has no photo library; the user's Pictures / Downloads folder is the closest thing. */
class JvmAlbumSaver : AlbumSaver {

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
}
