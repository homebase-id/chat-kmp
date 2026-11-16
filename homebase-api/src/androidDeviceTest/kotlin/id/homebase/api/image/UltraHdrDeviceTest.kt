package id.homebase.api.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The shared Ultra HDR container against Android's own reader and writer. */
@RunWith(AndroidJUnit4::class)
class UltraHdrDeviceTest {
    @Before
    fun gainmapsNeedAndroid14() = assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

    @Test
    fun theSharedReaderReadsWhatAndroidWrites() {
        val source = ultraHdrJpeg(800, 600)
        val android = assertNotNull(BitmapFactory.decodeByteArray(source, 0, source.size).gainmap)
        val shared = assertNotNull(UltraHdr.gainmapOf(source)).metadata
        assertSame(android, shared)
    }

    @Test
    fun androidReadsWhatTheSharedWriterWrites() {
        val source = ultraHdrJpeg(800, 600)
        val gainmap = assertNotNull(UltraHdr.gainmapOf(source))
        // A fresh SDR primary, as the pipeline encodes one: no gain map, XMP or MPF of its own.
        val primary = ByteArrayOutputStream().also {
            Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF6699CC.toInt()) }
                .compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()

        val joined = UltraHdr.join(primary, gainmap.jpeg, gainmap.metadata)

        val decoded = assertNotNull(BitmapFactory.decodeByteArray(joined, 0, joined.size))
        assertTrue(decoded.hasGainmap(), "Android finds the gain map")
        assertEquals(200, decoded.gainmap!!.gainmapContents.width)
        assertSame(decoded.gainmap!!, gainmap.metadata)
    }

    private fun assertSame(android: Gainmap, shared: GainmapMetadata) {
        fun close(a: Float, b: Double, what: String) = assertTrue(abs(a - b) < 1e-3, "$what: Android $a, shared $b")
        for (c in 0 until 3) {
            val s = if (shared.channels == 1) 0 else c
            close(log2(android.ratioMin[c]), shared.gainMapMin[s], "gain map min")
            close(log2(android.ratioMax[c]), shared.gainMapMax[s], "gain map max")
            close(android.gamma[c], shared.gamma[s], "gamma")
            close(android.epsilonSdr[c], shared.offsetSdr[s], "offset SDR")
            close(android.epsilonHdr[c], shared.offsetHdr[s], "offset HDR")
        }
        close(log2(android.minDisplayRatioForHdrTransition), shared.hdrCapacityMin, "HDR capacity min")
        close(log2(android.displayRatioForFullHdr), shared.hdrCapacityMax, "HDR capacity max")
    }
}
