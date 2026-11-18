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
import id.homebase.api.image.openImageFile
import kotlin.test.assertNotNull
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check: how long [MediaMetadataReader] takes from a file and from the media library, as Photos reads it:
 * a video's location, and a photo's EXIF against reading it from an image already opened for its thumbnails.
 * Run with `-e locationBench true` and videos and JPEGs in the test app's `files/bench/`.
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
            val uri = intoMediaLibrary(context, input, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
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

    @Test
    fun photoExif() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("locationBench") != "true") return@runBlocking
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileOps = AndroidFileOperationsProvider(context)
        val reader = MediaMetadataReader(fileOps)
        val inputs = File(context.filesDir, "bench").listFiles { f -> f.extension.lowercase() == "jpg" }.orEmpty()
        for (input in inputs.sortedBy { it.length() }) {
            val uri = intoMediaLibrary(context, input, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            try {
                for ((access, path) in listOf("file" to input.absolutePath, "content" to uri.toString())) {
                    repeat(ROUNDS) {
                        val opened = assertNotNull(openImageFile(path, fileOps))
                        val fromOpen = opened.use {
                            val started = System.nanoTime()
                            it.metadata().also { Log.i(TAG, "${Build.MODEL} ${input.name} $access EXIF from the open image: %.1f ms".format((System.nanoTime() - started) / 1e6)) }
                        }
                        val started = System.nanoTime()
                        val read = reader.image(path)
                        val ms = (System.nanoTime() - started) / 1e6
                        Log.i(TAG, "${Build.MODEL} ${input.name} $access EXIF by the reader: %.1f ms, same=${read == fromOpen}, gps=${read?.location != null}".format(ms))
                    }
                }
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun intoMediaLibrary(context: Context, source: File, collection: Uri): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "location_bench_${System.nanoTime()}.${source.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, when (source.extension.lowercase()) { "mov" -> "video/quicktime"; "jpg" -> "image/jpeg"; else -> "video/mp4" })
        }
        val uri = context.contentResolver.insert(collection, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 20) } }
        return uri
    }

    private companion object {
        const val TAG = "LocationBenchmark"
        const val ROUNDS = 4
    }
}
