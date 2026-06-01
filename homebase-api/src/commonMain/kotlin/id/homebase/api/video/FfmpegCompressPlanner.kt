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
 * The output of [FfmpegCompressPlanner.plan]. Either tells the caller to skip
 * ffmpeg ([skipReason] non-null — input was already within the quality envelope)
 * or provides the [args] to invoke ffmpeg with.
 */
data class FfmpegCompressPlan(
    /** ffmpeg argv (after the ffmpeg-binary name itself). Empty when [skipReason] is non-null. */
    val args: List<String>,
    /** Non-null = caller should skip ffmpeg invocation and return null. Description for logs. */
    val skipReason: String?,
    /** Resolved output dimensions, for logging. Null when [skipReason] is non-null. */
    val outputDims: Pair<Int, Int>?,
)

/**
 * Pure-function planner for ffmpeg `compressVideo` invocations. Caller supplies
 * probed input metadata + caller-driven params; planner decides whether to
 * short-circuit (already-optimal) and assembles the full ffmpeg argv list when
 * not skipping.
 *
 * Lives in commonMain so Android, iOS, and Desktop actuals share one
 * implementation of:
 *  - the quality → bitrate/dimension mapping ([QualityTargets])
 *  - the already-optimal predicate ([isAlreadyOptimal])
 *  - output-dimension computation ([computeOutputDims])
 *  - ffmpeg arg assembly (this object's [plan] method)
 *
 * Each platform's `FFmpegUtils.compressVideo` actual then reduces to: probe
 * the input via the platform-native API (MediaExtractor / Swift bridge /
 * ffprobe), call [plan], and either short-circuit or hand `plan.args` to the
 * platform-native ffmpeg invoker. No I/O or platform dependencies in this file.
 */
object FfmpegCompressPlanner {

