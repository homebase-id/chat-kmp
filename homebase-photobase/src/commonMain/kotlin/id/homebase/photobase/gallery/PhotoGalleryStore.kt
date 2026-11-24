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
import kotlin.uuid.Uuid

/**
 * The photos and days of one [shelf], read from the device's copy of the Photos drive, which the drive sync keeps
 * current.
 */
class PhotoGalleryStore internal constructor(
    private val db: DatabaseManager,
    private val identityId: suspend () -> Uuid,
    val shelf: PhotoShelf = PhotoShelf.Library,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) : PhotoGallerySource {
    private val status = shelf.status.value.toLong()

    // Called from each gallery view model's own reading thread.
    private val unparsable = mutableSetOf<Int>()
    private val unparsableLock = Mutex()

    override suspend fun photosOnDays(fromDay: Int, toDay: Int): Map<Int, List<PhotoGalleryItem>> =
        db.photoDays.headersOnDays(identityId(), photosDriveId, status, fromDay, toDay)
            .mapNotNull { row -> row.toGalleryItem()?.let { row.day to it } }
            .groupBy({ it.first }, { it.second })

    // One header that won't parse loses that photo, not the day; it's logged once, not on every read of its day.
    private suspend fun DayHeader.toGalleryItem(): PhotoGalleryItem? = try {
        OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader).toGalleryItem()
    } catch (e: Exception) {
        if (unparsableLock.withLock { unparsable.add(jsonHeader.hashCode()) }) {
            Logger.e(tag = "PhotoGalleryStore", throwable = e) { "a Photos header on day $day won't parse" }
        }
        null
    }

    suspend fun dayCounts(): List<DayCount> = db.photoDays.dayCounts(identityId(), photosDriveId, status)

    suspend fun dayOf(userDate: Long): Int? = db.photoDays.dayOf(userDate)

    suspend fun photosOn(day: Int): Int = db.photoDays.photosOn(identityId(), photosDriveId, status, day)

    /** The device's copy of those of [fileIds] it holds, on any shelf; one that won't parse is left out. */
    suspend fun filesOf(fileIds: Collection<Uuid>): List<HomebaseFile> =
        db.photoDays.headersOf(identityId(), photosDriveId, fileIds).mapNotNull(::parsedOrNull)

    /** The device's copy of every photo on this shelf. */
    suspend fun filesOnShelf(): List<HomebaseFile> =
        db.photoDays.headersOnShelf(identityId(), photosDriveId, status).mapNotNull(::parsedOrNull)

    private fun parsedOrNull(jsonHeader: String): HomebaseFile? =
        runCatching { OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader) }.getOrNull()

    /** Whose library and which time zone the day counts are for. */
    suspend fun daysCountedFor(): String = "${identityId()} ${TimeZone.currentSystemDefault().id}"

    /** Photobase is gone from the server: its records and where the drive's sync got to. Unmount the drive first. */
    suspend fun forget() {
        db.driveMainIndex.deleteDrive(identityId(), photosDriveId)
    }
}

fun photoGalleryStore(db: DatabaseManager, credentials: CredentialsManager, shelf: PhotoShelf = PhotoShelf.Library) =
    PhotoGalleryStore(db, { credentials.requireActiveCredentials().getIdentityId() }, shelf)
