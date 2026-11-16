package id.homebase.api.image.gif

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
        currentCoroutineContext().ensureActive()
        val canvas = frames.next()
        if (schedule.copiesOf(index) > 0) {
            histogram.add(canvas, previous)
            previous = (previous ?: IntArray(canvas.size)).also { canvas.copyInto(it) }
        }
    }
    val writer = GifWriter(width, height, histogram.palette(colors), decoder.loopCount)
    // Decoding and writing are sequential; scaling and mapping colours run a few frames at once, in order.
    val scalers = Channel<Scaler>(PARALLEL_FRAMES).apply { repeat(PARALLEL_FRAMES) { trySend(Scaler(decoder.width, decoder.height, width, height)) } }
    var outputIndex = 0
    coroutineScope {
        flow {
            val again = decoder.frames()
            for (index in 0 until decoder.frameCount) {
                val canvas = again.next()
                repeat(schedule.copiesOf(index)) { emit(canvas.copyOf()) }
            }
        }.map { canvas ->
            async(Dispatchers.Default) {
                val scaler = scalers.receive()
                try { writer.indices(scaler.scale(canvas)) } finally { scalers.send(scaler) }
            }
        }.buffer(PARALLEL_FRAMES).collect { writer.add(it.await(), schedule.delayCs(outputIndex++)) }
    }
    return writer.finish()
}

private const val PARALLEL_FRAMES = 4

/** Which output frames each source frame becomes when the frame rate is lowered: ticks of 1/[fps] s. */
private class FrameSchedule(private val sourceDelaysCs: IntArray, fps: Int?) {
    private val copies: IntArray
    private val delays: IntArray

    init {
        val total = sourceDelaysCs.sum()
        if (fps == null || sourceDelaysCs.size * 100L <= fps.toLong() * total) {
            copies = IntArray(sourceDelaysCs.size) { 1 }
            delays = sourceDelaysCs
        } else {
            val ticks = ((total * fps.toLong() + 99) / 100).toInt().coerceAtLeast(1)
            fun tickCs(k: Int) = (k * 100.0 / fps).roundToInt().coerceAtMost(total)
            copies = IntArray(sourceDelaysCs.size)
            var start = 0
            var tick = 0
            for (i in sourceDelaysCs.indices) {
                val end = start + sourceDelaysCs[i]
                while (tick < ticks && tickCs(tick) < end) {
                    copies[i]++
                    tick++
                }
                start = end
            }
            delays = IntArray(ticks) { k -> max(2, (if (k == ticks - 1) total else tickCs(k + 1)) - tickCs(k)) }
        }
    }

    fun copiesOf(sourceIndex: Int) = copies[sourceIndex]

    fun delayCs(outputIndex: Int) = delays[outputIndex]
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

    /** [block] gets the canvas after each frame is drawn; it's reused, so copy what must be kept. */
    fun forEachFrame(block: (canvas: IntArray, index: Int) -> Unit) {
        val frames = frames()
        for (index in 0 until frameCount) block(frames.next(), index)
    }

