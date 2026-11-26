package id.homebase.api.sync.database

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.common.time.UnixTimeUtcRange
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * How fast a Photos grid can read a 40K-photo library from the phone's own database, with fixed
 * hand-written SQL rather than QueryBatch's per-call string. See PHOTOS_OPTIMIZE.md for how to run it.
 *
 * Statements go through [SqlDriver.executeQuery] with a fixed identifier, as SQLDelight's generated
 * queries do, so each is prepared once per connection and reused.
 */
@RunWith(AndroidJUnit4::class)
class PhotoGridDbBench {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runs = args.getString("photoGridRuns")?.toInt() ?: 15
    private val zone = TimeZone.currentSystemDefault()

    @Test
    fun measure() = runBlocking {
        assumeTrue("Pass photoGridBench=true to run", args.getString("photoGridBench") == "true")
        var dbm = library("photo-grid-bench.txt")
        dbm.driver.execute(null, "DROP INDEX IF EXISTS $GRID_INDEX", 0)
        // What an installed app does once, at its first open with IdxPhotoShelf in the schema.
        dbm.driver.execute(null, "DROP INDEX IF EXISTS $SHELF_INDEX", 0)
        once("build $SHELF_INDEX, once per install") { OdinDatabase.Schema.create(dbm.driver) }

        // A fresh process and connection, as the first open of the gallery after a cold start finds it.
        dbm.close()
        dbm = open()
        once("first day counts on a fresh connection, existing indexes") { dayCountsSql(dbm.driver) }
        val days = dayCountsSql(dbm.driver)
        log("  ${days.size} days with photos, ${days.sumOf { it.second }} photos; held as two IntArrays: ${days.size * 8 / 1024} KB")
        assertEquals(dayCountsKotlin(dbm.driver), days, "SQLite's localtime and kotlinx-datetime must agree on every day")

        measure("day counts, SQL localtime, existing indexes") { dayCountsSql(dbm.driver) }
        val shipped = dbm.photoDays.dayCounts(IDENTITY, PHOTOS_DRIVE, PhotoFilter.Shelf(LIBRARY))
        assertEquals(days, shipped.map { it.day to it.photos }, "PhotoDays.dayCounts must count the days this bench does")
        measure("day counts, PhotoDays.dayCounts (shipped)") { dbm.photoDays.dayCounts(IDENTITY, PHOTOS_DRIVE, PhotoFilter.Shelf(LIBRARY)) }
        measure("day counts, PhotoDays.dayCounts (shipped), the bin") { dbm.photoDays.dayCounts(IDENTITY, PHOTOS_DRIVE, PhotoFilter.Shelf(BIN)) }
        val busiest = days.maxBy { it.second }
        val busiestDay = checkNotNull(dbm.photoDays.dayOf(busiest.first * DAY_MS + DAY_MS / 2))
        log("  busiest day ${busiest.first}: ${busiest.second} photos; photosOn found ${dbm.photoDays.photosOn(IDENTITY, PHOTOS_DRIVE, PhotoFilter.Shelf(LIBRARY), busiestDay)} on day $busiestDay")
        measure("one day's count, PhotoDays.photosOn (shipped), busiest day") {
            dbm.photoDays.photosOn(IDENTITY, PHOTOS_DRIVE, PhotoFilter.Shelf(LIBRARY), busiest.first)
        }
        val typical = days.sortedBy { it.second }[days.size / 2]
        log("  median day has ${typical.second} photos")
        measure("one day's count, PhotoDays.photosOn (shipped), median day") {
            dbm.photoDays.photosOn(IDENTITY, PHOTOS_DRIVE, PhotoFilter.Shelf(LIBRARY), typical.first)
        }
        measure("a photo's day, PhotoDays.dayOf (shipped)") { dbm.photoDays.dayOf(busiest.first * DAY_MS + DAY_MS / 2) }
        measure("photo count, existing indexes") { photoCount(dbm.driver) }
        val deep = dateAround(days, yearsBack = 7)
        measure("100 headers near 7 years back, QueryBatch (today's gallery)") { queryBatchPage(dbm, deep) }
        measure("100 headers near 7 years back, fixed SQL, existing indexes") { headersBefore(dbm.driver, deep) }

        dbm.driver.execute(null, "CREATE INDEX $GRID_INDEX ON DriveMainIndex(identityId,driveId,fileType,fileState,userDate DESC,rowId)", 0)
        dbm.close()
        dbm = open()
        once("first day counts on a fresh connection, Photos index") { dayCountsSql(dbm.driver) }
        measure("day counts, SQL localtime, Photos index") { dayCountsSql(dbm.driver) }
        measure("day counts, dates in SQL + grouping in Kotlin, Photos index") { dayCountsKotlin(dbm.driver) }
        measure("photo count, Photos index") { photoCount(dbm.driver) }
        measure("100 headers near 7 years back, fixed SQL, Photos index") { headersBefore(dbm.driver, deep) }
        measure("30 headers near 7 years back (one screen), fixed SQL, Photos index") { headersBefore(dbm.driver, deep, limit = 30) }
        val headers = headersBefore(dbm.driver, deep)
        log("  a header averages ${headers.sumOf { it.length } / headers.size} characters of JSON")
        measure("parse 100 headers into HomebaseFile") { headers.forEach { OdinSystemSerializer.deserialize<HomebaseFile>(it) } }
        measure("100 headers near 7 years back + parse, Photos index") {
            headersBefore(dbm.driver, deep).forEach { OdinSystemSerializer.deserialize<HomebaseFile>(it) }
        }
        dbm.driver.execute(null, "DROP INDEX IF EXISTS $GRID_INDEX", 0)
        dbm.close()
        log("done")
    }

    private suspend fun library(resultsFile: String): DatabaseManager {
        results = File(context.externalCacheDir, resultsFile).apply { delete() }
        var dbm = open()
        // A run stopped while building leaves a partial database behind.
        if (args.getString("photoGridRebuild") == "true" || photoCount(dbm.driver) != PHOTOS.toLong()) {
            dbm.close()
            context.deleteDatabase(DB)
            dbm = open()
            val took = TimeSource.Monotonic.markNow()
            Fixture().populate(dbm)
            log("built $PHOTOS photos + $CHAT_MESSAGES chat rows in ${took.elapsedNow().inWholeSeconds} s")
        }
        // A bench stopped partway leaves its tags and indexes behind, which every later run would measure.
        clearAlbums(dbm.driver)
        log("database ${context.getDatabasePath(DB).length() / MB} MB, zone $zone, runs $runs")
        return dbm
    }

