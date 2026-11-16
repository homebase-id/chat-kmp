package id.homebase.api.image.gif

import id.homebase.api.lib.image.ImageFormatDetector.playedDelayCs
import id.homebase.api.lib.image.ImageFormatDetector.skipGifSubBlocks
import id.homebase.api.lib.image.ImageFormatDetector.u16le
import id.homebase.api.util.ByteBuilder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield

/**
 * Re-encodes an animated GIF smaller: [width]×[height], at most [colors] colours, and at most [fps] frames a second
 * when it's given and lower than the source's. The palette is built from the pixels that change between frames,
 * colours are mapped without dithering, and each frame stores only the rectangle that changed. The source is decoded
 * twice (once for the palette, once to encode), so only a few frames are held at a time. Null when [gif] can't be
 * decoded.
 */
internal suspend fun reencodeGif(gif: ByteArray, width: Int, height: Int, colors: Int, fps: Int? = null): ByteArray? {
    val decoder = GifDecoder.open(gif) ?: return null
    val schedule = FrameSchedule(decoder.delaysCs(), fps)
    // The palette comes from the source frames on a grid as coarse as the shrink, so frames are scaled only once.
    val histogram = Histogram(stride = max(1, min(decoder.width / width, decoder.height / height)), decoder.width)
    var previous: IntArray? = null
    val frames = decoder.frames()
    for (index in 0 until decoder.frameCount) {
        // Each frame also gives a single-threaded platform (the web) back its event loop, and lets a timeout fire.
        yield()
        val canvas = frames.next()
        if (schedule.delayCs(index) > 0) {
            histogram.add(canvas, previous)
            previous = (previous ?: IntArray(canvas.size)).also { canvas.copyInto(it) }
        }
    }
    val writer = GifWriter(width, height, histogram.palette(colors), decoder.loopCount, histogram.transparent)
    // Decoding and writing are sequential; scaling and mapping colours run a few frames at once, in order. A frame
    // waits for a free scaler before it's copied, so at most that many copies are alive.
    val scalers = Channel<Scaler>(PARALLEL_FRAMES).apply { repeat(PARALLEL_FRAMES) { trySend(Scaler(decoder.width, decoder.height, width, height)) } }
    coroutineScope {
        flow {
            val again = decoder.frames()
            for (index in 0 until decoder.frameCount) {
                yield()
                val canvas = again.next()
                val delayCs = schedule.delayCs(index)
                if (delayCs > 0) emit(Triple(scalers.receive(), canvas.copyOf(), delayCs))
            }
        }.map { (scaler, canvas, delayCs) ->
            async {
                try { writer.indices(scaler.scale(canvas)) to delayCs } finally { scalers.send(scaler) }
            }
        }.buffer(PARALLEL_FRAMES).collect { val (frame, delayCs) = it.await(); writer.add(frame, delayCs) }
    }
    return writer.finish()
}

private const val PARALLEL_FRAMES = 4

/** Whether [fps] is below the rate of [frames] frames in [durationCs]: a frame rate is only ever lowered. */
internal fun lowersFrameRate(frames: Int, durationCs: Int, fps: Int) = frames * 100L > fps.toLong() * durationCs

/**
 * How long each source frame shows once the frame rate is lowered to ticks of 1/[fps] s: 0 when no tick falls in it
 * (dropped), else the time until the next kept frame, so a held frame is written once.
 */
private class FrameSchedule(sourceDelaysCs: IntArray, fps: Int?) {
    private val delays: IntArray

    init {
        val total = sourceDelaysCs.sum()
        if (fps == null || !lowersFrameRate(sourceDelaysCs.size, total, fps)) {
            delays = sourceDelaysCs
        } else {
            val ticks = ((total * fps.toLong() + 99) / 100).toInt().coerceAtLeast(1)
            fun tickCs(k: Int) = (k * 100.0 / fps).roundToInt().coerceAtMost(total)
            val ticksIn = IntArray(sourceDelaysCs.size)
            var start = 0
            var tick = 0
            for (i in sourceDelaysCs.indices) {
                val end = start + sourceDelaysCs[i]
                while (tick < ticks && tickCs(tick) < end) {
                    ticksIn[i]++
                    tick++
                }
                start = end
            }
            delays = IntArray(sourceDelaysCs.size)
            var firstTick = 0
            for (i in sourceDelaysCs.indices) {
                if (ticksIn[i] == 0) continue
                val lastTick = firstTick + ticksIn[i]
                delays[i] = max(2, (if (lastTick >= ticks) total else tickCs(lastTick)) - tickCs(firstTick))
                firstTick = lastTick
            }
        }
    }

