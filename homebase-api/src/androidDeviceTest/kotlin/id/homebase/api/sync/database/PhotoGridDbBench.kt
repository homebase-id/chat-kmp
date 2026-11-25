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
 * hand-written SQL rather than QueryBatch's per-call string. See OPTIMIZE.md for how to run it.
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
        results.delete()
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
        log("database ${context.getDatabasePath(DB).length() / MB} MB, zone $zone, runs $runs")
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
    private val results = File(context.externalCacheDir, "photo-grid-bench.txt")

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
