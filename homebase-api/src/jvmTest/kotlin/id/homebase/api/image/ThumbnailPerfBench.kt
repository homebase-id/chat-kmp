package id.homebase.api.image

import kotlin.test.Test
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking

/** Skipped unless IMAGE_BENCH=1, so CI never runs it. See OPTIMIZE.md. */
class ThumbnailPerfBench {
    @Test
    fun createThumbnailsTimingsAndBytes() {
        if (System.getenv("IMAGE_BENCH") != "1") return
        for (name in listOf("5760_x_4320.jpg", "waterhouse.jpg", "roof_test_800x600.jpg", "dice.png")) {
            val bytes = ImageTestHelper.loadImage(name)
            val runs = (1..5).map { measureTime { runBlocking { createThumbnails(bytes, "k") } } }.sorted()
            val (_, tiny, thumbs) = runBlocking { createThumbnails(bytes, "k") }
            for (setting in MediaQuality.entries) {
                val media = runBlocking { createMediaImages(bytes, "k", setting) }
                println(
                    "IMAGE_BENCH $name $setting " +
                        media.thumbnails.joinToString { "${it.pixelWidth}x${it.pixelHeight}q${it.quality}=${it.thumbnailBytes.size / 1024}KB" } +
                        " final=" + media.finalImage?.let { "${it.width}x${it.height}q${it.quality}=${it.bytes.size / 1024}KB" },
                )
            }
            println(
                "IMAGE_BENCH $name median=${runs[2].inWholeMilliseconds}ms " +
                    "tiny=${tiny?.content?.bytes?.size} thumbs=" +
                    thumbs.joinToString { "${it.pixelWidth}x${it.pixelHeight}q${it.quality}=${it.thumbnailBytes.size / 1024}KB" },
            )
        }
    }
}