    private class AlbumSet(val name: String, val favorites: Int, val trips: Int, val tripSize: Int, val big: Int)

    /** What an album costs to show, by how many photos carry a tag, without and with an index on the tag. */
    @Test
    fun measureAlbums() = runBlocking {
        assumeTrue("Pass photoAlbumsBench=true to run", args.getString("photoAlbumsBench") == "true")
        var dbm = library("photo-albums-bench.txt")
        val sets = listOf(
            AlbumSet("typical: 500 favorites, 10 trip albums of 100", favorites = 500, trips = 10, tripSize = 100, big = 0),
            AlbumSet("one big album: 2,000 favorites, 10 trips of 100, an album of 10,000", favorites = 2_000, trips = 10, tripSize = 100, big = 10_000),
            AlbumSet("heavy: 5,000 favorites, 50 trips of 400, an album of 10,000", favorites = 5_000, trips = 50, tripSize = 400, big = 10_000),
        )
        val ids = Random(11)
        for (set in sets) {
            clearAlbums(dbm.driver)
            val favorites = Uuid.fromLongs(ids.nextLong(), ids.nextLong())
            // Rows went in shuffled, so rowId order spreads a tag over the years; a trip is a run of consecutive photos.
            tag(dbm.driver, favorites, "rowId DESC", set.favorites, 0)
            val trips = List(set.trips) { i ->
                Uuid.fromLongs(ids.nextLong(), ids.nextLong()).also { tag(dbm.driver, it, "userDate DESC", set.tripSize, i * (PHOTOS / set.trips)) }
            }
            val big = if (set.big > 0) Uuid.fromLongs(ids.nextLong(), ids.nextLong()).also { tag(dbm.driver, it, "rowId", set.big, 0) } else null
            val albums = trips + listOfNotNull(big)
            val rows = dbm.driver.executeQuery(null, "SELECT count(*) FROM DriveLocalTagIndex", { it.next(); QueryResult.Value(it.getLong(0)!!) }, 0).value
            log("")
            log("== ${set.name}: $rows tag rows on the Photos drive")
            if (big != null) assertEquals(set.big, dbm.photoDays.taggedFileIds(IDENTITY, PHOTOS_DRIVE, big).size)

            dbm.close()
            dbm = open()
            val before = measureTagged(dbm, "no tag index", favorites, trips.first(), big, albums)
            once("build $TAG_INDEX") { dbm.driver.execute(null, "CREATE INDEX $TAG_INDEX ON DriveLocalTagIndex(identityId, driveId, tagId, fileId)", 0) }
            dbm.close()
            dbm = open()
            val after = measureTagged(dbm, "tag index", favorites, trips.first(), big, albums)
            assertEquals(before, after, "the index must not change what the albums show")
            once("build $PHOTO_ID_INDEX") {
                dbm.driver.execute(
                    null,
                    "CREATE INDEX $PHOTO_ID_INDEX ON DriveMainIndex(identityId, driveId, fileId, fileState, archivalStatus, userDate) " +
                        "WHERE fileType = 7201 AND fileSystemType = 0",
                    0,
                )
            }
            dbm.close()
            dbm = open()
            val both = measureTagged(dbm, "tag index + photo id index", favorites, trips.first(), big, albums)
            assertEquals(before, both, "the index must not change what the albums show")
            measure("Library, two steps: counts + newest date per album, then the newest header of each") { coversInTwoSteps(dbm.driver, albums) }
            dbm.driver.execute(null, "DROP INDEX IF EXISTS $PHOTO_ID_INDEX", 0)
        }
        clearAlbums(dbm.driver)
        dbm.close()
        log("done")
    }

