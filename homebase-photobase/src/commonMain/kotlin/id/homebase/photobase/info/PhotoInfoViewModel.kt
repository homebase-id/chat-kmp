package id.homebase.photobase.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.common.OdinId
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.readImageMetadata
import id.homebase.api.video.VideoPrefetchDriveAccess
import id.homebase.photobase.gallery.PhotoGalleryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Info sheet reads beyond the photo's header. */
interface PhotoInfoReader {
    /** The EXIF of [item]'s original, read from [owner]'s drive over peer when given; null for a video. */
    suspend fun camera(item: PhotoGalleryItem, owner: OdinId?): ImageMetadata?

    /** The place's name; null when it has none or the lookup failed. */
    suspend fun place(latitude: Double, longitude: Double): String?

    suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray?
}

/**
 * The camera details from the original's EXIF, and the place's name and map tiles from OpenStreetMap, each read when
 * the sheet opens: a stand-in until how they load is decided.
 */
class PhotoInfoDrives(
    private val drive: VideoPrefetchDriveAccess,
    private val peer: PeerFileByGlobalTransitProvider,
    private val places: LocationPreviewProvider,
) : PhotoInfoReader {
    override suspend fun camera(item: PhotoGalleryItem, owner: OdinId?): ImageMetadata? {
        val payload = item.payload
        if (!payload.isImage()) return null
        // Each payload has its own IV.
        val keyHeader = payload.iv?.let { KeyHeader(iv = it.bytes, aesKey = item.keyHeader.aesKey) } ?: item.keyHeader
        val original = if (owner == null) {
            drive.getPayloadBytesDecrypted(item.driveId, item.fileId, payload.key, keyHeader, null, null, null)
        } else {
            val globalTransitId = item.globalTransitId ?: return null
            peer.getPayloadOverPeerByGlobalTransitId(owner, item.driveId, globalTransitId, payload.key, keyHeader)
        } ?: return null
        return withContext(Dispatchers.Default) { readImageMetadata(original.bytes) }
    }

    override suspend fun place(latitude: Double, longitude: Double): String? = places.reverseGeocode(latitude, longitude)

    override suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray? = places.getTilePng(zoom, x, y)
}

/** A part of the sheet read on demand. */
sealed interface InfoPart<out T> {
    data object Loading : InfoPart<Nothing>
    data class Read<T>(val value: T?) : InfoPart<T>
    data object Failed : InfoPart<Nothing>
}

data class PhotoInfoUiState(
    val camera: InfoPart<ImageMetadata> = InfoPart.Loading,
    val place: InfoPart<String> = InfoPart.Loading,
)

/** [item]'s camera details and place, each read once and on its own: one failing leaves the other. */
class PhotoInfoViewModel(
    private val item: PhotoGalleryItem,
    private val owner: OdinId?,
    val reader: PhotoInfoReader,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotoInfoUiState(place = if (item.location() == null) InfoPart.Read(null) else InfoPart.Loading))
    val uiState: StateFlow<PhotoInfoUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val camera = read("camera details") { reader.camera(item, owner) }
            _uiState.update { it.copy(camera = camera) }
        }
        item.location()?.let { at ->
            viewModelScope.launch {
                val place = read("place") { reader.place(at.latitude, at.longitude) }
                _uiState.update { it.copy(place = place) }
            }
        }
    }

    private suspend fun <T> read(what: String, block: suspend () -> T?): InfoPart<T> =
        try {
            InfoPart.Read(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "${item.fileId}'s $what not read" }
            InfoPart.Failed
        }

    private companion object {
        const val TAG = "PhotoInfoViewModel"
    }
}
