package id.homebase.core.image

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.TimeSource

/**
 * Main-thread image work since the last frame, so a slow frame can say what ran before it.
 * Diagnostic for #1987 (iOS chat scroll stutter while images load in); remove once understood.
 */
internal object ImageFrameWork {
    var imagesComposed = 0
    var stateChanges = 0
    var previewDecodes = 0
    var previewDecodeNanos = 0L
    var blurSteps = 0
    var blurredImages = 0

    inline fun <T> timePreviewDecode(decode: () -> T): T {
        val start = TimeSource.Monotonic.markNow()
        return decode().also {
            previewDecodes++
            previewDecodeNanos += start.elapsedNow().inWholeNanoseconds
        }
    }

    fun take(): FrameWork = FrameWork(
        imagesComposed, stateChanges, previewDecodes, previewDecodeNanos, blurSteps, blurredImages,
    ).also {
        imagesComposed = 0
        stateChanges = 0
        previewDecodes = 0
        previewDecodeNanos = 0
        blurSteps = 0
    }
}

internal data class FrameWork(
    val imagesComposed: Int,
    val stateChanges: Int,
    val previewDecodes: Int,
    val previewDecodeNanos: Long,
    val blurSteps: Int,
    val blurredImages: Int,
) {
    val any: Boolean get() = imagesComposed + stateChanges + previewDecodes + blurSteps > 0

    override fun toString(): String =
        "composed=$imagesComposed stateChanges=$stateChanges " +
            "previewDecodes=$previewDecodes (${previewDecodeNanos.ms()} ms) " +
            "blurSteps=$blurSteps blurredImages=$blurredImages"
}

/** Keeps [ImageFrameWork.blurredImages] right for an image that leaves composition while blurred. */
internal class BlurredImageCount : RememberObserver {
    var blurred = false
        set(value) {
            if (field != value) ImageFrameWork.blurredImages += if (value) 1 else -1
            field = value
        }

    override fun onRemembered() {}
    override fun onForgotten() { blurred = false }
    override fun onAbandoned() { blurred = false }
}

/**
 * After each scroll of [state] that had slow frames, logs them, each with the [ImageFrameWork]
 * done since the frame before it. Slow is relative to the scroll's own median frame, so 60 Hz
 * and 120 Hz screens both work.
 */
@Composable
fun ScrollJankLogger(state: LazyListState, tag: String) {
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) return@collectLatest
            val frames = ArrayList<Pair<Long, FrameWork>>()
            try {
                var last = withFrameNanos { it }
                ImageFrameWork.take()
                while (true) {
                    val now = withFrameNanos { it }
                    frames += (now - last) to ImageFrameWork.take()
                    last = now
                }
            } finally {
                logSlowFrames(tag, frames)
            }
        }
    }
}

private const val MaxLoggedFrames = 20

internal fun slowFrames(frames: List<Pair<Long, FrameWork>>): Pair<Long, List<Pair<Long, FrameWork>>> {
    if (frames.size < 3) return 0L to emptyList()
    val median = frames.map { it.first }.sorted()[frames.size / 2]
    return median to frames.filter { it.first > median * 3 / 2 }
}

private fun logSlowFrames(tag: String, frames: List<Pair<Long, FrameWork>>) {
    val (median, slow) = slowFrames(frames)
    if (slow.isEmpty()) return
    val withImageWork = slow.count { it.second.any }
    Logger.w(tag = tag) {
        buildString {
            append("scroll: ${frames.size} frames, median ${median.ms()} ms, ${slow.size} slow ")
            append("(worst ${slow.maxOf { it.first }.ms()} ms), $withImageWork of them after image work")
            slow.sortedByDescending { it.first }.take(MaxLoggedFrames).forEach { (nanos, work) ->
                append("\n  ${nanos.ms()} ms: $work")
            }
        }
    }
}

private fun Long.ms(): String = ((this / 100_000) / 10.0).toString()
