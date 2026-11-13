package id.homebase.api.client.drives.files

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.client.drives.ServerFile
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoMetadata
import id.homebase.api.video.videoMetadataFrom
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// UPLOADS.md sections 1, 2 and 6.
class DescriptorContentV1Test {

    private val photo = DescriptorContentV1(
        kind = DescriptorContentV1.Kind.IMAGE,
        name = "PXL_20261107_083131163.jpg",
        original = 0,
        pixelWidth = 1152,
        pixelHeight = 2048,
        quality = DescriptorContentV1.Quality.SD,
    )

    private fun payload(contentType: String, descriptorContent: String?, key: String = "chat_web0") =
        PayloadDescriptor(key = key, contentType = contentType, descriptorContent = descriptorContent)

    @Test
    fun itIsWrittenWithShortKeysAndNoNulls() {
        assertEquals(
            """{"v":1,"k":"i","n":"PXL_20261107_083131163.jpg","o":0,"w":1152,"h":2048,"q":"sd"}""",
            photo.encode(),
        )
        assertEquals("""{"v":1,"k":"f"}""", DescriptorContentV1(kind = DescriptorContentV1.Kind.FILE).encode())
    }

    @Test
    fun itReadsBackEveryField() {
        val video = DescriptorContentV1(
            kind = DescriptorContentV1.Kind.VIDEO,
            name = "clip.mov",
            original = 0,
            pixelWidth = 1280,
            pixelHeight = 720,
            quality = DescriptorContentV1.Quality.HD,
            video = DescriptorContentV1.Video(durationMs = 12_500, codec = "video/mp2t", segmented = 1, playlistKey = "pls_0001", plainBytes = 4_000_000, bitDepth = 10, hdr = 1, bitrateBps = 2_500_000),
        )
        assertEquals(photo, DescriptorContentV1.parseOrNull(photo.encode()))
        assertEquals(video, DescriptorContentV1.parseOrNull(video.encode()))
    }

    @Test
    fun noOlderFormIsMistakenForIt() {
        listOf(
            "IMG_1234.pdf",
            "",
            """{"isSticker":true,"format":"image/png"}""",
            """{"name":"note.m4a","lengthSeconds":3}""",
            """{"mimeType":"video/mp4","isSegmented":false,"duration":1000.0}""",
            """[{"url":"https://example.com","hasImage":false,"description":"","title":"Example"}]""",
        ).forEach { assertNull(DescriptorContentV1.parseOrNull(it), it) }
    }

    @Test
    fun theParserReadsEachKindIntoTheTypesTheAppUses() {
        val image = payload("image/webp", photo.encode()).descriptorInfo() as DescriptorContent.ImageFile
        assertEquals(DescriptorContent.ImageFile(quality = MediaQuality.STANDARD, pixelWidth = 1152, pixelHeight = 2048, name = "PXL_20261107_083131163.jpg", original = false), image)

        val sticker = payload("image/png", DescriptorContentV1(kind = DescriptorContentV1.Kind.STICKER).encode()).descriptorInfo()
        assertEquals(DescriptorContent.ImageFile(isSticker = true, format = "image/png"), sticker)

        val audio = payload("audio/mp4", DescriptorContentV1(kind = DescriptorContentV1.Kind.AUDIO, name = "memo.m4a", lengthSeconds = 7).encode()).descriptorInfo()
        assertEquals(DescriptorContent.AudioFile(name = "memo.m4a", lengthSeconds = 7), audio)

        val file = payload("application/pdf", DescriptorContentV1(kind = DescriptorContentV1.Kind.FILE, name = "contract.pdf").encode()).descriptorInfo()
        assertEquals(DescriptorContent.File(name = "contract.pdf"), file)
    }

