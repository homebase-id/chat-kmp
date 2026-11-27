package id.homebase.photobase.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.api.client.payload.PayloadLocation
import id.homebase.api.client.payload.PayloadReader
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.readImageMetadata
import id.homebase.api.media.MediaLocation
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
import kotlinx.datetime.UtcOffset
import kotlin.uuid.Uuid

/** What the Info sheet reads beyond the photo's header, and where it saves a change to the header. */
interface PhotoInfoSource {
    /** Whether the map and the place's name may be looked up online: the Maps setting isn't None. */
    val mapsOnline: Boolean

    /** The EXIF of [item]'s original; null for a video. */
    suspend fun camera(item: PhotoGalleryItem): ImageMetadata?

    /** The place's name; null when it has none or the lookup failed. */
    suspend fun place(latitude: Double, longitude: Double): String?

    suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray?

    suspend fun save(item: PhotoGalleryItem, edit: PhotoEdit)
}

/** The camera details from the whole original's EXIF, the place's name and map tiles from OpenStreetMap. */
class DrivePhotoInfoSource(
    private val payloads: PayloadReader,
    private val places: LocationPreviewProvider,
    private val locationPreferences: LocationPreferences,
    private val editor: PhotoHeaderEditor,
) : PhotoInfoSource {
    override val mapsOnline: Boolean get() = locationPreferences.mapProvider.value == LocationMapProvider.OpenStreetMap

    override suspend fun camera(item: PhotoGalleryItem): ImageMetadata? {
        val payload = item.payload
        if (!payload.isImage()) return null
        val original = payloads.bytes(PayloadLocation.Own(item.driveId, item.fileId), payload.key, payload.keyHeaderIn(item.keyHeader)) ?: return null
        return withContext(Dispatchers.Default) { readImageMetadata(original.bytes) }
    }

    override suspend fun place(latitude: Double, longitude: Double): String? = places.reverseGeocode(latitude, longitude)

    override suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray? = places.getTilePng(zoom, x, y)

    override suspend fun save(item: PhotoGalleryItem, edit: PhotoEdit) = editor.save(item.driveId, item.fileId, edit)
}

sealed interface InfoPart<out T> {
    data object Loading : InfoPart<Nothing>
    data class Read<T>(val value: T?) : InfoPart<T>
    data object Failed : InfoPart<Nothing>
}

data class PhotoInfoUiState(
    /** The open sheet's photo; null while none is open. */
    val fileId: Uuid? = null,
    val capturedAtMs: Long = 0,
    /** The offset a time set by hand was set at; it wins over the EXIF's. */
    val offsetByHand: UtcOffset? = null,
    val location: MediaLocation? = null,
    val camera: InfoPart<ImageMetadata> = InfoPart.Loading,
    /** Read(null) when the place has no name, or isn't looked up because maps are offline. */
    val place: InfoPart<String> = InfoPart.Loading,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

/** The camera details and place are read each on its own, so one failing leaves the other. */
class PhotoInfoViewModel(private val source: PhotoInfoSource) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotoInfoUiState())
    val uiState: StateFlow<PhotoInfoUiState> = _uiState.asStateFlow()

    private var item: PhotoGalleryItem? = null
    private var reading: Job? = null
    private var placing: Job? = null

    /** Drops what was being read for the photo before. */
    fun show(item: PhotoGalleryItem) {
        reading?.cancel()
        placing?.cancel()
        this.item = item
        val content = item.photoContent()
        val location = content?.location
        _uiState.update {
            PhotoInfoUiState(
                fileId = item.fileId,
                capturedAtMs = item.capturedAtMs,
                offsetByHand = content?.offsetSeconds?.let { UtcOffset(seconds = it) },
                location = location,
                place = if (source.mapsOnline) InfoPart.Loading else InfoPart.Read(null),
            )
        }
        reading = viewModelScope.launch {
            val camera = read(item.fileId, "camera details") { source.camera(item) }
            update(item.fileId) { it.copy(camera = camera) }
        }
        lookUpPlace(item.fileId, location)
    }

    fun save(edit: PhotoEdit) {
        val item = item ?: return
        if (_uiState.value.saving) return
        update(item.fileId) { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val saved = read(item.fileId, "edit") { source.save(item, edit) } is InfoPart.Read
            update(item.fileId) { state ->
                val done = state.copy(saving = false, saveFailed = !saved)
                when {
                    !saved -> done
                    edit is PhotoEdit.Time -> done.copy(capturedAtMs = edit.at.toEpochMilliseconds(), offsetByHand = edit.offset)
                    edit is PhotoEdit.Place -> done.copy(location = edit.at, place = if (source.mapsOnline) InfoPart.Loading else InfoPart.Read(null))
                    else -> done
                }
            }
            if (saved && edit is PhotoEdit.Place) lookUpPlace(item.fileId, edit.at)
        }
    }

    // A download nobody's looking at isn't worth finishing.
    fun close() {
        reading?.cancel()
        placing?.cancel()
        item = null
        _uiState.update { PhotoInfoUiState() }
    }

    private fun lookUpPlace(fileId: Uuid, at: MediaLocation?) {
        placing?.cancel()
        if (at == null || !source.mapsOnline) return
        placing = viewModelScope.launch {
            val place = read(fileId, "place") { source.place(at.latitude, at.longitude) }
            update(fileId) { it.copy(place = place) }
        }
    }

    // A read finishing as it's cancelled mustn't land on the next photo's sheet.
    private fun update(fileId: Uuid, change: (PhotoInfoUiState) -> PhotoInfoUiState) =
        _uiState.update { state -> if (state.fileId == fileId) change(state) else state }

    private suspend fun <T> read(fileId: Uuid, what: String, block: suspend () -> T?): InfoPart<T> =
        try {
            InfoPart.Read(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "$fileId's $what failed" }
            InfoPart.Failed
        }

    private companion object {
        const val TAG = "PhotoInfoViewModel"
    }
}
