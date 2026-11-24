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

    /** Newest day first. */
    suspend fun dayCounts(identityId: Uuid, driveId: Uuid, fileType: Long): List<DayCount> =
        databaseManager.readValue("photoDays.dayCounts") {
            queries.dayCounts(identityId, driveId, fileType) { day, photos -> DayCount(day!!.toInt(), photos.toInt()) }.executeAsList()
        }

    /** The day a photo taken at [userDate] falls on, or null for a date SQLite has no day for. */
    suspend fun dayOf(userDate: Long): Int? =
        databaseManager.readValue("photoDays.dayNumberOf") { queries.dayNumberOf(userDate).executeAsOne() }.toInt().takeIf { it != Int.MIN_VALUE }

    suspend fun photosOn(identityId: Uuid, driveId: Uuid, fileType: Long, day: Int): Int =
        databaseManager.readValue("photoDays.photosOnDay") {
            queries.photosOnDay(identityId, driveId, fileType, day.toLong()).executeAsOne()
        }.toInt()

    /** The headers of days [fromDay] to [toDay], newest first, each with its day. */
    suspend fun headersOnDays(identityId: Uuid, driveId: Uuid, fileType: Long, fromDay: Int, toDay: Int): List<DayHeader> =
        databaseManager.readValue("photoDays.photosOnDays") {
            queries.photosOnDays(identityId, driveId, fileType, fromDay.toLong(), toDay.toLong()).executeAsList()
        }.map { row -> DayHeader(row.day.toInt(), row.jsonHeader) }
}

/** [day] counts days since 1970-01-01 of the phone's local date. */
data class DayCount(val day: Int, val photos: Int)

data class DayHeader(val day: Int, val jsonHeader: String)
