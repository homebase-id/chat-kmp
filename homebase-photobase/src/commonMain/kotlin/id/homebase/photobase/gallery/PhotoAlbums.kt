package id.homebase.photobase.gallery

import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.PhotobaseRecord
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable

/** [photos] and [cover], the newest of them, count the library and the archive, as the album shows them. */
data class PhotoAlbum(val id: Uuid, val name: String, val photos: Int = 0, val cover: PhotoGalleryItem? = null)

interface PhotoAlbumSource {
    /** Newest made first; read again on every change to the Photos drive while collected. */
    val albums: Flow<List<PhotoAlbum>>

    /** Returns the new album's id. */
    suspend fun create(name: String): Uuid

    suspend fun rename(id: Uuid, name: String)

    /** Takes the album's tag off its photos, then deletes the album; the photos stay where they are. */
    suspend fun delete(id: Uuid)
}

@Serializable
private data class AlbumContent(val name: String)

/** An album is a record on the Photos drive whose unique id is the local tag its photos carry. */
class PhotoAlbums(
    private val store: PhotoGalleryStore,
    private val record: PhotobaseRecord,
    private val server: PhotoMoveServer,
    private val mover: PhotoMover,
    changes: Flow<PhotoChange>,
    private val sync: () -> Unit,
) : PhotoAlbumSource {
    override val albums: Flow<List<PhotoAlbum>> = changes
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

    override suspend fun create(name: String): Uuid {
        val id = Uuid.random()
        write(id, name)
        return id
    }

    override suspend fun rename(id: Uuid, name: String) {
        // Writing creates a record that isn't there: one deleted elsewhere mustn't come back by a rename.
        if (record.header(id)?.fileState != FileState.Active) throw IllegalStateException("album $id is gone")
        write(id, name)
    }

    override suspend fun delete(id: Uuid) {
        val tagged = store.taggedFileIds(id)
        if (tagged.isNotEmpty()) mover.setTag(id, on = false, tagged)
        val header = record.header(id)?.takeIf { it.fileState == FileState.Active } ?: return
        val versionTag = header.fileMetadata.versionTag ?: throw IllegalStateException("album $id has no version tag")
        try {
            val answer = server.softDelete(listOf(FileVersionTag(header.fileId, versionTag))).single()
            if (answer.errorCode != null && answer.errorCode != OdinClientErrorCode.FileNotFound) {
                throw IllegalStateException("album $id not deleted: ${answer.errorCode}")
            }
        } finally {
            sync()
        }
    }

    private suspend fun write(id: Uuid, name: String) {
        val content = OdinSystemSerializer.serialize(AlbumContent(name.trim().truncateToCodePoints(MAX_NAME)))
        try {
            record.write(id, PhotobaseProtocol.AlbumFileType) { content }
        } finally {
            sync()
        }
    }

    private suspend fun list(): List<PhotoAlbum> {
        val records = store.albumRecords().mapNotNull { file -> file.album()?.let { it to file.fileMetadata.created.milliseconds } }
        val covers = store.albumCovers(records.map { it.first.id })
        return records.sortedByDescending { it.second }.map { (album, _) ->
            covers[album.id]?.let { (photos, cover) -> album.copy(photos = photos, cover = cover) } ?: album
        }
    }

    private fun HomebaseFile.album(): PhotoAlbum? {
        val id = fileMetadata.appData.uniqueId ?: return null
        val content = fileMetadata.appData.content ?: return null
        val name = try {
            OdinSystemSerializer.deserialize<AlbumContent>(content).name
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "album $id won't parse" }
            return null
        }
        return PhotoAlbum(id, name)
    }

    private companion object {
        const val TAG = "PhotoAlbums"
        const val MAX_NAME = 100
    }
}
