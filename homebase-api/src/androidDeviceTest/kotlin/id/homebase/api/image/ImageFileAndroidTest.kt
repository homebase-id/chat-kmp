package id.homebase.api.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.file.AndroidFileOperationsProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageFileAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val png = ImageUtils.encodeArgbToPng(ArgbImage(IntArray(300 * 200) { 0xFF336699.toInt() }, 300, 200))

    @Test
    fun aFileOpensWithoutBeingReadWhole() {
        val file = File(context.cacheDir, "image_file_test.png").apply { writeBytes(png) }
        try {
            assertNotNull(openImageFile(file.absolutePath, AndroidFileOperationsProvider(context))).use {
                assertEquals(ImageSize(300, 200), it.displayedSize)
            }
        } finally {
            file.delete()
        }
    }
}
