package id.homebase.photobase.gallery

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus

/** Which of the library's photos a gallery shows, by each photo's archival status on the server. */
enum class PhotoShelf(val status: ArchivalStatus) {
    Library(ArchivalStatus.None),
    Bin(ArchivalStatus.Removed);

    /** Whether [file], as it arrived, belongs here now. */
    fun holds(file: HomebaseFile): Boolean = file.isAt(status)
}

internal fun HomebaseFile.isAt(status: ArchivalStatus): Boolean =
    fileState == FileState.Active && (fileMetadata.appData.archivalStatus ?: ArchivalStatus.None) == status
