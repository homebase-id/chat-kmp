package id.homebase.chat.entephotos

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.Base64Bytes
import id.homebase.core.config.EntePhotosDrive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// Shaped as the Ente web app writes a photo (homebase-ente web/packages/homebase/photo.ts, upload-service.ts uploadToHomebase).
class EntePhotoTest {

    private val fileKey = ByteArray(16) { it.toByte() }
    private val fileIv = ByteArray(16) { (it + 50).toByte() }
    private val payloadIv = ByteArray(16) { (it + 100).toByte() }

    private fun photo(
        content: String? = """{"metadata":{"title":"IMG_0001.jpg","fileType":0,"creationTime":1793647716285000},"pubMagicMetadata":{"w":1}}""",
        fileType: Int = EntePhotosDrive.PHOTO_FILE_TYPE,
        contentType: String = "image/jpeg",
        payloadKey: String = EntePhotosDrive.PHOTO_PAYLOAD_KEY,
        iv: ByteArray? = payloadIv,
        encrypted: Boolean = true,
        size: Long? = 2_000_000,
        thumbnails: List<ThumbnailDescriptor>? = listOf(ThumbnailDescriptor(pixelWidth = 320, pixelHeight = 240, contentType = "image/webp")),
        uniqueId: Uuid? = Uuid.random(),
    ) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = Uuid.parse(EntePhotosDrive.ALIAS),
        serverFileIsEncrypted = encrypted,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(fileIv, SecureByteArray(fileKey)),
        fileMetadata = FileMetadata(
            isEncrypted = encrypted,
            appData = AppFileMetaData(uniqueId = uniqueId, tags = uniqueId?.let(::listOf), fileType = fileType, content = content),
            payloads = listOf(
                PayloadDescriptor(key = payloadKey, contentType = contentType, iv = iv?.let(::Base64Bytes), bytesWritten = size, thumbnails = thumbnails),
            ),
        ),
        serverMetadata = ServerMetadata(),
    )

    @Test
    fun aPhotoIsReadFromItsSealedDetailsWithThePayloadsOwnIv() {
        val file = photo()
        val read = file.toEntePhoto()!!
        assertEquals(file.fileId, read.fileId)
        assertEquals(file.fileMetadata.appData.uniqueId, read.uniqueId)
        assertFalse(read.isVideo)
        assertEquals("IMG_0001.jpg", read.title)
        assertEquals(1793647716285000, read.takenMicros)
        assertContentEquals(payloadIv, read.payloadKey.iv)
        assertContentEquals(fileKey, read.payloadKey.aesKey.unsafeBytes)
    }

    @Test
    fun aVideoIsReadWithItsDuration() {
        val read = photo(
            content = """{"metadata":{"title":"clip.mov","fileType":1,"creationTime":1,"duration":75}}""",
            contentType = "video/quicktime",
        ).toEntePhoto()!!
        assertTrue(read.isVideo)
        assertEquals(75, read.durationSeconds)
        assertTrue(read.hasThumbnails)
        assertFalse(read.tooBigToSend)
    }

    @Test
    fun onlyVideosOver200MbAreTooBigToSend() {
        val video = """{"metadata":{"title":"v.mp4","fileType":1}}"""
        assertFalse(photo(content = video, contentType = "video/mp4", size = MAX_ENTE_VIDEO_BYTES).toEntePhoto()!!.tooBigToSend)
        assertTrue(photo(content = video, contentType = "video/mp4", size = MAX_ENTE_VIDEO_BYTES + 1).toEntePhoto()!!.tooBigToSend)
        assertFalse(photo(size = MAX_ENTE_VIDEO_BYTES * 2).toEntePhoto()!!.tooBigToSend)
        assertFalse(photo(content = video, contentType = "video/mp4", size = null).toEntePhoto()!!.tooBigToSend)
    }

    @Test
    fun onlyHomebaseEncryptedImagesAndVideosAreTaken() {
        assertNull(photo(encrypted = false).toEntePhoto())
        assertNull(photo(iv = null).toEntePhoto())
        assertNull(photo(payloadKey = "thumb").toEntePhoto())
        assertNull(photo(fileType = 7211).toEntePhoto())
        // The details and the payload must agree.
        assertNull(photo(contentType = "video/mp4").toEntePhoto())
        assertNull(photo(content = """{"metadata":{"title":"v.mov","fileType":1}}""").toEntePhoto())
        // Live photos are a zip of a still and a clip.
        assertNull(photo(content = """{"metadata":{"title":"l.heic","fileType":2}}""", contentType = "application/zip").toEntePhoto())
        assertNull(photo(content = "not json").toEntePhoto())
        assertNull(photo(content = null).toEntePhoto())
    }

    private fun album(content: String?, uniqueId: Uuid? = Uuid.random(), fileType: Int = EntePhotosDrive.ALBUM_FILE_TYPE) =
        photo(content = content, fileType = fileType, uniqueId = uniqueId)

    @Test
    fun anAlbumIsReadFromItsSealedDetails() {
        val file = album("""{"type":"album","name":"Holiday"}""")
        val read = file.toEnteAlbum()!!
        assertEquals(file.fileMetadata.appData.uniqueId, read.id)
        assertEquals("Holiday", read.name)
        assertEquals("album", read.type)
        assertFalse(read.hidden)
        assertNull(album("""{"type":"album"}""").toEnteAlbum())
        assertNull(album("""{"type":"album","name":"x"}""", uniqueId = null).toEnteAlbum())
        assertNull(album("""{"type":"album","name":"x"}""", fileType = EntePhotosDrive.PHOTO_FILE_TYPE).toEnteAlbum())
    }

    @Test
    fun hiddenAlbumsAreKnownByTheirSubTypeOrVisibility() {
        assertTrue(album("""{"type":"album","name":".hidden","magicMetadata":{"version":1,"count":1,"data":{"subType":1}}}""").toEnteAlbum()!!.hidden)
        assertTrue(album("""{"type":"album","name":"Secret","magicMetadata":{"version":1,"count":1,"data":{"visibility":2}}}""").toEnteAlbum()!!.hidden)
        assertFalse(album("""{"type":"album","name":"Old","magicMetadata":{"version":1,"count":1,"data":{"visibility":1}}}""").toEnteAlbum()!!.hidden)
    }

    @Test
    fun albumsAreOfferedFavoritesFirstUncategorizedLastAndNoneHidden() {
        fun a(name: String, type: String = "album", hidden: Boolean = false) = EnteAlbum(Uuid.random(), name, type, hidden)
        val offered = listOf(a("Uncategorized", "uncategorized"), a("beach"), a("Secret", hidden = true), a("Favorites", "favorites"), a("Alps", "folder"))
            .offered().map { it.name }
        assertEquals(listOf("Favorites", "Alps", "beach", "Uncategorized"), offered)
    }

    @Test
    fun aMembershipNamesItsPhotoByTheTagThatIsntTheAlbum() {
        val albumId = Uuid.random()
        val photoId = Uuid.random()
        fun membership(group: Uuid?, tags: List<Uuid>?, fileType: Int = EntePhotosDrive.MEMBERSHIP_FILE_TYPE) = photo(content = null, fileType = fileType).let {
            it.copy(fileMetadata = it.fileMetadata.copy(appData = AppFileMetaData(groupId = group, tags = tags, fileType = fileType)))
        }
        assertEquals(photoId, membership(albumId, listOf(photoId, albumId)).memberOf(albumId))
        assertNull(membership(Uuid.random(), listOf(photoId, albumId)).memberOf(albumId))
        assertNull(membership(albumId, listOf(albumId)).memberOf(albumId))
        assertNull(membership(albumId, listOf(photoId, albumId), fileType = EntePhotosDrive.PHOTO_FILE_TYPE).memberOf(albumId))
    }

    @Test
    fun theTempFileGetsTheExtensionTheSendPathReadsTheTypeFrom() {
        assertEquals(".jpg", extensionFor("image/jpeg", "a.png"))
        assertEquals(".heic", extensionFor("image/HEIC", null))
        assertEquals(".png", extensionFor("image/x-unknown", "Shot.PNG"))
        assertEquals(".jpg", extensionFor(null, "no extension"))
        assertEquals(".mov", extensionFor("video/quicktime", "clip.MP4"))
        assertEquals(".mkv", extensionFor("video/x-unknown", "a.mkv"))
        assertEquals(".mp4", extensionFor("video/x-unknown", null))
    }
}
