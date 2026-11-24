package id.homebase.photobase.gallery

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.sync.database.CountedDay
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.DayCount
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.uuid.Uuid

/** The gallery's pages, read from the device's copy of the Photos drive, which the drive sync keeps current. */
class PhotoGalleryStore internal constructor(
    private val db: DatabaseManager,
    private val identityId: suspend () -> Uuid,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) : PhotoGalleryPageSource {
    // Parsing a page of headers doesn't belong on the view model's Main.
    override suspend fun page(cursor: String?, newestFirst: Boolean): PhotoGalleryPage = withContext(Dispatchers.Default) {
        val result = QueryBatch(identityId()).queryBatchAsync(
            dbm = db,
            driveId = photosDriveId,
            noOfItems = galleryPageSize(cursor),
            cursor = cursor?.let { QueryBatchCursor.fromJson(it) },
            sortOrder = if (newestFirst) QueryBatchSortOrder.NewestFirst else QueryBatchSortOrder.OldestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            fileState = FileStateFilter.Active,
            filetypesAnyOf = listOf(PhotobaseProtocol.PhotoFileType),
        )
        PhotoGalleryPage(
            result.records.mapNotNull { it.toGalleryItem() },
            nextCursor = result.cursor.toJson().takeIf { result.hasMoreRows },
        )
    }

    suspend fun dayCounts(): List<DayCount> = db.photoDays.dayCounts(identityId(), photosDriveId, PhotobaseProtocol.PhotoFileType.toLong())

    suspend fun dayOf(userDate: Long): CountedDay? = db.photoDays.dayOf(identityId(), photosDriveId, PhotobaseProtocol.PhotoFileType.toLong(), userDate)

    /** Whose library and which time zone the day counts are for. */
    suspend fun daysCountedFor(): String = "${identityId()} ${TimeZone.currentSystemDefault().id}"

    /** Photobase is gone from the server: its records and where the drive's sync got to. Unmount the drive first. */
    suspend fun forget() {
        db.driveMainIndex.deleteDrive(identityId(), photosDriveId)
    }
}

fun photoGalleryStore(db: DatabaseManager, credentials: CredentialsManager) =
    PhotoGalleryStore(db, { credentials.requireActiveCredentials().getIdentityId() })