    fun delayCs(sourceIndex: Int) = delays[sourceIndex]
}

/** Streams a GIF's frames, each as the full composited canvas (ARGB, 0 = transparent). */
internal class GifDecoder private constructor(private val b: ByteArray) {
    var width = 0
        private set
    var height = 0
        private set

    /** NETSCAPE2.0 loop count (0 = forever), or null when the GIF plays once. */
    var loopCount: Int? = null
        private set
    private var globalColors: IntArray? = null
    private var firstBlock = 0
    private val images = mutableListOf<Image>()

    val frameCount get() = images.size

    fun delaysCs(): IntArray = IntArray(images.size) { images[it].delayCs }

    /** The frames in order; [Frames.next] returns the same canvas each time, so copy what must be kept. */
    fun frames() = Frames()

    inner class Frames {
        private val canvas = IntArray(width * height)
        private var index = 0
        private var dispose: (() -> Unit)? = null

        fun next(): IntArray {
            val image = images[index++]
            dispose?.invoke()
            val saved = if (image.disposal == 3) canvas.copyOf() else null
            image.draw(canvas)
            dispose = when (image.disposal) {
                2 -> { { image.clear(canvas) } }
                3 -> { { saved!!.copyInto(canvas) } }
                else -> null
            }
            return canvas
        }
    }

    private inner class Image(
        val left: Int, val top: Int, val w: Int, val h: Int,
        val colors: IntArray, val transparent: Int, val disposal: Int, val delayCs: Int,
        val interlaced: Boolean, val dataStart: Int,
    ) {
        fun draw(canvas: IntArray) {
            // A short frame leaves the rest of its rectangle as it was, as browsers and ffmpeg do.
            val pixels = lzwDecode(b, dataStart, w * h) ?: return
            var row = 0
            for (pass in if (interlaced) INTERLACE else NO_INTERLACE) {
                var y = pass.first
                while (y < h) {
                    val cy = top + y
                    if (cy in 0 until height) {
                        val from = row * w
                        if (from >= pixels.size) return
                        for (x in 0 until min(w, pixels.size - from)) {
                            val cx = left + x
                            val p = pixels[from + x].toInt() and 0xFF
                            if (p == transparent || cx !in 0 until width) continue
                            canvas[cy * width + cx] = if (p < colors.size) colors[p] else 0
                        }
                    }
                    row++
                    y += pass.second
                }
            }
        }

        fun clear(canvas: IntArray) {
            for (y in max(0, top) until min(height, top + h)) {
                canvas.fill(0, y * width + max(0, left), y * width + min(width, left + w))
            }
        }
    }

    private fun walk(onImage: (Image) -> Unit) {
        var i = firstBlock
        var transparent = -1
        var disposal = 0
        var delayCs = 0
        while (i < b.size) {
            when (b[i].toInt() and 0xFF) {
                0x21 -> {
                    if (i + 2 > b.size) return
                    val label = b[i + 1].toInt() and 0xFF
                    if (label == 0xF9 && i + 8 <= b.size) {
                        val packed = b[i + 3].toInt() and 0xFF
                        disposal = (packed shr 2) and 7
                        delayCs = u16(i + 4)
                        transparent = if (packed and 1 != 0) b[i + 6].toInt() and 0xFF else -1
                    } else if (label == 0xFF && i + 14 <= b.size && b.decodeToString(i + 3, i + 14) == "NETSCAPE2.0" && i + 19 <= b.size) {
                        loopCount = u16(i + 16)
                    }
                    i = skipSubBlocks(i + 2)
                }
                0x2C -> {
                    if (i + 10 > b.size) return
                    val packed = b[i + 9].toInt() and 0xFF
                    var at = i + 10
                    val colors = if (packed and 0x80 != 0) {
                        val n = 2 shl (packed and 7)
                        readColors(at, n).also { at += n * 3 }
                    } else {
                        globalColors ?: IntArray(0)
                    }
                    // A frame bigger than the screen is malformed, and would size the decode buffer from its own header.
                    if (at >= b.size || u16(i + 5).toLong() * u16(i + 7) > width.toLong() * height) return
                    onImage(Image(u16(i + 1), u16(i + 3), u16(i + 5), u16(i + 7), colors, transparent, disposal,
                        playedDelayCs(delayCs), packed and 0x40 != 0, at))
                    transparent = -1
                    disposal = 0
                    delayCs = 0
                    i = skipSubBlocks(at + 1)
                }
                else -> return
            }
        }
    }

