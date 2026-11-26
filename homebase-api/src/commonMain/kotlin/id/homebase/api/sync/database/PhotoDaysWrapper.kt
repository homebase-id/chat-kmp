package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.uuid.Uuid

/** A drive's photos counted per local day, for a gallery that lays out the whole library without reading it. */
class PhotoDaysWrapper(
    driver: SqlDriver,
    driveMainIndexAdapter: DriveMainIndex.Adapter,
    driveLocalTagIndexAdapter: DriveLocalTagIndex.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val queries = PhotoDaysQueries(driver, driveMainIndexAdapter, driveLocalTagIndexAdapter)

    /** Newest day first. */
    suspend fun dayCounts(identityId: Uuid, driveId: Uuid, filter: PhotoFilter): List<DayCount> =
        databaseManager.readValue("photoDays.dayCounts") {
            val counted = { day: Long?, photos: Long -> DayCount(day!!.toInt(), photos.toInt()) }
            when (filter) {
                is PhotoFilter.Shelf -> queries.dayCounts(identityId, driveId, filter.archivalStatus, counted)
                is PhotoFilter.Tagged -> queries.taggedDayCounts(identityId = identityId, driveId = driveId, archivalStatuses = filter.archivalStatuses, tagId = filter.tag, mapper = counted)
            }.executeAsList()
        }

    /** The day a photo taken at [userDate] falls on, or null for a date SQLite has no day for. */
    suspend fun dayOf(userDate: Long): Int? =
        databaseManager.readValue("photoDays.dayNumberOf") { queries.dayNumberOf(userDate).executeAsOne() }.toInt().takeIf { it != Int.MIN_VALUE }

    suspend fun photosOn(identityId: Uuid, driveId: Uuid, filter: PhotoFilter, day: Int): Int =
        databaseManager.readValue("photoDays.photosOnDay") {
            when (filter) {
                is PhotoFilter.Shelf -> queries.photosOnDay(identityId, driveId, filter.archivalStatus, day.toLong())
                is PhotoFilter.Tagged -> queries.taggedPhotosOnDay(identityId = identityId, driveId = driveId, archivalStatuses = filter.archivalStatuses, day = day.toLong(), tagId = filter.tag)
            }.executeAsOne()
        }.toInt()

    /** The headers of days [fromDay] to [toDay], newest first, each with its day. */
    suspend fun headersOnDays(identityId: Uuid, driveId: Uuid, filter: PhotoFilter, fromDay: Int, toDay: Int): List<DayHeader> =
        databaseManager.readValue("photoDays.photosOnDays") {
            val row = { day: Long, jsonHeader: String -> DayHeader(day.toInt(), jsonHeader) }
            when (filter) {
                is PhotoFilter.Shelf -> queries.photosOnDays(identityId, driveId, filter.archivalStatus, fromDay.toLong(), toDay.toLong(), row)
                is PhotoFilter.Tagged -> queries.taggedPhotosOnDays(
                    identityId = identityId, driveId = driveId, archivalStatuses = filter.archivalStatuses,
                    fromDay = fromDay.toLong(), toDay = toDay.toLong(), tagId = filter.tag, mapper = row,
                )
            }.executeAsList()
        }

    /** The headers of those of [fileIds] the drive holds, on any shelf, in no order. */
    suspend fun headersOf(identityId: Uuid, driveId: Uuid, fileIds: Collection<Uuid>): List<String> =
        // SQLite caps the variables a statement binds.
        fileIds.chunked(IDS_PER_READ).flatMap { chunk ->
            databaseManager.readValue("photoDays.headersOf") { queries.headersOf(identityId, driveId, chunk).executeAsList() }
        }

    /** The headers of every photo with [archivalStatus], in no order. */
    suspend fun headersOnShelf(identityId: Uuid, driveId: Uuid, archivalStatus: Long): List<String> =
        databaseManager.readValue("photoDays.headersOnShelf") { queries.headersOnShelf(identityId, driveId, archivalStatus).executeAsList() }

    /** The headers of the drive's active files of [fileType] that belong to no group. */
    suspend fun headersOfType(identityId: Uuid, driveId: Uuid, fileType: Long): List<String> =
        databaseManager.readValue("photoDays.headersOfType") { queries.headersOfType(identityId, driveId, fileType).executeAsList() }

    /** For each of [tags] that a photo with one of [archivalStatuses] carries: how many do, and the newest one's header. */
    suspend fun taggedCovers(identityId: Uuid, driveId: Uuid, tags: Collection<Uuid>, archivalStatuses: List<Long>): List<TagCover> =
        tags.chunked(IDS_PER_READ).flatMap { chunk ->
            databaseManager.readValue("photoDays.taggedCovers") {
                queries.taggedCovers(identityId, driveId, chunk, archivalStatuses) { tag, photos, jsonHeader, _ -> TagCover(tag, photos.toInt(), jsonHeader) }
                    .executeAsList()
            }
        }

    /** Every file carrying [tag], in any state. */
    suspend fun taggedFileIds(identityId: Uuid, driveId: Uuid, tag: Uuid): List<Uuid> =
        databaseManager.readValue("photoDays.taggedFileIds") { queries.taggedFileIds(identityId, driveId, tag).executeAsList() }

    private companion object {
        const val IDS_PER_READ = 500
    }
}

/** The photos a gallery shows, by archival status: 0 the library, 1 the archive, 2 the bin. */
sealed interface PhotoFilter {
    data class Shelf(val archivalStatus: Long) : PhotoFilter

    /** Those with one of [archivalStatuses] that carry the local [tag]. */
    data class Tagged(val tag: Uuid, val archivalStatuses: List<Long>) : PhotoFilter
}

/** [day] counts days since 1970-01-01 of the phone's local date. */
data class DayCount(val day: Int, val photos: Int)

data class DayHeader(val day: Int, val jsonHeader: String)

data class TagCover(val tag: Uuid, val photos: Int, val newestHeader: String)
