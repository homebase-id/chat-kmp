@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.diagnostics

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.Blake3Hasher
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.file.IOSFileOperationsProvider
import id.homebase.api.foundation.toByteArray
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.image.MediaImages
import id.homebase.api.image.MediaQuality
import id.homebase.api.image.createMediaImages
import id.homebase.api.image.gif.reencodeGif
import id.homebase.api.platform.footprintDuring
import id.homebase.api.video.GifShrinker
import id.homebase.api.video.HLS_MIN_BYTES
import id.homebase.api.video.IosVideoCompressor
import id.homebase.api.video.VideoQuality
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetBytesPerRow
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.thermalState
import platform.UIKit.UIDevice
import platform.posix.getenv
import platform.posix.uname
import platform.posix.utsname
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** MEDIA_BENCHMARKS.md's phone measurements on an iPhone; OPTIMIZE.md, "Media on an iPhone", says how to run them. */
object MediaBenchmarks {
    /** [run] for Swift, called back on a background thread. */
    fun runBenchmarks(onDone: (CheckReport) -> Unit) {
        launchChecks({ run() }, onDone)
    }

    suspend fun run(): CheckReport = checkRun {
        val dir = benchDir()
        val inputs = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty().map { it as String }.sorted()
        record("${machine()}, iOS ${UIDevice.currentDevice.systemVersion}, ${inputs.size} files in $dir")
        if (inputs.isEmpty()) fail("no videos, GIFs or photos in $dir: copy them there first")
        verify("BLAKE3") { blake3() }
        verify("generated photos") { generatedPhotos() }
        for (name in inputs) {
            val path = "$dir/$name"
            when (name.substringAfterLast('.').lowercase()) {
                "mp4", "mov" -> verify("sending $name") { videoSend(name, path) }
                "gif" -> verify("shrinking $name") { gifSteps(name, path) }
                "heic", "heif", "jpg", "jpeg" -> verify("$name's images") { photo(name, path) }
            }
        }
    }

    private fun CheckRun.blake3() {
        val chunk = ByteArray(MB.toInt()) { it.toByte() }
        Blake3Hasher().apply { update(chunk) }.digest()
        val speeds = List(3) {
            val started = TimeSource.Monotonic.markNow()
            val hasher = Blake3Hasher()
            repeat(hashMb) { hasher.update(chunk) }
            hasher.digest()
            (hashMb / started.elapsedNow().toDouble(DurationUnit.SECONDS)).toInt()
        }
        record("BLAKE3, the Kotlin port iOS runs: ${speeds.min()}–${speeds.max()} MB/s over $hashMb MB")
    }

    private suspend fun CheckRun.generatedPhotos() {
        for ((width, height) in photoSizes) {
            val pixels = photoLike(width, height)
            val files = try {
                listOf(ImageIOEncoder.JPEG, ImageIOEncoder.HEIC).associateWith { ImageIOEncoder.encode(it, listOf(pixels)).toByteArray() }
            } finally {
                CGImageRelease(pixels)
            }
            for ((type, bytes) in files) {
                val label = "generated ${width}x$height ${type.substringAfter('.').uppercase()} (${bytes.size / 1024} KB)"
                for (setting in MediaQuality.entries) pipeline("$label $setting") { createMediaImages(bytes, "bench", setting, hdr = true) }
            }
        }
    }

    private suspend fun CheckRun.photo(name: String, path: String) {
        for (setting in MediaQuality.entries) {
            pipeline("$name $setting") { createMediaImages(path, fileOps, "bench", setting, hdr = true) }
        }
    }

    // The absolute peak, not growth: once earlier steps leave the allocator's pages behind, growth reads low.
    private suspend fun CheckRun.pipeline(label: String, render: suspend () -> MediaImages) {
        val (images, _, peak) = footprintDuring { render() }
        val times = List(runs) {
            val started = TimeSource.Monotonic.markNow()
            render()
            started.elapsedNow().inWholeMilliseconds
        }.sorted()
        val outputs = images.thumbnails.map { "${it.pixelWidth}x${it.pixelHeight}" } +
            listOfNotNull(images.finalImage?.let { "${it.width}x${it.height} final" })
        record(
            "$label: ${images.displayedSize.pixelWidth}x${images.displayedSize.pixelHeight} -> ${outputs.joinToString()}; " +
                "median ${times[runs / 2]} ms of $runs; footprint peak ${peak / MB} MB; ${thermal()}",
        )
    }

