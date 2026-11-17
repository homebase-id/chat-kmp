package id.homebase.core.util

import id.homebase.api.util.safeFileName
import kotlinx.io.files.Path

object WebAlbumSaver : AlbumSaver {
    override fun save(
        file: Path,
        suggestedName: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) = onError(AlbumAccessDeniedException("The browser has no photo album to write to"))

    // A person's save downloads the file; nothing downloads unasked.
    override suspend fun target(name: String, asked: Boolean): AlbumTarget =
        if (asked) {
            AlbumTarget.FinishedFile { file -> downloadWebFile(Path(file), safeFileName(name)); DOWNLOADS_LOCATION }
        } else {
            super.target(name, asked)
        }
}
