package id.homebase.api.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.lib.image.ImageFormatDetector
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Landscape_6.heic` is `jvmTest/resources/test_images/orientation/Landscape_6.jpg` transcoded to
 * HEIC by macOS `sips`: 450x600 sensor pixels plus EXIF orientation 6, so it displays 600x450 —
 * the shape an iPhone camera roll HEIC arrives in.
 *
 * Must run on a device: the bug is `BitmapFactory` + `Bitmap.compress`, both of which are stubs on
 * the host JVM, and the jvm/skia actuals take an entirely different (Skia, EXIF-aware) path. CI
 * doesn't run `connectedAndroidDeviceTest`, so run it locally with a booted device:
 *
 *   ./gradlew homebase-api:connectedAndroidDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class HeicOrientationAndroidTest {

    private fun fixture(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("test_images/$FIXTURE").use { it.readBytes() }

    @Test
    fun fixture_isHeicTaggedRotate90() {
        val heic = fixture()
        assertTrue(ImageFormatDetector.isHeic(heic), "$FIXTURE must be detected as HEIC")
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface(ByteArrayInputStream(heic))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
            "$FIXTURE must carry EXIF orientation 6",
        )
    }

    /**
     * The premise of the fix. If a platform ever starts applying the tag inside `BitmapFactory`,
     * `convertHeicToJpeg` would rotate a second time — this fails first and says so.
     */
    @Test
    fun bitmapFactory_returnsSensorPixels_notDisplayPixels() {
        val heic = fixture()
        val raw = assertNotNull(
            BitmapFactory.decodeByteArray(heic, 0, heic.size, null),
            "Device cannot decode HEIC at all — HEIF decode needs API 28+ and an HEVC decoder",
        )
        assertEquals(SENSOR_W to SENSOR_H, raw.width to raw.height, "BitmapFactory must hand back the un-rotated sensor frame")
        raw.recycle()
    }

    @Test
    fun convertHeicToJpeg_bakesOrientationIntoPixels() {
        val jpeg = assertNotNull(convertHeicToJpeg(fixture()), "HEIC conversion returned null")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        assertEquals(
            DISPLAY_W to DISPLAY_H,
            bounds.outWidth to bounds.outHeight,
            "Converted JPEG must be upright ${DISPLAY_W}x$DISPLAY_H, not the ${SENSOR_W}x$SENSOR_H sensor frame",
        )

        assertEquals(
            ExifInterface.ORIENTATION_UNDEFINED,
            ExifInterface(ByteArrayInputStream(jpeg))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
            "Converted JPEG must be self-describing — no leftover tag for a consumer to re-apply",
        )
    }

    /** Proves the rotation went 90 clockwise rather than counter-clockwise. */
    @Test
    fun convertHeicToJpeg_putsRedFlowersAtBottomLeft() {
        val jpeg = assertNotNull(convertHeicToJpeg(fixture()), "HEIC conversion returned null")
        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, null))
        try {
            val flowers = redRatio(bitmap, 0, 350, 150, 100)
            val trees = redRatio(bitmap, 450, 0, 150, 100)
            assertTrue(
                flowers > trees,
                "Bottom-left (flowers, ratio=$flowers) should out-red top-right (trees, ratio=$trees)",
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun getNaturalSize_reportsDisplayDimensions() {
        val size = ImageUtils.getNaturalSize(fixture())
        assertEquals(DISPLAY_W to DISPLAY_H, size.pixelWidth to size.pixelHeight)
    }

    /** `decodeBitmap` must not re-apply the tag on top of the already-upright conversion. */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    @RequiresApi(Build.VERSION_CODES.R)
    fun imagePipeline_doesNotRotateTwice() {
        val result = ImageUtils.compressOnly(fixture(), ImageFormat.JPEG, 90)
        assertEquals(
            DISPLAY_W to DISPLAY_H,
            result.size.pixelWidth to result.size.pixelHeight,
            "A second rotation would land back on ${SENSOR_W}x$SENSOR_H",
        )
    }

    private fun redRatio(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Double {
        assertTrue(
            x + w <= bitmap.width && y + h <= bitmap.height,
            "Sample region ${w}x$h at ($x,$y) falls outside the ${bitmap.width}x${bitmap.height} bitmap",
        )
        var red = 0L
        var green = 0L
        var count = 0
        for (row in y until (y + h).coerceAtMost(bitmap.height)) {
            for (col in x until (x + w).coerceAtMost(bitmap.width)) {
                val pixel = bitmap.getPixel(col, row)
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                count++
            }
        }
        val avgRed = red.toDouble() / count
        val avgGreen = green.toDouble() / count
        return avgRed / (avgGreen + 1.0)
    }

    private companion object {
        const val FIXTURE = "Landscape_6.heic"
        const val SENSOR_W = 450
        const val SENSOR_H = 600
        const val DISPLAY_W = 600
        const val DISPLAY_H = 450
    }
}