    /** Other shapes of the same album reads, on the heavy set: what the time is spent on, and what avoids it. */
    @Test
    fun measureAlbumQueries() = runBlocking {
        assumeTrue("Pass photoAlbumQueriesBench=true to run", args.getString("photoAlbumQueriesBench") == "true")
        var dbm = library("photo-album-queries-bench.txt")
        clearAlbums(dbm.driver)
        val (favorites, trips, big) = tagHeavySet(dbm.driver)
        val albums = trips + big
        log("heavy: 5,000 favorites, 50 trips of 400, an album of 10,000")
        dbm.close()
        dbm = open()
        val driver = dbm.driver

        for ((name, tag) in listOf("the big album (10,000)" to big, "a trip album (400)" to trips.first())) {
            log("")
            log("== $name")
            val shipped = rawDays(driver, tagFirstDays(tag))
            measure("shipped: tag rows first, look up each photo") { rawDays(driver, tagFirstDays(tag)) }
            measure("tag rows first, count only (no day, no photo columns)") {
                driver.executeQuery(null, "SELECT count(*) $TAG_JOIN WHERE t.identityId = $ME AND t.driveId = $DRIVE AND t.tagId = ${hex(tag)}", { it.next(); QueryResult.Value(it.getLong(0)) }, 0)
            }
            measure("tag rows first, photo filters, no day") {
                driver.executeQuery(null, "SELECT count(*) $TAG_JOIN WHERE ${taggedWhere(tag)}", { it.next(); QueryResult.Value(it.getLong(0)) }, 0)
            }
            measure("ids first, then photos by IN (500 ids a statement)") { assertEquals(shipped, idsThenIn(dbm, tag)) }
            assertEquals(shipped, rawDays(driver, photosFirstDays(tag, SHELF_INDEX)))
            plan(driver, photosFirstDays(tag, SHELF_INDEX))
            measure("photos first by date ($SHELF_INDEX), keep those in the album") { rawDays(driver, photosFirstDays(tag, SHELF_INDEX)) }
        }

        once("build $SHELF_ID_INDEX (IdxPhotoShelf + fileId)") {
            driver.execute(
                null,
                "CREATE INDEX $SHELF_ID_INDEX ON DriveMainIndex(identityId, driveId, archivalStatus, fileState, userDate, fileId) WHERE fileType = 7201 AND fileSystemType = 0",
                0,
            )
        }
        once("build $TAG_INDEX") { driver.execute(null, "CREATE INDEX $TAG_INDEX ON DriveLocalTagIndex(identityId, driveId, tagId, fileId)", 0) }
        dbm.close()
        dbm = open()
        for ((name, tag) in listOf("the big album (10,000)" to big, "a trip album (400)" to trips.first(), "Favorites (5,000)" to favorites)) {
            log("")
            log("== $name, with both indexes")
            val shipped = rawDays(dbm.driver, tagFirstDays(tag))
            measure("shipped: tag rows first") { rawDays(dbm.driver, tagFirstDays(tag)) }
            assertEquals(shipped, rawDays(dbm.driver, photosFirstDays(tag, SHELF_ID_INDEX)))
            plan(dbm.driver, photosFirstDays(tag, SHELF_ID_INDEX))
            measure("photos first by date ($SHELF_ID_INDEX), keep those in the album") { rawDays(dbm.driver, photosFirstDays(tag, SHELF_ID_INDEX)) }
            val (from, to, upTo) = firstScreen(shipped)
            val window = "m.userDate >= ${(from - 1L) * DAY_MS} AND m.userDate < ${(to + 2L) * DAY_MS} AND $DAY BETWEEN $from AND $to"
            val shippedPage = rawHeaders(dbm.driver, "SELECT m.jsonHeader $TAG_JOIN WHERE ${taggedWhere(tag)} AND $window ORDER BY m.userDate DESC, m.rowId DESC")
            measure("page of ${upTo + 1} days, before: tag rows first") {
                rawHeaders(dbm.driver, "SELECT m.jsonHeader $TAG_JOIN WHERE ${taggedWhere(tag)} AND $window ORDER BY m.userDate DESC, m.rowId DESC")
            }
            val dateFirst = "SELECT m.jsonHeader FROM DriveMainIndex m INDEXED BY $SHELF_ID_INDEX WHERE ${photoFilters()} AND $window AND ${inAlbum(tag)} ORDER BY m.userDate DESC, m.rowId DESC"
            assertEquals(shippedPage, rawHeaders(dbm.driver, dateFirst))
            measure("page of ${upTo + 1} days: photos on those days first, keep those in the album") { rawHeaders(dbm.driver, dateFirst) }
        }

        log("")
        log("== the Library's covers of ${albums.size} albums, with both indexes")
        val tagList = albums.joinToString(",") { hex(it) }
        val shippedCovers = "SELECT t.tagId, count(*), m.jsonHeader, max(m.userDate) $TAG_JOIN WHERE t.identityId = $ME AND t.driveId = $DRIVE AND t.tagId IN ($tagList) " +
            "AND ${photoFilters()} GROUP BY t.tagId"
        measure("shipped: tag rows first") { rawCovers(dbm.driver, shippedCovers) }
        val photosFirstCovers = "SELECT t.tagId, count(*), m.jsonHeader, max(m.userDate) FROM DriveMainIndex m INDEXED BY $SHELF_ID_INDEX " +
            "JOIN DriveLocalTagIndex t ON t.identityId = m.identityId AND t.driveId = m.driveId AND t.fileId = m.fileId " +
            "WHERE ${photoFilters()} AND t.tagId IN ($tagList) GROUP BY t.tagId"
        assertEquals(rawCovers(dbm.driver, shippedCovers), rawCovers(dbm.driver, photosFirstCovers))
        plan(dbm.driver, photosFirstCovers)
        measure("photos first by date, then each photo's tags") { rawCovers(dbm.driver, photosFirstCovers) }
        measure("two steps: counts + newest date, then one header per album") { coversInTwoSteps(dbm.driver, albums) }
        val countsOnly = "SELECT t.tagId, count(*), max(m.userDate) $TAG_JOIN WHERE t.identityId = $ME AND t.driveId = $DRIVE AND t.tagId IN ($tagList) AND ${photoFilters()} GROUP BY t.tagId"
        measure("tag rows first, counts + newest date only (no header)") { rawHeaders(dbm.driver, countsOnly.replace("SELECT t.tagId,", "SELECT hex(t.tagId) ||") ) }

        log("")
        log("== page cache: the big album's shipped day counts on one connection")
        dbm.withWriteTransaction {
            timeBlocking("default cache (2 MB)") { rawDays(dbm.driver, tagFirstDays(big)) }
            dbm.driver.execute(null, "PRAGMA cache_size = -65536", 0)
            timeBlocking("64 MB cache") { rawDays(dbm.driver, tagFirstDays(big)) }
            dbm.driver.execute(null, "PRAGMA cache_size = -2000", 0)
        }

        clearAlbums(dbm.driver)
        dbm.close()
        log("done")
    }

