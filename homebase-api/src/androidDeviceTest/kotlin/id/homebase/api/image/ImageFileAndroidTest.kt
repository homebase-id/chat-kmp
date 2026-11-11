package id.homebase.api.image

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.ActivityProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** The contract test proves a path makes what its bytes make; this proves Android opens paths itself. */
@RunWith(AndroidJUnit4::class)
class ImageFileAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val png = ImageUtils.encodeArgbToPng(ArgbImage(IntArray(300 * 200) { 0xFF336699.toInt() }, 300, 200))

    @Test
    fun aFileAndAFileUriOpenWithoutReadingThemWhole() {
        val file = File(context.cacheDir, "image_file_test.png").apply { writeBytes(png) }
        try {
            ActivityProvider.initializeApplicationContext(context)
            for (path in listOf(file.absolutePath, file.toUri().toString())) {
                assertNotNull(openImageFile(path), path).use { assertEquals(ImageSize(300, 200), it.displayedSize, path) }
            }
        } finally {
            file.delete()
        }
    }
}
