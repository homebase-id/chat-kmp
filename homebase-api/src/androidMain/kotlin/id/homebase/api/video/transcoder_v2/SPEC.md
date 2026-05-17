# Homebase Android Video Transcoder — Spec

**Status: DRAFT — Phase 1 deliverable.** Sections tagged `OPEN` carry a
recommendation but are open to discussion in the design review (Phase 2).

This document is the load-bearing design artefact for the clean-room
rewrite of the Android compress path. The vendored Signal tree at
`../transcoder/` is reference material only; nothing in it survives the
rewrite. See `signal-reference-notes.md` for the targeted notes
accumulated while drafting this spec.

Companion plan: `~/.claude/plans/reactive-zooming-papert.md`.

---

## 1. Goal

A Kotlin object that takes an input video file (path or Android
`MediaDataSource`), re-encodes it to H.264 + AAC in an MP4 container
within a chosen quality envelope, optionally trims it, optionally
short-circuits to "no transcode needed", and surfaces continuous
progress + cooperative cancellation. Runs entirely on `android.media.*`
+ Kotlin coroutines — no FFmpeg, no third-party container libraries,
no OpenGL pipeline.

**In scope:** the work that today's `FFmpegUtils.android.kt:compressVideo`
does. Single-track video + single-track audio MP4 input → single-track
video + single-track audio MP4 output.

**Out of scope:** HLS segmentation (stays on ffmpeg-kit because Signal
doesn't do it and Android has no equivalent), HDR tone-mapping, multi-
audio-track preservation, custom MP4 muxing (we trust `MediaMuxer`),
faststart post-processing (`MediaMuxer` produces faststart-friendly
output by default — VERIFY in Phase 3), iOS / desktop ports.

---

## 2. Public API

One object, one suspend entry point.

```kotlin
// homebase-api/src/androidMain/kotlin/id/homebase/api/video/HomebaseVideoTranscoder.kt
object HomebaseVideoTranscoder {

    suspend fun transcode(
        inputPath: String,
        outputPath: String,
        quality: VideoQuality = VideoQuality.STANDARD,
        trim: TrimRange? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Result

    sealed interface Result {
        /** Output file written and ready. */
        data class Transcoded(val outputPath: String) : Result

        /** Input was already within the quality envelope and no trim requested. */
        data object AlreadyOptimal : Result
    }

    data class TrimRange(val startMs: Long, val endMs: Long)
}
```

**Contract:**

- `inputPath` is a filesystem path (caller resolves `content://` URIs
  before calling — same as today).
- `outputPath` is where the result lands. Caller owns the location +
  cleanup.
- `quality` maps to a concrete encoder configuration per section 6.
- `trim`, if non-null, requires `startMs < endMs` and applies at
  extractor-seek time. A non-null `trim` always forces a re-encode.
- `onProgress` fires `0f..1f`, monotonically non-decreasing, called
  from the transcoder's coroutine context (so callers should marshal
  back to the UI thread if needed). Fires at every integer-percent
  boundary the muxer crosses — typically dozens of times over a
  several-second transcode.
- Returns `Result.AlreadyOptimal` if the input passes the
  short-circuit check in section 7 AND `trim == null`. Caller can use
  the original input path unchanged.
- Returns `Result.Transcoded(outputPath)` on success.
- Throws `TranscodeException` (see section 8) on failure. NEVER
  returns null — null was a footgun in the FFmpeg wrapper because
  caller had to know whether null meant "skipped" or "failed".
  Distinct `Result` variants vs. exception solves this.
- Cancellation: cooperative via `CoroutineScope` cancellation. The
  internal pump loop calls `ensureActive()` on every iteration;
  cancelling the calling scope unwinds via the codec/muxer release
  finally-block within ~one frame interval.

**Why this shape and not Signal's:**

