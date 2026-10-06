package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.fillMaxSize
import com.sun.net.httpserver.HttpServer
import id.homebase.api.client.drives.files.HLS_PLAYLIST_CONTENT_TYPE
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Fixtures are 4 s, 320x240: 1 s each of red, lime, blue, yellow, so the colour at the centre of
 * the rendered Compose frame says which second of the clip is on screen.
 */
class DesktopVideoBackendJvmTest {

    private enum class Seg { BLANK, RED, LIME, BLUE, YELLOW, OTHER }

    private val fixtures = File(javaClass.classLoader.getResource("spike-video/h264.mp4")!!.toURI()).parentFile
    private var server: HttpServer? = null
    private val rangeRequests = Collections.synchronizedList(mutableListOf<String>())

    @BeforeTest
    fun onlyOnMac() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac"))
    }

    @AfterTest
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun h264Mp4File() = playsThroughChooser(File(fixtures, "h264.mp4").absolutePath)

    @Test
    fun hevcMovFile() = playsThroughChooser(File(fixtures, "hevc.mov").absolutePath)

    @Test
    fun h264HlsOverLocalhost() {
        val port = startHlsServer(File(fixtures, "hls"))
        playsThroughChooser("http://localhost:$port/index.m3u8")
        assertTrue(rangeRequests.isNotEmpty(), "AVPlayer never issued a segment range request")
    }

    @Test
    fun portraitHlsKeepsTrueAspect() {
        val port = startHlsServer(File(fixtures, "hls-portrait"))
        val firstFrame = AtomicBoolean(false)
        scene({
            DesktopVideoPlayer(
                videoPath = "http://localhost:$port/index.m3u8",
                aspectRatio = 180f / 320f,
                modifier = Modifier.fillMaxSize(),
                showControls = false,
                onFirstFrameRendered = { firstFrame.set(true) },
            )
        }) { scene ->
            awaitTrue(scene, 10_000) { firstFrame.get() }
            awaitTrue(scene, 2_000) { centreSeg(scene) != Seg.BLANK }
            val px = render(scene)
            val drawn = (0 until px.width).count { classify(px[it, px.height / 4]) != Seg.BLANK }
            assertTrue(drawn in 125..145, "portrait HLS drawn $drawn px wide, expected ~135")
            assertEquals(Seg.RED, classify(px[px.width / 2, px.height / 4]))
            assertEquals(Seg.BLUE, classify(px[px.width / 2, px.height * 3 / 4]))
        }
    }

    @Test
    fun clipRangeLoopsWithinStartAndEnd() {
        val positions = Collections.synchronizedList(mutableListOf<Long>())
        val firstFrame = AtomicBoolean(false)
        scene({
            DesktopVideoPlayer(
                videoPath = File(fixtures, "h264.mp4").absolutePath,
                modifier = Modifier.fillMaxSize(),
                showControls = false,
                clipStartMs = 1_000,
                clipEndMs = 2_000,
                muted = true,
                onPositionMs = { positions += it },
                onFirstFrameRendered = { firstFrame.set(true) },
            )
        }) { scene ->
            awaitTrue(scene, 10_000) { firstFrame.get() }
            val deadline = System.currentTimeMillis() + 4_500
            while (System.currentTimeMillis() < deadline) { render(scene); Thread.sleep(16) }
            val seen = positions.toList()
            assertTrue(seen.isNotEmpty() && seen.max() < 2_300, "clip overshot its end: max=${seen.maxOrNull()}")
            assertTrue(seen.count { it in 1_000..2_100 } > 20, "position never stayed inside the clip")
        }
    }

    @Test
    fun reportsEndedKeepsLastFrameAndReplays() {
        val ended = AtomicInteger(0)
        val token = mutableIntStateOf(0)
        scene({
            DesktopVideoPlayer(
                videoPath = File(fixtures, "h264.mp4").absolutePath,
                modifier = Modifier.fillMaxSize(),
                showControls = false,
                muted = true,
                onEnded = { ended.incrementAndGet() },
                replayToken = token.intValue,
            )
        }) { scene ->
            awaitTrue(scene, 12_000) { ended.get() == 1 }
            assertEquals(Seg.YELLOW, centreSeg(scene), "last frame should stay on screen")
            token.intValue = 1
            awaitTrue(scene, 3_000) { centreSeg(scene) == Seg.RED }
        }
    }

    @Test
    fun garbageFileRaisesUnplayableWithinWatchdog() {
        val garbage = File.createTempFile("garbage", ".mp4").apply {
            writeBytes(ByteArray(64 * 1024) { (it * 31).toByte() }); deleteOnExit()
        }
        val unplayable = AtomicBoolean(false)
        val first = AtomicBoolean(false)
        scene({
            NativeAvPlayer(
                videoPath = garbage.absolutePath,
                aspectRatio = null,
                modifier = Modifier.fillMaxSize(),
                onFirstFrameRendered = { first.set(true) },
                onUnplayable = { unplayable.set(true) },
                watchdogMs = 2_000,
            )
        }) { scene ->
            awaitTrue(scene, 8_000) { unplayable.get() }
            assertTrue(!first.get(), "garbage must not report a first frame")
        }
    }

    @Test
    fun missingFileRaisesUnplayableImmediately() {
        val unplayable = AtomicBoolean(false)
        scene({
            NativeAvPlayer(
                videoPath = "/nonexistent/clip.mp4",
                aspectRatio = null,
                modifier = Modifier.fillMaxSize(),
                onUnplayable = { unplayable.set(true) },
            )
        }) { scene -> awaitTrue(scene, 3_000) { unplayable.get() } }
    }

    private fun playsThroughChooser(uri: String) {
        val firstFrame = AtomicBoolean(false)
        scene({
            DesktopVideoPlayer(
                videoPath = uri,
                modifier = Modifier.fillMaxSize(),
                showControls = false,
                muted = true,
                onFirstFrameRendered = { firstFrame.set(true) },
            )
        }) { scene ->
            awaitTrue(scene, 10_000) { firstFrame.get() }
            awaitTrue(scene, 2_000) { centreSeg(scene) != Seg.BLANK }
            val seg = centreSeg(scene)
            assertTrue(seg == Seg.RED || seg == Seg.LIME, "first frame should be the red/lime opening, was $seg")
            assertTrue(nonBlankFraction(scene) > 0.9, "rendered frame mostly blank")
        }
    }

    private fun scene(content: @Composable () -> Unit, block: (ImageComposeScene) -> Unit) {
        val scene = ImageComposeScene(320, 240) { content() }
        try {
            block(scene)
        } finally {
            scene.close()
        }
    }

    private fun render(scene: ImageComposeScene) = run {
        Snapshot.sendApplyNotifications()
        scene.render(System.nanoTime()).toComposeImageBitmap().toPixelMap()
    }

    private fun classify(c: Color): Seg {
        val r = c.red; val g = c.green; val b = c.blue
        return when {
            c.alpha < 0.5f || (r < 0.1f && g < 0.1f && b < 0.1f) -> Seg.BLANK
            r > 0.75f && g > 0.75f && b < 0.3f -> Seg.YELLOW
            r > 0.75f && g < 0.3f && b < 0.3f -> Seg.RED
            g > 0.75f && r < 0.3f && b < 0.3f -> Seg.LIME
            b > 0.75f && r < 0.3f && g < 0.3f -> Seg.BLUE
            else -> Seg.OTHER
        }
    }

    private fun centreSeg(scene: ImageComposeScene): Seg = render(scene).let { classify(it[it.width / 2, it.height / 2]) }

    private fun nonBlankFraction(scene: ImageComposeScene): Double {
        val px = render(scene)
        var total = 0; var nonBlank = 0
        for (x in px.width / 4 until px.width * 3 / 4 step 4) for (y in px.height / 4 until px.height * 3 / 4 step 4) {
            total++
            if (classify(px[x, y]) != Seg.BLANK) nonBlank++
        }
        return nonBlank.toDouble() / total
    }

    private fun awaitTrue(scene: ImageComposeScene, timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            render(scene)
            if (cond()) return
            Thread.sleep(16)
        }
        error("condition not met within $timeoutMs ms")
    }

    private fun startHlsServer(dir: File): Int {
        val segment = File(dir, "stream.ts")
        val totalSize = segment.length()
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val name = exchange.requestURI.path.trimStart('/')
                if (name.endsWith(".m3u8")) {
                    val bytes = File(dir, name).readBytes()
                    exchange.responseHeaders.add("Content-Type", HLS_PLAYLIST_CONTENT_TYPE)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    return@createContext
                }
                exchange.responseHeaders.add("Content-Type", "video/mp2t")
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                val range = exchange.requestHeaders.getFirst("Range")
                val (start, end) = if (range != null) {
                    val parts = range.removePrefix("bytes=").split("-")
                    parts[0].toLong() to (if (parts[1].isNotEmpty()) parts[1].toLong() else totalSize - 1)
                } else 0L to totalSize - 1
                rangeRequests += "$name:$start-$end"
                val bytes = segment.readBytes().copyOfRange(start.toInt(), (end + 1).toInt())
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$totalSize")
                exchange.sendResponseHeaders(206, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            executor = Executors.newFixedThreadPool(4)
            start()
        }
        server = s
        return s.address.port
    }
}
