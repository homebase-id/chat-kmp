package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.uuid.Uuid

/**
 * One buffered GPS point. Mirrors the LocationPoint table row; the Location
 * add-on's tracker layer (homebase-common) maps its capture type to this.
 */
data class BufferedLocationPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val acc: Double?,
    val alt: Double? = null,
    val altAcc: Double? = null,
    val spd: Double? = null,
    val hdg: Double? = null,
    val src: String,
    val fg: Boolean,
)

/**
 * Wrapper for the LocationPoint buffer table following the ChatReadCountWrapper
 * pattern — reads on the read lane, writes through the single-writer dispatcher.
 */
class LocationPointWrapper(
    driver: SqlDriver,
    locationPointAdapter: LocationPoint.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = LocationPointQueries(driver, locationPointAdapter)

    suspend fun insertPoints(points: List<BufferedLocationPoint>) {
        if (points.isEmpty()) return
        databaseManager.withWriteTransaction { db ->
            for (p in points) {
                db.locationPointQueries.insertPoint(
                    t = p.t,
                    lat = p.lat,
                    lon = p.lon,
                    acc = p.acc,
                    alt = p.alt,
                    altAcc = p.altAcc,
                    spd = p.spd,
                    hdg = p.hdg,
                    src = p.src,
                    fg = if (p.fg) 1L else 0L,
                )
            }
        }
    }

    /** Hour buckets (epoch ms / 3_600_000) that still have un-enqueued rows. */
    suspend fun selectPendingHours(): List<Long> =
        databaseManager.readValue("locationPoint.selectPendingHours") {
            delegate.selectPendingHours().executeAsList()
        }

    suspend fun selectByTimeRange(fromInclusiveMs: Long, toExclusiveMs: Long): List<BufferedLocationPoint> {
        val rows = databaseManager.readValue("locationPoint.selectByTimeRange") {
            delegate.selectByTimeRange(fromInclusiveMs, toExclusiveMs).executeAsList()
        }
        return rows.map {
            BufferedLocationPoint(
                t = it.t, lat = it.lat, lon = it.lon, acc = it.acc,
                alt = it.alt, altAcc = it.altAcc, spd = it.spd, hdg = it.hdg,
                src = it.src, fg = it.fg != 0L,
            )
        }
    }

    suspend fun markFlushed(fileUid: Uuid, fromInclusiveMs: Long, toExclusiveMs: Long) {
        databaseManager.withWrite { db ->
            db.locationPointQueries.markFlushed(fileUid, fromInclusiveMs, toExclusiveMs)
        }
    }

    suspend fun clearFlushMark(fileUid: Uuid) {
        databaseManager.withWrite { db ->
            db.locationPointQueries.clearFlushMark(fileUid)
        }
    }

    suspend fun deleteByFlushUid(fileUid: Uuid) {
        databaseManager.withWrite { db ->
            db.locationPointQueries.deleteByFlushUid(fileUid)
        }
    }

    suspend fun countAll(): Long =
        databaseManager.readValue("locationPoint.countAll") {
            delegate.countAll().executeAsOne()
        }

    suspend fun countSince(fromInclusiveMs: Long): Long =
        databaseManager.readValue("locationPoint.countSince") {
            delegate.countSince(fromInclusiveMs).executeAsOne()
        }

    /** Rows since [fromInclusiveMs] not yet part of any enqueued hour file. */
    suspend fun countUnmarkedSince(fromInclusiveMs: Long): Long =
        databaseManager.readValue("locationPoint.countUnmarkedSince") {
            delegate.countUnmarkedSince(fromInclusiveMs).executeAsOne()
        }

    suspend fun selectLatest(): BufferedLocationPoint? {
        val row = databaseManager.readValue("locationPoint.selectLatest") {
            delegate.selectLatest().executeAsOneOrNull()
        } ?: return null
        return BufferedLocationPoint(
            t = row.t, lat = row.lat, lon = row.lon, acc = row.acc,
            alt = row.alt, altAcc = row.altAcc, spd = row.spd, hdg = row.hdg,
            src = row.src, fg = row.fg != 0L,
        )
    }

    suspend fun deleteOlderThan(beforeMs: Long) {
        databaseManager.withWrite { db ->
            db.locationPointQueries.deleteOlderThan(beforeMs)
        }
    }

    suspend fun deleteAll() {
        databaseManager.withWrite { db ->
            db.locationPointQueries.deleteAll()
        }
    }
}