    private fun u16(at: Int) = u16le(b, at)

    private fun skipSubBlocks(from: Int) = skipGifSubBlocks(b, from)

    private fun readColors(at: Int, n: Int): IntArray = IntArray(n) { k ->
        val p = at + k * 3
        if (p + 2 >= b.size) 0
        else (0xFF shl 24) or ((b[p].toInt() and 0xFF) shl 16) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() and 0xFF)
    }

    companion object {
        private val NO_INTERLACE = listOf(0 to 1)
        private val INTERLACE = listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)

        fun open(b: ByteArray): GifDecoder? {
            if (b.size < 13 || b.decodeToString(0, 3) != "GIF") return null
            val d = GifDecoder(b)
            d.width = d.u16(6)
            d.height = d.u16(8)
            if (d.width <= 0 || d.height <= 0) return null
            val packed = b[10].toInt() and 0xFF
            d.firstBlock = 13
            if (packed and 0x80 != 0) {
                val n = 2 shl (packed and 7)
                d.globalColors = d.readColors(13, n)
                d.firstBlock += n * 3
            }
            d.walk { d.images += it }
            return d.takeIf { it.images.isNotEmpty() }
        }
    }
}

/** GIF's variable-length LZW, from the sub-blocks at [from] (the min code size byte); null on bad data. */
private fun lzwDecode(b: ByteArray, from: Int, pixels: Int): ByteArray? =
    if (from >= b.size || (b[from].toInt() and 0xFF) !in 1..11) null else LzwDecoder(b, from + 1, b[from].toInt() and 0xFF).decode(pixels)

// Fields, not captured locals: Kotlin boxes a local var a nested function changes, and that costs per byte on Android.
private class LzwDecoder(private val b: ByteArray, private var block: Int, private val minCodeSize: Int) {
    private var blockLeft = 0
    private var acc = 0
    private var bits = 0

    private fun byte(): Int {
        if (blockLeft == 0) {
            if (block >= b.size) return -1
            blockLeft = b[block].toInt() and 0xFF
            block++
            if (blockLeft == 0 || block >= b.size) return -1
        }
        if (block >= b.size) return -1
        blockLeft--
        return b[block++].toInt() and 0xFF
    }

    fun decode(pixels: Int): ByteArray? {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val prefix = ShortArray(4096)
        val suffix = ByteArray(4096)
        val stack = ByteArray(4097)
        for (k in 0 until clear) suffix[k] = k.toByte()
        val out = ByteArray(pixels)
        var outAt = 0
        var codeSize = minCodeSize + 1
        var next = end + 1
        var old = -1
        var first = 0
        while (outAt < pixels) {
            while (bits < codeSize) {
                val v = byte()
                if (v < 0) return out.takeIf { outAt > 0 }?.copyOf(outAt)
                acc = acc or (v shl bits)
                bits += 8
            }
            val code = acc and ((1 shl codeSize) - 1)
            acc = acc ushr codeSize
            bits -= codeSize
            if (code == clear) {
                codeSize = minCodeSize + 1
                next = end + 1
                old = -1
                continue
            }
            if (code == end) return out.copyOf(outAt)
            if (old == -1) {
                if (code >= clear) return out.takeIf { outAt > 0 }?.copyOf(outAt)
                out[outAt++] = code.toByte()
                old = code
                first = code
                continue
            }
            var top = 0
            var c = code
            if (code >= next) {
                stack[top++] = first.toByte()
                c = old
            }
            while (c >= clear) {
                if (c >= 4096 || top >= 4096) return out.takeIf { outAt > 0 }?.copyOf(outAt)
                stack[top++] = suffix[c]
                c = prefix[c].toInt()
            }
            first = suffix[c].toInt() and 0xFF
            stack[top++] = first.toByte()
            while (top > 0 && outAt < pixels) out[outAt++] = stack[--top]
            if (next < 4096) {
                prefix[next] = old.toShort()
                suffix[next] = first.toByte()
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            old = code
        }
        return out
    }
}

/**
 * Area-average downscale of ARGB canvases where alpha is 0 or 255: a pixel is opaque when most of its area is.
 * In integers (coverage in 1/256ths), one output row at a time, so it holds a row of sums, not a frame.
 */
internal class Scaler(private val sw: Int, private val sh: Int, private val dw: Int, private val dh: Int) {
    private val xs = spans(sw, dw)
    private val ys = spans(sh, dh)
    private val r = LongArray(dw)
    private val g = LongArray(dw)
    private val b = LongArray(dw)
    private val a = LongArray(dw)
    // Opaque when covered by at least half the output pixel's area, in 1/256ths squared.
    private val half = (sw.toLong() * sh * 256 * 256) / (2L * dw * dh)

