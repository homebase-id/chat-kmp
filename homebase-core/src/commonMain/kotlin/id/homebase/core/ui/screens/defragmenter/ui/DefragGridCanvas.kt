package id.homebase.core.ui.screens.defragmenter.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import id.homebase.core.ui.screens.defragmenter.InFlightMove
import id.homebase.core.ui.screens.defragmenter.model.BlockGrid
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Block sizes at or above this (in pixels) switch from modern neon glow to
// authentic Win98 defrag.exe rendering with per-block bevels.
private const val CLASSIC_BLOCK_THRESHOLD: Float = 10f

/**
 * Geometry helpers for mapping a block index → pixel rect.
 */
internal class GridLayout(
    val cols: Int,
    val rows: Int,
    val blockSize: Float,
    val gap: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    fun indexOffset(index: Int): Offset {
        val col = index % cols
        val row = index / cols
        val step = blockSize + gap
        return Offset(offsetX + col * step, offsetY + row * step)
    }

    companion object {
        fun forArea(totalBlocks: Int, width: Float, height: Float): GridLayout {
            if (totalBlocks <= 0 || width <= 0f || height <= 0f) {
                return GridLayout(1, 1, 0f, 0f, 0f, 0f)
            }
            val aspect = width / height
            var cols = sqrt(totalBlocks.toDouble() * aspect).toInt().coerceAtLeast(1)
            var rows = ceil(totalBlocks.toDouble() / cols).toInt()
            val block1 = min(width / cols, height / rows)
            val cols2 = cols + 1
            val rows2 = ceil(totalBlocks.toDouble() / cols2).toInt()
            val block2 = min(width / cols2, height / rows2)
            if (block2 > block1) {
                cols = cols2
                rows = rows2
            }
            val rawBlock = min(width / cols, height / rows)
            val gap = if (rawBlock >= 4f) 1f else 0f
            val blockSize = max(1f, rawBlock - gap)
            val totalWidth = cols * (blockSize + gap) - gap
            val totalHeight = rows * (blockSize + gap) - gap
            val offsetX = (width - totalWidth) / 2f
            val offsetY = (height - totalHeight) / 2f
            return GridLayout(cols, rows, blockSize, gap, offsetX, offsetY)
        }
    }
}

/**
 * Dark defrag canvas. Single Canvas, never one-composable-per-block.
 *
 * Renders in one of two styles based on [GridLayout.blockSize]:
 *  • `>= CLASSIC_BLOCK_THRESHOLD` → authentic Win98 defrag.exe look: black
 *    background, solid navy filled blocks with top/left highlight and
 *    bottom/right shadow, yellow moving blocks, no glow or trails.
 *  • Otherwise → modern neon: dark gradient background, cyan/blue glowing
 *    blocks, fading linear-gradient trails, pulsing radial target halos.
 *
 * Filled-block positions are cached via `remember(gridVersion, layout)` so
 * they're only recomputed on commits or resizes.
 */
@Composable
fun DefragGridCanvas(
    grid: BlockGrid,
    gridVersion: Long,
    inFlight: List<InFlightMove>,
    targetHighlights: IntArray,
    frameTimeNanos: Long,
    celebratoryProgress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val layout = remember(widthPx, heightPx, grid.totalBlocks) {
            GridLayout.forArea(grid.totalBlocks, widthPx, heightPx)
        }
        val classic = layout.blockSize >= CLASSIC_BLOCK_THRESHOLD

        // In classic mode we keep filled positions as IntArray so per-block
        // bevel draws can iterate without reading the bitset. In modern mode
        // we cache a Path of all rects for a single drawPath call.
        val classicIndices: IntArray? = remember(gridVersion, layout, classic) {
            if (classic) buildFilledIndices(grid) else null
        }
        val modernPath: Path? = remember(gridVersion, layout, classic) {
            if (!classic) buildFilledPath(grid, layout) else null
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (classic) {
                drawClassic(
                    layout = layout,
                    filledIndices = classicIndices!!,
                    inFlight = inFlight,
                    targetHighlights = targetHighlights,
                    frameTimeNanos = frameTimeNanos,
                    celebratoryProgress = celebratoryProgress,
                )
            } else {
                drawModern(
                    layout = layout,
                    filledPath = modernPath!!,
                    inFlight = inFlight,
                    targetHighlights = targetHighlights,
                    frameTimeNanos = frameTimeNanos,
                    celebratoryProgress = celebratoryProgress,
                )
            }
        }
    }
}