    /** The album reads without a new index: the album as rowIds from the fileId index, then the shelf index, so only shown photos' rows are read. */
    @Test
    fun measureAlbumRowIds() = runBlocking {
        assumeTrue("Pass photoAlbumRowIdsBench=true to run", args.getString("photoAlbumRowIdsBench") == "true")
        var dbm = library("photo-album-rowids-bench.txt")
        clearAlbums(dbm.driver)
        val (favorites, trips, big) = tagHeavySet(dbm.driver)
        val albums = trips + big
        log("heavy: 5,000 favorites, 50 trips of 400, an album of 10,000; no new index")
        dbm.close()
        dbm = open()
        val driver = dbm.driver
        val shelf = "m.identityId = $ME AND m.driveId = $DRIVE AND m.fileSystemType = 0 AND m.fileType = 7201 AND m.fileState = 1 AND m.archivalStatus IN (0, 1)"
        val dated = "+m.userDate BETWEEN -62167132800000 AND 253402214399999"
        fun rowIds(tagWhere: String, tagColumn: Boolean = false) =
            "SELECT ${if (tagColumn) "t.tagId, " else ""}p.rowId AS rid FROM DriveLocalTagIndex t CROSS JOIN DriveMainIndex p " +
                "ON p.identityId = t.identityId AND p.driveId = t.driveId AND p.fileId = t.fileId WHERE t.identityId = $ME AND t.driveId = $DRIVE AND $tagWhere"

        log("SQLite ${rawHeaders(driver, "SELECT sqlite_version()").single()}")
        val coversOnly = args.getString("photoAlbumCoversOnly") == "true"
        for ((name, tag) in listOf("the big album (10,000)" to big, "Favorites (5,000)" to favorites, "a trip album (400)" to trips.first()).takeUnless { coversOnly }.orEmpty()) {
            log("")
            log("== $name")
            val inAlbum = "+m.rowId IN (${rowIds("t.tagId = ${hex(tag)}")})"
            measure("the album's rowIds alone") { rawHeaders(driver, "SELECT count(*) || '' FROM (${rowIds("t.tagId = ${hex(tag)}")})") }

            val shipped = rawDays(driver, tagFirstDays(tag))
            measure("day counts, before: tag rows first") { rawDays(driver, tagFirstDays(tag)) }
            val shelfFirst = "SELECT $DAY AS day, count(*) FROM DriveMainIndex m WHERE $shelf AND $dated AND $inAlbum GROUP BY day ORDER BY day DESC"
            assertEquals(shipped, rawDays(driver, shelfFirst))
            plan(driver, shelfFirst)
            measure("day counts: shelf index first, keep the album's rowIds") { rawDays(driver, shelfFirst) }
            val sortedRows = "SELECT $DAY AS day, count(*) FROM DriveMainIndex m WHERE m.rowId IN (${rowIds("t.tagId = ${hex(tag)}")}) AND $shelf AND $dated GROUP BY day ORDER BY day DESC"
            assertEquals(shipped, rawDays(driver, sortedRows))
            plan(driver, sortedRows)
            measure("day counts: the album's rows in rowId order") { rawDays(driver, sortedRows) }

            val (from, to, upTo) = firstScreen(shipped)
            val window = "m.userDate >= ${(from - 1L) * DAY_MS} AND m.userDate < ${(to + 2L) * DAY_MS} AND $DAY BETWEEN $from AND $to"
            val shippedPage = "SELECT m.jsonHeader $TAG_JOIN WHERE ${taggedWhere(tag)} AND $window ORDER BY m.userDate DESC, m.rowId DESC"
            val page = rawHeaders(driver, shippedPage)
            measure("page of ${upTo + 1} days, before: tag rows first") { rawHeaders(driver, shippedPage) }
            val shelfPage = "SELECT m.jsonHeader FROM DriveMainIndex m WHERE $shelf AND $window AND $inAlbum ORDER BY m.userDate DESC, m.rowId DESC"
            assertEquals(page, rawHeaders(driver, shelfPage))
            plan(driver, shelfPage)
            measure("page of ${upTo + 1} days: shelf index first, keep the album's rowIds") { rawHeaders(driver, shelfPage) }
            val existsPage = "SELECT m.jsonHeader FROM DriveMainIndex m WHERE $shelf AND $window AND EXISTS (SELECT 1 FROM DriveLocalTagIndex t " +
                "WHERE t.identityId = m.identityId AND t.driveId = m.driveId AND t.fileId = m.fileId AND t.tagId = ${hex(tag)}) ORDER BY m.userDate DESC, m.rowId DESC"
            assertEquals(page, rawHeaders(driver, existsPage))
            measure("page of ${upTo + 1} days: shelf index first, look up each photo's tag") { rawHeaders(driver, existsPage) }

            // What the app runs now, through PhotoDays, checked against the tag-first SQL it replaced.
            val filter = PhotoFilter.Tagged(tag, listOf(0L, 1L))
            assertEquals(shipped, dbm.photoDays.dayCounts(IDENTITY, PHOTOS_DRIVE, filter).map { it.day to it.photos })
            measure("day counts, PhotoDays (this build)") { dbm.photoDays.dayCounts(IDENTITY, PHOTOS_DRIVE, filter) }
            assertEquals(page, dbm.photoDays.headersOnDays(IDENTITY, PHOTOS_DRIVE, filter, from, to).map { it.jsonHeader })
            measure("page of ${upTo + 1} days, PhotoDays (this build)") { dbm.photoDays.headersOnDays(IDENTITY, PHOTOS_DRIVE, filter, from, to) }

            val busiest = shipped.maxBy { it.second }.first
            val dayWindow = "m.userDate >= ${(busiest - 1L) * DAY_MS} AND m.userDate < ${(busiest + 2L) * DAY_MS} AND $DAY = $busiest"
            measure("one day's count, before: tag rows first") { rawHeaders(driver, "SELECT count(*) || '' $TAG_JOIN WHERE ${taggedWhere(tag)} AND $dayWindow") }
            val shelfDay = "SELECT count(*) || '' FROM DriveMainIndex m WHERE $shelf AND $dayWindow AND $inAlbum"
            assertEquals(rawHeaders(driver, "SELECT count(*) || '' $TAG_JOIN WHERE ${taggedWhere(tag)} AND $dayWindow"), rawHeaders(driver, shelfDay))
            measure("one day's count: shelf index first") { rawHeaders(driver, shelfDay) }
            assertEquals(rawHeaders(driver, shelfDay).single().toInt(), dbm.photoDays.photosOn(IDENTITY, PHOTOS_DRIVE, filter, busiest))
            measure("one day's count, PhotoDays (this build)") { dbm.photoDays.photosOn(IDENTITY, PHOTOS_DRIVE, filter, busiest) }
        }

        log("")
        log("== the Library's covers of ${albums.size} albums")
        val tagList = albums.joinToString(",") { hex(it) }
        val shippedCovers = "SELECT t.tagId, count(*), m.jsonHeader, max(m.userDate) $TAG_JOIN WHERE t.identityId = $ME AND t.driveId = $DRIVE AND t.tagId IN ($tagList) " +
            "AND ${photoFilters()} GROUP BY t.tagId"
        measure("shipped: tag rows first") { rawCovers(driver, shippedCovers) }
        // Per album: photos and the newest date. A tie on the newest date may pick either header, so headers aren't compared.
        fun counted(sql: String) = driver.executeQuery(null, sql, { c -> QueryResult.Value(c.collect { it.getString(0)!! to (it.getLong(1)!! to it.getLong(2)!!) }.toMap()) }, 0).value
        val expected = counted("SELECT hex(t.tagId), count(*), max(m.userDate) $TAG_JOIN WHERE t.identityId = $ME AND t.driveId = $DRIVE AND t.tagId IN ($tagList) AND ${photoFilters()} GROUP BY t.tagId")
        fun headersByRowId(rowIds: Collection<Long>) = driver.executeQuery(
            null,
            "SELECT rowId, jsonHeader FROM DriveMainIndex WHERE rowId IN (${rowIds.joinToString(",")})",
            { cursor -> QueryResult.Value(cursor.collect { it.getLong(0)!! to it.getString(1)!! }) },
            0,
        ).value.toMap()

        fun inKotlin(): Map<String, Pair<Long, String>> {
            val tagged = driver.executeQuery(null, rowIds("t.tagId IN ($tagList)", tagColumn = true).replace("SELECT t.tagId,", "SELECT hex(t.tagId),"),
                { c -> QueryResult.Value(c.collect { it.getString(0)!! to it.getLong(1)!! }) }, 0).value
            val dates = HashMap<Long, Long>()
            driver.executeQuery(null, "SELECT m.rowId, m.userDate FROM DriveMainIndex m WHERE $shelf AND $dated", { c ->
                while (c.next().value) dates[c.getLong(0)!!] = c.getLong(1)!!
                QueryResult.Unit
            }, 0)
            val newest = HashMap<String, Triple<Long, Long, Long>>()
            for ((tag, rowId) in tagged) {
                val date = dates[rowId] ?: continue
                val was = newest[tag]
                newest[tag] = when {
                    was == null -> Triple(1L, date, rowId)
                    date > was.second -> Triple(was.first + 1, date, rowId)
                    else -> was.copy(first = was.first + 1)
                }
            }
            val headers = headersByRowId(newest.values.map { it.third })
            return newest.mapValues { (_, v) -> v.first to headers.getValue(v.third) }
        }
        assertEquals(expected.mapValues { it.value.first }, inKotlin().mapValues { it.value.first })
        measure("album rowIds and shelf dates read separately, counted in Kotlin, then the newest headers by rowId") { inKotlin() }

        clearAlbums(dbm.driver)
        dbm.close()
        log("done")
    }

