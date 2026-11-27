package id.homebase.photobase.info

import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.media.MediaLocation
import id.homebase.photobase.gallery.galleryTestFile
import id.homebase.photobase.gallery.newVersion
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.UtcOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PhotoHeaderEditorTest {
    private val tag = Uuid.random()
    private val photo = galleryTestFile(1_000).let {
        it.copy(fileMetadata = it.fileMetadata.copy(appData = it.fileMetadata.appData.copy(tags = listOf(tag), content = """{"v":1,"latitude":1.0,"longitude":2.0,"altitude":3.0,"later":"kept"}""")))
    }.newVersion()

    @Test
    fun aNewTimeSetsUserDateAndKeepsTheRestOfTheHeader() = runTest {
        val sent = mutableListOf<UpdateFileByFileIdRequest>()
        var synced = 0
        val editor = PhotoHeaderEditor(header = { _, _ -> photo }, update = { sent += it }, sync = { synced++ })
        val at = Instant.parse("2026-01-15T06:00:00Z")

        editor.save(photo.driveId, photo.fileId, PhotoEdit.Time(at, UtcOffset(hours = 9)))

        val appData = sent.single().metadata.appData
        assertEquals(at.toEpochMilliseconds(), appData.userDate)
        assertEquals(listOf(tag), appData.tags)
        assertEquals(photo.fileMetadata.appData.fileType, appData.fileType)
        assertEquals(photo.fileMetadata.versionTag, sent.single().metadata.versionTag)
        assertEquals(1, synced)
    }

    @Test
    fun aConflictReadsTheHeaderAgain() = runTest {
        var reads = 0
        var tries = 0
        val editor = PhotoHeaderEditor(
            header = { _, _ -> reads++; photo },
            update = { if (tries++ == 0) throw ClientException(400, OdinClientErrorCode.VersionTagMismatch, "conflict", null, ProblemDetails(status = 400, title = "conflict")) },
            sync = {},
        )

        editor.save(photo.driveId, photo.fileId, PhotoEdit.Place(null))

        assertEquals(2, reads)
        assertEquals(2, tries)
    }

    @Test
    fun aNewPlaceDropsTheOldAltitudeAndKeepsUnknownFields() {
        val moved = Json.parseToJsonElement(editedContent(photo.fileMetadata.appData.content, PhotoEdit.Place(MediaLocation(35.0, 139.0)))).jsonObject

        assertEquals("35.0", moved["latitude"]?.jsonPrimitive?.content)
        assertEquals("139.0", moved["longitude"]?.jsonPrimitive?.content)
        assertFalse("altitude" in moved)
        assertEquals("kept", moved["later"]?.jsonPrimitive?.content)
    }

    @Test
    fun aRemovedPlaceLeavesNoCoordinatesAndATimeRecordsItsOffset() {
        val removed = Json.parseToJsonElement(editedContent(photo.fileMetadata.appData.content, PhotoEdit.Place(null))).jsonObject
        assertFalse("latitude" in removed || "longitude" in removed || "altitude" in removed)

        val timed = Json.parseToJsonElement(editedContent(null, PhotoEdit.Time(Instant.fromEpochSeconds(0), UtcOffset(hours = -4)))).jsonObject
        assertEquals("-14400", timed["offsetSeconds"]?.jsonPrimitive?.content)
        assertEquals("1", timed["v"]?.jsonPrimitive?.content)
    }

    @Test
    fun aNewerAppsVersionStays() {
        val edited = Json.parseToJsonElement(editedContent("""{"v":2,"latitude":1.0,"longitude":2.0}""", PhotoEdit.Place(null))).jsonObject

        assertEquals("2", edited["v"]?.jsonPrimitive?.content)
    }
}
