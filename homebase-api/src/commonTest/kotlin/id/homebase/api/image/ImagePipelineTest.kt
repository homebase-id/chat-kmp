package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagePipelineTest {

    private class FakeBitmap(override val width: Int, override val height: Int, val log: Log) : PipelineBitmap {
        init { log.open++ }

        override fun scaledTo(width: Int, height: Int): PipelineBitmap {
            log.scales += (this.width to this.height) to (width to height)
            return FakeBitmap(width, height, log)
        }

        override fun encode(format: ImageFormat, quality: Int): ByteArray {
            log.encodedQualities += quality
            return ByteArray(log.bytesFor(width, height, quality))
        }

        override fun close() { log.open-- }
    }

    private class Log(val bytesFor: (Int, Int, Int) -> Int = { _, _, _ -> 10 }) {
        var decodes = 0
        var decodedAt = 0
        var open = 0
        val scales = mutableListOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        val encodedQualities = mutableListOf<Int>()
    }

    private class FakeSource(val width: Int, val height: Int, val log: Log) : ImageSource {
        override val displayedSize get() = ImageSize(width, height)

        override fun decode(minLongEdge: Int): PipelineBitmap {
            log.decodes++
            log.decodedAt = minLongEdge
            return FakeBitmap(width, height, log)
        }

        override fun close() = Unit
    }

    private fun output(width: Int, height: Int, quality: Int = 80, maxBytes: Int = 1000, minQuality: Int = 40) =
        ImageOutput(width, height, quality, maxBytes, minQuality = minQuality)

    @Test
    fun manySizesDecodeOnceAtTheLargestSize() {
        val log = Log()

        renderFrom(FakeSource(4000, 3000, log)) {
            listOf(output(20, 15), output(1600, 1200), output(320, 240), output(640, 480))
        }

        assertEquals(1, log.decodes)
        assertEquals(1600, log.decodedAt)
    }

    @Test
    fun everySizeScalesFromTheOneAbove() {
        val log = Log()

        renderFrom(FakeSource(640, 480, log)) { listOf(output(320, 240), output(640, 480), output(160, 120)) }

        assertEquals(
            listOf((640 to 480) to (320 to 240), (320 to 240) to (160 to 120)),
            log.scales,
        )
    }

    @Test
    fun bigReductionsHalveThenFinishWithOneSmallStep() {
        val log = Log()

        renderFrom(FakeSource(4000, 3000, log)) { listOf(output(300, 225)) }

        assertEquals(
            listOf(2000 to 1500, 1000 to 750, 500 to 375, 300 to 225),
            log.scales.map { it.second },
        )
    }

    @Test
    fun aThinImageHalvesOnlyTheAxisThatNeedsIt() {
        val log = Log()

        renderFrom(FakeSource(4000, 2, log)) { listOf(output(1000, 2)) }

        assertEquals(listOf(2000 to 2, 1000 to 2), log.scales.map { it.second })
    }

    @Test
    fun everyBitmapGetsClosed() {
        val log = Log()

        renderFrom(FakeSource(4000, 3000, log)) { listOf(output(320, 240), output(20, 15, maxBytes = 5)) }

        assertEquals(0, log.open)
    }

    @Test
    fun overTheLimitLowersQualityToTheFloorThenShrinks() {
        val log = Log { width, _, quality -> if (width >= 100 || quality > 40) 2000 else 500 }

        val image = renderFrom(FakeSource(100, 100, log)) { listOf(output(100, 100)) }.images.single()

        assertEquals(40, image.quality)
        assertEquals(40, log.encodedQualities.last())
        assertEquals(listOf(80, 72, 64, 56, 48, 40), log.encodedQualities.take(6))
        assertTrue(image.width < 100, "Should have shrunk once quality reached its floor")
        assertEquals(500, image.bytes.size)
    }

    @Test
    fun noPlanMeansNoDecode() {
        val log = Log()

        val result = renderFrom(FakeSource(100, 100, log)) { emptyList() }

        assertEquals(0, log.decodes)
        assertEquals(ImageSize(100, 100), result.displayedSize)
    }
}
