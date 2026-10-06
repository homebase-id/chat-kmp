package id.homebase.chat.widget.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.sun.net.httpserver.HttpServer
import id.homebase.api.client.drives.files.HLS_PLAYLIST_CONTENT_TYPE
import io.github.kdroidfilter.composemediaplayer.DefaultVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Fixtures are 4 s, 320x240: 1 s each of red, lime, blue, yellow, so the decoded colour at the
 * centre of the rendered Compose frame says which second of the clip is on screen.
 */
class SpikeComposeMediaPlayerTest {

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
    fun h264Mp4File() = exercise(File(fixtures, "h264.mp4").absolutePath, "h264-file")

    @Test
    fun hevcMovFile() = exercise(File(fixtures, "hevc.mov").absolutePath, "hevc-file")

    @Test
    fun h264HlsOverLoopback() {
        val port = startHlsServer(File(fixtures, "hls"))
        exercise("http://127.0.0.1:$port/index.m3u8", "hls-127.0.0.1")
        log("hls-127.0.0.1", "range requests served: ${rangeRequests.size} e.g. ${rangeRequests.take(3)}")
        assertTrue(rangeRequests.isNotEmpty(), "AVPlayer never issued a segment range request")
    }

    @Test
    fun h264HlsOverLocalhostName() {
        val port = startHlsServer(File(fixtures, "hls"))
        withPlayer { state, scene ->
            val t0 = System.nanoTime()
            state.openUri("http://localhost:$port/index.m3u8", InitialPlayerState.PLAY)
            val seg = awaitSeg(scene, 10_000) { it == Seg.RED || it == Seg.LIME }
            log("hls-localhost", "first frame $seg after ${ms(t0)} ms")
            assertTrue(seg == Seg.RED || seg == Seg.LIME)
        }
    }

    @Test
    fun errorSignalling() = withPlayer { state, _ ->
        state.openUri("/nonexistent/clip.mp4", InitialPlayerState.PLAY)
        awaitTrue(2_000) { state.error != null }
        log("error", "missing file -> ${state.error}")
        assertIs<VideoPlayerError.SourceError>(state.error)

        val port = startHlsServer(File(fixtures, "hls"))
        probeError("http://127.0.0.1:$port/missing.m3u8", "hls-404")

        val garbage = File.createTempFile("spike_garbage", ".mp4").apply {
            writeBytes(ByteArray(64 * 1024) { (it * 31).toByte() }); deleteOnExit()
        }
        probeError(garbage.absolutePath, "garbage-mp4")
    }

    @Test
    fun portraitHlsAspect() {
        val port = startHlsServer(File(fixtures, "hls-portrait"))
        val uri = "http://127.0.0.1:$port/index.m3u8"
        withPlayer { state, scene ->
            state.openUri(uri, InitialPlayerState.PLAY)
            awaitSeg(scene, 10_000) { it != Seg.BLANK }
            log("hls-portrait", "default Fit: aspectRatio=${state.aspectRatio} metadata=${state.metadata.width}x${state.metadata.height} drawnWidth=${drawnWidth(scene)}px of 320")
        }
        val trueAspect = 180f / 320f
        withPlayer(content = { st ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight().aspectRatio(trueAspect)) {
                    VideoPlayerSurface(playerState = st, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
            }
        }) { state, scene ->
            state.openUri(uri, InitialPlayerState.PLAY)
            awaitSeg(scene, 10_000) { it != Seg.BLANK }
            val px = render(scene)
            val top = classify(px[px.width / 2, px.height / 4]); val bottom = classify(px[px.width / 2, px.height * 3 / 4])
            val w = drawnWidth(scene)
            log("hls-portrait", "own aspectRatio box + FillBounds: drawnWidth=${w}px (expect ~135) top=$top bottom=$bottom")
            assertTrue(w in 125..145, "portrait HLS drawn $w px wide")
            assertTrue(top == Seg.RED && bottom == Seg.BLUE, "portrait halves $top/$bottom")
        }
    }

