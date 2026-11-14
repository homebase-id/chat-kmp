package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.DescriptorContent
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

        val raw = build(UploadSource(file("raw", ByteArray(8)), "", libraryKind = LibraryKind.IMAGE))
        assertEquals(DescriptorContentV1.Kind.IMAGE, raw.written().kind, "a picture the decoder can't draw is still a picture")

        val png = build(UploadSource(file("untyped", image(64, 64, EncodedImageFormat.PNG)), "", libraryKind = LibraryKind.IMAGE), MediaQuality.ORIGINAL)
        assertEquals("image/png", png.payloads.single().contentType)
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

    @Test
    fun theAppsMetadataPolicyTravelsWithTheImage() = runTest {
        val path = file("p.jpg", image(400, 300))
        assertTrue(build(UploadSource(path, "image/jpeg"), MediaQuality.ORIGINAL, MetadataPolicy.STRIP).payloads.single().stripMetadata)
        assertFalse(build(UploadSource(path, "image/jpeg"), MediaQuality.ORIGINAL, MetadataPolicy.KEEP).payloads.single().stripMetadata)
    }
}