// region Modern (neon) renderer ------------------------------------------------

private fun DrawScope.drawModern(
    layout: GridLayout,
    filledPath: Path,
    inFlight: List<InFlightMove>,
    targetHighlights: IntArray,
    frameTimeNanos: Long,
    celebratoryProgress: Float,
) {
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Win98Palette.CanvasBackgroundTop, Win98Palette.CanvasBackground)
        ),
        size = size,
    )
    // Always draw the original-blue filled path. During the celebration,
    // paint a green copy over the left portion via clipRect — a vertical scan
    // line sweeping left-to-right over 5s.
    drawPath(filledPath, Win98Palette.BlockEdge)
    if (celebratoryProgress > 0f) {
        val sweepX = celebratoryProgress * size.width
        clipRect(left = 0f, top = 0f, right = sweepX, bottom = size.height) {
            drawPath(filledPath, Win98Palette.GreenGlowEdge)
        }
        return
    }
    drawTargetHalos(targetHighlights, layout, frameTimeNanos)
    drawInFlightModern(inFlight, layout, frameTimeNanos)
}

private fun buildFilledPath(grid: BlockGrid, layout: GridLayout): Path {
    val path = Path()
    val b = layout.blockSize
    val step = b + layout.gap
    val cols = layout.cols
    if (b <= 0f || cols <= 0) return path
    val total = grid.totalBlocks
    var i = 0
    while (i < total) {
        if (grid.isFilled(i)) {
            val col = i % cols
            val row = i / cols
            val x = layout.offsetX + col * step
            val y = layout.offsetY + row * step
            path.addRect(Rect(x, y, x + b, y + b))
        }
        i++
    }
    return path
}

private fun DrawScope.drawTargetHalos(
    targets: IntArray,
    layout: GridLayout,
    frameTimeNanos: Long,
) {
    if (targets.isEmpty() || layout.blockSize <= 0f) return
    val ms = frameTimeNanos / 1_000_000L
    val pulse = 0.5f + 0.5f * sin(ms * 0.006f)
    val haloAlpha = 0.25f + 0.35f * pulse
    val haloRadius = max(8f, layout.blockSize * 2.4f)
    for (target in targets) {
        val origin = layout.indexOffset(target)
        val centre = Offset(
            origin.x + layout.blockSize / 2f,
            origin.y + layout.blockSize / 2f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Win98Palette.TargetHalo.copy(alpha = haloAlpha),
                    Color.Transparent,
                ),
                center = centre,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = centre,
        )
    }
}

private fun DrawScope.drawInFlightModern(
    inFlight: List<InFlightMove>,
    layout: GridLayout,
    frameTimeNanos: Long,
) {
    if (inFlight.isEmpty() || layout.blockSize <= 0f) return
    val b = layout.blockSize
    for (move in inFlight) {
        val t = ((frameTimeNanos - move.startTimeNanos).toFloat() / move.durationNanos)
            .coerceIn(0f, 1f)
        val eased = FastOutSlowInEasing.transform(t)

        val from = layout.indexOffset(move.fromIndex)
        val to = layout.indexOffset(move.toIndex)
        val x = from.x + (to.x - from.x) * eased
        val y = from.y + (to.y - from.y) * eased
        val currentCenter = Offset(x + b / 2f, y + b / 2f)
        val fromCenter = Offset(from.x + b / 2f, from.y + b / 2f)

        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Win98Palette.TrailEnd, Win98Palette.TrailStart),
                start = fromCenter,
                end = currentCenter,
            ),
            start = fromCenter,
            end = currentCenter,
            strokeWidth = max(1f, b * 0.6f),
        )

        val glowRadius = max(4f, b * 2.2f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Win98Palette.BlockGlow, Color.Transparent),
                center = currentCenter,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = currentCenter,
        )

        drawRect(color = Win98Palette.BlockEdge, topLeft = Offset(x, y), size = Size(b, b))
        val inset = max(0.5f, b * 0.2f)
        drawRect(
            color = Win98Palette.BlockCore,
            topLeft = Offset(x + inset, y + inset),
            size = Size(b - inset * 2f, b - inset * 2f),
        )
    }
}

// endregion

// region Classic (Win98) renderer ----------------------------------------------

