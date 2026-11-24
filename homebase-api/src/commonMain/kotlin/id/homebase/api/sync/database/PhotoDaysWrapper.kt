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
    suspend fun dayOf(identityId: Uuid, driveId: Uuid, fileType: Long, userDate: Long): CountedDay? =
        databaseManager.readValue("photoDays.dayOf") { queries.dayOf(userDate, identityId, driveId, fileType).executeAsOne() }.run {
            if (day == null || startMs == null || endMs == null) null else CountedDay(DayCount(day.toInt(), photos.toInt()), startMs..<endMs)
        }
}

/** [day] counts days since 1970-01-01 of the phone's local date. */
data class DayCount(val day: Int, val photos: Int)

/** A day's count and the userDates it spans, from local midnight to the next. */
data class CountedDay(val count: DayCount, val userDates: LongRange)