| Signal | Ours | Why |
|---|---|---|
| `StreamingTranscoder.transcode(progress, outputStream, cancellationSignal): Long` returning `mdatSize` | One suspend function returning `Result` | mdatSize was needed for faststart; we don't faststart. Suspending obsoletes `cancellationSignal`. |
| `Progress` SAM interface with `onProgress(int 0..100)` | `((Float) -> Unit)?` lambda with `0f..1f` | One less type. Float matches our existing `onProgress` convention. |
| `TranscoderCancelationSignal` interface | `CoroutineScope` cancellation | Native to coroutines. |
| Writes to `OutputStream` | Writes to a file path | `MediaMuxer` only writes to file paths or FDs anyway; the `OutputStream` wrapper Signal needs (for size-limit, for encrypted writes) doesn't apply to our pipeline. |
| Returns Long, throws on failure, never null | Returns `Result` sealed type, throws on failure | Explicit "already optimal" vs. "transcoded" distinction at the type level. |

---

## 3. Native API surface

| API | Used for | Why this and not... |
|---|---|---|
| `android.media.MediaExtractor` | Demux input MP4. Seek to trim start. Read per-track formats. | Only stdlib MP4 demuxer. No reason to do this manually. |
| `android.media.MediaFormat` | Carry codec config + key/value metadata between extractor, codecs, muxer. | The contract type for all four. |
| `android.media.MediaCodec` (sync API) | Decode video, decode audio (if remuxing not possible), encode video (H.264), encode audio (AAC). | See section 5 for sync-vs-async. |
| `android.media.MediaCodecList` + `android.media.MediaCodecInfo` | Codec discovery + selection. REGULAR_CODECS preferred, ALL_CODECS fallback, dedup by name. | Signal's pattern; matches Android conventions. |
| `android.media.MediaMuxer` | Write the output MP4. | The whole reason scope-cut #1 holds: we don't need Signal's custom muxer. |
| `android.media.MediaMetadataRetriever` | Cheap pre-flight probe of input (codec, width, height, bitrate, rotation, duration) for the "already optimal" check. | Already used in `FFmpegUtils.android.kt`; no reason to introduce a second probe API. |
| `android.media.MediaCodec.BufferInfo` | Per-sample timing + flag exchange. | Required by MediaCodec API. |
| `android.os.HandlerThread` / `android.os.Handler` | _Only if_ we go async-callback in section 5. OPEN. | n/a |

**Native APIs we explicitly DON'T use:**

- **OpenGL ES** (`android.opengl.GLES20`, `android.opengl.EGL14`,
  `android.view.Surface` as a GL render target). Signal uses these
  to render rotated frames through a GL pipeline so the encoder sees
  upright pixels. We sidestep this entirely: preserve source rotation
  via `MediaMuxer.setOrientationHint()` and let the player rotate at
  playback time. This kills `InputSurface.java` + `OutputSurface.java`
  + `TextureRender.java` (~700 LOC) from the rewrite. See section 10
  for the trade-off.
- **`SurfaceTexture`**. Same reasoning.
- **`MediaCodec`'s async-callback API**. OPEN — see section 5; we
  default to sync for now.
- **Custom MP4 atom parsing**. We rely on `MediaMuxer` for output
  and on `MediaExtractor` + `MediaMetadataRetriever` for input
  inspection.

---

## 4. Data flow

