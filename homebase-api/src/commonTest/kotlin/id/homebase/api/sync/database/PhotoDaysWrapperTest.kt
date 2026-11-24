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
        assertEquals(expected, db.photoDays.dayCounts(identity, photos, PHOTO_TYPE))
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
        assertEquals(2, db.photoDays.photosOn(identity, photos, PHOTO_TYPE, day), "local midnight to local midnight, however long the day")
        assertEquals(0, db.photoDays.photosOn(identity, photos, PHOTO_TYPE, local(2024, 5, 1, 9, 0).localDay()))
    }

    // Before 1970 SQLite guesses a zone's summer time from a stand-in year, so only the queries can be compared.
    @Test
    fun beforeEpochEveryQueryPutsAPhotoOnTheSameDay() = runDbTest { db ->
        val halfASecondBefore = -500L
        db.add(halfASecondBefore)

        val day = assertNotNull(db.photoDays.dayOf(halfASecondBefore))
        assertEquals(listOf(DayCount(day, 1)), db.photoDays.dayCounts(identity, photos, PHOTO_TYPE))
        assertEquals(1, db.photoDays.photosOn(identity, photos, PHOTO_TYPE, day))
    }

    @Test
    fun aPhotoDatedPastTheYear9999IsLeftOutRatherThanStoppingTheCount() = runDbTest { db ->
        val inMicroseconds = local(2024, 3, 9, 12, 0) * 1000
        db.add(local(2024, 3, 9, 12, 0))
        db.add(inMicroseconds)
        // .NET's DateTime.MaxValue, which a zone ahead of UTC turns into the year 10000.
        db.add(253_402_300_799_999)

        assertEquals(listOf(DayCount(local(2024, 3, 9, 12, 0).localDay(), 1)), db.photoDays.dayCounts(identity, photos, PHOTO_TYPE))
        assertEquals(null, db.photoDays.dayOf(inMicroseconds))
        assertEquals(null, db.photoDays.dayOf(253_402_300_799_999))
    }

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private fun Long.localDay(): Int = Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).date.toEpochDays().toInt()

    private suspend fun DatabaseManager.add(userDate: Long, state: String = "active", fileType: Long = PHOTO_TYPE, drive: Uuid = photos) {
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(this)
        MainIndexMetaHelpers.upsertDriveMainIndex(this, processor.convertFileHeaderToDriveMainIndexRecord(identity, drive, file(drive, userDate, state, fileType)))
    }

    private fun file(drive: Uuid, userDate: Long, state: String, fileType: Long): HomebaseFile = OdinSystemSerializer.deserialize(
        """{
            "fileId": "${Uuid.random()}", "driveId": "$drive", "fileState": "$state", "fileSystemType": "standard",
            "serverFileIsEncrypted": false,
            "keyHeader": {"iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0], "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}},
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}", "created": $userDate, "updated": $userDate,
                "transitCreated": 0, "transitUpdated": 0, "isEncrypted": false,
                "senderOdinId": "test.sender", "originalAuthor": "test.sender",
                "appData": {"uniqueId": "${Uuid.random()}", "tags": null, "fileType": $fileType, "dataType": 0, "groupId": null,
                    "userDate": $userDate, "content": null, "previewThumbnail": null, "archivalStatus": 0},
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
    }
}