    @Test
    fun namesComeFromTheNameFieldAndStickersStayNeutral() {
        assertEquals("PXL_20261107_083131163.jpg", payload("image/webp", photo.encode()).filename())
        assertEquals("StickerFile.png", payload("image/png", DescriptorContentV1(kind = DescriptorContentV1.Kind.STICKER, name = "IMG_9.png").encode()).filename())
        assertEquals("clip.mov", payload("video/mp4", DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO, name = "clip.mov", video = DescriptorContentV1.Video(1, "video/mp4")).encode()).filename())
        assertNull(payload("image/webp", photo.copy(name = null).encode()).filename())
    }

    @Test
    fun appContentIsTheXFieldOrTheWholeOlderString() {
        val links = """[{"url":"https://example.com","hasImage":false,"description":"","title":"Example"}]"""
        assertEquals(links, payload("image/webp", DescriptorContentV1(kind = DescriptorContentV1.Kind.APP, appContent = links).encode(), "chat_links").appContent())
        assertEquals(links, payload("image/webp", links, "chat_links").appContent())
        assertNull(payload("image/webp", photo.encode()).appContent())
        assertEquals(DescriptorContent.NoteFile("# Groceries"), payload("text/markdown", DescriptorContentV1(kind = DescriptorContentV1.Kind.APP, appContent = "# Groceries").encode()).descriptorInfo())
    }

    @Test
    fun sealedContentOpensWithTheFileKeyOnly() = runTest {
        val key = KeyHeader.newRandom16().aesKey
        val sealed = DescriptorContentSeal.seal(photo.encode(), key)

        assertTrue(DescriptorContentSeal.isSealed(sealed))
        assertTrue(sealed.startsWith("""{"e":"""))
        assertEquals(photo.encode(), DescriptorContentSeal.openOrNull(sealed, key))
        assertNull(DescriptorContentSeal.openOrNull(sealed, KeyHeader.newRandom16().aesKey))
    }

    @Test
    fun aChangedByteFailsToOpen() = runTest {
        val key = KeyHeader.newRandom16().aesKey
        val sealed = DescriptorContentSeal.seal(photo.encode(), key)
        val bytes = Base64.decode(sealed.substringAfter("\"e\":\"").substringBefore("\""))
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 1).toByte()
        val tampered = """{"e":"${Base64.encode(bytes)}"}"""

        assertNull(DescriptorContentSeal.openOrNull(tampered, key))
    }

    @Test
    fun everySealHasItsOwnIv() = runTest {
        val key = KeyHeader.newRandom16().aesKey
        assertNotEquals(DescriptorContentSeal.seal(photo.encode(), key), DescriptorContentSeal.seal(photo.encode(), key))
    }

    @Test
    fun olderDescriptorContentIsNotSealed() {
        listOf("IMG_1234.pdf", "e", """{"e":1,"f":2}""", """{"isSticker":true}""", photo.encode()).forEach {
            assertFalse(DescriptorContentSeal.isSealed(it), it)
        }
    }

    @Test
    fun aFileIsOpenedOnArrivalSoReadersSeePlainDescriptorContent() = runTest {
        val sharedSecret = SecureByteArray(ByteArrayUtil.getRndByteArray(16))
        val fileKey = KeyHeader.newRandom16()
        val locked = EncryptedKeyHeader.encryptKeyHeaderAes(fileKey, ByteArrayUtil.getRndByteArray(16), sharedSecret)
        val sealed = DescriptorContentSeal.seal(photo.encode(), fileKey.aesKey)
        val legacy = "contract.pdf"
        val json = """
            {
              "fileId": "${Uuid.random()}",
              "driveId": "${Uuid.random()}",
              "fileState": "active",
              "fileSystemType": "standard",
              "sharedSecretEncryptedKeyHeader": { "encryptionVersion": 1, "type": "aes", "iv": "${Base64.encode(locked.iv)}", "encryptedAesKey": "${Base64.encode(locked.encryptedAesKey)}" },
              "fileMetadata": {
                "isEncrypted": true,
                "versionTag": "${Uuid.random()}",
                "appData": { "uniqueId": "${Uuid.random()}", "fileType": 1 },
                "payloads": [
                  { "key": "chat_web0", "contentType": "image/webp", "descriptorContent": ${OdinSystemSerializer.serialize(sealed)} },
                  { "key": "chat_web1", "contentType": "application/pdf", "descriptorContent": "$legacy" }
                ]
              },
              "serverMetadata": {}
            }
        """

        val file = OdinSystemSerializer.deserialize<ServerFile>(json).asHomebaseFile(sharedSecret)

        assertEquals(photo.encode(), file.fileMetadata.payloads!![0].descriptorContent)
        assertEquals("PXL_20261107_083131163.jpg", file.fileMetadata.payloads!![0].filename())
        assertEquals(legacy, file.fileMetadata.payloads!![1].descriptorContent)
    }

    @Test
    fun aVideoInTheCurrentFormatReadsItsPlaylistPayload() = runTest {
        val video = DescriptorContentV1(
            kind = DescriptorContentV1.Kind.VIDEO,
            pixelWidth = 1280,
            pixelHeight = 720,
            video = DescriptorContentV1.Video(durationMs = 9_000, codec = "video/mp2t", segmented = 1, playlistKey = "pls_0000", plainBytes = 1_000),
        )
        val read = mutableListOf<String>()
        val metadata = videoMetadataFrom(video.encode(), "chat_web0") { key -> read += key; "#EXTM3U" }!!

        assertEquals(listOf("pls_0000"), read)
        assertEquals("#EXTM3U", metadata.hlsPlaylist)
        assertTrue(metadata.isSegmented)
        assertEquals(9_000f, metadata.duration)
        assertEquals(1280, metadata.widthPx)
        assertEquals("chat_web0", metadata.key)
    }

    @Test
    fun anOlderVideoStubStillReadsItsOverflowPayload() = runTest {
        val full = VideoMetadata(mimeType = "application/vnd.apple.mpegurl", isSegmented = true, duration = 5_000f, hlsPlaylist = "#EXTM3U")
        val stub = full.copy(isDescriptorContentComplete = false, hlsPlaylist = null, key = "pld_desc0")
        val metadata = videoMetadataFrom(OdinSystemSerializer.serialize(stub), "chat_web0") { key ->
            if (key == "pld_desc0") OdinSystemSerializer.serialize(full) else null
        }

        assertEquals("#EXTM3U", metadata?.hlsPlaylist)
        assertEquals(full, videoMetadataFrom(OdinSystemSerializer.serialize(full), "chat_web0") { null })
    }
}
