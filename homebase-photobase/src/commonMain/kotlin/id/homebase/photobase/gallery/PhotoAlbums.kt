package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.PhotobaseRecord
import id.homebase.photobase.parseRecord
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

/** [photos] and [cover], the newest of them, count the library and the archive, as the album shows them. */
data class PhotoAlbum(val id: Uuid, val name: String, val photos: Int = 0, val cover: PhotoGalleryItem? = null)

interface PhotoAlbumSource {
    /** Newest made first, null until read; kept current while collected. */
    val albums: StateFlow<List<PhotoAlbum>?>

    suspend fun create(name: String): Uuid

    suspend fun rename(id: Uuid, name: String)

    /** Takes the album's tag off its photos, then deletes the album; the photos stay where they are. */
    suspend fun delete(id: Uuid)

    /** Photobase is gone: the albums are read again from scratch. */
    fun forget()
}

@Serializable
private data class AlbumContent(val name: String)

/** An album is a record on the Photos drive whose unique id is the local tag its photos carry. */
class PhotoAlbums(
    private val store: PhotoGalleryStore,
    private val record: PhotobaseRecord,
    private val mover: PhotoMover,
    // Photos tagged on another device may not have synced here yet.
    private val taggedOnServer: suspend (tag: Uuid) -> List<Uuid>,
    changes: Flow<PhotoChange>,
    scope: CoroutineScope,
    private val sync: () -> Unit,
) : PhotoAlbumSource {
    private val forgotten = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val albums: StateFlow<List<PhotoAlbum>?> = forgotten
        .flatMapLatest {
            val reads = changes
                .filter { it.mayTouchAlbums() }
                .map { }
                .onStart { emit(Unit) }
                .conflate()
                .mapNotNull {
                    try {
                        list()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Reading throws while nobody is signed in; the next change reads again.
                        Logger.w(tag = TAG, throwable = e) { "couldn't read the albums" }
                        null
                    }
                }
            reads.onStart<List<PhotoAlbum>?> { emit(null) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    override fun forget() {
        forgotten.value++
    }

    override suspend fun create(name: String): Uuid {
        val id = Uuid.random()
        write(id, name, create = true)
        return id
    }

    // One deleted elsewhere mustn't come back by a rename.
    override suspend fun rename(id: Uuid, name: String) = write(id, name, create = false)

    override suspend fun delete(id: Uuid) {
        val tagged = (store.taggedFileIds(id) + taggedOnServer(id)).distinct()
        // A record gone first would leave tags no album names, and nothing to delete again to clear them.
        if (tagged.isNotEmpty() && mover.setTag(id, on = false, tagged).failed > 0) throw IllegalStateException("album $id: tags not all taken off")
        try {
            record.delete(id)
        } finally {
            sync()
        }
    }

    private suspend fun write(id: Uuid, name: String, create: Boolean) {
        val content = OdinSystemSerializer.serialize(AlbumContent(name.trim().truncateToCodePoints(MAX_NAME)))
        try {
            record.write(id, PhotobaseProtocol.AlbumFileType, create) { content }
        } finally {
            sync()
        }
    }

    private suspend fun list(): List<PhotoAlbum> {
        val albums = store.albumRecords().sortedByDescending { it.fileMetadata.created.milliseconds }.mapNotNull { it.album() }
        val covers = store.albumCovers(albums.map { it.id })
        return albums.map { album -> covers[album.id]?.let { album.copy(photos = it.photos, cover = it.cover) } ?: album }
    }

    private fun HomebaseFile.album(): PhotoAlbum? {
        val id = fileMetadata.appData.uniqueId ?: return null
        val content = parseRecord<AlbumContent>(fileMetadata.appData.content, "album $id") ?: return null
        return PhotoAlbum(id, content.name)
    }

    private companion object {
        const val TAG = "PhotoAlbums"
        const val MAX_NAME = 100
    }
}

// A photo whose local metadata was never written has never had a tag to change an album: a backup brings one a second.
private fun PhotoChange.mayTouchAlbums(): Boolean = when (this) {
    PhotoChange.RoundFinished -> true
    is PhotoChange.FilesArrived -> files.any {
        // A deleted photo's tombstone keeps no local metadata.
        it.fileMetadata.appData.fileType == PhotobaseProtocol.AlbumFileType || it.fileMetadata.localAppData?.versionTag != null ||
            it.fileState != FileState.Active
    }
}

/** Every file on the Photos drive carrying [tag], as the server has them. */
suspend fun DriveQueryProvider.photosTagged(tag: Uuid): List<Uuid> = buildList {
    var cursor: String? = null
    do {
        val page = queryBatch(
            PhotobaseProtocol.DriveAlias,
            QueryBatchRequest(
                queryParams = FileQueryParams(localTagsMatchAtLeastOne = listOf(tag), fileState = listOf(FileState.Active, FileState.Deleted)),
                resultOptionsRequest = QueryBatchResultOptionsRequest(cursorState = cursor, maxRecords = DriveFileHttpProvider.FILE_BATCH),
            ),
        )
        page.searchResults.mapTo(this) { it.fileId }
        cursor = page.cursorState?.takeIf { page.hasMoreRows && it != cursor }
    } while (cursor != null)
}