    private inner class Image(
        val left: Int, val top: Int, val w: Int, val h: Int,
        val colors: IntArray, val transparent: Int, val disposal: Int, val delayCs: Int,
        val interlaced: Boolean, val dataStart: Int,
    ) {
        fun draw(canvas: IntArray) {
            val pixels = lzwDecode(b, dataStart, w * h) ?: return
            var row = 0
            for (pass in if (interlaced) INTERLACE else NO_INTERLACE) {
                var y = pass.first
                while (y < h) {
                    val cy = top + y
                    if (cy in 0 until height) {
                        val from = row * w
                        for (x in 0 until w) {
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
                    if (at >= b.size) return
                    // Browsers and ffmpeg both play a delay under 2 cs at 10 cs.
                    onImage(Image(u16(i + 1), u16(i + 3), u16(i + 5), u16(i + 7), colors, transparent, disposal,
                        if (delayCs < 2) 10 else delayCs, packed and 0x40 != 0, at))
                    transparent = -1
                    disposal = 0
                    delayCs = 0
                    i = skipSubBlocks(at + 1)
                }
                else -> return
            }
        }
    }

    private fun u16(at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun skipSubBlocks(from: Int): Int {
        var i = from
        while (i < b.size) {
            val n = b[i].toInt() and 0xFF
            i += n + 1
            if (n == 0) break
        }
        return i
    }

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
                if (v < 0) return out.takeIf { outAt > 0 }
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
            if (code == end) break
            if (old == -1) {
                if (code >= clear) return out.takeIf { outAt > 0 }
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
                if (c >= 4096 || top >= 4096) return out.takeIf { outAt > 0 }
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
 * Separable and in integers (coverage in 1/256ths), with its buffers kept between frames.
 */
internal class Scaler(private val sw: Int, private val sh: Int, private val dw: Int, private val dh: Int) {
    private val xs = spans(sw, dw)
    private val ys = spans(sh, dh)
    private val rowR = IntArray(sh * dw)
    private val rowG = IntArray(sh * dw)
    private val rowB = IntArray(sh * dw)
    private val rowA = IntArray(sh * dw)

    fun scale(src: IntArray): IntArray {
        if (sw == dw && sh == dh) return src.copyOf()
        for (y in 0 until sh) {
            val from = y * sw
            val to = y * dw
            for (ox in 0 until dw) {
                val span = xs[ox]
                var r = 0; var g = 0; var b = 0; var a = 0
                for (k in span.weights.indices) {
                    val p = src[from + span.first + k]
                    if (p ushr 24 == 0) continue
                    val w = span.weights[k]
                    r += w * ((p shr 16) and 0xFF)
                    g += w * ((p shr 8) and 0xFF)
                    b += w * (p and 0xFF)
                    a += w
                }
                rowR[to + ox] = r; rowG[to + ox] = g; rowB[to + ox] = b; rowA[to + ox] = a
            }
        }
        val out = IntArray(dw * dh)
        // Opaque when covered by at least half the output pixel's area, in the same 1/256 units squared.
        val half = (sw.toLong() * sh * 256 * 256) / (2L * dw * dh)
        for (oy in 0 until dh) {
            val span = ys[oy]
            for (ox in 0 until dw) {
                var r = 0L; var g = 0L; var b = 0L; var a = 0L
                for (k in span.weights.indices) {
                    val i = (span.first + k) * dw + ox
                    val w = span.weights[k].toLong()
                    r += w * rowR[i]; g += w * rowG[i]; b += w * rowB[i]; a += w * rowA[i]
                }
                if (a < half || a == 0L) continue
                out[oy * dw + ox] = (0xFF shl 24) or (((r + a / 2) / a).toInt() shl 16) or (((g + a / 2) / a).toInt() shl 8) or ((b + a / 2) / a).toInt()
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
        val height = frame.size / width
        for (y in 0 until height step stride) for (x in 0 until width step stride) {
            val i = y * width + x
            val p = frame[i]
            if (previous != null && previous[i] == p) continue
            if (p ushr 24 == 0) {
                transparent = true
                continue
            }
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

/** Writes GIF89a: one global palette, each frame only where it changed, unchanged pixels transparent. */
internal class GifWriter(private val width: Int, private val height: Int, palette: IntArray, loopCount: Int?) {
    private val out = GrowingBytes()
    private val colors = palette
    private val clearIndex = palette.size
    private val tableBits = run { var n = 1; while ((1 shl n) < palette.size + 1) n++; n }
    private val nearest = IntArray(1 shl 15) { -1 }
    private var previous: IntArray? = null
    private var previousHadTransparency = false
    private val lzw = LzwEncoder(out)

    init {
        "GIF89a".forEach { out.add(it.code) }
        out.u16(width); out.u16(height)
        out.add(0x80 or (7 shl 4) or (tableBits - 1)); out.add(0); out.add(0)
        for (k in 0 until (1 shl tableBits)) {
            val c = if (k < colors.size) colors[k] else 0
            out.add((c shr 16) and 0xFF); out.add((c shr 8) and 0xFF); out.add(c and 0xFF)
        }
        if (loopCount != null) {
            out.add(0x21); out.add(0xFF); out.add(11)
            "NETSCAPE2.0".forEach { out.add(it.code) }
            out.add(3); out.add(1); out.u16(loopCount); out.add(0)
        }
    }

    /** A frame's palette indices; safe to call for several frames at once. */
    fun indices(frame: IntArray): IntArray = IntArray(frame.size) { i -> if (frame[i] ushr 24 == 0) clearIndex else index(frame[i]) }

    /** Writes frames in order, from their [indices]. */
    fun add(indices: IntArray, delayCs: Int) {
        val prev = previous
        val transparentNow = indices.any { it == clearIndex }
        // A frame that drops pixels to transparent needs its predecessor cleared first, so frames with transparency
        // are written whole and cleared after; an opaque animation stores only what changed.
        val whole = prev == null || transparentNow || previousHadTransparency
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
        val rect = ByteArray(w * h)
        var k = 0
        for (y in y0..y1) {
            val row = y * width
            for (x in x0..x1) {
                val i = row + x
                rect[k++] = (if (!whole && indices[i] == prev!![i]) clearIndex else indices[i]).toByte()
            }
        }
        out.add(0x21); out.add(0xF9); out.add(4)
        out.add(((if (transparentNow) 2 else 1) shl 2) or 1)
        out.u16(delayCs); out.add(clearIndex); out.add(0)
        out.add(0x2C); out.u16(x0); out.u16(y0); out.u16(w); out.u16(h); out.add(0)
        lzw.encode(rect, max(2, tableBits))
        previous = indices
        previousHadTransparency = transparentNow
    }

    fun finish(): ByteArray {
        out.add(0x3B)
        return out.toByteArray()
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

private class GrowingBytes {
    private var bytes = ByteArray(64 * 1024)
    private var size = 0
    fun add(v: Int) {
        if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
        bytes[size++] = v.toByte()
    }
    fun u16(v: Int) { add(v and 0xFF); add((v shr 8) and 0xFF) }
    fun toByteArray() = bytes.copyOf(size)
}

/** GIF LZW: codes from minCodeSize+1 bits up to 12, cleared when the table is full, in 255-byte sub-blocks. */
private class LzwEncoder(private val out: GrowingBytes) {
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
        out.add(blockFill)
        for (k in 0 until blockFill) out.add(block[k].toInt())
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
        out.add(minCodeSize)
        val clear = 1 shl minCodeSize
        val end = clear + 1
        keys.fill(-1)
        var next = end + 1
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
                if (next < 4096) {
                    keys[slot] = key
                    codes[slot] = next++
                    if (next > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    emit(clear)
                    keys.fill(-1)
                    next = end + 1
                    codeSize = minCodeSize + 1
                }
                prefix = c
            }
            emit(prefix)
        }
        emit(end)
        if (bits > 0) {
            block[blockFill++] = acc.toByte()
            if (blockFill == 255) flushBlock()
        }
        flushBlock()
        out.add(0)
    }
}

// 2^13 slots for at most 4096 codes keeps probing short.
private const val HASH_BITS = 13
private const val HASH_SIZE = 1 shl HASH_BITS