```
                                ┌────────────────────┐
                                │  input MP4 file    │
                                └──────────┬─────────┘
                                           │
                                           ▼
                              ┌────────────────────────┐
                              │   MediaMetadataRetriever│  ─── pre-flight probe
                              │   (cheap; one-shot)     │      (codec, w, h, bps,
                              └──────────┬──────────────┘       rotation, duration)
                                         │
                              ┌──────────▼──────────────┐
                              │ Already-optimal check?  │  ─── section 7
                              └──────────┬──────────────┘
                            yes          │           no
                             │           │            │
                             ▼           │            ▼
                  Result.AlreadyOptimal  │   ┌───────────────────┐
                                         │   │  MediaExtractor   │
                                         │   │  + selectTrack(v) │
                                         │   │  + selectTrack(a) │
                                         │   │  + seek(trim.start)│
                                         │   └─────────┬─────────┘
                                                       │
                            ┌──────────────────────────┼──────────────────────────┐
                            │                          │                          │
                            ▼                          │                          ▼
                  ┌─────────────────┐                  │              ┌──────────────────┐
                  │ MediaCodec dec  │                  │              │ MediaCodec dec   │
                  │ (video, H.264   │                  │              │ (audio, AAC      │
                  │  or HEVC in)    │                  │              │  in)             │
                  └────────┬────────┘                  │              └─────────┬────────┘
                           │ ByteBuffer (YUV)          │                        │ ByteBuffer (PCM)
                           ▼                           │                        ▼
                  ┌─────────────────┐                  │              ┌──────────────────┐
                  │ MediaCodec enc  │                  │              │ MediaCodec enc   │
                  │ (video, H.264   │                  │              │ (audio, AAC      │
                  │  out)           │                  │              │  out, 128 kbps)  │
                  └────────┬────────┘                  │              └─────────┬────────┘
                           │ ByteBuffer (compressed)   │                        │ ByteBuffer (compressed)
                           │ + BufferInfo (PTS, flags) │                        │ + BufferInfo
                           ▼                           │                        ▼
                       ┌───────────────────────────────▼───────────────────────────┐
                       │                       MediaMuxer                          │
                       │      start() lazily after BOTH encoders emit              │
                       │      INFO_OUTPUT_FORMAT_CHANGED for the first time        │
                       │      writeSampleData(videoTrack | audioTrack, ...)        │
                       │      setOrientationHint(sourceRotation)   ◄── source rot. │
                       │      stop() + release() in finally                        │
                       └───────────────────────────────┬───────────────────────────┘
                                                       │
                                                       ▼
                                            ┌─────────────────────┐
                                            │  output MP4 file    │
                                            └─────────────────────┘
```

**Buffer lifetimes:**

- Decoder ByteBuffers are owned by `MediaCodec`. We `dequeueOutputBuffer`,
  read PTS + flags from the `BufferInfo`, copy the bytes into the
  encoder's input buffer, then `releaseOutputBuffer(idx, render=false)`.
- Encoder ByteBuffers are owned by `MediaCodec`. We `dequeueOutputBuffer`,
  hand the buffer + `BufferInfo` straight to `MediaMuxer.writeSampleData(...)`,
  then `releaseOutputBuffer(idx)`.
- `MediaCodec.BufferInfo` instances are reused — read values out before
  the next `dequeue*`.

**Thread crossings:** none in sync mode (everything on the transcode
coroutine's dispatcher). If we go async-callback (section 5), the
`MediaCodec.Callback` fires on a `HandlerThread`; cross over to the
coroutine via a `Channel<Event>`.

**Where rotation lives:** `MediaExtractor.getTrackFormat(videoTrack)`
exposes `MediaFormat.KEY_ROTATION`. We extract it, pass to
`MediaMuxer.setOrientationHint(degrees)`. The encoder pipeline operates
on the decoded pixel buffer with no transformation. Player rotates at
display time using the embedded `tkhd` matrix.

---

## 5. Threading & lifecycle — **OPEN (recommended: sync pump on Dispatchers.IO)**

Three credible models:

### Model A — Sync pump on `Dispatchers.IO` coroutine (RECOMMENDED)

```kotlin
suspend fun transcode(...) = withContext(Dispatchers.IO) {
    val extractor = ...
    val videoDecoder = ...
    val videoEncoder = ...
    // ... audio pair, muxer ...
    try {
        while (!videoEncoderDone || !audioEncoderDone) {
            ensureActive()  // cancellation check
            stepVideo()     // dequeue/feed both ends of the video pair
            stepAudio()     // ditto audio, interleaved by PTS
            maybeStartMuxer()
            maybeEmitProgress()
        }
    } finally {
        videoDecoder.release(); videoEncoder.release()
        audioDecoder.release(); audioEncoder.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
        extractor.release()
    }
}
```

**Pros:**
- Simplest implementation; one execution context end to end.
- Cancellation is `ensureActive()` in the loop + the `finally` block —
  no separate cancellation signal type, no callback marshalling.
- Matches Signal's working model (we know it works on a wide device
  range).
