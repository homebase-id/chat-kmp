package id.homebase.photobase.gallery

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.sync.database.PhotoFilter
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.uuid.Uuid

/** Where a photo is, by its archival status on the server; photos move between these. */
enum class PhotoShelf(val status: ArchivalStatus) {
    Library(ArchivalStatus.None),
    Archive(ArchivalStatus.Archived),
    Bin(ArchivalStatus.Removed),
}

/** What a gallery shows: the photos on one shelf, or the tagged photos of the library and the archive. */
sealed interface PhotoGallery {
    /** Names its route. */
    val key: String
    val filter: PhotoFilter

    /** Whether [file], as it arrived, belongs here now. */
    fun holds(file: HomebaseFile): Boolean

    data class OnShelf(val shelf: PhotoShelf) : PhotoGallery {
        override val key: String get() = shelf.name
        override val filter: PhotoFilter get() = PhotoFilter.Shelf(shelf.status.value.toLong())

        override fun holds(file: HomebaseFile): Boolean = file.isAt(shelf.status)
    }

    /** A binned photo drops out, and is back, still tagged, if restored. */
    sealed interface Tagged : PhotoGallery {
        val tag: Uuid
        override val filter: PhotoFilter get() = PhotoFilter.Tagged(tag, TAGGED_STATUSES)

        override fun holds(file: HomebaseFile): Boolean = TAGGED_SHELVES.any { file.isAt(it.status) } && tag in file.localTags()
    }

    data object Favorites : Tagged {
        override val key: String = "Favorites"
        override val tag: Uuid = PhotobaseProtocol.FavoriteTag
    }

    /** The photos carrying the album's tag, which is the album record's unique id. */
    data class Album(val id: Uuid) : Tagged {
        override val key: String get() = "$ALBUM_KEY$id"
        override val tag: Uuid get() = id
    }

    companion object {
        val Library = OnShelf(PhotoShelf.Library)
        val Archive = OnShelf(PhotoShelf.Archive)
        val Bin = OnShelf(PhotoShelf.Bin)

        /** The galleries the Library screen opens, in its order. */
        val away: List<PhotoGallery> = listOf(Favorites, Archive, Bin)

        fun ofKey(key: String): PhotoGallery? =
            away.firstOrNull { it.key == key } ?: if (key.startsWith(ALBUM_KEY)) Uuid.parseOrNull(key.drop(ALBUM_KEY.length))?.let(::Album) else null
    }
}

private val TAGGED_SHELVES = listOf(PhotoShelf.Library, PhotoShelf.Archive)
internal val TAGGED_STATUSES = TAGGED_SHELVES.map { it.status.value.toLong() }
private const val ALBUM_KEY = "album-"

internal fun HomebaseFile.isAt(status: ArchivalStatus): Boolean =
    fileState == FileState.Active && (fileMetadata.appData.archivalStatus ?: ArchivalStatus.None) == status

internal fun HomebaseFile.localTags(): List<Uuid> = fileMetadata.localAppData?.tags.orEmpty()
