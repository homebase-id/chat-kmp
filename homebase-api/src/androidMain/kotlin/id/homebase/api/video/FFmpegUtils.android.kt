package id.homebase.api.video

import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import id.homebase.api.ActivityProvider
import id.homebase.api.client.KeyHeader
import id.homebase.api.video.transcoder_v2.HomebaseVideoTranscoder
import id.homebase.api.video.transcoder_v2.TranscodeException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual object FFmpegUtils {
    private const val TAG = "FFmpegUtils"

    @Volatile private var cachedFfmpegVersion: String? = null
    @Volatile private var ffmpegVersionProbed: Boolean = false

    actual suspend fun getFfmpegVersion(): String? = withContext(Dispatchers.IO) {
        if (ffmpegVersionProbed) return@withContext cachedFfmpegVersion
        val v = try {
            val session = FFmpegKit.execute("-version")
            if (!ReturnCode.isSuccess(session.returnCode)) {
                null
            } else {
                parseFfmpegVersionBanner(session.allLogsAsString)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getFfmpegVersion failed", e)
            null
        }
        cachedFfmpegVersion = v
        ffmpegVersionProbed = true
        v
    }

    actual suspend fun getDurationMs(inputPath: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            // setDataSource(String) requires a filesystem path. FileKit returns
            // content:// URIs for gallery picks — use the (Context, Uri) overload.
            if (inputPath.startsWith("content://") || inputPath.startsWith("content:")) {
                val context = ActivityProvider.requireApplicationContext()
                retriever.setDataSource(context, inputPath.toUri())
            } else {
                retriever.setDataSource(inputPath)
            }
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    actual fun getUniqueId(filePath: String): String {
        // TODO: potential BUG — for content:// URIs, `File(filePath).length()`
        // returns 0 and `File(filePath).name` is the encoded URI tail, so every
        // gallery pick collapses toward the same hash bucket. Callers that
        // cache by this id (grabThumbnail, etc.) can return another video's
        // cached output. Fix by resolving the URI to size via
        // ContentResolver.openAssetFileDescriptor(...).length when filePath
        // starts with "content://".
        val file = File(filePath)
        return UUID.nameUUIDFromBytes("${file.name}_${file.length()}".toByteArray()).toString()
    }

    actual suspend fun grabThumbnail(inputPath: String): String? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            val uniqueId = getUniqueId(inputPath)
            val outputFile = File(context.cacheDir, "thumb-$uniqueId.jpg")
            if (outputFile.exists() && outputFile.length() > 0L) {
                return@withContext outputFile.absolutePath
            }

            // Delegate to the platform-native poster-frame extractor. Same
            // MediaMetadataRetriever + content-resolver fallbacks that the
            // scrubber preview uses; works for both file paths and content://
            // URIs, and avoids the FFmpegKit JNI surface entirely (the v7
            // upgrade aborted on HLS-remuxed MP4 inputs here, see homebase.log
            // tombstone from 2026-05-17).
            val bytes = VideoThumbnailExtractor.extractPosterFrame(inputPath)
                ?: return@withContext null
            outputFile.writeBytes(bytes)
            outputFile.absolutePath
        }

    actual suspend fun getRotationFromFile(filePath: String): Int =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                if (filePath.startsWith("content://") || filePath.startsWith("content:")) {
                    val context = ActivityProvider.requireApplicationContext()
                    retriever.setDataSource(context, filePath.toUri())
                } else {
                    retriever.setDataSource(filePath)
                }
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                    ?: 0
                if (rotation in -360..360) rotation else 0
            } catch (e: Exception) {
                Log.w(TAG, "getRotationFromFile failed", e)
                0
            } finally {
                retriever.runCatching { release() }
            }
        }

    /**
     * Pure-platform MediaCodec/Extractor/Format/Muxer transcode. Returns the
     * absolute path of the compressed MP4, or null if the input is already
     * within the target quality envelope (caller falls back to the original
     * file). Hardware → software codec fallback is handled internally. On
     * failure (codec unavailable, decode error, etc.) logs and returns null —
     * the caller proceeds with the uncompressed original.
     */
    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String? = withContext(Dispatchers.IO) {
        val context = ActivityProvider.requireApplicationContext()
        val inFile = File(inputPath)
        if (!inFile.exists()) {
            Log.e(TAG, "File not found: $inputPath")
            return@withContext null
        }

        val trim: HomebaseVideoTranscoder.TrimRange? = when {
            trimStartMs != null && trimEndMs != null ->
                HomebaseVideoTranscoder.TrimRange(trimStartMs, trimEndMs)
            trimStartMs != null || trimEndMs != null -> {
                Log.w(TAG, "Partial trim ignored (got start=$trimStartMs end=$trimEndMs); pass both or neither")
                null
            }
            else -> null
        }

        val outFile = File(context.cacheDir, "compressed_${inFile.name}")

        val result = try {
            HomebaseVideoTranscoder.transcode(
                inputPath = inFile.absolutePath,
                outputPath = outFile.absolutePath,
                quality = quality,
                trim = trim,
                onProgress = onProgress,
            )
        } catch (e: TranscodeException) {
            Log.e(
                TAG,
                "Transcode failed (in=$inputPath, codec=${e.inputCodec}, " +
                    "decoder=${e.attemptedDecoder}, encoder=${e.attemptedEncoder})",
                e,
            )
            outFile.delete()
            return@withContext null
        } catch (e: Throwable) {
            Log.e(TAG, "Transcode crashed", e)
            outFile.delete()
            return@withContext null
        }

        when (result) {
            is HomebaseVideoTranscoder.Result.AlreadyOptimal -> {
                // The input passes through unchanged on this path — so it still
                // carries any EXIF / GPS / location atoms the camera wrote. Try
                // a metadata-only strip via mp4parser (no re-encode). Returns
                // null if the input had no location atoms; caller falls back to
                // the original via `?: payload.filePath`.
                val sanitized = File(context.cacheDir, "sanitized_${inFile.name}")
                if (Mp4LocationStripper.stripTo(inputPath, sanitized.absolutePath)) {
                    Log.d(TAG, "Stripped location atoms → ${sanitized.absolutePath}")
                    sanitized.absolutePath
                } else {
                    null
                }
            }
            is HomebaseVideoTranscoder.Result.Transcoded -> result.outputPath
        }
    }

    actual suspend fun segmentVideo(inputPath: String,
                                    onProgress: ((Float) -> Unit)?): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            val file = File(inputPath)
            if (!file.exists()) return@withContext null

            val rotation = getRotationFromFile(inputPath)
            val absRot = kotlin.math.abs(((rotation % 360) + 360) % 360)
            val needsRotationFix = absRot == 90 || absRot == 270

            val outputDir = context.cacheDir
            val playlistName = "ffmpeg-segmented-${UUID.randomUUID()}.m3u8"
            val playlistPath = File(outputDir, playlistName).absolutePath

            val commandArgs = mutableListOf<String>()
            commandArgs.add("-y")
            commandArgs.add("-i")
            commandArgs.add(inputPath)

            if (!needsRotationFix) {
                // Pure copy — fastest, no rotation needed
                commandArgs.add("-codec:v")
                commandArgs.add("copy")
                commandArgs.add("-codec:a")
                commandArgs.add("copy")
            } else {
                // Re-encode only when rotated → preserves rotation + smaller file
                commandArgs.add("-c:v")
                commandArgs.add("libx264")
                commandArgs.add("-preset")
                commandArgs.add("veryfast")
                commandArgs.add("-crf")
                commandArgs.add("23")
                commandArgs.add("-g")
                commandArgs.add("30")
                commandArgs.add("-bf")
                commandArgs.add("2")
                commandArgs.add("-c:a")
                commandArgs.add("copy")
            }

            commandArgs.add("-hls_time")
            commandArgs.add("6")
            commandArgs.add("-hls_list_size")
            commandArgs.add("0")
            commandArgs.add("-hls_flags")
            commandArgs.add("single_file")
            commandArgs.add("-f")
            commandArgs.add("hls")
            commandArgs.add(playlistPath)

            val command = commandArgs.joinToString(" ")
            Log.d(TAG, "Segment command: $command")

            val session = FFmpegKit.execute(command)
            if (ReturnCode.isSuccess(session.returnCode)) {
                val segmentPath = playlistPath.replace(".m3u8", ".ts")
                return@withContext Pair(playlistPath, segmentPath)
            } else {
                Log.e(TAG, "Segmentation failed: ${session.failStackTrace}")
                // Delete the partial playlist + segment left behind (see #5).
                deleteFailedFfmpegOutput(playlistPath)
                deleteFailedFfmpegOutput(playlistPath.replace(".m3u8", ".ts"))
                return@withContext null
            }
        }

    actual suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            val cacheFile = File(context.cacheDir, "input_$fileName")
            cacheFile.writeBytes(data)
            return@withContext cacheFile.absolutePath
        }

    actual suspend fun segmentAndEncryptVideo(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: ((Float) -> Unit)?
    ): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val context = ActivityProvider.requireApplicationContext()
            val inputFile = File(inputPath)
            if (!inputFile.exists()) return@withContext null

            val rotation = getRotationFromFile(inputPath)
            val absRot = kotlin.math.abs(((rotation % 360) + 360) % 360)
            val needsRotationFix = absRot == 90 || absRot == 270

            val outputDir = File(
                context.cacheDir,
                "hls_${UUID.randomUUID()}"
            ).apply { mkdirs() }

            val playlistPath = File(outputDir, "index.m3u8").absolutePath
            val segmentPath = File(outputDir, "index.ts").absolutePath

            // 🔐 Generate key + keyinfo files
            val keyInfoFile = generateHlsKeyInfoFile(
                outputDir = outputDir,
                aesKey = keyHeader.aesKey.unsafeBytes,
                iv = keyHeader.iv
            )

            val args = mutableListOf<String>()
            args.add("-y")
            args.add("-i")
            args.add(inputPath)

            if (!needsRotationFix) {
                args.addAll(listOf("-codec:v", "copy", "-codec:a", "copy"))
            } else {
                args.addAll(
                    listOf(
                        "-c:v", "libx264",
                        "-preset", "veryfast",
                        "-crf", "23",
                        "-g", "30",
                        "-bf", "2",
                        "-c:a", "copy"
                    )
                )
            }

            args.addAll(
                listOf(
                    "-hls_time", "6",
                    "-hls_list_size", "0",
                    "-hls_flags", "single_file",
                    "-hls_key_info_file", keyInfoFile.absolutePath,
                    "-f", "hls",
                    "-hls_segment_filename", segmentPath,
                    playlistPath
                )
            )

            Log.d(TAG, "Segment+Encrypt args: $args")

            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (ReturnCode.isSuccess(session.returnCode)) {
                // Segmentation done — FFmpeg has consumed the key material. Delete it now
                // so the plaintext AES key doesn't linger in the cache dir (see #7).
                deleteHlsKeyMaterial(outputDir.absolutePath)
                Pair(playlistPath, segmentPath)
            } else {
                Log.e(TAG, "Segment+Encrypt failed: ${session.failStackTrace}")
                // Delete the whole hls_<uuid>/ dir — partial segments + key material (see #5).
                deleteFailedFfmpegOutput(outputDir.absolutePath)
                null
            }
        }

    actual suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val args = arrayOf(
                "-y",
                "-allowed_extensions", "ALL",
                "-i", playlistPath,
                "-c", "copy",
                "-bsf:a", "aac_adtstoasc",
                "-movflags", "+faststart",
                outputPath
            )
            Log.d(TAG, "Remux HLS→MP4 args: ${args.joinToString(" ")}")
            val session = FFmpegKit.executeWithArguments(args)
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (!ok) Log.e(TAG, "Remux failed: ${session.failStackTrace}")
            ok
        }

    fun generateHlsKeyInfoFile(
        outputDir: File,
        aesKey: ByteArray,
        iv: ByteArray,
        keyFileName: String = "enc.key",
        keyInfoFileName: String = "keyinfo.txt"
    ): File {
        require(aesKey.size == 16) { "AES key must be 16 bytes (AES-128)" }
        require(iv.size == 16) { "IV must be 16 bytes" }

        outputDir.mkdirs()

        // 1️⃣ Write raw AES key (binary)
        val keyFile = File(outputDir, keyFileName)
        keyFile.writeBytes(aesKey)

        // 2️⃣ Convert IV to hex (no 0x prefix)
        val ivHex = iv.joinToString("") { "%02x".format(it) }

        // 3️⃣ Write key info file (consumed by FFmpeg)
        val keyInfoFile = File(outputDir, keyInfoFileName)
        keyInfoFile.writeText(
            """
        $keyFileName
        ${keyFile.absolutePath}
        $ivHex
        """.trimIndent()
        )

        return keyInfoFile
    }
}
