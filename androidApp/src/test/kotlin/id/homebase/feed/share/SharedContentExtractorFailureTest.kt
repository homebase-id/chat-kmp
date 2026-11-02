package id.homebase.feed.share

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SharedContentExtractorFailureTest {

    private val contentResolver get() = RuntimeEnvironment.getApplication().contentResolver

    private fun tempDir() =
        File(RuntimeEnvironment.getApplication().cacheDir, "share_fail_${System.nanoTime()}")

    private val missing = File("/nonexistent/gone.jpg").toUri()

    @Test
    fun missing_single_file_is_counted_as_failed() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, missing)
        }
        val content = assertNotNull(SharedContentExtractor.extract(intent, contentResolver, tempDir()))
        assertEquals(0, content.files.size)
        assertEquals(1, content.failedFileCount)
    }

    @Test
    fun partial_multi_share_counts_only_the_missing_file() {
        val ok = File.createTempFile("okfile", ".jpg").apply { writeText("x") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(ok.toUri(), missing))
        }
        val content = assertNotNull(SharedContentExtractor.extract(intent, contentResolver, tempDir()))
        assertEquals(1, content.files.size)
        assertEquals(1, content.failedFileCount)
    }
}
