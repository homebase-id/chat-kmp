package id.homebase.core.util

import kotlinx.io.files.Path

object WebAlbumSaver : AlbumSaver {
    override val savesUnasked: Boolean get() = false

    override suspend fun target(name: String, asked: Boolean): AlbumTarget {
        if (!asked) throw AlbumAccessDeniedException("The browser has no photo album to write to")
        return AlbumTarget.FinishedFile { file -> downloadWebFile(Path(file), name); "Downloads" }
    }
}