    /** [src] is the caller's own copy: at the same size it comes back as it is. */
    fun scale(src: IntArray): IntArray {
        if (sw == dw && sh == dh) return src
        val out = IntArray(dw * dh)
        for (oy in 0 until dh) {
            r.fill(0); g.fill(0); b.fill(0); a.fill(0)
            val yspan = ys[oy]
            for (ky in yspan.weights.indices) {
                val wy = yspan.weights[ky]
                val from = (yspan.first + ky) * sw
                for (ox in 0 until dw) {
                    val span = xs[ox]
                    for (k in span.weights.indices) {
                        val p = src[from + span.first + k]
                        if (p ushr 24 == 0) continue
                        val w = (wy * span.weights[k]).toLong()
                        r[ox] += w * ((p shr 16) and 0xFF)
                        g[ox] += w * ((p shr 8) and 0xFF)
                        b[ox] += w * (p and 0xFF)
                        a[ox] += w
                    }
                }
            }
            for (ox in 0 until dw) {
                val area = a[ox]
                if (area < half || area == 0L) continue
                out[oy * dw + ox] = (0xFF shl 24) or (((r[ox] + area / 2) / area).toInt() shl 16) or
                    (((g[ox] + area / 2) / area).toInt() shl 8) or ((b[ox] + area / 2) / area).toInt()
            }
        }
        return out
    }
}

/** The source pixels one output pixel covers along an axis, and how much of each in 1/256ths of a pixel. */
private class Span(val first: Int, val weights: IntArray)

private fun spans(source: Int, target: Int): Array<Span> = Array(target) { o ->
    val start = o * source.toDouble() / target
    val end = (o + 1) * source.toDouble() / target
    val first = start.toInt()
    val last = min(source - 1, kotlin.math.ceil(end).toInt() - 1)
    Span(first, IntArray(last - first + 1) { k -> ((min(end, first + k + 1.0) - max(start, (first + k).toDouble())) * 256).roundToInt() })
}

/**
 * Colour counts at 5 bits a channel, of the opaque pixels that change from one frame to the next, sampled every
 * [stride] pixels across and down a canvas [width] wide.
 */
private class Histogram(private val stride: Int, private val width: Int) {
    private val counts = LongArray(1 shl 15)
    private val sums = Array(3) { LongArray(1 shl 15) }
    var transparent = false
        private set

    fun add(frame: IntArray, previous: IntArray?) {
        if (!transparent) transparent = frame.any { it ushr 24 == 0 }
        val height = frame.size / width
        for (y in 0 until height step stride) for (x in 0 until width step stride) {
            val i = y * width + x
            val p = frame[i]
            if (previous != null && previous[i] == p) continue
            if (p ushr 24 == 0) continue
            val key = key(p)
            counts[key]++
            sums[0][key] += ((p shr 16) and 0xFF).toLong()
            sums[1][key] += ((p shr 8) and 0xFF).toLong()
            sums[2][key] += (p and 0xFF).toLong()
        }
    }