    private fun drawnWidth(scene: ImageComposeScene): Int {
        val px = render(scene)
        return (0 until px.width).count { classify(px[it, px.height / 4]) != Seg.BLANK }
    }

    private fun probeError(uri: String, label: String) = withPlayer { state, scene ->
        state.openUri(uri, InitialPlayerState.PLAY)
        val t0 = System.nanoTime()
        val gotError = runCatching { awaitTrue(8_000) { state.error != null } }.isSuccess
        log(
            label,
            "error=${state.error} after ${ms(t0)} ms (signalled=$gotError) hasMedia=${state.hasMedia} " +
                "isLoading=${state.isLoading} centre=${centre(scene)}",
        )
    }

    private fun exercise(uri: String, label: String) = withPlayer { state, scene ->
        val ended = AtomicBoolean(false)
        state.onPlaybackEnded = { ended.set(true) }

        val t0 = System.nanoTime()
        state.openUri(uri, InitialPlayerState.PLAY)
        val first = awaitSeg(scene, 10_000) { it != Seg.BLANK }
        log(label, "first frame $first after ${ms(t0)} ms, duration=${state.duration}s metadata=${state.metadata}")
        assertTrue(first == Seg.RED || first == Seg.LIME, "first frame should be the red/lime opening, was $first")
        assertTrue(nonBlankFraction(scene) > 0.9, "rendered frame mostly blank")
        awaitTrue(5_000) { state.currentTime > 0.3 }
        log(label, "playing: clock advancing ${ms(t0)} ms after open, aspectRatio=${state.aspectRatio}")

        state.volume = 0f
        awaitTrue(2_000) { nativeVolume(state) == 0f }
        log(label, "mute: volume=0 reached AVPlayer (native volume ${nativeVolume(state)})")
        state.volume = 1f
        awaitTrue(2_000) { nativeVolume(state) == 1f }

        state.pause()
        awaitTrue(2_000) { !state.isPlaying }
        Thread.sleep(300)
        val p1 = state.currentTime
        Thread.sleep(700)
        val p2 = state.currentTime
        log(label, "pause: isPlaying=${state.isPlaying} t=$p1 -> $p2")
        assertTrue(abs(p2 - p1) < 0.05, "position advanced while paused ($p1 -> $p2)")

        val duration = state.duration
        assertTrue(duration in 3.5..4.5, "duration $duration")
        // AVPlayer's default seek tolerance snaps a file to the previous keyframe (fixtures: GOP 1 s).
        state.seekTo((2.3 / duration * 1000).toFloat())
        awaitTrue(3_000) { state.currentTime in 1.95..2.35 }
        Thread.sleep(500)
        val pausedSeekSeg = centre(scene)
        log(label, "paused seek to 2.3s: landed t=${state.currentTime} frame=$pausedSeekSeg isLoading=${state.isLoading}")

        state.volume = 0f
        state.play()
        val scrubbed = awaitSeg(scene, 3_000) { it == Seg.BLUE }
        state.pause()
        state.volume = 1f
        awaitTrue(2_000) { !state.isPlaying }
        log(label, "derived paused scrub (muted play->first new frame->pause): frame=$scrubbed t=${state.currentTime}")

        state.play()
        val afterSeek = awaitSeg(scene, 3_000) { it == Seg.BLUE || it == Seg.YELLOW }
        log(label, "play after seek: frame=$afterSeek t=${state.currentTime}")

        awaitTrue(6_000) { ended.get() }
        log(label, "ended: onPlaybackEnded fired, isPlaying=${state.isPlaying} frame=${centre(scene)}")
        assertFalse(state.isPlaying)

        ended.set(false)
        state.restart()
        val replay = awaitSeg(scene, 3_000) { it == Seg.RED }
        log(label, "replay: frame=$replay t=${state.currentTime}")

        state.seekTo((1.2 / duration * 1000).toFloat())
        awaitTrue(3_000) { state.currentTime >= 0.95 }
        val clipEnd = 2.0
        awaitTrue(3_000) { state.currentTime >= clipEnd }
        state.pause()
        Thread.sleep(300)
        log(label, "derived clip end at ${clipEnd}s: paused at t=${state.currentTime} frame=${centre(scene)}")
        assertTrue(state.currentTime < clipEnd + 0.25, "overshot derived clip end: ${state.currentTime}")
    }

