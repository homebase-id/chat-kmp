package id.homebase.core.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollJankTraceTest {
    private val idle = FrameWork(0, 0, 0, 0, 0, 0)
    private val ms = 1_000_000L

    @Test
    fun slowIsRelativeToTheScrollsOwnMedianFrame() {
        val at60 = List(10) { 16 * ms to idle } + (40 * ms to idle)
        assertEquals(listOf(40 * ms), slowFrames(at60).second.map { it.first })

        val at120 = List(10) { 8 * ms to idle } + (16 * ms to idle) + (11 * ms to idle)
        assertEquals(listOf(16 * ms), slowFrames(at120).second.map { it.first })
    }

    @Test
    fun tooFewFramesReportNothing() {
        assertTrue(slowFrames(listOf(16 * ms to idle, 90 * ms to idle)).second.isEmpty())
    }

    @Test
    fun aPreviewDecodeIsCountedAndTimedUntilTaken() {
        ImageFrameWork.take()
        ImageFrameWork.timePreviewDecode { 42 }
        val work = ImageFrameWork.take()
        assertEquals(1, work.previewDecodes)
        assertTrue(work.any)
        assertEquals(0, ImageFrameWork.take().previewDecodes)
    }

    @Test
    fun aBlurredImageLeavingCompositionStopsCounting() {
        val before = ImageFrameWork.blurredImages
        val count = BlurredImageCount()
        count.blurred = true
        count.blurred = true
        assertEquals(before + 1, ImageFrameWork.blurredImages)
        count.onForgotten()
        assertEquals(before, ImageFrameWork.blurredImages)
    }
}
