@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.image

import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.api.platform.peakFootprintGrowth
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.getenv
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Time and peak memory of real photos through the pipeline, as the app renders them from a file. Skipped unless
 * IMAGE_BENCH_DIR names a folder of HEIC or JPEG photos (pass it to the simulator as SIMCTL_CHILD_IMAGE_BENCH_DIR); the
 * table goes to results-<IMAGE_BENCH_LABEL>.md in that folder. How to run it is in OPTIMIZE.md.
 */
class IphonePhotoBench {
    @Test
    fun measure() {
        val dir = getenv("IMAGE_BENCH_DIR")?.toKString() ?: return
        val label = getenv("IMAGE_BENCH_LABEL")?.toKString() ?: "run"
        val photos = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty()
            .map { it as String }
            .filter { it.substringAfterLast('.').lowercase() in setOf("heic", "heif", "jpg", "jpeg") }
            .sorted()
        val ops = IOSFileOperationsProvider()
        val rows = mutableListOf(
            "| Photo | Source | Setting | Outputs | Time (ms, median of 3) | Peak footprint growth (MB, max of 3) |",
            "|---|---|---|---|---|---|",
        )
        runBlocking(Dispatchers.Default) {
            for (name in photos) for (setting in MediaQuality.entries) {
                val path = "$dir/$name"
                val warmUp = createMediaImages(path, ops, "bench", setting)
                val outputs = (warmUp.thumbnails.map { "${it.pixelWidth}x${it.pixelHeight}" } +
                    listOfNotNull(warmUp.finalImage?.let { "${it.width}x${it.height} final" })).joinToString(", ")
                val samples = List(3) {
                    peakFootprintGrowth {
                        val started = TimeSource.Monotonic.markNow()
                        createMediaImages(path, ops, "bench", setting)
                        started.elapsedNow().inWholeMilliseconds
                    }
                }
                val source = "${warmUp.displayedSize.pixelWidth}x${warmUp.displayedSize.pixelHeight}"
                val time = samples.map { it.first }.sorted()[1]
                val peakMb = samples.maxOf { it.second } / (1024 * 1024)
                rows += "| $name | $source | $setting | $outputs | $time | $peakMb |"
            }
        }
        NSString.create(string = rows.joinToString("\n", postfix = "\n"))
            .writeToFile("$dir/results-$label.md", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}
