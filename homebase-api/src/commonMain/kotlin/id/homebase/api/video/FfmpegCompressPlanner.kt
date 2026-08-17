package id.homebase.api.video

/**
 * The bitrate + dimension targets for a given [VideoQuality]. Single source of
 * truth so Android, iOS, and Desktop produce comparable output for the same
 * `quality` enum value.
 */
data class QualityTargets(
    /** Target short-edge dimension in pixels. Source is scaled DOWN to this; not up. */
    val shortEdgePx: Int,
    /** Target average video bitrate, bits per second. */
    val videoBitrateBps: Int,
    /** Target AAC audio bitrate, bits per second. */
    val audioBitrateBps: Int,
)

fun VideoQuality.targets(): QualityTargets = when (this) {
    VideoQuality.LOW      -> QualityTargets(shortEdgePx = 480,  videoBitrateBps = 1_250_000, audioBitrateBps = 128_000)
    VideoQuality.STANDARD -> QualityTargets(shortEdgePx = 720,  videoBitrateBps = 2_500_000, audioBitrateBps = 128_000)
    VideoQuality.HIGH     -> QualityTargets(shortEdgePx = 1080, videoBitrateBps = 5_000_000, audioBitrateBps = 192_000)
}

/**
 * The output of [FfmpegCompressPlanner.plan] — the [args] to invoke ffmpeg with.
 *
 * There is no "skip ffmpeg" outcome: every video is re-encoded so the output is
 * unconditionally 8-bit. A pass-through would ship whatever the source was, and no
 * probe is trustworthy enough to gate that on (#1278).
 */
data class FfmpegCompressPlan(
    /** ffmpeg argv (after the ffmpeg-binary name itself). */
    val args: List<String>,
    /** Resolved output dimensions, for logging. */
    val outputDims: Pair<Int, Int>?,
)

/**
 * Pure-function planner for ffmpeg `compressVideo` invocations. Caller supplies
 * probed input metadata + caller-driven params; planner assembles the full
 * ffmpeg argv list. Every input is re-encoded — there is no skip path.
 *
 * Lives in commonMain so Android, iOS, and Desktop actuals share one
 * implementation of:
 *  - the quality → bitrate/dimension mapping ([QualityTargets])
 *  - output-dimension computation ([computeOutputDims])
 *  - ffmpeg arg assembly (this object's [plan] method)
 *
 * Each platform's `FFmpegUtils.compressVideo` actual then reduces to: probe
 * the input via the platform-native API (MediaExtractor / Swift bridge /
 * ffprobe), call [plan], and hand `plan.args` to the platform-native ffmpeg
 * invoker. No I/O or platform dependencies in this file.
 */
object FfmpegCompressPlanner {

