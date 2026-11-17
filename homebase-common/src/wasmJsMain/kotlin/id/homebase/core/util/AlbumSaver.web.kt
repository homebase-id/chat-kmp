package id.homebase.core.util

import kotlinx.io.files.Path

object WebAlbumSaver : AlbumSaver {
    // A person's save downloads the file; nothing downloads unasked.
    override suspend fun target(name: String, asked: Boolean): AlbumTarget {
        if (!asked) throw AlbumAccessDeniedException("The browser has no photo album to write to")
        return AlbumTarget.FinishedFile { file -> downloadToDownloads(Path(file), name) }
    }
}