    /** Median cut to at most [colors] entries; the last index is left for transparency. */
    fun palette(colors: Int): IntArray {
        val keys = counts.indices.filter { counts[it] > 0 }.toIntArray()
        if (keys.isEmpty()) return intArrayOf(0xFF000000.toInt())
        val boxes = mutableListOf(keys)
        while (boxes.size < colors - 1) {
            val (index, axis) = boxes.withIndex().mapNotNull { (i, box) -> widestAxis(box)?.let { i to it } }
                .maxByOrNull { (i, axis) -> range(boxes[i], axis) * boxes[i].sumOf { counts[it] }.toDouble() } ?: break
            val box = boxes[index].sortedBy { channel(it, axis) }
            val total = box.sumOf { counts[it] }
            var running = 0L
            var cut = 1
            for ((k, key) in box.withIndex()) {
                running += counts[key]
                if (running * 2 >= total) {
                    cut = (k + 1).coerceIn(1, box.size - 1)
                    break
                }
            }
            boxes[index] = box.subList(0, cut).toIntArray()
            boxes += box.subList(cut, box.size).toIntArray()
        }
        return IntArray(boxes.size) { b ->
            val box = boxes[b]
            val n = box.sumOf { counts[it] }.toDouble()
            fun avg(c: Int) = (box.sumOf { sums[c][it] } / n).roundToInt()
            (0xFF shl 24) or (avg(0) shl 16) or (avg(1) shl 8) or avg(2)
        }
    }

    private fun channel(key: Int, axis: Int) = (key shr (10 - axis * 5)) and 31

    private fun range(box: IntArray, axis: Int) = box.maxOf { channel(it, axis) } - box.minOf { channel(it, axis) }

    private fun widestAxis(box: IntArray): Int? = if (box.size < 2) null else (0..2).maxBy { range(box, it) }
}

private fun key(p: Int) = (((p shr 19) and 31) shl 10) or (((p shr 11) and 31) shl 5) or ((p shr 3) and 31)

/**
 * Writes GIF89a: one global palette, each frame only where it changed, unchanged pixels transparent. With
 * [anyTransparency] every frame is written whole and cleared after: a frame can only uncover pixels if the one
 * before was cleared, and a frame's disposal is written before the next frame is known.
 */
internal class GifWriter(
    private val width: Int, private val height: Int, private val colors: IntArray, loopCount: Int?,
    private val anyTransparency: Boolean,
) {
    private val out = ByteBuilder(64 * 1024)
    private val clearIndex = colors.size
    private val tableBits = run { var n = 1; while ((1 shl n) < colors.size + 1) n++; n }
    private val nearest = IntArray(1 shl 15) { -1 }
    private var previous: ByteArray? = null
    private val lzw = LzwEncoder(out)

    init {
        "GIF89a".forEach { out.addByte(it.code) }
        u16(width); u16(height)
        out.addByte(0x80 or (7 shl 4) or (tableBits - 1)); out.addByte(0); out.addByte(0)
        for (k in 0 until (1 shl tableBits)) {
            val c = if (k < colors.size) colors[k] else 0
            out.addByte((c shr 16) and 0xFF); out.addByte((c shr 8) and 0xFF); out.addByte(c and 0xFF)
        }
        if (loopCount != null) {
            out.addByte(0x21); out.addByte(0xFF); out.addByte(11)
            "NETSCAPE2.0".forEach { out.addByte(it.code) }
            out.addByte(3); out.addByte(1); u16(loopCount); out.addByte(0)
        }
    }

    private fun u16(v: Int) { out.addByte(v and 0xFF); out.addByte((v shr 8) and 0xFF) }

    /** A frame's palette indices; safe to call for several frames at once. */
    fun indices(frame: IntArray): ByteArray =
        ByteArray(frame.size) { i -> frame[i].let { p -> if (p ushr 24 == 0) clearIndex.toByte() else index(p).toByte() } }

    /** Writes frames in order. */
    fun add(indices: ByteArray, delayCs: Int) {
        val prev = previous
        val whole = prev == null || anyTransparency
        var x0 = 0; var y0 = 0; var x1 = width - 1; var y1 = height - 1
        if (!whole) {
            x0 = width; y0 = height; x1 = -1; y1 = -1
            for (y in 0 until height) {
                val row = y * width
                var left = 0
                while (left < width && indices[row + left] == prev!![row + left]) left++
                if (left == width) continue
                var right = width - 1
                while (indices[row + right] == prev!![row + right]) right--
                if (left < x0) x0 = left
                if (right > x1) x1 = right
                if (y0 == height) y0 = y
                y1 = y
            }
            if (x1 < 0) { x0 = 0; y0 = 0; x1 = 0; y1 = 0 }
        }
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val rect = if (whole) indices else ByteArray(w * h).also { rect ->
            val clear = clearIndex.toByte()
            var k = 0
            for (y in y0..y1) {
                val row = y * width
                for (x in x0..x1) {
                    val i = row + x
                    rect[k++] = if (indices[i] == prev!![i]) clear else indices[i]
                }
            }
        }
        out.addByte(0x21); out.addByte(0xF9); out.addByte(4)
        out.addByte(((if (anyTransparency) 2 else 1) shl 2) or 1)
        u16(delayCs); out.addByte(clearIndex); out.addByte(0)
        out.addByte(0x2C); u16(x0); u16(y0); u16(w); u16(h); out.addByte(0)
        lzw.encode(rect, max(2, tableBits))
        previous = indices
    }

    fun finish(): ByteArray {
        out.addByte(0x3B)
        return out.take()
    }

    // A racing lookup computes the same answer twice, so the cache needs no lock.
    private fun index(p: Int): Int {
        val key = key(p)
        nearest[key].let { if (it >= 0) return it }
        val r = ((key shr 10) shl 3) or 4
        val g = (((key shr 5) and 31) shl 3) or 4
        val b = ((key and 31) shl 3) or 4
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for ((k, c) in colors.withIndex()) {
            val dr = ((c shr 16) and 0xFF) - r
            val dg = ((c shr 8) and 0xFF) - g
            val db = (c and 0xFF) - b
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestDistance) { bestDistance = d; best = k }
        }
        nearest[key] = best
        return best
    }
}