    private fun hex(id: Uuid) = "X'${id.toHexString()}'"

    /** Drops every bench index and the photos' tags, so each bench starts from, and leaves, the plain library. */
    private fun clearAlbums(driver: SqlDriver) {
        for (index in listOf(TAG_INDEX, PHOTO_ID_INDEX, SHELF_ID_INDEX)) driver.execute(null, "DROP INDEX IF EXISTS $index", 0)
        driver.execute(null, "DELETE FROM DriveLocalTagIndex WHERE identityId = ? AND driveId = ?", 2) { bindPhotos() }
    }

    /** The heavy set: 5,000 favorites, 50 trips of 400 and an album of 10,000, the same ids each run. */
    private fun tagHeavySet(driver: SqlDriver): Triple<Uuid, List<Uuid>, Uuid> {
        val ids = Random(11)
        val favorites = Uuid.fromLongs(ids.nextLong(), ids.nextLong())
        tag(driver, favorites, "rowId DESC", 5_000, 0)
        val trips = List(50) { i -> Uuid.fromLongs(ids.nextLong(), ids.nextLong()).also { tag(driver, it, "userDate DESC", 400, i * (PHOTOS / 50)) } }
        val big = Uuid.fromLongs(ids.nextLong(), ids.nextLong()).also { tag(driver, it, "rowId", 10_000, 0) }
        return Triple(favorites, trips, big)
    }

    /** The grid's first screen: the newest days until they hold 100 photos, and the day after, as (oldest day, newest day, index of the oldest). */
    private fun firstScreen(days: List<Pair<Int, Int>>): Triple<Int, Int, Int> {
        var upTo = 0
        var photos = 0
        while (upTo < days.lastIndex && photos < 100) photos += days[upTo++].second
        return Triple(days[upTo].first, days.first().first, upTo)
    }

    private fun photoFilters() =
        "m.identityId = $ME AND m.driveId = $DRIVE AND m.fileSystemType = 0 AND m.fileType = 7201 AND m.fileState = 1 AND m.archivalStatus IN (0, 1) " +
            "AND m.userDate BETWEEN -62167132800000 AND 253402214399999"

    private fun taggedWhere(tag: Uuid) = "t.identityId = $ME AND t.driveId = $DRIVE AND t.tagId = ${hex(tag)} AND ${photoFilters()}"

    private fun inAlbum(tag: Uuid) = "m.fileId IN (SELECT fileId FROM DriveLocalTagIndex WHERE identityId = $ME AND driveId = $DRIVE AND tagId = ${hex(tag)})"

    private fun tagFirstDays(tag: Uuid) = "SELECT $DAY AS day, count(*) $TAG_JOIN WHERE ${taggedWhere(tag)} GROUP BY day ORDER BY day DESC"

    private fun photosFirstDays(tag: Uuid, index: String) =
        "SELECT $DAY AS day, count(*) FROM DriveMainIndex m INDEXED BY $index WHERE ${photoFilters()} AND ${inAlbum(tag)} GROUP BY day ORDER BY day DESC"

    private suspend fun idsThenIn(dbm: DatabaseManager, tag: Uuid): List<Pair<Int, Int>> {
        val counts = sortedMapOf<Int, Int>(reverseOrder())
        for (chunk in dbm.photoDays.taggedFileIds(IDENTITY, PHOTOS_DRIVE, tag).sorted().chunked(500)) {
            val sql = "SELECT $DAY AS day, count(*) FROM DriveMainIndex m WHERE ${photoFilters()} AND m.fileId IN (${chunk.joinToString(",") { hex(it) }}) GROUP BY day"
            for ((day, photos) in rawDays(dbm.driver, sql)) counts[day] = (counts[day] ?: 0) + photos
        }
        return counts.map { it.key to it.value }
    }

    private fun rawDays(driver: SqlDriver, sql: String): List<Pair<Int, Int>> =
        driver.executeQuery(null, sql, { cursor -> QueryResult.Value(cursor.collect { it.getLong(0)!!.toInt() to it.getLong(1)!!.toInt() }) }, 0).value