    /**
     * Build the compress plan.
     *
     * @param inputPath path to the source video file (used as the `-i` arg)
     * @param outputPath where ffmpeg should write the result
     * @param quality target quality preset → resolved via [VideoQuality.targets]
     * @param trimStartMs trim start in milliseconds; if both trim ends are non-null,
     *   trim args (`-ss`/`-to`) are appended and the already-optimal short-circuit is
     *   disabled (trim always re-encodes)
     * @param trimEndMs trim end in milliseconds; same semantics as [trimStartMs]
     * @param probedWidthPx source video width as probed by the caller; pass 0 if unknown
     *   (planner will fall through to ffmpeg encoding at source dims)
     * @param probedHeightPx source video height as probed by the caller; pass 0 if unknown
     * @param probedCodecMime source video codec, in either MediaCodec form
     *   (`"video/avc"`, `"video/hevc"`) or ffprobe short form (`"h264"`, `"hevc"`).
     *   Pass null when probe failed — already-optimal short-circuit will not fire.
     * @param inputDurationMs source video duration in milliseconds (for bitrate calc).
     *   Pass ≤0 if unknown — already-optimal short-circuit will not fire.
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
     * @param probedBitDepth source luma bit depth (8/10/12). Pass 8 when the
     *   probe couldn't determine it. >8 forces a re-encode (disables the
     *   already-optimal short-circuit) so the output can be pinned to 8-bit.
     * @param probedIsHdr true when the source is HDR (BT2020 primaries with a
     *   PQ/HLG transfer, or HDR static/dynamic metadata). Forces a re-encode for
     *   the same reason. Note: Tier 1 only pins output to 8-bit SDR-decodable
     *   `yuv420p` (no tone-map yet), which is enough to make every receiver's
     *   hardware AVC decoder accept the stream.
     * @param allowTenBit developer/test escape hatch (default false). When true,
     *   a >8-bit source is no longer force-downconverted: the bit-depth gate on
     *   the already-optimal short-circuit is dropped (an in-budget 10-bit H.264
     *   clip passes through untouched) and any re-encode pins `yuv420p10le`
     *   (High 10) instead of `yuv420p`. The resulting stream is High 10 and will
     *   fail on most receivers' hardware AVC decoders — this exists purely to
     *   inspect 10-bit output locally. HDR is unaffected (still forces a
     *   re-encode; no tone-map yet). Callers driving 10-bit output must also use
     *   a 10-bit-capable encoder (libx264) — `h264_videotoolbox` cannot emit
     *   10-bit H.264.
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
        probedBitDepth: Int = 8,
        probedIsHdr: Boolean = false,
        allowTenBit: Boolean = false,
    ): FfmpegCompressPlan {
        // Reason in display dims from here on — FFmpeg auto-rotate has already
        // swapped them by the time the scale filter sees the frames.
        val swapDims = kotlin.math.abs(((rotationDegrees % 360) + 360) % 360) % 180 == 90
        val displayWidthPx = if (swapDims) probedHeightPx else probedWidthPx
        val displayHeightPx = if (swapDims) probedWidthPx else probedHeightPx
        val targets = quality.targets()
        val hasTrim = trimStartMs != null && trimEndMs != null

        // Already-optimal short-circuit: skip ffmpeg entirely. Trim forces
        // re-encode (the trim points are precise only with a real encode pass).
        if (!hasTrim && isAlreadyOptimal(
                probedCodecMime, displayWidthPx, displayHeightPx,
                inputBytes, inputDurationMs, targets,
                probedBitDepth, probedIsHdr, allowTenBit,
            )
        ) {
            return FfmpegCompressPlan(
                args = emptyList(),
                skipReason = "input already optimal " +
                    "(codec=$probedCodecMime, ${displayWidthPx}x${displayHeightPx}, " +
                    "${inputDurationMs}ms, ${inputBytes}B, quality=$quality)",
                outputDims = null,
            )
        }

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
            // No-op for already-8-bit 4:2:0 sources. (Tier 1: no tone-map yet —
            // HDR colours may look flat until the zscale/tonemap follow-up.)
            //
            // allowTenBit (dev/test only): keep a >8-bit source at 10-bit
            // (yuv420p10le → High 10) so the 10-bit pipeline can be inspected.
            // 8-bit sources stay 8-bit regardless — the flag permits, not forces.
            val outputPixFmt = if (allowTenBit && probedBitDepth > 8) "yuv420p10le" else "yuv420p"
            add("-pix_fmt"); add(outputPixFmt)
            add("-c:a"); add("aac")
            add("-b:a"); add("${targets.audioBitrateBps / 1000}k")
            add("-movflags"); add("+faststart")
            add(outputPath)
        }

        return FfmpegCompressPlan(
            args = args,
            skipReason = null,
            // Encoded frames come out post-rotation, so the reported output
            // dims are the display ones (not the pre-rotation probe values).
            outputDims = outDims ?: (displayWidthPx to displayHeightPx),
        )
    }

    /**
     * Already-optimal predicate. True iff the source is H.264 single-video-track
     * within both the short-edge and the average-bitrate budget (×1.2 slack),
     * and is 8-bit SDR.
     *
     * Mirrors the Android v2 SPEC §7 logic. Returns false on any probe
     * gap (null codec, zero dim, zero duration) — caller falls through to a
     * real transcode in that case. Also returns false for 10-bit/HDR sources:
     * even an otherwise-in-budget clip must re-encode so the output can be
     * pinned to 8-bit `yuv420p` (else the original 10-bit High 10 bytes would
     * pass through untouched and fail on hardware AVC decoders). When
     * [allowTenBit] is set, the bit-depth disqualifier is lifted (a 10-bit
     * source may short-circuit and pass through untouched); HDR still
     * disqualifies regardless.
     */
    internal fun isAlreadyOptimal(
        codecMime: String?,
        widthPx: Int,
        heightPx: Int,
        inputBytes: Long,
        durationMs: Long,
        targets: QualityTargets,
        bitDepth: Int = 8,
        isHdr: Boolean = false,
        allowTenBit: Boolean = false,
    ): Boolean {
        if (codecMime == null || widthPx <= 0 || heightPx <= 0 || durationMs <= 0) return false
        if (bitDepth > 8 && !allowTenBit) return false
        if (isHdr) return false
        if (!isH264(codecMime)) return false
        val shortEdgePx = minOf(widthPx, heightPx)
        if (shortEdgePx > targets.shortEdgePx) return false
        val avgBitrateBps = inputBytes * 8L * 1000L / durationMs
        val budgetBps = (1.2 * (targets.videoBitrateBps + targets.audioBitrateBps)).toLong()
        return avgBitrateBps <= budgetBps
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

    /**
     * H.264 codec detection. Accepts both Android MediaExtractor's MIME form
     * (`"video/avc"`) and ffprobe / iOS bridge's short form (`"h264"`).
     * Case-insensitive.
     */
    private fun isH264(codecMime: String): Boolean {
        val lower = codecMime.lowercase()
        return lower == "video/avc" || lower == "h264" || lower == "avc"
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
