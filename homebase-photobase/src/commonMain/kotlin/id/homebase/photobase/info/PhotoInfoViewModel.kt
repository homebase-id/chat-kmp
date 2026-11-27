package id.homebase.photobase.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.location.LocationPreview
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

    /** The place's map and name, as a location shows in chat; its name is the coordinates when it has none. */
    suspend fun preview(latitude: Double, longitude: Double): LocationPreview

    /** A point's address, for the place picker; null when it has none. */
    suspend fun address(latitude: Double, longitude: Double): String?

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

    override suspend fun preview(latitude: Double, longitude: Double): LocationPreview = places.getLocationPreview(latitude, longitude)

    override suspend fun address(latitude: Double, longitude: Double): String? = places.reverseGeocode(latitude, longitude)

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
    /** Read(null) when it isn't looked up because maps are offline. */
    val place: InfoPart<LocationPreview> = InfoPart.Loading,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

/** The camera details and place are read each on its own, so one failing leaves the other. */
class PhotoInfoViewModel(private val source: PhotoInfoSource) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotoInfoUiState())
    val uiState: StateFlow<PhotoInfoUiState> = _uiState.asStateFlow()

    private var item: PhotoGalleryItem? = null
    // What a save set, until the gallery's copy of the header catches up with it: reopened before then, it still shows.
    private val saved = mutableMapOf<Uuid, Saved>()

    private data class Saved(val capturedAtMs: Long, val offsetByHand: UtcOffset?, val location: MediaLocation?)
    private var reading: Job? = null
    private var placing: Job? = null

    /** Drops what was being read for the photo before. */
    fun show(item: PhotoGalleryItem) {
        reading?.cancel()
        placing?.cancel()
        this.item = item
        val content = item.photoContent()
        // A header from another app version can hold anything; UtcOffset throws outside ±18 h.
        val header = Saved(item.capturedAtMs, content?.offsetSeconds?.takeIf { it in -MAX_OFFSET..MAX_OFFSET }?.let { UtcOffset(seconds = it) }, content?.location)
        val shown = saved[item.fileId]?.takeIf { it != header } ?: header.also { saved.remove(item.fileId) }
        val location = shown.location
        _uiState.update {
            PhotoInfoUiState(
                fileId = item.fileId,
                capturedAtMs = shown.capturedAtMs,
                offsetByHand = shown.offsetByHand,
                location = location,
                place = placeUnread(),
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
            val ok = read(item.fileId, "edit") { source.save(item, edit) } is InfoPart.Read
            update(item.fileId) { state ->
                val done = state.copy(saving = false, saveFailed = !ok)
                if (!ok) {
                    done
                } else {
                    when (edit) {
                        is PhotoEdit.Time -> done.copy(capturedAtMs = edit.at.toEpochMilliseconds(), offsetByHand = edit.offset)
                        is PhotoEdit.Place -> done.copy(location = edit.at, place = placeUnread())
                    }.also { saved[item.fileId] = Saved(it.capturedAtMs, it.offsetByHand, it.location) }
                }
            }
            if (ok && edit is PhotoEdit.Place) lookUpPlace(item.fileId, edit.at)
        }
    }

    // A download nobody's looking at isn't worth finishing.
    fun close() {
        reading?.cancel()
        placing?.cancel()
        item = null
        _uiState.update { PhotoInfoUiState() }
    }

    // Read(null) with maps offline: nothing will be looked up.
    private fun placeUnread(): InfoPart<LocationPreview> = if (source.mapsOnline) InfoPart.Loading else InfoPart.Read(null)

    private fun lookUpPlace(fileId: Uuid, at: MediaLocation?) {
        placing?.cancel()
        if (at == null || !source.mapsOnline) return
        placing = viewModelScope.launch {
            val place = read(fileId, "place") { source.preview(at.latitude, at.longitude) }
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
        const val MAX_OFFSET = 18 * 3600
    }
}
