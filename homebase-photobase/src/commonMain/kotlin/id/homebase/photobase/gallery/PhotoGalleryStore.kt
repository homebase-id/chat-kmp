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
import kotlin.uuid.Uuid

/** The gallery's photos and days, read from the device's copy of the Photos drive, which the drive sync keeps current. */
class PhotoGalleryStore internal constructor(
    private val db: DatabaseManager,
    private val identityId: suspend () -> Uuid,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) : PhotoGallerySource {
    override suspend fun photosOnDays(fromDay: Int, toDay: Int): Map<Int, List<PhotoGalleryItem>> =
        db.photoDays.headersOnDays(identityId(), photosDriveId, PhotobaseProtocol.PhotoFileType.toLong(), fromDay, toDay)
            .mapNotNull { row -> row.toGalleryItem()?.let { row.day to it } }
            .groupBy({ it.first }, { it.second })

    suspend fun dayCounts(): List<DayCount> = db.photoDays.dayCounts(identityId(), photosDriveId, PhotobaseProtocol.PhotoFileType.toLong())

    suspend fun dayOf(userDate: Long): Int? = db.photoDays.dayOf(userDate)

    suspend fun photosOn(day: Int): Int = db.photoDays.photosOn(identityId(), photosDriveId, PhotobaseProtocol.PhotoFileType.toLong(), day)

    /** Whose library and which time zone the day counts are for. */
    suspend fun daysCountedFor(): String = "${identityId()} ${TimeZone.currentSystemDefault().id}"

    /** Photobase is gone from the server: its records and where the drive's sync got to. Unmount the drive first. */
    suspend fun forget() {
        db.driveMainIndex.deleteDrive(identityId(), photosDriveId)
    }
}

fun photoGalleryStore(db: DatabaseManager, credentials: CredentialsManager) =
    PhotoGalleryStore(db, { credentials.requireActiveCredentials().getIdentityId() })

// One header that won't parse loses that photo, not the day.
private fun DayHeader.toGalleryItem(): PhotoGalleryItem? = try {
    OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader).toGalleryItem()
} catch (e: Exception) {
    Logger.e(tag = "PhotoGalleryStore", throwable = e) { "a Photos header on day $day won't parse" }
    null
}
