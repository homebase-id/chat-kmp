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

    /** The place's map and name, as a location shows in chat; a blank name when it has none. Throws when offline. */
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

    // The name asked first, where a network failure throws: getLocationPreview falls back to the coordinates unsaid.
    override suspend fun preview(latitude: Double, longitude: Double): LocationPreview {
        val name = places.reverseGeocode(latitude, longitude)
        // With no name, not kept: the provider's cache is everyone's, and a name it never looked up mustn't be in it.
        val preview = places.getLocationPreview(latitude, longitude, knownAddress = name ?: coordinates(MediaLocation(latitude, longitude)), keep = name != null)
        return if (name == null) preview.copy(address = "") else preview
    }

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
    /** userDate was set by hand, and wins over the original's EXIF time. */
    val timeByHand: Boolean = false,
    /** The zone a time set by hand was set in; null when the photo had none. */
    val offsetByHand: UtcOffset? = null,
    val location: MediaLocation? = null,
    /** The place was set by hand: the original's GPS details don't describe it. */
    val placeByHand: Boolean = false,
    val camera: InfoPart<ImageMetadata> = InfoPart.Loading,
    /** Read(null) when it isn't looked up because maps are offline. */
    val place: InfoPart<LocationPreview> = InfoPart.Loading,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

/** When and where, as the photo's header says it. */
internal data class HeaderFacts(
    val capturedAtMs: Long,
    val timeByHand: Boolean,
    val offsetByHand: UtcOffset?,
    val location: MediaLocation?,
    val placeByHand: Boolean,
)

/** The camera details and place are read each on its own, so one failing leaves the other. */
class PhotoInfoViewModel(private val source: PhotoInfoSource) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotoInfoUiState())
    val uiState: StateFlow<PhotoInfoUiState> = _uiState.asStateFlow()

    private var item: PhotoGalleryItem? = null
    // What a save set, until the gallery's copy of the header is newer than the one it changed: reopened before
    // then, it still shows; after, the gallery's wins, whoever changed it since.
    private val saved = mutableMapOf<Uuid, Pair<Long, HeaderFacts>>()
    private var reading: Job? = null
    private var placing: Job? = null

    /** Drops what was being read for the photo before. */
    fun show(item: PhotoGalleryItem) {
        reading?.cancel()
        placing?.cancel()
        this.item = item
        val ours = saved[item.fileId]
        val shown = ours?.takeIf { (changedAtMs, _) -> item.changedAtMs <= changedAtMs }?.second
            ?: item.headerFacts().also { saved.remove(item.fileId) }
        _uiState.update { PhotoInfoUiState(fileId = item.fileId, place = placeUnread()).with(shown) }
        reading = viewModelScope.launch {
            val camera = read(item.fileId, "camera details") { source.camera(item) }
            update(item.fileId) { it.copy(camera = camera) }
        }
        lookUpPlace(item.fileId, shown.location)
    }

    /**
     * The gallery's newer copy of the photo shown, so a save is stamped with the header it changes; a change made
     * elsewhere shows at once, while one of ours still waiting to sync keeps showing.
     */
    fun refresh(item: PhotoGalleryItem) {
        val held = this.item?.takeIf { it.fileId == item.fileId } ?: return
        this.item = item
        if (item.changedAtMs <= held.changedAtMs || _uiState.value.saving) return
        val ours = saved[item.fileId]
        if (ours != null && item.changedAtMs <= ours.first) return
        saved.remove(item.fileId)
        val facts = item.headerFacts()
        val moved = facts.location != _uiState.value.location
        update(item.fileId) { it.with(facts).let { state -> if (moved) state.copy(place = placeUnread()) else state } }
        if (moved) lookUpPlace(item.fileId, facts.location)
    }

    /** [onSaved] runs once the server has it. */
    fun save(edit: PhotoEdit, onSaved: () -> Unit = {}) {
        val item = item ?: return
        val before = _uiState.value.takeIf { it.fileId == item.fileId && !it.saving }?.facts() ?: return
        update(item.fileId) { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val ok = read(item.fileId, "edit") { source.save(item, edit) } is InfoPart.Read
            // A newer copy that came while it saved is the one the edit lands on.
            val base = this@PhotoInfoViewModel.item?.takeIf { it.fileId == item.fileId && it.changedAtMs > item.changedAtMs }?.headerFacts() ?: before
            // Kept whatever the sheet shows by now: closed or on another photo, this one reopens with it.
            val after = if (!ok) base else when (edit) {
                is PhotoEdit.Time -> base.copy(capturedAtMs = edit.at.toEpochMilliseconds(), timeByHand = true, offsetByHand = edit.offset)
                is PhotoEdit.Place -> base.copy(location = edit.at, placeByHand = true)
            }
            if (ok) {
                saved[item.fileId] = (this@PhotoInfoViewModel.item?.takeIf { it.fileId == item.fileId } ?: item).changedAtMs to after
                onSaved()
            }
            update(item.fileId) { state ->
                state.copy(saving = false, saveFailed = !ok, place = if (ok && edit is PhotoEdit.Place) placeUnread() else state.place).with(after)
            }
            if (ok && edit is PhotoEdit.Place && _uiState.value.fileId == item.fileId) lookUpPlace(item.fileId, edit.at)
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
    }
}

private const val MAX_OFFSET = 18 * 3600

private fun PhotoInfoUiState.facts() = HeaderFacts(capturedAtMs, timeByHand, offsetByHand, location, placeByHand)

private fun PhotoInfoUiState.with(facts: HeaderFacts) = copy(
    capturedAtMs = facts.capturedAtMs,
    timeByHand = facts.timeByHand,
    offsetByHand = facts.offsetByHand,
    location = facts.location,
    placeByHand = facts.placeByHand,
)

internal fun PhotoGalleryItem.headerFacts(): HeaderFacts {
    val content = photoContent()
    return HeaderFacts(
        capturedAtMs = capturedAtMs,
        timeByHand = content?.timeSetAtMs != null,
        // A header from another app version can hold anything; UtcOffset throws outside ±18 h.
        offsetByHand = content?.offsetSeconds?.takeIf { it in -MAX_OFFSET..MAX_OFFSET }?.let { UtcOffset(seconds = it) },
        location = content?.location,
        placeByHand = content?.placeByHand == true,
    )
}
