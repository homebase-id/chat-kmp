package id.homebase.chat.services.builder

import id.homebase.api.image.readJpegExifOrientation
import id.homebase.api.image.ImageMetadataScrubber
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.DescriptorContentSeal
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.MediaQuality
import id.homebase.upload.LibraryKind
import id.homebase.upload.MetadataPolicy
import id.homebase.upload.PayloadBundle
import id.homebase.upload.UploadOptions
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What the builder writes once the switch is on, read back the way receivers read it (UPLOADS.md sections 1 and 3).
class UserUploadBuilderCurrentFormatTest {

    private val fileOps = JvmFileOperationsProvider()
    private val dir = kotlin.io.path.createTempDirectory("hb-builder-test").toFile()

    private fun image(w: Int, h: Int, format: EncodedImageFormat = EncodedImageFormat.JPEG): ByteArray {
        val info = ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB), w, h)
        val pixels = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else ((i / 4) % 251).toByte() }
        return Image.makeRaster(info, pixels, w * 4).encodeToData(format)?.bytes ?: error("encode failed")
    }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }.path

    private suspend fun build(
        source: UploadSource,
        quality: MediaQuality = MediaQuality.STANDARD,
        metadata: MetadataPolicy = MetadataPolicy.STRIP,
    ): PayloadBundle = UserUploadBuilder.build(
        listOf(source),
        UploadOptions(quality = quality, metadata = metadata, currentFormat = true),
        fileOps,
    ) { "chat_web0" }

    private fun PayloadBundle.received() = payloads.single().let {
        PayloadDescriptor(key = it.key, contentType = it.contentType, descriptorContent = it.descriptorContent)
    }

    private fun PayloadBundle.written() = DescriptorContentV1.parseOrNull(payloads.single().descriptorContent!!)!!

    @Test
    fun aPhotoSentAtStandardIsACopyWithItsNameSizeAndQuality() = runTest {
        val sent = build(UploadSource(file("a.jpg", image(3000, 2000)), "image/jpeg", pickedName = "PXL_20261107_083131163.jpg"))
        val written = sent.written()

        assertEquals("image/webp", sent.payloads.single().contentType)
        assertEquals(DescriptorContentV1.Kind.IMAGE, written.kind)
        assertEquals(0, written.original)
        assertEquals(DescriptorContentV1.Quality.SD, written.quality)
        val image = sent.received().imageFile()!!
        assertEquals("PXL_20261107_083131163.jpg", image.name)
        assertEquals(false, image.original)
        assertTrue(image.pixelWidth!! < 3000 && image.pixelHeight!! < 2000, "the final image's size, not the source's")
        assertNull(sent.received().filename(), "WebP bytes are never saved under .jpg")
    }

    @Test
    fun aPhotoSentAtOriginalIsTheUsersFile() = runTest {
        val sent = build(UploadSource(file("b.jpg", image(800, 600)), "image/jpeg", pickedName = "IMG_1.jpg"), MediaQuality.ORIGINAL)
        val image = sent.received().imageFile()!!

        assertEquals("image/jpeg", sent.payloads.single().contentType)
        assertEquals(true, image.original)
        assertEquals(MediaQuality.ORIGINAL, image.quality)
        assertEquals(800, image.pixelWidth)
        assertEquals("IMG_1.jpg", sent.received().filename())
    }

    @Test
    fun aStickerNeverCarriesTheSendersName() = runTest {
        val sent = build(UploadSource(file("s.png", image(300, 300, EncodedImageFormat.PNG)), "image/png", pickedName = "IMG_9.png", sticker = true))
        assertEquals(DescriptorContentV1.Kind.STICKER, sent.written().kind)
        assertNull(sent.written().name)
        assertEquals("StickerFile.png", sent.received().filename())
    }

    @Test
    fun theBytesDecideAnImagesType() = runTest {
        val png = file("mislabelled.jpg", image(200, 200, EncodedImageFormat.PNG))
        assertEquals("image/png", build(UploadSource(png, "image/jpeg"), MediaQuality.ORIGINAL).payloads.single().contentType)
        val untyped = build(UploadSource(png, "application/octet-stream"), MediaQuality.ORIGINAL)
        assertEquals("image/png", untyped.payloads.single().contentType)
        assertTrue(untyped.thumbnails.isNotEmpty(), "an untyped image still goes the image way")
    }

    @Test
    fun aLibraryFileIsWhatTheLibrarySaysWhateverItsType() = runTest {
        val clip = build(UploadSource(file("clip", ByteArray(8)), "application/octet-stream", libraryKind = LibraryKind.VIDEO, untouched = true))
        assertEquals(DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO, original = 1), clip.written())
        assertTrue(clip.payloads.single().skipVideoProcessing, "an untouched video is sent as it is")

        val raw = build(UploadSource(file("raw", ByteArray(8)), "", libraryKind = LibraryKind.IMAGE))
        assertEquals(DescriptorContentV1.Kind.IMAGE, raw.written().kind, "a picture the decoder can't draw is still a picture")

        val png = build(UploadSource(file("untyped", image(64, 64, EncodedImageFormat.PNG)), "", libraryKind = LibraryKind.IMAGE), MediaQuality.ORIGINAL)
        assertEquals("image/png", png.payloads.single().contentType)
    }

    @Test
    fun aNotesPreviewIsCutToFitOnceSealed() = runTest {
        suspend fun note(preview: String) = UserUploadBuilder.build(
            listOf(UploadSource(file("n.md", "# hi".encodeToByteArray()), "text/markdown", appContent = preview)),
            UploadOptions(currentFormat = true),
            fileOps,
        ) { "vlt_pg_00" }.payloads.single().descriptorContent!!

        val emoji = "\uD83D\uDE00".repeat(200)
        val cut = note(emoji)
        assertTrue(DescriptorContentSeal.fits(cut, sealed = true), "sealed, it fits the server's 1,024")
        val kept = DescriptorContentV1.parseOrNull(cut)!!.appContent!!
        assertTrue(kept.isNotEmpty() && emoji.startsWith(kept))

        val short = "Shopping: milk, eggs"
        assertEquals(short, DescriptorContentV1.parseOrNull(note(short))!!.appContent, "a preview that fits is left alone")
    }

    @Test
    fun appContentIsWrappedAndMustFitOnceSealed() {
        val preview = """[{"url":"https://example.com"}]"""
        assertEquals(preview, UserUploadBuilder.appDescriptor(preview, currentFormat = false))
        val wrapped = DescriptorContentV1.parseOrNull(UserUploadBuilder.appDescriptor(preview, currentFormat = true)!!)!!
        assertEquals(DescriptorContentV1(kind = DescriptorContentV1.Kind.APP, appContent = preview), wrapped)

        val long = "x".repeat(900)
        assertEquals(long, UserUploadBuilder.appDescriptor(long, currentFormat = false), "fits the server's 1,024 unsealed")
        assertNull(UserUploadBuilder.appDescriptor(long, currentFormat = true), "but not once sealed")
        assertEquals(preview, DescriptorContentV1.parseOrNull(UserUploadBuilder.appDescriptor(long, preview, currentFormat = true)!!)!!.appContent)
    }

    @Test
    fun theNameIsMadeSafe() = runTest {
        val sent = build(UploadSource(file("c.pdf", "%PDF-1.4".encodeToByteArray()), "application/pdf", pickedName = "../../x/Q3: plan?.pdf"))
        assertEquals(DescriptorContent.File(name = "Q3_ plan_.pdf"), sent.received().descriptorInfo())
    }

    @Test
    fun eachOtherKindReadsBackAsTheTypeTheAppUses() = runTest {
        val memo = build(UploadSource(file("m.m4a", ByteArray(8)), "audio/mp4", pickedName = "memo.m4a", audioLengthSeconds = 7))
        assertEquals(DescriptorContent.AudioFile(name = "memo.m4a", lengthSeconds = 7), memo.received().descriptorInfo())

        val voiceNote = build(UploadSource(file("v.m4a", ByteArray(8)), "audio/mp4", audioLengthSeconds = 3))
        assertNull(voiceNote.written().name, "a recording has no name to keep")

        val zip = build(UploadSource(file("d.zip", ByteArray(8)), "application/zip", pickedName = "photos.zip"))
        assertEquals(DescriptorContent.File(name = "photos.zip"), zip.received().descriptorInfo())

        val clip = build(UploadSource(file("e.mov", ByteArray(8)), "video/quicktime", pickedName = "clip.mov"))
        assertEquals(DescriptorContentV1(kind = DescriptorContentV1.Kind.VIDEO, name = "clip.mov"), clip.written())
    }

    @Test
    fun aNotesPreviewIsItsAppContent() = runTest {
        val note = build(UploadSource(file("n.md", "# Groceries".encodeToByteArray()), "text/markdown", appContent = "# Groceries"))
        assertEquals(DescriptorContentV1.Kind.APP, note.written().kind)
        assertEquals(DescriptorContent.NoteFile("# Groceries"), note.received().descriptorInfo())

        val old = UserUploadBuilder.build(listOf(UploadSource(file("o.md", ByteArray(4)), "text/markdown", appContent = "# Old")), UploadOptions(currentFormat = false), fileOps) { "vlt_pg_00" }
        assertEquals("# Old", old.payloads.single().descriptorContent, "the old format keeps the preview as the whole string")
    }

    private suspend fun old(source: UploadSource) =
        UserUploadBuilder.build(listOf(source), UploadOptions(quality = MediaQuality.ORIGINAL, currentFormat = false), fileOps) { "vlt_pg_00" }.payloads.single()

    @Test
    fun anUnnamedUnmeasuredAudioFileRecordsNothing() = runTest {
        assertNull(old(UploadSource(file("therapy-session.m4a", ByteArray(8)), "audio/mp4")).descriptorContent, "Vault keeps names in its own content")
    }

    @Test
    fun aStickerIsAnImageWhateverTypeItArrivesWith() = runTest {
        val sticker = old(UploadSource(file("s.bin", image(200, 200, EncodedImageFormat.PNG)), "application/octet-stream", sticker = true))
        assertEquals(DescriptorContent.descriptorContentFromImage(isSticker = true, format = "image/png"), sticker.descriptorContent)
    }

    @Test
    fun anAppMayKeepItsOwnVideoTier() = runTest {
        val clip = UserUploadBuilder.build(
            listOf(UploadSource(file("c.mp4", ByteArray(8)), "video/mp4")),
            UploadOptions(quality = MediaQuality.ORIGINAL, videoQuality = id.homebase.api.video.VideoQuality.STANDARD),
            fileOps,
        ) { "vlt_pg_00" }
        assertEquals(id.homebase.api.video.VideoQuality.STANDARD, clip.payloads.single().videoQuality)
    }

    // A camera JPEG: an EXIF block whose GPS IFD holds a latitude ref marker and whose Orientation is 6.
    private fun geotagged(w: Int, h: Int): ByteArray {
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Int) = ByteArray(4) { (v ushr (8 * it)).toByte() }
        fun entry(tag: Int, type: Int, count: Int, value: ByteArray) = le16(tag) + le16(type) + le32(count) + value.copyOf(4)
        val ifd0 = le16(2) + entry(0x0112, 3, 1, le16(6)) + entry(0x8825, 4, 1, le32(38)) + le32(0)
        val gps = le16(1) + entry(0x0002, 2, 8, le32(56)) + le32(0)
        val tiff = "II".encodeToByteArray() + le16(42) + le32(8) + ifd0 + gps + "GPS-7749".encodeToByteArray()
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        val plain = image(w, h)
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    @Test
    fun aReencodedPhotoKeepsItsExifOnlyWhenTheAppKeepsMetadata() = runTest {
        val source = file("geo.jpg", geotagged(5000, 3000))
        for (quality in listOf(MediaQuality.STANDARD, MediaQuality.HD)) {
            val kept = build(UploadSource(source, "image/jpeg"), quality, MetadataPolicy.KEEP).payloads.single()
            val keptBytes = File(kept.filePath).readBytes()
            assertEquals("image/webp", kept.contentType, "$quality is a re-encode")
            val exif = ImageMetadataScrubber.readBlocks(keptBytes)?.exif
            assertTrue(exif != null && "GPS-7749" in exif.decodeToString(), "$quality keeps the location when the app keeps metadata")
            assertEquals(1, readJpegExifOrientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), ((exif.size + 8) shr 8).toByte(), (exif.size + 8).toByte()) + "Exif".encodeToByteArray() + byteArrayOf(0, 0) + exif), "$quality: its pixels are upright")

            val stripped = build(UploadSource(source, "image/jpeg"), quality, MetadataPolicy.STRIP).payloads.single()
            assertEquals("image/webp", stripped.contentType)
            assertNull(ImageMetadataScrubber.readBlocks(File(stripped.filePath).readBytes()), "$quality carries nothing when the app strips")
        }
    }

    @Test
    fun theAppsMetadataPolicyTravelsWithTheImage() = runTest {
        val path = file("p.jpg", image(400, 300))
        assertTrue(build(UploadSource(path, "image/jpeg"), MediaQuality.ORIGINAL, MetadataPolicy.STRIP).payloads.single().stripMetadata)
        assertFalse(build(UploadSource(path, "image/jpeg"), MediaQuality.ORIGINAL, MetadataPolicy.KEEP).payloads.single().stripMetadata)
    }

    @Test
    fun theAppsMetadataPolicyTravelsWithTheVideo() = runTest {
        val path = file("v.mp4", "video".encodeToByteArray())
        for (quality in MediaQuality.entries) {
            assertTrue(build(UploadSource(path, "video/mp4"), quality, MetadataPolicy.STRIP).payloads.single().stripMetadata)
            assertFalse(build(UploadSource(path, "video/mp4"), quality, MetadataPolicy.KEEP).payloads.single().stripMetadata, "$quality: the transcode keeps it")
        }
    }
}