    private fun rawHeaders(driver: SqlDriver, sql: String): List<String> =
        driver.executeQuery(null, sql, { cursor -> QueryResult.Value(cursor.collect { it.getString(0)!! }) }, 0).value

    private fun rawCovers(driver: SqlDriver, sql: String): Set<Pair<Long, String>> =
        driver.executeQuery(null, sql, { cursor -> QueryResult.Value(cursor.collect { it.getLong(1)!! to it.getString(2)!! }.toSet()) }, 0).value

    private fun plan(driver: SqlDriver, sql: String) {
        val steps = driver.executeQuery(null, "EXPLAIN QUERY PLAN $sql", { cursor -> QueryResult.Value(cursor.collect { it.getString(3)!! }) }, 0).value
        log("  plan: ${steps.joinToString(" | ")}")
    }

    private fun timeBlocking(name: String, block: () -> Unit) {
        repeat(2) { block() }
        val samples = List(runs) {
            val start = TimeSource.Monotonic.markNow()
            block()
            start.elapsedNow().inWholeMicroseconds / 1000.0
        }.sorted()
        log("$name: median ${samples[runs / 2]} ms, p90 ${samples[runs * 9 / 10]} ms, min ${samples.first()} ms")
    }

    private fun tag(driver: SqlDriver, tag: Uuid, order: String, photos: Int, skip: Int) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO DriveLocalTagIndex(identityId, driveId, fileId, tagId) " +
                "SELECT identityId, driveId, fileId, ? FROM DriveMainIndex WHERE $photoWhere ORDER BY $order LIMIT ? OFFSET ?",
            5,
        ) {
            bindBytes(0, tag.toByteArray())
            bindBytes(1, IDENTITY.toByteArray())
            bindBytes(2, PHOTOS_DRIVE.toByteArray())
            bindLong(3, photos.toLong())
            bindLong(4, skip.toLong())
        }
    }

    /** The shipped PhotoDays reads behind the Library's albums, an album's screen and deleting an album; returns what they read. */
    private suspend fun measureTagged(dbm: DatabaseManager, index: String, favorites: Uuid, trip: Uuid, big: Uuid?, albums: List<Uuid>): List<Any> {
        val photoDays = dbm.photoDays
        val shown = listOf(0L, 1L)
        log("-- $index")
        plan(dbm.driver, "tag rows first (the shape before PHOTOS_OPTIMIZE.md 2026-11-26)", favorites)
        once("first Library covers on a fresh connection") { photoDays.taggedCovers(IDENTITY, PHOTOS_DRIVE, albums, shown) }
        measure("Library: covers + counts of ${albums.size} albums") { photoDays.taggedCovers(IDENTITY, PHOTOS_DRIVE, albums, shown) }
        measure("Library: covers + counts + parse the cover headers") {
            photoDays.taggedCovers(IDENTITY, PHOTOS_DRIVE, albums, shown).forEach { OdinSystemSerializer.deserialize<HomebaseFile>(it.newestHeader) }
        }
        val read = mutableListOf<Any>(photoDays.taggedCovers(IDENTITY, PHOTOS_DRIVE, albums, shown).map { it.tag to it.photos }.toSet())
        for ((name, tag) in listOfNotNull("Favorites" to favorites, "a trip album" to trip, big?.let { "the big album" to it })) {
            val filter = PhotoFilter.Tagged(tag, shown)
            val days = photoDays.dayCounts(IDENTITY, PHOTOS_DRIVE, filter)
            measure("$name (${days.sumOf { it.photos }} photos on ${days.size} days): day counts") { photoDays.dayCounts(IDENTITY, PHOTOS_DRIVE, filter) }
            val (from, to, upTo) = firstScreen(days.map { it.day to it.photos })
            measure("$name: headers of its newest ${upTo + 1} days") { photoDays.headersOnDays(IDENTITY, PHOTOS_DRIVE, filter, from, to) }
            val busiest = days.maxBy { it.photos }.day
            measure("$name: one day's count, busiest day") { photoDays.photosOn(IDENTITY, PHOTOS_DRIVE, filter, busiest) }
            measure("$name: every file id (delete)") { photoDays.taggedFileIds(IDENTITY, PHOTOS_DRIVE, tag) }
            read.addAll(days)
            read.addAll(photoDays.headersOnDays(IDENTITY, PHOTOS_DRIVE, filter, from, to))
        }
        return read
    }

    // Counting needs only indexed columns; the header is read once per album rather than with every photo.
    private fun coversInTwoSteps(driver: SqlDriver, albums: List<Uuid>): List<String> {
        val join = "FROM DriveLocalTagIndex t CROSS JOIN DriveMainIndex m ON m.identityId = t.identityId AND m.driveId = t.driveId AND m.fileId = t.fileId " +
            "WHERE t.identityId = ? AND t.driveId = ? AND t.tagId = ? AND m.fileSystemType = 0 AND m.fileType = 7201 AND m.fileState = 1 AND m.archivalStatus IN (0, 1)"
        return albums.mapNotNull { tag ->
            val bind: SqlPreparedStatement.() -> Unit = {
                bindBytes(0, IDENTITY.toByteArray())
                bindBytes(1, PHOTOS_DRIVE.toByteArray())
                bindBytes(2, tag.toByteArray())
            }
            val newest = driver.executeQuery(COVER_COUNT_ID, "SELECT count(*), max(m.userDate) $join", { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(1)) }, 3, bind).value
                ?: return@mapNotNull null
            driver.executeQuery(COVER_HEADER_ID, "SELECT m.jsonHeader $join AND m.userDate = ? LIMIT 1", { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0)) }, 4) {
                bind()
                bindLong(3, newest)
            }.value
        }
    }

    private fun plan(driver: SqlDriver, name: String, tag: Uuid) {
        val sql = "EXPLAIN QUERY PLAN SELECT count(*) FROM DriveLocalTagIndex t CROSS JOIN DriveMainIndex m " +
            "ON m.identityId = t.identityId AND m.driveId = t.driveId AND m.fileId = t.fileId " +
            "WHERE t.identityId = ? AND t.driveId = ? AND t.tagId = ? AND m.fileSystemType = 0 AND m.fileType = 7201 AND m.fileState = 1"
        val steps = driver.executeQuery(null, sql, { cursor -> QueryResult.Value(cursor.collect { it.getString(3)!! }) }, 3) {
            bindBytes(0, IDENTITY.toByteArray())
            bindBytes(1, PHOTOS_DRIVE.toByteArray())
            bindBytes(2, tag.toByteArray())
        }.value
        log("  $name plan: ${steps.joinToString(" | ")}")
    }

    private fun open() = DatabaseManager({ openAndroidSqliteDriver(context, DB, SqlCipherRawKey.format(KEY)) })

    private val photoWhere = "identityId = ? AND driveId = ? AND fileSystemType = 0 AND fileType = $PHOTO_FILE_TYPE AND fileState = 1"

    private fun SqlPreparedStatement.bindPhotos() {
        bindBytes(0, IDENTITY.toByteArray())
        bindBytes(1, PHOTOS_DRIVE.toByteArray())
    }

    /** Newest day first: (epoch day in the phone's zone, photos that day). */
    private fun dayCountsSql(driver: SqlDriver): List<Pair<Int, Int>> = driver.executeQuery(
        DAY_COUNTS_ID,
        "SELECT CAST(julianday(date(userDate / 1000, 'unixepoch', 'localtime')) - 2440587.5 AS INTEGER) AS day, count(*) " +
            "FROM DriveMainIndex WHERE $photoWhere GROUP BY day ORDER BY day DESC",
        { cursor -> QueryResult.Value(cursor.collect { it.getLong(0)!!.toInt() to it.getLong(1)!!.toInt() }) },
        2,
    ) { bindPhotos() }.value

    private fun dayCountsKotlin(driver: SqlDriver): List<Pair<Int, Int>> {
        val dates = driver.executeQuery(
            USER_DATES_ID,
            "SELECT userDate FROM DriveMainIndex WHERE $photoWhere ORDER BY userDate DESC",
            { cursor -> QueryResult.Value(cursor.collect { it.getLong(0)!! }) },
            2,
        ) { bindPhotos() }.value
        val days = ArrayList<Pair<Int, Int>>()
        for (ms in dates) {
            val day = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date.toEpochDays().toInt()
            if (days.isNotEmpty() && days.last().first == day) days[days.lastIndex] = day to days.last().second + 1 else days += day to 1
        }
        return days
    }

    private fun photoCount(driver: SqlDriver): Long = driver.executeQuery(
        COUNT_ID,
        "SELECT count(*) FROM DriveMainIndex WHERE $photoWhere",
        { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0)!!) },
        2,
    ) { bindPhotos() }.value

    /** The page the grid would load for rows around [beforeMs]: a keyset, as chat pages. */
    private fun headersBefore(driver: SqlDriver, beforeMs: Long, limit: Long = 100): List<String> = driver.executeQuery(
        HEADERS_ID,
        "SELECT jsonHeader FROM DriveMainIndex WHERE $photoWhere AND (userDate, rowId) < (?, ?) " +
            "ORDER BY userDate DESC, rowId DESC LIMIT ?",
        { cursor -> QueryResult.Value(cursor.collect { it.getString(0)!! }) },
        5,
    ) {
        bindPhotos()
        bindLong(2, beforeMs)
        bindLong(3, Long.MAX_VALUE)
        bindLong(4, limit)
    }.value

    private suspend fun queryBatchPage(dbm: DatabaseManager, beforeMs: Long) = QueryBatch(IDENTITY).queryBatchAsync(
        dbm = dbm,
        driveId = PHOTOS_DRIVE,
        noOfItems = 100,
        cursor = null,
        sortOrder = QueryBatchSortOrder.NewestFirst,
        sortField = QueryBatchSortField.UserDate,
        fileSystemType = 0,
        fileState = FileStateFilter.Active,
        filetypesAnyOf = listOf(PHOTO_FILE_TYPE),
        userDateSpan = UnixTimeUtcRange(UnixTimeUtc(0), UnixTimeUtc(beforeMs)),
    )

    private fun dateAround(days: List<Pair<Int, Int>>, yearsBack: Int): Long {
        val target = days.first().first - yearsBack * 365
        val day = days.minBy { kotlin.math.abs(it.first - target) }.first
        return (day + 1) * DAY_MS
    }

    private inline fun <T> SqlCursor.collect(row: (SqlCursor) -> T): List<T> {
        val out = ArrayList<T>()
        while (next().value) out += row(this)
        return out
    }

    private suspend fun once(name: String, block: suspend () -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        block()
        log("$name: ${start.elapsedNow().inWholeMicroseconds / 1000.0} ms")
    }

    private suspend fun measure(name: String, block: suspend () -> Unit) {
        repeat(2) { block() }
        val samples = List(runs) {
            val start = TimeSource.Monotonic.markNow()
            block()
            start.elapsedNow().inWholeMicroseconds / 1000.0
        }.sorted()
        log("$name: median ${samples[runs / 2]} ms, p90 ${samples[runs * 9 / 10]} ms, min ${samples.first()} ms")
    }

    // A file outlives a dropped USB link, where logcat output would be lost.
    private lateinit var results: File

    private fun log(message: String) {
        Log.i(TAG, message)
        results.appendText(message + "\n")
    }

    /** Photos shaped like Photos uploads: four thumbnails, a tiny, some Live photos and videos, in bursts. */
    private class Fixture {
        private val random = Random(7)
        private val nowMs = 1_795_000_000_000L

        suspend fun populate(dbm: DatabaseManager) {
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
            // Photos come in bursts: 2,500 days with photos, a few with hundreds.
            val photoDays = List(2_500) { nowMs - random.nextLong(10L * 365 * DAY_MS) }
            val weights = List(photoDays.size) { 1.0 / (it + 1.0).pow(0.8) }
            val total = weights.sum()
            val perDay = weights.map { maxOf(1, (PHOTOS * it / total).toInt()) }.toMutableList()
            perDay[0] += PHOTOS - perDay.sum()
            photoDays.indices.flatMap { d -> List(perDay[d]) { photoDays[d] + random.nextLong(DAY_MS) } }
                .shuffled(random)
                .map { photo(it) }
                .chunked(BATCH)
                .forEach { processor.performBaseUpsert(IDENTITY, PHOTOS_DRIVE, it, cursor = null) }
            generateSequence { chat(nowMs - random.nextLong(3L * 365 * DAY_MS)) }
                .take(CHAT_MESSAGES)
                .chunked(BATCH)
                .forEach { processor.performBaseUpsert(IDENTITY, CHAT_DRIVE, it, cursor = null) }
        }

        private fun photo(userDate: Long): HomebaseFile {
            val kind = random.nextDouble()
            val main = if (kind < 0.1) "photo_vid" else "photo_main"
            val type = if (kind < 0.1) "video/mp4" else "image/jpeg"
            val thumbs = listOf(320, 640, 1280, 2048).joinToString(",") { edge ->
                """{"pixelWidth":$edge,"pixelHeight":${edge * 3 / 4},"contentType":"image/webp","bytesWritten":${edge * random.nextInt(30, 90)}}"""
            }
            val tiny = """{"pixelWidth":20,"pixelHeight":15,"contentType":"image/webp","content":"${base64(random.nextInt(600, 1024))}"}"""
            val payloads = buildList {
                add(payload(main, type, userDate, tiny, "[$thumbs]"))
                if (kind in 0.1..0.18) add(payload("photo_live", "video/mp4", userDate, "null", "[]"))
            }.joinToString(",", "[", "]")
            return header(PHOTOS_DRIVE, PHOTO_FILE_TYPE, if (kind in 0.1..0.18) 3 else 1, null, userDate, base64(random.nextInt(200, 600)), payloads, tiny)
        }

        private fun payload(key: String, type: String, userDate: Long, tiny: String, thumbs: String) =
            """{"key":"$key","contentType":"$type","iv":"${base64(24)}","bytesWritten":${random.nextInt(500_000, 9_000_000)},""" +
                """"lastModified":$userDate,"descriptorContent":"${base64(random.nextInt(80, 240))}","uid":${random.nextLong(1, Long.MAX_VALUE)},""" +
                """"previewThumbnail":$tiny,"thumbnails":$thumbs}"""

        private fun chat(userDate: Long) =
            header(CHAT_DRIVE, 7878, 0, Uuid.fromLongs(1, random.nextLong(300)), userDate, base64(random.nextInt(60, 1200)), "[]", "null")

        private fun header(drive: Uuid, fileType: Int, dataType: Int, groupId: Uuid?, userDate: Long, content: String, payloads: String, tiny: String): HomebaseFile {
            val json = """{
                "driveId":"$drive","fileId":"${uuid()}","fileState":"active","fileSystemType":"standard","serverFileIsEncrypted":true,
                "keyHeader":{"iv":[${bytes(16)}],"aesKey":{"bytes":[${bytes(48)}]}},
                "fileMetadata":{
                    "globalTransitId":null,"created":$userDate,"updated":$userDate,"transitCreated":0,"transitUpdated":0,
                    "isEncrypted":true,"senderOdinId":"","originalAuthor":"me.dotyou.cloud",
                    "appData":{"uniqueId":"${uuid()}","tags":null,"fileType":$fileType,"dataType":$dataType,
                        "groupId":${groupId?.let { "\"$it\"" } ?: "null"},"userDate":$userDate,
                        "content":"$content","previewThumbnail":$tiny,"archivalStatus":0},
                    "localAppData":null,"referencedFile":null,"reactionPreview":null,
                    "versionTag":"${uuid()}","payloads":$payloads,"dataSource":null
                },
                "serverMetadata":{"accessControlList":{"requiredSecurityGroup":"owner","circleIdList":null,"odinIdList":null},
                    "doNotIndex":false,"allowDistribution":false,"fileSystemType":"standard","fileByteCount":${random.nextInt(1_000, 9_000_000)},
                    "originalRecipientCount":0,"transferHistory":null},
                "priority":300,"fileByteCount":${random.nextInt(1_000, 9_000_000)}
            }"""
            return OdinSystemSerializer.deserialize<HomebaseFile>(json)
        }

        private fun uuid() = Uuid.fromLongs(random.nextLong(), random.nextLong())

        private fun bytes(n: Int) = List(n) { random.nextInt(-128, 128) }.joinToString(",")

        private fun base64(chars: Int): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            return buildString(chars) { repeat(chars) { append(alphabet[random.nextInt(64)]) } }
        }
    }

    private companion object {
        const val TAG = "PhotoGridBench"
        const val DB = "photo-grid-bench.db"
        const val KEY = "5eed5eed5eed5eed5eed5eed5eed5eed5eed5eed5eed5eed5eed5eed5eed5eed"
        const val MB = 1024 * 1024
        const val DAY_MS = 86_400_000L
        const val PHOTOS = 40_000
        const val CHAT_MESSAGES = 50_000
        const val BATCH = 500
        const val PHOTO_FILE_TYPE = 7201
        const val GRID_INDEX = "IdxBenchPhotoGrid"
        const val TAG_INDEX = "IdxBenchLocalTag"
        const val PHOTO_ID_INDEX = "IdxBenchPhotoById"
        const val SHELF_ID_INDEX = "IdxBenchPhotoShelfId"
        const val TAG_JOIN = "FROM DriveLocalTagIndex t CROSS JOIN DriveMainIndex m ON m.identityId = t.identityId AND m.driveId = t.driveId AND m.fileId = t.fileId"
        const val DAY = "CAST(julianday(date(m.userDate / 1000.0, 'unixepoch', 'localtime')) - 2440587.5 AS INTEGER)"
        const val ME = "X'11111111111141118111111111111111'"
        const val DRIVE = "X'44444444444444448444444444444444'"
        const val COVER_COUNT_ID = 0x50470005
        const val COVER_HEADER_ID = 0x50470006
        const val SHELF_INDEX = "IdxPhotoShelf"
        const val LIBRARY = 0L
        const val BIN = 2L
        const val DAY_COUNTS_ID = 0x50470001
        const val USER_DATES_ID = 0x50470002
        const val COUNT_ID = 0x50470003
        const val HEADERS_ID = 0x50470004
        val IDENTITY: Uuid = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val PHOTOS_DRIVE: Uuid = Uuid.parse("44444444-4444-4444-8444-444444444444")
        val CHAT_DRIVE: Uuid = Uuid.parse("22222222-2222-4222-8222-222222222222")
    }
}
