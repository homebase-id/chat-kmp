package id.homebase.api.image

import id.homebase.api.image.draw.PathCommand
import id.homebase.api.image.draw.StrokeCap
import id.homebase.api.image.draw.StrokeCommand
import id.homebase.api.image.draw.StrokeKind
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke coverage for the shared (skiaMain) image-editor ops that weren't otherwise tested:
 * warpAffine, drawStrokes, blurBytes, toImageBitmap. After the skiaMain consolidation these run
 * the same Skia code on Desktop/iOS/Web, so exercising them on the JVM skia runtime guards the
 * one shared implementation. (getNaturalSize/resize/compress/crop/rotate/rasterizeSvg are already
 * covered in ImageUtilsTest + CreateThumbnailsTest.)
 */
class ImageUtilsEditorOpsTest {

    private val src by lazy { ImageTestHelper.loadImage("dice.png") }

    @Test
    fun warpAffine_identity_producesValidOutputAtRequestedSize() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val r = ImageUtils.warpAffine(
            srcBytes = src,
            matrix9 = identity,
            outputWidth = 120,
            outputHeight = 80,
            fillColorArgb = 0,
            outputFormat = ImageFormat.WEBP,
            quality = 80,
        )
        ImageTestHelper.assertValidWebp(r.bytes)
        assertTrue(r.size.pixelWidth == 120 && r.size.pixelHeight == 80, "expected 120x80, got ${r.size}")
    }

    @Test
    fun drawStrokes_paintStroke_producesValidSameSizeOutput() {
        val natural = ImageUtils.getNaturalSize(src)
        val stroke = StrokeCommand(
            cap = StrokeCap.Round,
            colorArgb = 0xFFFF0000.toInt(),
            thicknessPx = 8f,
            pathCommands = listOf(PathCommand.MoveTo(2f, 2f), PathCommand.LineTo(20f, 20f)),
            kind = StrokeKind.PAINT,
        )
        val r = ImageUtils.drawStrokes(src, listOf(stroke), ImageFormat.WEBP, 80)
        ImageTestHelper.assertValidWebp(r.bytes)
        assertTrue(
            r.size.pixelWidth == natural.pixelWidth && r.size.pixelHeight == natural.pixelHeight,
            "stroked output must keep source size; expected $natural, got ${r.size}",
        )
    }

    @Test
    fun blurBytes_producesValidSameSizeOutput() {
        val natural = ImageUtils.getNaturalSize(src)
        val r = ImageUtils.blurBytes(src, radius = 8, outputFormat = ImageFormat.WEBP, quality = 80)
        ImageTestHelper.assertValidWebp(r.bytes)
        assertTrue(
            r.size.pixelWidth == natural.pixelWidth && r.size.pixelHeight == natural.pixelHeight,
            "blurred output must keep source size; expected $natural, got ${r.size}",
        )
    }

    @Test
    fun toImageBitmap_decodesValidImageToNonNull() {
        assertNotNull(src.toImageBitmap(), "toImageBitmap should decode a valid PNG")
    }
}
