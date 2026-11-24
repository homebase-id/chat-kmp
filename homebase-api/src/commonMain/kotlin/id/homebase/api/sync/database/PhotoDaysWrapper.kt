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
        if (userDate !in DATED) null else databaseManager.readValue("photoDays.dayNumberOf") { queries.dayNumberOf(userDate).executeAsOne() }.toInt()

    suspend fun photosOn(identityId: Uuid, driveId: Uuid, fileType: Long, day: Int): Int =
        databaseManager.readValue("photoDays.photosOnDay") {
            queries.photosOnDay(identityId, driveId, fileType, day.toLong()).executeAsOne()
        }.toInt()

    /** The headers of days [fromDay] to [toDay], newest first, each with its day. */
    suspend fun headersOnDays(identityId: Uuid, driveId: Uuid, fileType: Long, fromDay: Int, toDay: Int): List<DayHeader> =
        databaseManager.readValue("photoDays.photosOnDays") {
            queries.photosOnDays(identityId, driveId, fileType, fromDay.toLong(), toDay.toLong()).executeAsList()
        }.mapNotNull { row -> DayHeader(row.day.toInt(), row.jsonHeader).takeIf { it.day in fromDay..toDay } }
}

/** [day] counts days since 1970-01-01 of the phone's local date. */
data class DayCount(val day: Int, val photos: Int)

// The bounds of dayCounts' guard: a day inside the years 0 to 9999, so no time zone pushes a date out of them.
private val DATED = -62_167_132_800_000L..253_402_214_399_999L

data class DayHeader(val day: Int, val jsonHeader: String)