    /**
     * Build the compress plan.
     *
     * @param inputPath path to the source video file (used as the `-i` arg)
     * @param outputPath where ffmpeg should write the result
     * @param quality target quality preset → resolved via [VideoQuality.targets]
     * @param trimStartMs trim start in milliseconds; if both trim ends are non-null,
     *   trim args (`-ss`/`-to`) are appended
     * @param trimEndMs trim end in milliseconds; same semantics as [trimStartMs]
     * @param probedWidthPx source video width as probed by the caller; pass 0 if unknown
     *   (planner will fall through to ffmpeg encoding at source dims)
     * @param probedHeightPx source video height as probed by the caller; pass 0 if unknown
     * @param probedCodecMime source video codec, in either MediaCodec form
     *   (`"video/avc"`, `"video/hevc"`) or ffprobe short form (`"h264"`, `"hevc"`).
     * @param inputDurationMs source video duration in milliseconds (for bitrate calc).
     * @param inputBytes source file size in bytes (for bitrate calc)
     * @param rotationDegrees source video rotation flag (0/90/180/270 or
     *   ±90/±270). Phone camera captures expose raw container dims to probes
     *   (e.g. 1920×1080 + rotation=90 for a portrait video); FFmpeg's default
     *   auto-rotate decodes those into the display orientation (1080×1920)
     *   before the scale filter runs. So the planner has to reason in display
     *   space too — when |rotation%180|=90, probedWidth and probedHeight are
     *   swapped before any dim math. Otherwise a portrait video gets the
     *   landscape scale target, squishing the decoded frames into the wrong
     *   aspect.
     * @param encoder ffmpeg encoder name. Defaults to `"libx264"`; iOS passes
     *   `"h264_videotoolbox"` first and falls back to libx264 on failure
     * @param probedBitDepth source luma bit depth, for logging only — output is
     *   pinned to 8-bit regardless.
     * @param probedIsHdr true when the source is HDR, for logging only.
     */
    fun plan(
        inputPath: String,
        outputPath: String,
        quality: VideoQuality,
        trimStartMs: Long?,
        trimEndMs: Long?,
        probedWidthPx: Int,
        probedHeightPx: Int,
        probedCodecMime: String?,
        inputDurationMs: Long,
        inputBytes: Long,
        rotationDegrees: Int = 0,
        encoder: String = "libx264",
        probedBitDepth: Int? = null,
        probedIsHdr: Boolean? = null,
    ): FfmpegCompressPlan {
        // Reason in display dims from here on — FFmpeg auto-rotate has already
        // swapped them by the time the scale filter sees the frames.
        val swapDims = kotlin.math.abs(((rotationDegrees % 360) + 360) % 360) % 180 == 90
        val displayWidthPx = if (swapDims) probedHeightPx else probedWidthPx
        val displayHeightPx = if (swapDims) probedWidthPx else probedHeightPx
        val targets = quality.targets()

        val outDims = computeOutputDims(displayWidthPx, displayHeightPx, targets.shortEdgePx)
        val scaleArgs = if (outDims != null) {
            // Explicit W:H — sidesteps the comma-escaping landmine that an
            // expression-based filter (`scale=min(N,iw):-2`) hits when fed
            // through executeWithArguments / shell parsers.
            listOf("-vf", "scale=${outDims.first}:${outDims.second}")
        } else {
            // Source already at-or-below target short edge — no scaling needed.
            emptyList()
        }

        val args = buildList {
            add("-y")
            add("-i"); add(inputPath)
            if (trimStartMs != null && trimEndMs != null) {
                // -ss AFTER -i: accurate seek (with re-encode pass; what the user expects).
                add("-ss"); add(formatSeconds(trimStartMs))
                add("-to"); add(formatSeconds(trimEndMs))
            }
            add("-c:v"); add(encoder)
            // -preset is a libx264 option. h264_videotoolbox ignores it with a
            // warning — skip on non-libx264 to keep stderr clean.
            if (encoder == "libx264") {
                add("-preset"); add("veryfast")
            }
            add("-b:v"); add("${targets.videoBitrateBps / 1000}k")
            addAll(scaleArgs)
            // Pin output to 8-bit 4:2:0. A 10-bit/HDR source (H.264 High 10,
            // avc1.6E001F) otherwise round-trips through libx264 as 10-bit
            // High 10, which most Android hardware AVC decoders reject with
            // ERROR_CODE_DECODING_FAILED / NO_EXCEEDS_CAPABILITIES. yuv420p
            // produces a Main/High-profile stream every receiver can decode.
            // No-op for already-8-bit 4:2:0 sources. (No tone-map yet — HDR
            // colours may look flat until the zscale/tonemap follow-up.)
            add("-pix_fmt"); add("yuv420p")
            add("-c:a"); add("aac")
            add("-b:a"); add("${targets.audioBitrateBps / 1000}k")
            add("-movflags"); add("+faststart")
            add(outputPath)
        }

        return FfmpegCompressPlan(
            args = args,
            // Encoded frames come out post-rotation, so the reported output
            // dims are the display ones (not the pre-rotation probe values).
            outputDims = outDims ?: (displayWidthPx to displayHeightPx),
        )
    }

    /**
     * Returns the (width, height) the encoder should produce, or null if the
     * source short edge is already at-or-below [shortEdgePx] (no scaling needed
     * — encode at source dims).
     *
     * Both output dims rounded UP to the nearest even integer — libx264 (and
     * h264_videotoolbox) require even W/H.
     */
    internal fun computeOutputDims(srcW: Int, srcH: Int, shortEdgePx: Int): Pair<Int, Int>? {
        if (srcW <= 0 || srcH <= 0) return null
        val srcShort = minOf(srcW, srcH)
        if (srcShort <= shortEdgePx) return null  // no upscaling; no scale arg needed
        val (outW, outH) = if (srcW < srcH) {
            // Portrait
            shortEdgePx to (srcH * shortEdgePx / srcW)
        } else {
            // Landscape (or square)
            (srcW * shortEdgePx / srcH) to shortEdgePx
        }
        return outW.toEven() to outH.toEven()
    }

    private fun Int.toEven(): Int = if (this and 1 == 0) this else this + 1

    /**
     * Formats `<ms>` as `S.fff` for ffmpeg's `-ss`/`-to` args. Locale-safe —
     * always uses `.` as the decimal separator (some default locales use `,`,
     * which ffmpeg parses as a separate arg).
     */
    private fun formatSeconds(ms: Long): String {
        val whole = ms / 1000L
        val frac = ms % 1000L
        return "$whole." + frac.toString().padStart(3, '0')
    }
}
