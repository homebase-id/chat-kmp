package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.uuid.Uuid

/** A drive's photos counted per local day, for a gallery that lays out the whole library without reading it. */
class PhotoDaysWrapper(
    driver: SqlDriver,
    driveMainIndexAdapter: DriveMainIndex.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val queries = PhotoDaysQueries(driver, driveMainIndexAdapter)

    /** Newest day first. [archivalStatus] picks the shelf: 0 the library, 2 the bin. */
    suspend fun dayCounts(identityId: Uuid, driveId: Uuid, archivalStatus: Long): List<DayCount> =
        databaseManager.readValue("photoDays.dayCounts") {
            queries.dayCounts(identityId, driveId, archivalStatus) { day, photos -> DayCount(day!!.toInt(), photos.toInt()) }.executeAsList()
        }

    /** The day a photo taken at [userDate] falls on, or null for a date SQLite has no day for. */
    suspend fun dayOf(userDate: Long): Int? =
        databaseManager.readValue("photoDays.dayNumberOf") { queries.dayNumberOf(userDate).executeAsOne() }.toInt().takeIf { it != Int.MIN_VALUE }

    suspend fun photosOn(identityId: Uuid, driveId: Uuid, archivalStatus: Long, day: Int): Int =
        databaseManager.readValue("photoDays.photosOnDay") {
            queries.photosOnDay(identityId, driveId, archivalStatus, day.toLong()).executeAsOne()
        }.toInt()

    /** The headers of days [fromDay] to [toDay], newest first, each with its day. */
    suspend fun headersOnDays(identityId: Uuid, driveId: Uuid, archivalStatus: Long, fromDay: Int, toDay: Int): List<DayHeader> =
        databaseManager.readValue("photoDays.photosOnDays") {
            queries.photosOnDays(identityId, driveId, archivalStatus, fromDay.toLong(), toDay.toLong()).executeAsList()
        }.map { row -> DayHeader(row.day.toInt(), row.jsonHeader) }

    /** The headers of those of [fileIds] the drive holds, on any shelf, in no order. */
    suspend fun headersOf(identityId: Uuid, driveId: Uuid, fileIds: Collection<Uuid>): List<String> =
        // SQLite caps the variables a statement binds.
        fileIds.chunked(IDS_PER_READ).flatMap { chunk ->
            databaseManager.readValue("photoDays.headersOf") { queries.headersOf(identityId, driveId, chunk).executeAsList() }
        }

    /** The headers of every photo with [archivalStatus], in no order. */
    suspend fun headersOnShelf(identityId: Uuid, driveId: Uuid, archivalStatus: Long): List<String> =
        databaseManager.readValue("photoDays.headersOnShelf") { queries.headersOnShelf(identityId, driveId, archivalStatus).executeAsList() }

    private companion object {
        const val IDS_PER_READ = 500
    }
}

/** [day] counts days since 1970-01-01 of the phone's local date. */
data class DayCount(val day: Int, val photos: Int)

data class DayHeader(val day: Int, val jsonHeader: String)