    private suspend fun CheckRun.videoSend(name: String, path: String) {
        val sizeMb = fileOps.getFileSize(path) / 1_000_000
        for (quality in listOf(VideoQuality.STANDARD, VideoQuality.HIGH)) repeat(repeats) {
            delay(coolDownMs)
            val key = KeyHeader(Random.nextBytes(16), SecureByteArray(Random.nextBytes(16)))
            val (timed, _, peak) = footprintDuring {
                val started = TimeSource.Monotonic.markNow()
                IosVideoCompressor.compress(path, quality = quality) to started.elapsedNow().inWholeMilliseconds
            }
            val (compressed, compressMs) = timed
            try {
                val bytes = fileOps.getFileSize(compressed)
                val hls = bytes >= HLS_MIN_BYTES
                val encrypted = TimeSource.Monotonic.markNow()
                if (hls) {
                    val segmented = checkNotNull(IosVideoCompressor.segmentAndEncrypt(compressed, key, null)) { "segmenting failed" }
                    NSFileManager.defaultManager.removeItemAtPath(segmented.segmentsPath.substringBeforeLast('/'), null)
                } else {
                    val out = "$compressed.encrypted"
                    try {
                        fileOps.encryptWithPayloadHash(compressed, out, key, PayloadHashAlgorithm.Blake3)
                    } finally {
                        fileOps.deleteTempFile(out)
                    }
                }
                val encryptMs = encrypted.elapsedNow().inWholeMilliseconds
                record(
                    "$name ($sizeMb MB) $quality: ${compressMs + encryptMs} ms end to end (compress $compressMs ms to ${bytes / 1000} kB, " +
                        "${if (hls) "HLS" else "MP4 encrypt"} $encryptMs ms); compress footprint peak ${peak / MB} MB; ${thermal()}",
                )
            } finally {
                fileOps.deleteTempFile(compressed)
            }
        }
    }

    private suspend fun CheckRun.gifSteps(name: String, path: String) {
        val bytes = fileOps.readFileBytes(path)
        val gif = checkNotNull(ImageFormatDetector.parseGif(bytes)) { "not a GIF" }
        for (step in GifShrinker.encodings(gif)) repeat(repeats) {
            delay(gifPauseMs)
            val started = TimeSource.Monotonic.markNow()
            val out = reencodeGif(bytes, step.width, step.height, step.colors, step.fps)
            record(
                "$name ${gif.width}x${gif.height}, ${gif.frameCount} frames, ${bytes.size / 1000} kB -> $step: " +
                    "${started.elapsedNow().inWholeMilliseconds} ms, ${out?.size?.div(1000)} kB; ${thermal()}",
            )
        }
    }

    // The simulator reads the Mac's own folder; on a phone, files are copied into the app's Documents/bench.
    private fun benchDir(): String = getenv("MEDIA_BENCH_DIR")?.toKString()
        ?: "${NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String}/bench"

    private fun machine(): String = memScoped {
        val info = alloc<utsname>()
        uname(info.ptr)
        info.machine.toKString()
    }

    // Printed as it's taken too: a long phone run must not lose every number to one timeout.
    private fun CheckRun.record(line: String) {
        measured(line)
        println("measured: $line")
    }

    private fun thermal() = "thermal ${NSProcessInfo.processInfo.thermalState.value}"

    // Smooth gradients, hard-edged blocks and sensor-like noise, so the files compress like photos (as on Android).
    private fun photoLike(width: Int, height: Int): CGImageRef {
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            null, width.toULong(), height.toULong(), 8u, 0u, space,
            CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value or kCGBitmapByteOrder32Big,
        )
        CGColorSpaceRelease(space)
        checkNotNull(context) { "no ${width}x$height bitmap context" }
        try {
            val pixels = checkNotNull(CGBitmapContextGetData(context)) { "no pixels" }.reinterpret<UByteVar>()
            val rowBytes = CGBitmapContextGetBytesPerRow(context).toLong()
            val random = Random(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val block = if (((x / 64) + (y / 64)) % 7 == 0) 60 else 0
                    val i = y * rowBytes + x * 4L
                    pixels[i] = channel(128 + 90 * sin(x / 97.0 + y / 151.0) + random.nextInt(-10, 11) + block)
                    pixels[i + 1] = channel(128 + 90 * sin(x / 173.0 - y / 89.0) + random.nextInt(-10, 11) - block)
                    pixels[i + 2] = channel(128 + 90 * sin((x + y) / 211.0) + random.nextInt(-10, 11))
                }
            }
            return checkNotNull(CGBitmapContextCreateImage(context)) { "no ${width}x$height image" }
        } finally {
            CGContextRelease(context)
        }
    }

    private fun channel(value: Double): UByte = value.toInt().coerceIn(0, 255).convert()

    private val fileOps = IOSFileOperationsProvider()

    // On the simulator the numbers mean nothing, so CI runs each step once, without the phone's cool-down pauses.
    private val simulator = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null
    private val runs = if (simulator) 1 else 3
    private val repeats = if (simulator) 1 else 2
    private val coolDownMs = if (simulator) 0L else 30_000L
    private val gifPauseMs = if (simulator) 0L else 2_000L
    private val hashMb = if (simulator) 16 else 256
    private val photoSizes = if (simulator) listOf(4000 to 3000) else listOf(4000 to 3000, 8000 to 6000)

    private const val MB = 1024L * 1024
}
