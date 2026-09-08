package id.homebase.core.util

import kotlinx.io.files.Path

object WebAlbumSaver : AlbumSaver {
    override fun save(
        file: Path,
        suggestedName: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) = onError(AlbumAccessDeniedException("The browser has no photo album to write to"))
}
