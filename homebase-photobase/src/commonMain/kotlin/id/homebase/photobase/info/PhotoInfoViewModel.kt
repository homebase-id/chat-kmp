package id.homebase.photobase.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.api.client.peer.PeerFileByGlobalTransitProvider
import id.homebase.api.common.OdinId
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.readImageMetadata
import id.homebase.api.media.MediaLocation
import id.homebase.api.video.VideoPrefetchDriveAccess
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.location.LocationPreferences
import id.homebase.photobase.gallery.PhotoGalleryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** What the Info sheet reads beyond the photo's header. */
interface PhotoInfoReader {
    /** Whether the map and the place's name may be looked up online: the Maps setting isn't None. */
    val mapsOnline: Boolean

    /** The EXIF of [item]'s original, read from [owner]'s drive over peer when given; null for a video. */
    suspend fun camera(item: PhotoGalleryItem, owner: OdinId?): ImageMetadata?

    /** The place's name; null when it has none or the lookup failed. */
    suspend fun place(latitude: Double, longitude: Double): String?

    suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray?
}

/** The camera details from the whole original's EXIF, and the place's name and map tiles from OpenStreetMap. */
class PhotoInfoDrives(
    private val drive: VideoPrefetchDriveAccess,
    private val peer: PeerFileByGlobalTransitProvider,
    private val places: LocationPreviewProvider,
    private val locationPreferences: LocationPreferences,
) : PhotoInfoReader {
    override val mapsOnline: Boolean get() = locationPreferences.mapProvider.value == LocationMapProvider.OpenStreetMap

    override suspend fun camera(item: PhotoGalleryItem, owner: OdinId?): ImageMetadata? {
        val payload = item.payload
        if (!payload.isImage()) return null
        val keyHeader = payload.keyHeaderIn(item.keyHeader)
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
    val fileId: Uuid,
    val location: MediaLocation?,
    val camera: InfoPart<ImageMetadata> = InfoPart.Loading,
    /** Read(null) when the place has no name, or isn't looked up because maps are offline. */
    val place: InfoPart<String> = InfoPart.Loading,
)

/** The open sheet's photo: its camera details and place, each read on its own, so one failing leaves the other. */
class PhotoInfoViewModel(private val reader: PhotoInfoReader) : ViewModel() {
    private val _uiState = MutableStateFlow<PhotoInfoUiState?>(null)
    /** Null while no sheet is open. */
    val uiState: StateFlow<PhotoInfoUiState?> = _uiState.asStateFlow()

    private var reading: Job? = null

    /** Reads [item]'s details, dropping what was being read for the photo before. */
    fun show(item: PhotoGalleryItem, owner: OdinId?) {
        reading?.cancel()
        val location = item.location()
        _uiState.value = PhotoInfoUiState(item.fileId, location, place = if (reader.mapsOnline) InfoPart.Loading else InfoPart.Read(null))
        reading = viewModelScope.launch {
            launch {
                val camera = read(item.fileId, "camera details") { reader.camera(item, owner) }
                update(item.fileId) { it.copy(camera = camera) }
            }
            location?.takeIf { reader.mapsOnline }?.let { at ->
                launch {
                    val place = read(item.fileId, "place") { reader.place(at.latitude, at.longitude) }
                    update(item.fileId) { it.copy(place = place) }
                }
            }
        }
    }

    /** Stops the reads: a download nobody's looking at isn't worth finishing. */
    fun close() {
        reading?.cancel()
        _uiState.value = null
    }

    // A read finishing as it's cancelled mustn't land on the next photo's sheet.
    private fun update(fileId: Uuid, change: (PhotoInfoUiState) -> PhotoInfoUiState) =
        _uiState.update { state -> state?.takeIf { it.fileId == fileId }?.let(change) ?: state }

    private suspend fun <T> read(fileId: Uuid, what: String, block: suspend () -> T?): InfoPart<T> =
        try {
            InfoPart.Read(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "$fileId's $what not read" }
            InfoPart.Failed
        }

    private companion object {
        const val TAG = "PhotoInfoViewModel"
    }
}
