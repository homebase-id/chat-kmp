package id.homebase.core.util

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
            val mimeType = detectContentTypeFromExtensionOrHint(suggestedName)
            val folder = if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                "Pictures"
            } else {
                "Downloads"
            }
            val destDir = File(System.getProperty("user.home"), folder).apply { mkdirs() }
            val safeName = suggestedName
                .replace('/', '_')
                .replace('\\', '_')
                .replace(' ', '_')
            File(file.toString()).copyTo(File(destDir, safeName), overwrite = true)
            onSuccess(folder)
        } catch (e: Exception) {
            onError(e)
        }
    }
}
