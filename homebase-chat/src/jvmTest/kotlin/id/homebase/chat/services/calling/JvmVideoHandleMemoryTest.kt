package id.homebase.chat.services.calling

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.FramePacer
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Real libwebrtc track feeding the real sink with 1080p frames; CALL_MEMORY_SOAK_SECONDS=20 for a full soak. */
class JvmVideoHandleMemoryTest {
    @Test
    fun a1080pCallKeepsResidentMemoryFlat() {
        val seconds = System.getenv("CALL_MEMORY_SOAK_SECONDS")?.toIntOrNull() ?: 3
        val factory = PeerConnectionFactory(HeadlessAudioDeviceModule())
        val source = CustomVideoSource()
        val track = factory.createVideoTrack("video0", source)
        val handle = JvmVideoHandle()
        track.addSink(handle.sink)
        // Incompressible pixels; footprint (what jetsam kills on) also counts compressed pages, which RSS hides.
        val noise = Random(7).nextBytes(WIDTH * HEIGHT)
        val images = HashSet<Int>()
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val pacer = FramePacer(FPS)
        val start = footprintMb()
        val allocatedBefore = threads.currentThreadAllocatedBytes
        var peak = start
        println("maxHeap=${Runtime.getRuntime().maxMemory() shr 20}MB start footprint=${start}MB rss=${rssMb()}MB")
        try {
            for (i in 1..seconds * FPS) {
                val buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT)
                buffer.dataY.put(noise)
                val frame = VideoFrame(buffer, System.nanoTime())
                source.pushFrame(frame)
                frame.release()
                handle.frame.value?.let { images += System.identityHashCode(it.image) }
                if (i % FPS == 0) {
                    val used = footprintMb()
                    peak = maxOf(peak, used)
                    val heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) shr 20
                    println("t=${i / FPS}s frames=$i footprint=${used}MB rss=${rssMb()}MB heapUsed=${heap}MB distinctImages=${images.size}")
                    if (used > ABORT_MB) fail("footprint ${used}MB passed the ${ABORT_MB}MB abort line after $i frames")
                }
                pacer.waitForNextFrame()
            }
        } finally {
            track.removeSink(handle.sink)
            handle.close()
            track.dispose()
            source.dispose()
            factory.dispose()
        }
        val frames = seconds * FPS
        val perFrameKb = (threads.currentThreadAllocatedBytes - allocatedBefore) / frames / 1024
        println("frames=$frames footprint start=${start}MB peak=${peak}MB growth=${peak - start}MB heapAllocPerFrame=${perFrameKb}KB distinctImages=${images.size}")
        assertTrue(peak - start < MAX_GROWTH_MB, "footprint grew ${peak - start}MB over $frames frames")
        assertEquals(1, images.size, "one bitmap reused for every frame of one size")
    }

    @Test
    fun aPortraitPhoneFrameKeepsItsRotationAndAResizeSwapsTheBitmap() {
        val factory = PeerConnectionFactory(HeadlessAudioDeviceModule())
        val source = CustomVideoSource()
        val track = factory.createVideoTrack("video0", source)
        val handle = JvmVideoHandle()
        track.addSink(handle.sink)
        try {
            push(source, 1280, 720, rotation = 90)
            val first = assertNotNull(handle.frame.value)
            assertEquals(90, first.rotation)
            assertEquals(1280, first.image.width)

            Thread.sleep(60)
            push(source, 640, 360, rotation = 0)
            val second = assertNotNull(handle.frame.value)
            assertEquals(640, second.image.width)
            var drewStale = false
            handle.draw(first) { drewStale = true }
            assertFalse(drewStale, "a frame whose bitmap was resized away is never drawn")

            handle.close()
            assertNull(handle.frame.value)
            var drewClosed = false
            handle.draw(second) { drewClosed = true }
            assertFalse(drewClosed)
        } finally {
            track.removeSink(handle.sink)
            track.dispose()
            source.dispose()
            factory.dispose()
        }
    }

    private fun push(source: CustomVideoSource, width: Int, height: Int, rotation: Int) {
        val frame = VideoFrame(NativeI420Buffer.allocate(width, height), rotation, System.nanoTime())
        source.pushFrame(frame)
        frame.release()
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 30
        const val ABORT_MB = 3_072L
        const val MAX_GROWTH_MB = 150L
    }
}