- IO dispatcher has many threads; we burn one for ~seconds — fine.

**Cons:**
- The loop polls `dequeueOutputBuffer` with a timeout
  (Signal uses `TIMEOUT_USEC = 10_000`, i.e. 10ms). That's effectively
  ~100 wakeups/sec while waiting for codec output. Mild but not free.
- One IO thread blocked for the transcode duration. Acceptable.

### Model B — Async `MediaCodec.Callback` driven by a `HandlerThread`

`MediaCodec.setCallback(...)` lets the codec post `onInputBufferAvailable` /
`onOutputBufferAvailable` to a `Handler` (on a dedicated `HandlerThread`).
Bridges into coroutines via `Channel<Event>` consumed by the transcode
suspend function.

**Pros:** Idiomatic Android, no polling.
**Cons:** Significantly more code (one HandlerThread + Channel per
codec, four codecs = a lot of plumbing). Cancellation has to teardown
the channels cleanly. Harder to reason about pacing (when do we let
the muxer drain vs. feed more decoder input?).

### Model C — Hybrid (async callbacks bridged to sync-pump coroutine)

Use async callbacks for "buffer available" notifications, but keep the
high-level orchestration as a sync loop that wakes when callbacks fire.
**Verdict:** all the complexity of B with marginal benefit over A on a
device-bound workload.

### Recommendation

**Go with Model A.** Reasons:
1. Signal's sync model has run on tens of millions of devices; the
   "one thread per transcode" cost is empirically fine.
2. Cancellation in coroutines + sync loop is two lines (`ensureActive()`
   in the loop, `try/finally` for cleanup).
3. We can revisit if we ever need to run multiple transcodes in
   parallel (which we don't today — `VideoPayloadProcessor` runs one
   at a time per send).

**Open for review:** if you've had bad experience with sync polling on
specific devices or want fewer wakeups, Model B is on the table at
~2-3x the code volume.

---

## 6. Quality model

```kotlin
internal data class QualityProfile(
    val videoCodec: String,        // MIME, e.g. "video/avc"
    val shortEdgePx: Int,          // scaling target: shorter dimension after rounding
    val videoBitrateBps: Int,
    val audioCodec: String,        // "audio/mp4a-latm"
    val audioBitrateBps: Int,
)

internal fun VideoQuality.profile(): QualityProfile = when (this) {
    VideoQuality.LOW      -> QualityProfile("video/avc",  480,  1_250_000, "audio/mp4a-latm", 128_000)
    VideoQuality.STANDARD -> QualityProfile("video/avc",  720,  2_500_000, "audio/mp4a-latm", 128_000)
    VideoQuality.HIGH     -> QualityProfile("video/avc", 1080,  5_000_000, "audio/mp4a-latm", 192_000)
}
```

**Numbers chosen:**

- LOW = Signal's `LEVEL_1` envelope (480p / 1.25 Mbps + 128 kbps audio).
  Suitable for slow connections / smaller storage.
- STANDARD = Signal's `LEVEL_3` envelope (720p / 2.5 Mbps + 128 kbps).
  Close to today's FFmpeg path (which targets 1280×720 at ~3 Mbps).
  Chosen as default for parity with current behaviour.
- HIGH = 1080p / 5 Mbps + 192 kbps. Custom (Signal's presets cap at
  720p). Roughly matches "good" phone-recorded quality.

**Short-edge scaling logic:** scale the shorter dimension to
`shortEdgePx`, scale the longer to preserve aspect, round both to the
next multiple of 16 (encoder alignment + iOS playback quirk inherited
from Signal). 16:9 input at LOW → 854×480; portrait at HIGH → 1080×1920.