private fun DrawScope.drawClassic(
    layout: GridLayout,
    filledIndices: IntArray,
    inFlight: List<InFlightMove>,
    targetHighlights: IntArray,
    frameTimeNanos: Long,
    celebratoryProgress: Float,
) {
    // Solid black background — no gradient.
    drawRect(color = Win98Palette.ClassicBg, size = size)

    val b = layout.blockSize
    val sweepX = if (celebratoryProgress > 0f) celebratoryProgress * size.width else Float.NEGATIVE_INFINITY
    for (idx in filledIndices) {
        val origin = layout.indexOffset(idx)
        val green = origin.x + b * 0.5f < sweepX
        val fill = if (green) Win98Palette.ClassicGreenFill else Win98Palette.ClassicBlockFill
        val light = if (green) Win98Palette.ClassicGreenLight else Win98Palette.ClassicBlockLight
        val shadow = if (green) Win98Palette.ClassicGreenShadow else Win98Palette.ClassicBlockShadow
        drawClassicBlock(
            topLeft = origin,
            blockSize = b,
            fill = fill,
            light = light,
            shadow = shadow,
        )
    }

    // No in-flight sprites or target halos during the celebratory sweep.
    if (celebratoryProgress > 0f) return

    // Target highlight: 1-px flashing yellow outline on the gap about to
    // receive a block. Pulses via alpha so it's visible against black.
    if (targetHighlights.isNotEmpty()) {
        val ms = frameTimeNanos / 1_000_000L
        val pulse = 0.5f + 0.5f * sin(ms * 0.010f)
        val alpha = 0.45f + 0.55f * pulse
        val outline = Win98Palette.ClassicMoving.copy(alpha = alpha)
        for (target in targetHighlights) {
            val o = layout.indexOffset(target)
            drawRect(
                color = outline,
                topLeft = o,
                size = Size(b, b),
                style = Stroke(width = max(1f, b * 0.08f)),
            )
        }
    }

    // In-flight: yellow block with bevel, no glow or trail.
    for (move in inFlight) {
        val t = ((frameTimeNanos - move.startTimeNanos).toFloat() / move.durationNanos)
            .coerceIn(0f, 1f)
        val eased = FastOutSlowInEasing.transform(t)
        val from = layout.indexOffset(move.fromIndex)
        val to = layout.indexOffset(move.toIndex)
        val x = from.x + (to.x - from.x) * eased
        val y = from.y + (to.y - from.y) * eased
        drawClassicBlock(
            topLeft = Offset(x, y),
            blockSize = b,
            fill = Win98Palette.ClassicMoving,
            light = Win98Palette.ClassicMovingLight,
            shadow = Win98Palette.ClassicMovingShadow,
        )
    }
}

private fun DrawScope.drawClassicBlock(
    topLeft: Offset,
    blockSize: Float,
    fill: Color,
    light: Color,
    shadow: Color,
) {
    val b = blockSize
    if (b <= 0f) return
    drawRect(color = fill, topLeft = topLeft, size = Size(b, b))
    if (b < 3f) return
    val x = topLeft.x
    val y = topLeft.y
    // Top edge (bright).
    drawLine(
        color = light,
        start = Offset(x, y + 0.5f),
        end = Offset(x + b, y + 0.5f),
        strokeWidth = 1f,
    )
    // Left edge (bright).
    drawLine(
        color = light,
        start = Offset(x + 0.5f, y),
        end = Offset(x + 0.5f, y + b),
        strokeWidth = 1f,
    )
    // Bottom edge (shadow).
    drawLine(
        color = shadow,
        start = Offset(x, y + b - 0.5f),
        end = Offset(x + b, y + b - 0.5f),
        strokeWidth = 1f,
    )
    // Right edge (shadow).
    drawLine(
        color = shadow,
        start = Offset(x + b - 0.5f, y),
        end = Offset(x + b - 0.5f, y + b),
        strokeWidth = 1f,
    )
}

private fun buildFilledIndices(grid: BlockGrid): IntArray {
    val total = grid.totalBlocks
    if (total == 0) return IntArray(0)
    // Count first to size the array exactly.
    var count = 0
    for (i in 0 until total) if (grid.isFilled(i)) count++
    val out = IntArray(count)
    var c = 0
    for (i in 0 until total) {
        if (grid.isFilled(i)) out[c++] = i
    }
    return out
}

// endregion
