package id.homebase.chat.entephotos

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.Base64Bytes
import id.homebase.core.config.EntePhotosDrive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    ) = HomebaseFile(
        fileId = Uuid.random(),
        driveId = Uuid.parse(EntePhotosDrive.ALIAS),
        serverFileIsEncrypted = encrypted,
        fileState = FileState.Active,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(fileIv, SecureByteArray(fileKey)),
        fileMetadata = FileMetadata(
            isEncrypted = encrypted,
            appData = AppFileMetaData(fileType = fileType, content = content),
            payloads = listOf(PayloadDescriptor(key = payloadKey, contentType = contentType, iv = iv?.let(::Base64Bytes))),
        ),
        serverMetadata = ServerMetadata(),
    )

    @Test
    fun aPhotoIsReadFromItsSealedDetailsWithThePayloadsOwnIv() {
        val file = photo()
        val read = file.toEntePhoto()!!
        assertEquals(file.fileId, read.fileId)
        assertEquals("IMG_0001.jpg", read.title)
        assertEquals(1793647716285000, read.takenMicros)
        assertContentEquals(payloadIv, read.payloadKey.iv)
        assertContentEquals(fileKey, read.payloadKey.aesKey.unsafeBytes)
    }

    @Test
    fun onlyHomebaseEncryptedImagesAreTaken() {
        assertNull(photo(encrypted = false).toEntePhoto())
        assertNull(photo(iv = null).toEntePhoto())
        assertNull(photo(payloadKey = "thumb").toEntePhoto())
        assertNull(photo(fileType = 7211).toEntePhoto())
        assertNull(photo(contentType = "video/mp4").toEntePhoto())
        assertNull(photo(content = """{"metadata":{"title":"v.mov","fileType":1}}""").toEntePhoto())
        assertNull(photo(content = "not json").toEntePhoto())
        assertNull(photo(content = null).toEntePhoto())
    }

    @Test
    fun theTempFileGetsTheExtensionTheSendPathReadsTheTypeFrom() {
        assertEquals(".jpg", extensionFor("image/jpeg", "a.png"))
        assertEquals(".heic", extensionFor("image/HEIC", null))
        assertEquals(".png", extensionFor("image/x-unknown", "Shot.PNG"))
        assertEquals(".jpg", extensionFor(null, "no extension"))
    }
}
