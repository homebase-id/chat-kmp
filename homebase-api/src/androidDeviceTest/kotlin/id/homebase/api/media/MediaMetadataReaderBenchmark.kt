package id.homebase.api.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.file.AndroidFileOperationsProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check: how long [MediaMetadataReader.videoLocation] takes from a file and from the media library, as
 * Photos reads it. Run with `-e locationBench true` and videos in the test app's `files/bench/`.
 */
@RunWith(AndroidJUnit4::class)
class MediaMetadataReaderBenchmark {

    @Test
    fun videoLocation() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("locationBench") != "true") return@runBlocking
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reader = MediaMetadataReader(AndroidFileOperationsProvider(context))
        val inputs = File(context.filesDir, "bench").listFiles { f -> f.extension.lowercase() in setOf("mp4", "mov") }.orEmpty()
        for (input in inputs.sortedBy { it.length() }) {
            val uri = intoMediaLibrary(context, input)
            try {
                for ((access, path) in listOf("file" to input.absolutePath, "content" to uri.toString())) {
                    repeat(ROUNDS) {
                        val started = System.nanoTime()
                        val location = reader.videoLocation(path)
                        val ms = (System.nanoTime() - started) / 1e6
                        Log.i(TAG, "${Build.MODEL} ${input.name} ${input.length() / 1_000_000}MB $access: %.1f ms, %s".format(ms, location))
                    }
                }
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun intoMediaLibrary(context: Context, source: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "location_bench_${System.nanoTime()}.${source.extension}")
            put(MediaStore.Video.Media.MIME_TYPE, if (source.extension == "mov") "video/quicktime" else "video/mp4")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 20) } }
        return uri
    }

    private companion object {
        const val TAG = "LocationBenchmark"
        const val ROUNDS = 4
    }
}
