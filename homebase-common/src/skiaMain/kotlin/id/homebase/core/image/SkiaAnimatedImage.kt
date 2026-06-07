package id.homebase.core.image

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import coil3.Image
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Rect
import kotlin.time.TimeSource
import org.jetbrains.skia.Image as SkiaImage

/** Monotonic wall-clock in milliseconds — portable across JVM, native, wasmJs. */
private val timeOrigin = TimeSource.Monotonic.markNow()
internal fun nowMs(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

/**
 * A [coil3.Image] that animates a multi-frame GIF/WebP through Skia's
 * [org.jetbrains.skia.Codec], returned by [AnimatedSkiaDecoder] on the
 * non-Android targets (Desktop/JVM, iOS/native, Web/wasmJs). Android animates
 * via Coil's coil-gif AnimatedImageDecoder instead (PR #663).
 *
 * How animation reaches the screen without touching HomebaseImage:
 *
 * Coil wraps a non-[coil3.BitmapImage] custom [coil3.Image] in its internal
 * `coil3.compose.ImagePainter`, whose `onDraw(DrawScope)` calls [draw] inside
 * the Compose draw phase. [draw] reads [displayedFrame], a Compose
 * [androidx.compose.runtime.MutableIntState]. Because that read happens during
 * the draw phase, Compose's snapshot observer records it and re-invalidates the
 * draw whenever the value changes. A shared [SkiaFrameDriver] advances
 * [displayedFrame] over time off [Dispatchers.Main]; the painter repaints in
 * lock-step. No second image loader, no re-fetch, no re-decrypt — the decoder
 * sits in the existing encrypted Coil pipeline, so it inherits decrypt / fetch
 * / cache / placeholder / sizing for free.
 *
 * Cost control:
 *  - Frames are decoded **on demand** (never all up front). One reused scratch
 *    bitmap holds the last decoded frame so Skia can composite delta (disposal)
 *    frames forward; finished frames are snapshotted into a small bounded LRU of
 *    immutable [SkiaImage]s, so large GIFs never hold every frame at once.
 *  - The driver only ticks while at least one animated image has [draw]n
 *    recently (i.e. is on-screen). Items scrolled out of a LazyColumn stop
 *    drawing, stop renewing their registration, and the driver reaps them and
 *    parks itself — so off-screen GIFs cost nothing.
 */
internal class SkiaAnimatedImage(
    private val codec: Codec,
    private val frameDurationsMs: IntArray,
    private val requiredFrames: IntArray,
    private val repetitionCount: Int,
) : Image {

    private val frameWidth: Int = codec.width
    private val frameHeight: Int = codec.height

    // Cumulative end-time timeline used to map elapsed ms -> frame index.
    private val frameEndTimesMs: LongArray = LongArray(frameDurationsMs.size).also { arr ->
        var acc = 0L
        for (i in frameDurationsMs.indices) {
            // Skia reports 0 for "as fast as possible" / malformed frames; clamp
            // to a sane minimum so a bad GIF doesn't spin at thousands of fps.
            acc += frameDurationsMs[i].coerceAtLeast(MIN_FRAME_DURATION_MS).toLong()
            arr[i] = acc
        }
    }
    private val totalDurationMs: Long = frameEndTimesMs.lastOrNull() ?: 0L
    private val frameCount: Int get() = frameDurationsMs.size

    /** Compose-observable index of the frame currently shown. Read in [draw]. */
    private var displayedFrame by mutableIntStateOf(0)

    // Single scratch bitmap that always holds [lastDecodedFrame]'s pixels so the
    // codec can composite the next delta frame on top of it.
    private val scratch: Bitmap = Bitmap().also { it.allocPixels(codec.imageInfo) }
    private var lastDecodedFrame: Int = -1

    // Bounded cache of decoded immutable frame images. Insertion-ordered
    // (LinkedHashMap is multiplatform); we evict the oldest entry once over the
    // cap. Java's removeEldestEntry hook is JVM-only, so eviction is manual to
    // keep this compiling on native and wasmJs.
    private val frameCache = LinkedHashMap<Int, SkiaImage>()

    override val size: Long = frameWidth.toLong() * frameHeight.toLong() * 4
    override val width: Int = frameWidth
    override val height: Int = frameHeight
    // Frame content changes over time, so the decoded result is not shareable
    // (Coil must not treat it as a stable immutable bitmap).
    override val shareable: Boolean = false

    override fun draw(canvas: Canvas) {
        // Reading displayedFrame here (draw phase) is what wires Compose
        // invalidation: SkiaFrameDriver writes it, Compose repaints.
        val frame = displayedFrame
        // Renew our "on-screen" lease so the driver keeps ticking us.
        SkiaFrameDriver.touch(this)

        val image = frameImageOrNull(frame) ?: return
        canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
    }

    /** Returns the decoded [SkiaImage] for [frame], decoding+caching on demand. */
    private fun frameImageOrNull(frame: Int): SkiaImage? {
        frameCache[frame]?.let { return it }
        return try {
            decodeFrame(frame)
        } catch (e: Throwable) {
            Logger.e(tag = TAG) { "Frame $frame decode failed: ${e.message}" }
            null
        }
    }

    /**
     * Decode [frame] into the scratch bitmap, honoring GIF/WebP disposal. The
     * driver advances frames monotonically (…→wrap to 0), so the scratch normally
     * already holds the required predecessor and Skia's 3-arg readPixels can
     * composite the delta. When the scratch is stale (first decode, a seek, or a
     * loop wrap), we replay forward from the required keyframe so the composite
     * base is correct. A snapshot of the result is cached as an immutable image.
     */
    private fun decodeFrame(frame: Int): SkiaImage {
        val required = requiredFrames.getOrElse(frame) { -1 }
        if (required >= 0 && lastDecodedFrame != required) {
            // Rebuild the composite base: walk from the keyframe up to `required`.
            replayTo(required)
        }
        if (required >= 0 && lastDecodedFrame == required) {
            codec.readPixels(scratch, frame, required)
        } else {
            codec.readPixels(scratch, frame)
        }
        lastDecodedFrame = frame

        val snapshot = snapshotScratch()
        putInCache(frame, snapshot)
        return snapshot
    }

    private fun putInCache(frame: Int, image: SkiaImage) {
        frameCache[frame] = image
        while (frameCache.size > MAX_CACHED_FRAMES) {
            val eldestKey = frameCache.keys.firstOrNull() ?: break
            frameCache.remove(eldestKey)?.close()
        }
    }

    /** Decode frames [0..target] forward into the scratch so it holds [target]. */
    private fun replayTo(target: Int) {
        var keyframe = target
        while (keyframe > 0 && requiredFrames.getOrElse(keyframe) { -1 } >= 0) {
            keyframe = requiredFrames[keyframe]
        }
        for (i in keyframe..target) {
            val req = requiredFrames.getOrElse(i) { -1 }
            if (req >= 0 && lastDecodedFrame == req) {
                codec.readPixels(scratch, i, req)
            } else {
                codec.readPixels(scratch, i)
            }
            lastDecodedFrame = i
        }
    }

    /**
     * Snapshot the current scratch pixels into an immutable [SkiaImage].
     * [SkiaImage.makeFromBitmap] deep-copies a *mutable* source bitmap (Skia's
     * Image::MakeFromBitmap), so the returned image is independent of the next
     * frame's mutation of [scratch].
     */
    private fun snapshotScratch(): SkiaImage = SkiaImage.makeFromBitmap(scratch)

    /**
     * Compute the frame index for [elapsedMs] since playback start, honoring the
     * loop count. Returns null once a finite-loop animation has completed (so the
     * driver can stop and the last frame stays painted).
     */
    fun frameForElapsed(elapsedMs: Long): Int? {
        if (totalDurationMs <= 0L || frameCount <= 1) return 0
        if (repetitionCount >= 0) {
            // repetitionCount is the number of *extra* loops after the first
            // play-through (skiko convention), so total plays = repetition + 1.
            val totalPlayMs = totalDurationMs * (repetitionCount + 1)
            if (elapsedMs >= totalPlayMs) return null // finished — hold last frame
        }
        val intoLoop = elapsedMs % totalDurationMs
        // Binary search the cumulative end-times for the first frame whose end
        // time is strictly greater than intoLoop.
        var lo = 0
        var hi = frameCount - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (frameEndTimesMs[mid] <= intoLoop) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Called by the driver to advance the visible frame; no-op if unchanged. */
    fun advanceTo(frame: Int) {
        if (frame != displayedFrame) displayedFrame = frame
    }

    val animatable: Boolean get() = frameCount > 1 && totalDurationMs > 0L

    private companion object {
        private const val TAG = "SkiaAnimatedImage"

        // GIFs with a 0ms (or absurdly small) delay should not burn CPU; 20ms ~= 50fps cap.
        private const val MIN_FRAME_DURATION_MS = 20

        // On-demand frame cache cap per image. Bounds memory for long GIFs while
        // keeping the recently-shown window warm for smooth looping.
        private const val MAX_CACHED_FRAMES = 24
    }
}

/**
 * Process-wide clock that advances every on-screen [SkiaAnimatedImage]. A single
 * coroutine on [Dispatchers.Main] re-evaluates each registered image's displayed
 * frame on a fixed cadence and parks itself once no image has drawn within
 * [GRACE_MS]. This bounds list-scroll cost: GIFs scrolled off-screen stop
 * drawing, stop renewing their lease, and get reaped — so off-screen GIFs cost
 * nothing.
 *
 * Threading: every method runs on the single-threaded [Dispatchers.Main], so the
 * registration map needs no locking. [touch] is called from the Compose draw
 * phase (also Main), and the tick loop runs on the same dispatcher.
 */
internal object SkiaFrameDriver {
    private val scope = CoroutineScope(Dispatchers.Main)

    private class Registration(val image: SkiaAnimatedImage, var lastTouchedMs: Long, val startedMs: Long)

    private val registrations = HashMap<SkiaAnimatedImage, Registration>()
    private var job: Job? = null

    /** Renew an image's on-screen lease (called from its [SkiaAnimatedImage.draw]). */
    fun touch(image: SkiaAnimatedImage) {
        if (!image.animatable) return
        val now = nowMs()
        val existing = registrations[image]
        if (existing != null) {
            existing.lastTouchedMs = now
        } else {
            registrations[image] = Registration(image, now, now)
        }
        ensureRunning()
    }

    private fun ensureRunning() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (!tick()) break
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /**
     * One driver pass: drop stale (off-screen) or finished images, advance live
     * ones. Returns false (and parks the loop) once nothing is left to animate.
     */
    private fun tick(): Boolean {
        val now = nowMs()
        val it = registrations.entries.iterator()
        while (it.hasNext()) {
            val reg = it.next().value
            if (now - reg.lastTouchedMs > GRACE_MS) {
                it.remove()
                continue
            }
            val frame = reg.image.frameForElapsed(now - reg.startedMs)
            if (frame == null) {
                // Finished looping — hold last frame, stop ticking this one.
                it.remove()
                continue
            }
            reg.image.advanceTo(frame)
        }
        if (registrations.isEmpty()) {
            job = null
            return false
        }
        return true
    }

    // Repaint cadence. We re-evaluate at <=60fps; the per-image timeline decides
    // which frame to show, so a slow 1fps GIF still only changes pixels once per
    // second even though we poll faster.
    private const val TICK_INTERVAL_MS = 16L

    // How long after the last draw before we consider an image off-screen.
    private const val GRACE_MS = 500L
}