**OPEN — H.265 for HIGH?** Signal has a `LEVEL_3_H265` preset that
they label experimental. H.265 at the same bitrate gives meaningfully
better quality, but receiver-side compatibility is uneven (some
players choke on HEVC in MP4). Recommendation: stay on H.264 across
the board for v1; add HEVC quality presets later when we have
receiver-side telemetry.

**OPEN — bitrate-vs-CRF.** MediaCodec encoders accept
`MediaFormat.KEY_BITRATE_MODE` (CBR / VBR / CQ). Signal uses VBR
implicitly. Constant-quality mode (CQ) is supported on more recent
encoders and would give more consistent visual quality at variable
file size. Recommendation: stick with VBR at the target bitrate for
v1 (matches current behaviour); CQ is a Phase-3 optimization.

---

## 7. "Already optimal" short-circuit

Skip transcode and return `Result.AlreadyOptimal` iff ALL these hold:

1. `trim == null` — any trim forces re-encode.
2. Input has exactly one video track and at most one audio track.
3. Video codec is H.264. Other codecs (HEVC, VP9, AV1) always re-encode
   for receiver compatibility.
4. Video short-edge ≤ `quality.profile().shortEdgePx`.
5. Computed input bitrate ≤ `1.2 * (quality.profile().videoBitrateBps + quality.profile().audioBitrateBps)`.
   - Bitrate computed as `fileSizeBytes * 8 * 1000 / durationMs`, not
     from `MediaFormat.KEY_BIT_RATE` (often missing / unreliable —
     matches Signal's and our current FFmpeg path's reasoning).

**Edge cases:**

- **HEVC input within quality envelope:** still re-encode. Receiver
  compatibility wins over the saved CPU.
- **Multi-audio-track input:** re-encode (the muxer will only write
  the first audio track; user gets the simpler file).