    private fun withPlayer(
        content: @Composable (VideoPlayerState) -> Unit = { VideoPlayerSurface(playerState = it, modifier = Modifier.fillMaxSize()) },
        block: (VideoPlayerState, ImageComposeScene) -> Unit,
    ) {
        val state = DefaultVideoPlayerState()
        try {
            val scene = ImageComposeScene(320, 240) { content(state) }
            try {
                block(state, scene)
            } finally {
                scene.close()
            }
        } finally {
            state.dispose()
        }
    }

    private fun render(scene: ImageComposeScene) =
        run {
            Snapshot.sendApplyNotifications()
            scene.render(System.nanoTime()).toComposeImageBitmap().toPixelMap()
        }

    private fun classify(argb: androidx.compose.ui.graphics.Color): Seg {
        val r = argb.red; val g = argb.green; val b = argb.blue
        return when {
            argb.alpha < 0.5f || (r < 0.1f && g < 0.1f && b < 0.1f) -> Seg.BLANK
            r > 0.75f && g > 0.75f && b < 0.3f -> Seg.YELLOW
            r > 0.75f && g < 0.3f && b < 0.3f -> Seg.RED
            g > 0.75f && r < 0.3f && b < 0.3f -> Seg.LIME
            b > 0.75f && r < 0.3f && g < 0.3f -> Seg.BLUE
            else -> Seg.OTHER
        }
    }

    private fun centre(scene: ImageComposeScene): Seg = render(scene).let { classify(it[it.width / 2, it.height / 2]) }

    private fun nonBlankFraction(scene: ImageComposeScene): Double {
        val px = render(scene)
        var total = 0; var nonBlank = 0
        for (x in px.width / 4 until px.width * 3 / 4 step 4) for (y in px.height / 4 until px.height * 3 / 4 step 4) {
            total++
            if (classify(px[x, y]) != Seg.BLANK) nonBlank++
        }
        return nonBlank.toDouble() / total
    }

    private fun awaitSeg(scene: ImageComposeScene, timeoutMs: Long, accept: (Seg) -> Boolean): Seg {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = Seg.BLANK
        while (System.currentTimeMillis() < deadline) {
            last = centre(scene)
            if (accept(last)) return last
            Thread.sleep(16)
        }
        error("timed out after $timeoutMs ms; last frame $last")
    }

    private fun awaitTrue(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        error("condition not met within $timeoutMs ms")
    }

    private fun nativeVolume(state: VideoPlayerState): Float {
        val mac = (state as DefaultVideoPlayerState).delegate as MacVideoPlayerState
        val ptr = MacVideoPlayerState::class.java.getDeclaredField("playerPtrAtomic")
            .apply { isAccessible = true }.get(mac) as AtomicLong
        val bridge = Class.forName("io.github.kdroidfilter.composemediaplayer.mac.MacNativeBridge")
        return bridge.getDeclaredMethod("nGetVolume", Long::class.javaPrimitiveType).invoke(null, ptr.get()) as Float
    }

    // Mirrors VideoPlayerSurface.jvm.kt's in-process server: playlist + byte-range segment reads.
    private fun startHlsServer(dir: File): Int {
        val segment = File(dir, "stream.ts")
        val totalSize = segment.length()
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val name = exchange.requestURI.path.trimStart('/')
                if (name.endsWith(".m3u8")) {
                    val f = File(dir, name)
                    if (!f.exists()) {
                        exchange.sendResponseHeaders(404, -1); exchange.close(); return@createContext
                    }
                    val bytes = f.readBytes()
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

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private fun log(label: String, msg: String) = println("SPIKE [$label] $msg")
}
