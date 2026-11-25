package id.homebase.photobase.gallery

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.DayCount
import id.homebase.api.sync.database.DayHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import co.touchlab.kermit.Logger
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * The photos and days of one [gallery], read from the device's copy of the Photos drive, which the drive sync keeps
 * current.
 */
class PhotoGalleryStore internal constructor(
    private val db: DatabaseManager,
    private val identityId: suspend () -> Uuid,
    val gallery: PhotoGallery = PhotoGallery.Library,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) : PhotoGallerySource {
    private val filter = gallery.filter

    // Called from each gallery view model's own reading thread.
    private val unparsable = mutableSetOf<Int>()
    private val unparsableLock = Mutex()

    override suspend fun photosOnDays(fromDay: Int, toDay: Int): Map<Int, List<PhotoGalleryItem>> =
        db.photoDays.headersOnDays(identityId(), photosDriveId, filter, fromDay, toDay)
            .mapNotNull { row -> row.toGalleryItem()?.let { row.day to it } }
            .groupBy({ it.first }, { it.second })

    private suspend fun DayHeader.toGalleryItem(): PhotoGalleryItem? = parsed(jsonHeader)?.toGalleryItem()

    // One header that won't parse loses that photo, not the day; it's logged once, not on every read of its day.
    private suspend fun parsed(jsonHeader: String): HomebaseFile? = try {
        OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
    } catch (e: Exception) {
        if (unparsableLock.withLock { unparsable.add(jsonHeader.hashCode()) }) {
            Logger.e(tag = "PhotoGalleryStore", throwable = e) { "a Photos header won't parse" }
        }
        null
    }

    suspend fun dayCounts(): List<DayCount> = db.photoDays.dayCounts(identityId(), photosDriveId, filter)

    suspend fun dayOf(userDate: Long): Int? = db.photoDays.dayOf(userDate)

    suspend fun photosOn(day: Int): Int = db.photoDays.photosOn(identityId(), photosDriveId, filter, day)

    /** The device's copy of those of [fileIds] it holds, on any shelf; one that won't parse is left out. */
    suspend fun filesOf(fileIds: Collection<Uuid>): List<HomebaseFile> =
        parsedOffMain(db.photoDays.headersOf(identityId(), photosDriveId, fileIds))

    /** The device's copy of every photo on [shelf]. */
    suspend fun filesOn(shelf: PhotoShelf): List<HomebaseFile> =
        parsedOffMain(db.photoDays.headersOnShelf(identityId(), photosDriveId, shelf.status.value.toLong()))

    /** The device's copy of every album record. */
    suspend fun albumRecords(): List<HomebaseFile> =
        parsedOffMain(db.photoDays.headersOfType(identityId(), photosDriveId, PhotobaseProtocol.AlbumFileType.toLong()))

    /** For each of [albums] that holds a photo in the library or the archive: how many, and the newest. */
    suspend fun albumCovers(albums: Collection<Uuid>): Map<Uuid, AlbumCover> =
        db.photoDays.taggedCovers(identityId(), photosDriveId, albums, TAGGED_STATUSES)
            .associate { it.tag to AlbumCover(it.photos, parsed(it.newestHeader)?.toGalleryItem()) }

    /** Every photo carrying [tag], binned and deleted ones too. */
    suspend fun taggedFileIds(tag: Uuid): List<Uuid> = db.photoDays.taggedFileIds(identityId(), photosDriveId, tag)

    // A bin action asks from Main; a few thousand headers take seconds to parse.
    private suspend fun parsedOffMain(headers: List<String>): List<HomebaseFile> =
        withContext(Dispatchers.Default) { headers.mapNotNull { parsed(it) } }

    /** Whose library and which time zone the day counts are for. */
    suspend fun daysCountedFor(): String = "${identityId()} ${TimeZone.currentSystemDefault().id}"

    /** Photobase is gone from the server: its records and where the drive's sync got to. Unmount the drive first. */
    suspend fun forget() {
        db.driveMainIndex.deleteDrive(identityId(), photosDriveId)
    }
}

class AlbumCover(val photos: Int, val cover: PhotoGalleryItem?)

fun photoGalleryStore(db: DatabaseManager, credentials: CredentialsManager, gallery: PhotoGallery) =
    PhotoGalleryStore(db, { credentials.requireActiveCredentials().getIdentityId() }, gallery)