/** GIF LZW: codes from minCodeSize+1 bits up to 12, cleared when the table is full, in 255-byte sub-blocks. */
private class LzwEncoder(private val out: ByteBuilder) {
    // Open addressing over (prefix, pixel) keys: a boxed map costs tens of millions of allocations a GIF, and a
    // direct 4096x256 table misses the cache on most lookups.
    private val keys = IntArray(HASH_SIZE)
    private val codes = IntArray(HASH_SIZE)
    private var minCodeSize = 2
    private val block = ByteArray(255)
    private var blockFill = 0
    private var acc = 0
    private var bits = 0
    private var codeSize = 3

    private fun flushBlock() {
        if (blockFill == 0) return
        out.addByte(blockFill)
        out.add(block, 0, blockFill)
        blockFill = 0
    }

    private fun emit(code: Int) {
        acc = acc or (code shl bits)
        bits += codeSize
        while (bits >= 8) {
            block[blockFill++] = acc.toByte()
            if (blockFill == 255) flushBlock()
            acc = acc ushr 8
            bits -= 8
        }
    }

    fun encode(pixels: ByteArray, minCodeSize: Int) {
        this.minCodeSize = minCodeSize
        codeSize = minCodeSize + 1
        blockFill = 0; acc = 0; bits = 0
        out.addByte(minCodeSize)
        val clear = 1 shl minCodeSize
        val end = clear + 1
        keys.fill(-1)
        var next = end + 1
        var codesSinceClear = 0
        emit(clear)
        if (pixels.isNotEmpty()) {
            var prefix = pixels[0].toInt() and 0xFF
            for (k in 1 until pixels.size) {
                val c = pixels[k].toInt() and 0xFF
                val key = (prefix shl 8) or c
                var slot = (key * 0x9E3779B1.toInt()).ushr(32 - HASH_BITS)
                while (keys[slot] != -1 && keys[slot] != key) slot = (slot + 1) and (HASH_SIZE - 1)
                if (keys[slot] == key) {
                    prefix = codes[slot]
                    continue
                }
                emit(prefix)
                codesSinceClear++
                if (next < 4096) {
                    keys[slot] = key
                    codes[slot] = next++
                    if (next > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    emit(clear)
                    keys.fill(-1)
                    codesSinceClear = 0
                    next = end + 1
                    codeSize = minCodeSize + 1
                }
                prefix = c
            }
            emit(prefix)
            // The decoder adds an entry on every code but the first after a clear, this last one included, and
            // reads the end code at the width that leaves it.
            if (codesSinceClear > 0 && next == (1 shl codeSize) && codeSize < 12) codeSize++
        }
        emit(end)
        if (bits > 0) {
            block[blockFill++] = acc.toByte()
            if (blockFill == 255) flushBlock()
        }
        flushBlock()
        out.addByte(0)
    }
}

// 2^13 slots for at most 4096 codes keeps probing short.
private const val HASH_BITS = 13
private const val HASH_SIZE = 1 shl HASH_BITS
