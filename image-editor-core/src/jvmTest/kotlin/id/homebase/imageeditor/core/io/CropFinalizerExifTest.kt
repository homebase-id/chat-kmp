package id.homebase.imageeditor.core.io

import id.homebase.api.image.ImageUtils
import id.homebase.imageeditor.core.EditorModel
import id.homebase.imageeditor.core.RectF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip the cropper through every EXIF orientation tag (1..8) and assert
 * the finalized output dimensions are correct regardless of the orientation
 * baked into the source bytes.
 *
 * This catches the most common cropper bug: applying the crop in display
 * coordinates while the underlying decode reads raw bytes (or vice versa).
 */
class CropFinalizerExifTest {

    private fun loadFixture(path: String): ByteArray =
        this::class.java.getResourceAsStream("/test_images/$path")?.readBytes()
            ?: error("fixture not found: $path")

    @Test fun fullCropOfLandscapeFixturePreservesDisplayOrientation() {
        // Landscape_*.jpg are 600x450 in display coords.
        for (i in 1..8) {
            val bytes = loadFixture("orientation/Landscape_$i.jpg")
            val prep = CropPreprocessor.prepare(bytes) ?: error("preprocess failed")
            assertEquals(600, prep.naturalSize.width, "Landscape_$i width")
            assertEquals(450, prep.naturalSize.height, "Landscape_$i height")

            val model = EditorModel.create()
            model.onImageReady(prep.naturalSize)
            model.setVisibleViewPort(RectF(0f, 0f, 1000f, 1000f))

            val result = CropFinalizer.finalize(prep.originalBytes, model)
            // Output should be approximately the natural display size.
            assertTrue(
                result.size.pixelWidth in 590..610,
                "Landscape_$i finalized width ${result.size.pixelWidth}",
            )
            assertTrue(
                result.size.pixelHeight in 440..460,
                "Landscape_$i finalized height ${result.size.pixelHeight}",
            )

            // The resulting bytes should decode to a valid image of the
            // claimed dimensions.
            val decoded = ImageUtils.getNaturalSize(result.bytes)
            assertEquals(
                result.size.pixelWidth,
                decoded.pixelWidth,
                "Landscape_$i: encoded vs decoded width",
            )
            assertEquals(
                result.size.pixelHeight,
                decoded.pixelHeight,
                "Landscape_$i: encoded vs decoded height",
            )
        }
    }

    @Test fun fullCropOfPortraitFixturePreservesDisplayOrientation() {
        // Portrait_*.jpg are 450x600 in display coords.
        for (i in 1..8) {
            val bytes = loadFixture("orientation/Portrait_$i.jpg")
            val prep = CropPreprocessor.prepare(bytes) ?: error("preprocess failed")
            assertEquals(450, prep.naturalSize.width, "Portrait_$i width")
            assertEquals(600, prep.naturalSize.height, "Portrait_$i height")

            val model = EditorModel.create()
            model.onImageReady(prep.naturalSize)
            model.setVisibleViewPort(RectF(0f, 0f, 1000f, 1000f))

            val result = CropFinalizer.finalize(prep.originalBytes, model)
            assertTrue(
                result.size.pixelWidth in 440..460,
                "Portrait_$i finalized width ${result.size.pixelWidth}",
            )
            assertTrue(
                result.size.pixelHeight in 590..610,
                "Portrait_$i finalized height ${result.size.pixelHeight}",
            )
        }
    }

    @Test fun snapRotate90SwapsOutputDimensions() {
        val bytes = loadFixture("roof_test_800x600.jpg")
        val prep = CropPreprocessor.prepare(bytes) ?: error("preprocess failed")
        val model = EditorModel.create()
        model.onImageReady(prep.naturalSize)
        model.setVisibleViewPort(RectF(0f, 0f, 1000f, 1000f))
        model.rotate90Clockwise()

        val result = CropFinalizer.finalize(prep.originalBytes, model)
        // After 90° rotation: width and height swap.
        assertTrue(
            result.size.pixelWidth in 590..610,
            "post-rotate width ${result.size.pixelWidth}",
        )
        assertTrue(
            result.size.pixelHeight in 790..810,
            "post-rotate height ${result.size.pixelHeight}",
        )
    }

    @Test fun maxEdgeCapShrinksOutput() {
        val bytes = loadFixture("roof_test_800x600.jpg")
        val prep = CropPreprocessor.prepare(bytes) ?: error("preprocess failed")
        val model = EditorModel.create()
        model.onImageReady(prep.naturalSize)
        model.setVisibleViewPort(RectF(0f, 0f, 1000f, 1000f))

        val result = CropFinalizer.finalize(prep.originalBytes, model, maxEdge = 400)
        val long = maxOf(result.size.pixelWidth, result.size.pixelHeight)
        assertTrue(long <= 400, "long edge $long should be ≤ 400")
        // Aspect preserved: 4:3 → ~400x300
        assertTrue(
            result.size.pixelWidth in 395..400 &&
                result.size.pixelHeight in 295..305,
            "expected ~400x300 got ${result.size.pixelWidth}x${result.size.pixelHeight}",
        )
    }
}