- **Rotation-bearing input:** rotation metadata is preserved through
  the short-circuit (it's intrinsic to the file). No re-encode needed
  for rotation alone — we just pass the file through.
- **Very small input (≤ 1 MB, ≤ 1 s):** still applies; the bitrate
  check naturally handles this.
- **Missing duration metadata:** treat as "can't probe → re-encode to
  be safe".

**What we explicitly DON'T check (vs. Signal):**

- EXIF / GPS location atoms — separately handled by
  `Mp4LocationStripper`, which runs in `compressVideo`'s wrapper.
- Upper file-size limit. Signal's `upperSizeLimit` parameter feeds
  into the short-circuit (`inSize > upperSizeLimit` forces transcode).
  We don't have a hard cap (HLS path handles large files).

---

## 8. Failure model

Single exception base + two specific subclasses. Replaces Signal's 8.

```kotlin
open class TranscodeException(
    message: String,
    cause: Throwable? = null,
    val inputCodec: String? = null,
    val attemptedDecoder: String? = null,
    val attemptedEncoder: String? = null,
    val inputBytes: Long? = null,
    val inputDurationMs: Long? = null,
    val isHdrInput: Boolean = false,
) : RuntimeException(message, cause)

/** No suitable codec on this device (after REGULAR + ALL fallback). */
class CodecUnavailableException(
    val codecMimeType: String,
    val isEncoder: Boolean,
) : TranscodeException("No ${if (isEncoder) "encoder" else "decoder"} available for $codecMimeType")

/** Input is unreadable/unsupported (no video track, HDR with no fallback, corrupt container, etc.) */
class UnsupportedSourceException(
    message: String,
    cause: Throwable? = null,
) : TranscodeException(message, cause)
```

**Mapping from Signal's 8:**

| Signal | Ours |
|---|---|
| `EncodingException` (generic + decoderName/encoderName fields) | `TranscodeException` (base) |
| `TranscodingException` (Java-level) | `TranscodeException` |
| `CodecUnavailableException` | `CodecUnavailableException` (same name, same role) |
| `HdrDecoderUnavailableException` | `UnsupportedSourceException("HDR input requires tone-mapping which is not implemented")` |
| `VideoSourceException` (can't read metadata / open file) | `UnsupportedSourceException` |
| `VideoSizeException` (output exceeded upperSizeLimit) | gone — we don't have a hard cap |
| `MuxingException` (muxer-specific failure) | `TranscodeException` (the muxer's underlying IO/state error becomes the cause) |
| `VideoPostProcessingException` (faststart failure) | gone — no post-processor |

**Diagnostic fields chosen for crash-report value:** codec names + input
size + duration + HDR flag are the things we'd actually want when
triaging a failed transcode in production. Everything else can come
from `cause.stackTrace`.

**Wrapper contract** (`FFmpegUtils.android.kt:compressVideo` after
switchover):

```kotlin
actual suspend fun compressVideo(...): String? = withContext(Dispatchers.IO) {
    val out = File(context.cacheDir, "compressed_${inFile.name}")
    val result = try {
        HomebaseVideoTranscoder.transcode(
            inputPath = inFile.absolutePath,
            outputPath = out.absolutePath,
            quality = quality,
            trim = if (trimStartMs != null && trimEndMs != null)
                HomebaseVideoTranscoder.TrimRange(trimStartMs, trimEndMs) else null,
            onProgress = onProgress,
        )
    } catch (e: TranscodeException) {
        Log.e(TAG, "Transcode failed (in=$inputPath, codec=${e.inputCodec}, " +
                   "decoder=${e.attemptedDecoder}, encoder=${e.attemptedEncoder})", e)
        out.delete()
        return@withContext null
    }
    when (result) {
        is HomebaseVideoTranscoder.Result.AlreadyOptimal -> {
            // Same as before: caller falls back to original input via `?: payload.filePath`.
            // EXIF strip still runs on the short-circuit branch.
            Mp4LocationStripper.stripTo(...) ...
            null
        }
        is HomebaseVideoTranscoder.Result.Transcoded -> result.outputPath
    }
}
```

---

## 9. Device-quirk allowlist

The bits of Signal's `MediaCodecCompat` + `MediaConverter` we KEEP
because they're real-world quirk workarounds, not Signal-architecture
artefacts. Each item cites the Signal file/lines so the implementer
can crib surgically.

1. **Codec selection: REGULAR → ALL with dedupe.**
   `MediaCodecList(REGULAR_CODECS).getCodecInfos()` first, then
   `MediaCodecList(ALL_CODECS).getCodecInfos()`, deduplicated by
   `codecInfo.getName()`. REGULAR_CODECS is the curated set the device
   manufacturer expects to work; ALL_CODECS includes software
   fallbacks. (`MediaConverter.java:409-442`.)

2. **Mid-stream codec-failure retry with exclusion.** When the encoder
   or decoder throws `IllegalStateException` from a native
   `MediaCodec.*` frame (visible in stack trace), the transcoder
   retries the entire pipeline with that codec name excluded from
   `selectCodecs()`. Catches per-device hardware-codec bugs without
   manual maintained quirks lists. Also fires on "frame counts should
   match" errors (spatial video). (`MediaConverter.java:152-199`.)

3. **Stuck-frame watchdog.** If the converter state (extractor PTS +
   decoder PTS + encoder PTS) doesn't change for `STUCK_FRAME_THRESHOLD = 100`
   iterations of the pump loop, mark as cancelled and bail.
   Catches runaway decoders that emit no output but no error either.
   (`MediaConverter.java:341-348`.)

4. **Output dimension alignment to multiple of 16.** Many encoders
   require this, plus an iOS playback quirk. After short-edge
   scaling, round both width and height to next multiple of 16:
   `(n + 7) & ~0xF`. (`VideoTrackConverter.java:144-146`.)

5. **`KEY_DISPLAY_WIDTH` / `KEY_DISPLAY_HEIGHT` preference over
   `KEY_WIDTH` / `KEY_HEIGHT`.** For container formats that record
   anamorphic / display dimensions separately, the display values are
   what users see; encode against those. Falls back to `KEY_WIDTH` /
   `KEY_HEIGHT`. (`VideoTrackConverter.java:129-134`.)

6. **HDR detection on the input format.** `MediaCodecCompat.isHdrVideo(format)`
   reads `MediaFormat.KEY_COLOR_TRANSFER` and `KEY_COLOR_STANDARD`.
   We surface this as `TranscodeException.isHdrInput` for crash
   reports but throw `UnsupportedSourceException` (rather than
   tone-mapping). Phase-3 implementation: copy the detection helper
   ~verbatim from `MediaCodecCompat.kt`.

7. **Time-interleaved decoder feeding.** The pump alternates between
   video and audio `step()` based on which side has the earlier
   muxing PTS. This keeps the muxer's per-track buffers roughly
   balanced and avoids one side stalling. (`MediaConverter.java:350-356`.)

8. **Lazy muxer start.** Don't call `MediaMuxer.start()` until BOTH
   encoders have produced their first output, because the encoder's
   actual output `MediaFormat` (with codec-specific data — SPS/PPS
   for H.264, ESDS for AAC) is only available after the first
   `dequeueOutputBuffer` returns `INFO_OUTPUT_FORMAT_CHANGED`.
   (`MediaConverter.java:373-385`.)

9. **EOS propagation on the encoder side.** When the decoder emits a
   buffer with `BUFFER_FLAG_END_OF_STREAM`, signal EOS to the
   encoder via a queued zero-length input buffer with the same flag.
   The encoder will drain its remaining frames and then emit its own
   EOS-flagged output. (Standard but easy to miss.)

10. **Don't process input past `mTimeTo`.** When trimming, after the
    extractor reads a sample whose PTS exceeds `mTimeTo * 1000` (μs),
    signal decoder EOS instead of feeding the sample. This bounds
    output duration accurately. (`VideoTrackConverter.java` — search
    "mTimeTo".)

---

## 10. Scope cuts (what we are explicitly NOT doing)

| Cut | Saves | Why | Risk |
|---|---|---|---|
| **No custom MP4 muxer.** Trust `MediaMuxer`. | `Mp4Writer.java` + `StreamingMuxer.java` + `AndroidMuxer.java` + `AvcTrack.java` + `HevcTrack.java` + `AacTrack.java` + `H264Utils.java` + `Utils.java` + `MuxingException.java` (~1,800 LOC) | `MediaMuxer` writes ISO BMFF MP4 with `AVCDecoderConfigurationRecord` + `AudioSpecificConfig` from the encoder's `INFO_OUTPUT_FORMAT_CHANGED` payload. That's what Signal's custom muxer also does, just behind a wider API surface. | Tiny: `MediaMuxer` is a stable Google-maintained API; bugs are rare. |
| **No faststart post-processor.** Skip the `moov`-before-`mdat` rewrite pass. | `Mp4FaststartPostProcessor.kt` + the entire `mp4parser` dep for transcode purposes (Mp4LocationStripper would migrate to a different parser or get rewritten — separate decision) | `MediaMuxer.setOutput*` produces files with `moov` at the end by default, BUT we can set `Mp4MoovBoxAtFrontOfFile` behaviour via... actually wait, Android's `MediaMuxer` writes `moov` at the END by default. **OPEN — VERIFY.** If `MediaMuxer` doesn't faststart natively, we either (a) keep a tiny faststart pass using `MediaMetadataRetriever`-based seek to make moov-end files seekable enough for players, or (b) accept the trade-off (streaming playback delayed by the time to read the file footer first — most players handle this fine for local playback). | Real — needs verification in Phase 3. If `MediaMuxer` writes moov-end and our cdn streaming requires moov-front, we need a faststart pass. |
| **No `Mp4Sanitizer` shim.** | `stub/Mp4Sanitizer.kt` + `stub/SanitizedMetadata.kt` | Already dead — only used by the (also-deleted) faststart processor. | None. |
| **No HDR tone-mapping.** Throw `UnsupportedSourceException` on HDR input the device can't decode to SDR natively. | Signal's `HdrDecoderUnavailableException` recovery path + any GL/colorspace conversion shaders. | HDR camera content is becoming common on phones (iPhone Pro, recent Samsung). Failing to send these is a real UX gap. **OPEN — call this out to the user.** v1 punts; v2 implements `MediaFormat.KEY_COLOR_TRANSFER_REQUEST` + verifies, falls back to tone-map shader if unsupported. | Real — affects HDR phone capture. |
| **No `LimitedSizeOutputStream` cap.** Skip Signal's "abort if encoder produces > N bytes" guard. | One inner class, ~30 LOC. | We don't take an `upperSizeLimit` parameter. If we ever want a cap, the bitrate envelope + duration gives us a soft estimate. | None given current callers. |
| **No `OutputStream` write target.** Only file paths. | `setOutput(OutputStream)` overload + `StreamOutput` adapter. | `MediaMuxer` doesn't accept an `OutputStream` — Signal's adapter is a Frankenstein of a custom muxer that knows how to write to one. Our scope cut #1 eliminates the need. Callers that want to encrypt during write can encrypt the file post-transcode (one more pass; acceptable). | None given current callers. |
| **No GL pixel pipeline.** Use source rotation metadata, not pixel rotation. | `InputSurface.java` + `OutputSurface.java` + `TextureRender.java` (~700 LOC + significant test exposure). | Source rotation is preserved through `MediaMuxer.setOrientationHint()`. Players read this from the `tkhd` matrix and rotate at display. We're not flipping/cropping/recoloring; no other reason for a GL pipeline. **OPEN — verify on receiver-side players (Compose VideoPlayer, web)**. | Medium — if some receivers ignore `tkhd` rotation, recipients see sideways video. Easy to test before commit. |
| **No async `MediaCodec.Callback`.** Sync pump only (section 5). | `HandlerThread` orchestration, `Channel` plumbing per codec. | Section 5 analysis. | None initially; can revisit. |
| **No multi-audio-track preservation.** Single audio track in, single audio track out. | Extra extractor track selection + muxer setup + per-track pump state. | The send pipeline currently doesn't support multi-track audio (descriptor only carries one); the chat playback also doesn't surface track selection. Source files with multi-track audio collapse to first track. | None given current scope. |

---

## Appendix A — Phase-3 implementation outline (preview)

This is for orientation only; Phase-2 review may revise.

```
homebase-api/src/androidMain/kotlin/id/homebase/api/video/transcoder_v2/
  SPEC.md                          (this file)
  signal-reference-notes.md        (targeted refs)
  HomebaseVideoTranscoder.kt       (the public object + Result/TrimRange)
  TranscodeException.kt            (exception hierarchy)
  internal/
    QualityProfile.kt              (enum → bitrate/dimension mapping)
    PreflightProbe.kt              (already-optimal check via MediaMetadataRetriever)
    CodecSelection.kt              (REGULAR → ALL with dedupe + exclusion set)
    VideoPair.kt                   (decoder + encoder for the video track, plus step())
    AudioPair.kt                   (decoder + encoder for the audio track, plus step())
    TranscodePump.kt               (main while-loop: interleaved step + lazy muxer start)
    OutputDimensions.kt            (short-edge scaling + multiple-of-16 rounding)
```

Estimated ~1,200-1,500 LOC, down from Signal's ~5,500 mostly through
the scope cuts.

## Appendix B — Open items (for review)

- **Section 5:** sync pump (Model A) vs. async callbacks (Model B).
- **Section 6:** H.265 for HIGH preset.
- **Section 6:** bitrate-vs-CRF on the encoder.
- **Section 10 (faststart):** verify `MediaMuxer` output is playable
  without a faststart pass for our receivers.
- **Section 10 (HDR):** punt HDR for v1, or invest in tone-mapping
  before first release?
- **Section 10 (rotation):** verify all receiver-side players honour
  `tkhd` orientation hint.
