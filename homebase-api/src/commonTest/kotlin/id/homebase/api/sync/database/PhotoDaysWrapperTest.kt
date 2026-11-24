package id.homebase.api.sync.database

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** On each platform's own SQLite (not the web, which has no test database yet), in the machine's time zone. */
class PhotoDaysWrapperTest {
    private val identity = Uuid.random()
    private val photos = Uuid.random()
    private val zone = TimeZone.currentSystemDefault()

    private fun runDbTest(body: suspend TestScope.(DatabaseManager) -> Unit) = runTest {
        val dbDispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager({ createInMemoryDatabase() }, dispatcher = dbDispatcher, readDispatcher = dbDispatcher)
        try {
            body(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun daysAreThePhonesLocalDaysAndOnlyActivePhotosOnTheDriveCount() = runDbTest { db ->
        // The first and last minute of local days, and both sides of this zone's clock changes, if it has them.
        val times = listOf(
            local(2024, 3, 9, 0, 0), local(2024, 3, 9, 23, 59),
            local(2024, 3, 31, 1, 30), local(2024, 3, 31, 3, 30),
            local(2024, 10, 27, 1, 30), local(2024, 10, 27, 3, 30), local(2024, 10, 27, 23, 59),
            local(1999, 12, 31, 23, 59), local(2000, 1, 1, 0, 0),
        )
        times.forEach { db.add(it) }
        db.add(local(2024, 3, 9, 12, 0), state = "deleted")
        db.add(local(2024, 3, 9, 12, 0), fileType = OTHER_TYPE)
        db.add(local(2024, 3, 9, 12, 0), drive = Uuid.random())

        val expected = times.groupingBy { it.localDay() }.eachCount().toList().sortedByDescending { it.first }.map { DayCount(it.first, it.second) }
        assertEquals(expected, db.photoDays.dayCounts(identity, photos, LIBRARY))
    }

    @Test
    fun oneDayIsCountedOverItsLocalMidnights() = runDbTest { db ->
        val morning = local(2024, 10, 27, 0, 0)
        db.add(morning)
        db.add(local(2024, 10, 27, 23, 59))
        db.add(local(2024, 10, 26, 23, 59))
        db.add(local(2024, 10, 28, 0, 0))

        val day = assertNotNull(db.photoDays.dayOf(local(2024, 10, 27, 13, 0)))
        assertEquals(morning.localDay(), day)
        assertEquals(2, db.photoDays.photosOn(identity, photos, LIBRARY, day), "local midnight to local midnight, however long the day")
        assertEquals(0, db.photoDays.photosOn(identity, photos, LIBRARY, local(2024, 5, 1, 9, 0).localDay()))
    }

    @Test
    fun aWindowOfDaysHoldsExactlyThePhotosTheDayCountsPutOnThem() = runDbTest { db ->
        // The first and last minute of four local days in a row, so the UTC range's spare day has photos to leave out.
        val times = (8..11).flatMap { listOf(local(2024, 3, it, 0, 0), local(2024, 3, it, 23, 59)) }
        times.forEach { db.add(it) }
        val from = local(2024, 3, 9, 12, 0).localDay()

        val window = db.photoDays.headersOnDays(identity, photos, LIBRARY, from, from + 1)

        assertEquals(listOf(from + 1, from + 1, from, from), window.map { it.day }, "newest first, and only the two days asked")
        val counted = db.photoDays.dayCounts(identity, photos, LIBRARY).filter { it.day in from..from + 1 }
        assertEquals(counted.associate { it.day to it.photos }, window.groupingBy { it.day }.eachCount())
    }

    // Before 1970 SQLite guesses a zone's summer time from a stand-in year, so only the queries can be compared.
    @Test
    fun beforeEpochEveryQueryPutsAPhotoOnTheSameDay() = runDbTest { db ->
        val halfASecondBefore = -500L
        db.add(halfASecondBefore)

        val day = assertNotNull(db.photoDays.dayOf(halfASecondBefore))
        assertEquals(listOf(DayCount(day, 1)), db.photoDays.dayCounts(identity, photos, LIBRARY))
        assertEquals(1, db.photoDays.photosOn(identity, photos, LIBRARY, day))
        assertEquals(listOf(day), db.photoDays.headersOnDays(identity, photos, LIBRARY, day, day).map { it.day })
    }

    @Test
    fun aPhotoDatedPastTheYear9999IsLeftOutRatherThanStoppingTheCount() = runDbTest { db ->
        val inMicroseconds = local(2024, 3, 9, 12, 0) * 1000
        db.add(local(2024, 3, 9, 12, 0))
        db.add(inMicroseconds)
        // .NET's DateTime.MaxValue, which a zone ahead of UTC turns into the year 10000.
        db.add(253_402_300_799_999)

        assertEquals(listOf(DayCount(local(2024, 3, 9, 12, 0).localDay(), 1)), db.photoDays.dayCounts(identity, photos, LIBRARY))
        assertEquals(null, db.photoDays.dayOf(inMicroseconds))
        assertEquals(null, db.photoDays.dayOf(253_402_300_799_999))
    }

    @Test
    fun theLibraryAndTheBinEachHoldOnlyTheirOwnPhotos() = runDbTest { db ->
        val noon = local(2024, 3, 9, 12, 0)
        db.add(noon)
        db.add(noon + 60_000)
        db.add(noon + 120_000, archivalStatus = BIN)
        db.add(noon + 180_000, archivalStatus = ARCHIVED)
        val day = noon.localDay()

        assertEquals(listOf(DayCount(day, 2)), db.photoDays.dayCounts(identity, photos, LIBRARY))
        assertEquals(listOf(DayCount(day, 1)), db.photoDays.dayCounts(identity, photos, BIN))
        assertEquals(2, db.photoDays.photosOn(identity, photos, LIBRARY, day))
        assertEquals(1, db.photoDays.photosOn(identity, photos, BIN, day))
        assertEquals(2, db.photoDays.headersOnDays(identity, photos, LIBRARY, day, day).size)
        assertEquals(1, db.photoDays.headersOnDays(identity, photos, BIN, day, day).size)
    }

    @Test
    fun headersAreFoundByFileIdOnAnyShelfAndTheBinListsOnlyItsOwn() = runDbTest { db ->
        val noon = local(2024, 3, 9, 12, 0)
        val kept = db.add(noon)
        val binned = db.add(noon, archivalStatus = BIN)
        db.add(noon, archivalStatus = BIN, drive = Uuid.random())
        val ids = listOf(kept, binned, Uuid.random()) + List(600) { Uuid.random() }

        val found = db.photoDays.headersOf(identity, photos, ids).map { OdinSystemSerializer.deserialize<HomebaseFile>(it).fileId }
        assertEquals(setOf(kept, binned), found.toSet(), "past one read's worth of ids, and only this drive's")
        val bin = db.photoDays.headersOnShelf(identity, photos, BIN).map { OdinSystemSerializer.deserialize<HomebaseFile>(it).fileId }
        assertEquals(listOf(binned), bin)
    }

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private fun Long.localDay(): Int = Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).date.toEpochDays().toInt()

    private suspend fun DatabaseManager.add(
        userDate: Long,
        state: String = "active",
        fileType: Long = PHOTO_TYPE,
        drive: Uuid = photos,
        archivalStatus: Long = LIBRARY,
    ): Uuid {
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(this)
        val file = file(drive, userDate, state, fileType, archivalStatus)
        MainIndexMetaHelpers.upsertDriveMainIndex(this, processor.convertFileHeaderToDriveMainIndexRecord(identity, drive, file))
        return file.fileId
    }

    private fun file(drive: Uuid, userDate: Long, state: String, fileType: Long, archivalStatus: Long): HomebaseFile = OdinSystemSerializer.deserialize(
        """{
            "fileId": "${Uuid.random()}", "driveId": "$drive", "fileState": "$state", "fileSystemType": "standard",
            "serverFileIsEncrypted": false,
            "keyHeader": {"iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0], "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}},
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}", "created": $userDate, "updated": $userDate,
                "transitCreated": 0, "transitUpdated": 0, "isEncrypted": false,
                "senderOdinId": "test.sender", "originalAuthor": "test.sender",
                "appData": {"uniqueId": "${Uuid.random()}", "tags": null, "fileType": $fileType, "dataType": 0, "groupId": null,
                    "userDate": $userDate, "content": null, "previewThumbnail": null, "archivalStatus": $archivalStatus},
                "localAppData": null, "referencedFile": null, "reactionPreview": null,
                "versionTag": "${Uuid.random()}", "payloads": [], "dataSource": null
            },
            "serverMetadata": {
                "accessControlList": {"requiredSecurityGroup": "owner", "circleIdList": null, "odinIdList": null},
                "doNotIndex": false, "allowDistribution": true, "fileSystemType": "standard", "fileByteCount": 100,
                "originalRecipientCount": 0, "transferHistory": null
            },
            "priority": 0, "fileByteCount": 100
        }""",
    )

    private companion object {
        const val PHOTO_TYPE = 7201L
        const val OTHER_TYPE = 7202L
        const val LIBRARY = 0L
        const val ARCHIVED = 1L
        const val BIN = 2L
    }
}
