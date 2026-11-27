package id.homebase.photobase.gallery

import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import id.homebase.api.media.MediaLocation
import kotlin.uuid.Uuid

class PhotoGalleryItemTest {
    @Test
    fun anUntouchedOriginalVideoShowsAndPlaysItsCopy() {
        val original = PayloadDescriptor(
            key = PhotobaseProtocol.VideoPayloadKey,
            contentType = "video/quicktime",
            descriptorContent = DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO, original = 1, video = DescriptorContentV1.Video(playableCopyKey = "vcp_0000")).encode(),
        )
        val copy = PayloadDescriptor(key = "vcp_0000", contentType = "video/mp4")
        val file = galleryTestFile(1).let {
            it.copy(fileMetadata = it.fileMetadata.copy(payloads = listOf(original, copy, PayloadDescriptor(key = "pls_0001", contentType = "application/vnd.apple.mpegurl"))))
        }

        val item = file.toGalleryItem()!!

        assertEquals(PhotobaseProtocol.VideoPayloadKey, item.payload.key, "the original is the item")
        assertEquals("vcp_0000", item.shown.key, "its copy is what the gallery shows and plays")
    }

    @Test
    fun itsDatesSpaceAndShelfComeFromItsRecord() {
        val payload = PayloadDescriptor(
            key = PhotobaseProtocol.ImagePayloadKey,
            contentType = "image/jpeg",
            bytesWritten = 3_000,
            thumbnails = listOf(ThumbnailDescriptor(bytesWritten = 200), ThumbnailDescriptor(bytesWritten = null)),
        )
        val file = galleryTestFile(1, payload = payload).let {
            it.copy(fileMetadata = it.fileMetadata.copy(created = UnixTimeUtc(1_000), updated = UnixTimeUtc(9_000)))
        }.at(ArchivalStatus.Archived)

        val item = file.toGalleryItem()!!

        assertEquals(1_000, item.backedUpAtMs)
        assertEquals(9_000, item.changedAtMs)
        assertEquals(3_200, item.storedBytes)
        assertEquals(true, item.archived)
        assertNull(galleryTestFile(1).toGalleryItem()!!.storedBytes, "nothing written: unknown, not 0 bytes")
    }

    @Test
    fun whereItWasTakenComesFromItsHeader() {
        val file = galleryTestFile(1).withContent("""{"v":1,"latitude":37.7749,"longitude":-122.4194,"altitude":12.5}""")

        assertEquals(MediaLocation(37.7749, -122.4194, 12.5), file.toGalleryItem()!!.location())
    }

    @Test
    fun aHeaderWithoutBothCoordinatesOrUnreadableHasNoLocation() {
        assertNull(galleryTestFile(1).toGalleryItem()!!.location())
        assertNull(galleryTestFile(1).withContent("""{"v":1,"latitude":37.7749}""").toGalleryItem()!!.location())
        assertNull(galleryTestFile(1).withContent("not json").toGalleryItem()!!.location())
    }

    private fun HomebaseFile.withContent(content: String) =
        copy(fileMetadata = fileMetadata.copy(appData = fileMetadata.appData.copy(content = content)))

    @Test
    fun aVideosPlaylistPayloadIsNeverTheOneShown() {
        val file = galleryTestFile(1).let {
            it.copy(
                fileMetadata = it.fileMetadata.copy(
                    payloads = listOf(
                        PayloadDescriptor(key = "pls_0000", contentType = "application/vnd.apple.mpegurl"),
                        PayloadDescriptor(key = PhotobaseProtocol.VideoPayloadKey, contentType = "video/mp4"),
                    ),
                ),
            )
        }
        assertEquals(PhotobaseProtocol.VideoPayloadKey, file.toGalleryItem()!!.payload.key)
    }
}

internal fun galleryTestFile(capturedAtMs: Long, fileId: Uuid = Uuid.random(), payload: PayloadDescriptor = PayloadDescriptor(key = PhotobaseProtocol.ImagePayloadKey, contentType = "image/jpeg")) = HomebaseFile(
    fileId = fileId,
    driveId = PhotobaseProtocol.DriveAlias,
    fileState = FileState.Active,
    fileSystemType = FileSystemType.Standard,
    keyHeader = KeyHeader.newRandom16(),
    fileMetadata = FileMetadata(
        appData = AppFileMetaData(
            fileType = PhotobaseProtocol.PhotoFileType,
            dataType = PhotobaseProtocol.ImageDataType,
            userDate = capturedAtMs,
        ),
        payloads = listOf(payload),
    ),
    serverMetadata = ServerMetadata(),
)

internal fun HomebaseFile.newVersion() = copy(fileMetadata = fileMetadata.copy(versionTag = Uuid.random()))

internal fun HomebaseFile.at(status: ArchivalStatus) =
    copy(fileMetadata = fileMetadata.copy(appData = fileMetadata.appData.copy(archivalStatus = status)))
