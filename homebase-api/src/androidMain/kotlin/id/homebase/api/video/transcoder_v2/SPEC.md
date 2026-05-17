# Homebase Android Video Transcoder — Spec

**Status: FROZEN — Phase 2 review complete.** All design decisions
recorded in Appendix B. Ready for Phase 3 implementation.

> **AMENDED 2026-05-17:** §10 GL pipeline scope-cut reversed — a minimal
> GL surface bridge has been added so HDR videos can be transcoded with
> hardware tone-mapping (matches Signal's HDR capability). See §10
> amendment row and the new Appendix B entry. The bridge is
> pixel-rotation-free; rotation continues to ride on
> `MediaMuxer.setOrientationHint`. YuvCopier was removed in trade
> (GL_LINEAR bilinear sampler replaces the previous nearest-neighbour
> scale at strictly better quality).

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

**Native APIs we explicitly USE for the GL surface bridge** (§10 amendment, 2026-05-17):

- **`android.opengl.EGL14`** — EGL 1.4 context + window-surface management.
  Used by `InputSurface` to wrap the encoder's `createInputSurface()`
  return as an EGL window surface.
- **`android.opengl.GLES20`** + **`android.opengl.GLES11Ext`** — GLES 2.0
  pass-through shader (vertex + fragment, single textured quad sampling
  a `GL_TEXTURE_EXTERNAL_OES` texture). Used by `TextureRender`.
- **`android.graphics.SurfaceTexture`** — wraps the decoder's output
  Surface, gives us frame-available notifications + `updateTexImage`.
  Used by `OutputSurface`. Only the *decoder* output uses a
  SurfaceTexture; the encoder consumes a raw `Surface` directly.

These three classes (~450 LOC) are the price of decoder-side HDR
tone-mapping — `KEY_COLOR_TRANSFER_REQUEST` is only honoured when the
decoder writes to a Surface.

**Native APIs we explicitly DON'T use:**

- **OpenGL ES rotation / colour-correction / cropping**. The GL bridge
  is pass-through only. Rotation lives in container metadata via
  `MediaMuxer.setOrientationHint()` (no pixel rotation in the GL
  pipeline). Scaling is implicit via `GL_LINEAR` sampling — no
  custom box-filter shader.
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
                  │  or HEVC in,    │                  │              │  in)             │
                  │  Surface out)   │                  │              └─────────┬────────┘
                  └────────┬────────┘                  │                        │ ByteBuffer (PCM)
                           │ SurfaceTexture (OES)      │                        ▼
                           │ + GL textured quad        │              ┌──────────────────┐
                           │ (pass-through shader,     │              │ MediaCodec enc   │
                           │  GL_LINEAR bilinear)      │              │ (audio, AAC      │
                           ▼                           │              │  out, 128 kbps)  │
                  ┌─────────────────┐                  │              └─────────┬────────┘
                  │ MediaCodec enc  │                  │                        │
                  │ (video, H.264   │                  │                        │
                  │  out, Surface   │                  │                        │
                  │  input)         │                  │                        │
                  └────────┬────────┘                  │                        │
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

Single exception base + three specific subclasses. Collapses Signal's
eight classes.

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

/** Input is unreadable / unsupported (no video track, corrupt container, etc.) */
class UnsupportedSourceException(
    message: String,
    cause: Throwable? = null,
) : TranscodeException(message, cause)

/**
 * HDR input — all decoder candidates failed to apply tone-mapping
 * (API 31+) or to produce viable output. See §11.
 */
class HdrDecoderUnavailableException(
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
| `HdrDecoderUnavailableException` | `HdrDecoderUnavailableException` (same name, same role — see §11) |
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

6. **HDR detection + decoder tone-mapping verify.** Signal's
   `MediaCodecCompat.isHdrVideo(format)` reads `KEY_COLOR_TRANSFER`,
   `KEY_HDR_STATIC_INFO`, `KEY_HDR10_PLUS_INFO`, and falls back to
   HEVC profile inspection. Handles non-standard `KEY_COLOR_TRANSFER`
   values (e.g. `65791`) some devices report.

   Equally important: `VideoTrackConverter.isToneMapEffective()` —
   after configuring + starting the decoder with
   `KEY_COLOR_TRANSFER_REQUEST`, verifies the codec is hardware (not
   software, which doesn't tone-map) AND the output format's
   `KEY_COLOR_TRANSFER` is no longer HDR. Some codecs accept the
   request without honoring it; the verify catches this.

   Both are real device-quirk knowledge; port nearly verbatim. See
   §11 for the full HDR strategy.

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
| **No faststart post-processor.** Skip the `moov`-before-`mdat` rewrite pass. | `Mp4FaststartPostProcessor.kt` + the `mp4parser` dep for transcode purposes (Mp4LocationStripper would migrate to a different parser or get rewritten — separate decision) | **RESOLVED.** Android's `MPEG4Writer` (the C++ backend of `MediaMuxer`) writes `moov` at the END by default — the `mStreamableFile` flag is true only when an explicit max-file-size limit is set, which `MediaMuxer`'s Java API doesn't expose. Confirmed against [AOSP frameworks/av source](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/master/media/libstagefright/MPEG4Writer.cpp). This is FINE for both downstream paths: (a) small videos that stay as MP4 (<5MB after compress) are downloaded *in full* by the receiver before local playback, and random-access players (ExoPlayer, AVPlayer, VLC) handle moov-end transparently; (b) large videos (≥5MB) are re-containerized to encrypted MPEG-TS segments by the HLS segmenter (`segmentAndEncryptVideo`, still ffmpeg) — `.ts` is not MP4, so faststart doesn't apply at all to the streamed segments. Faststart matters only for HTTP progressive download of MP4, which neither path does. | None for current scope. If we ever add HTTP progressive download of small MP4s (vs. download-then-play), we'd need a faststart pass then. |
| **No `Mp4Sanitizer` shim.** | `stub/Mp4Sanitizer.kt` + `stub/SanitizedMetadata.kt` | Already dead — only used by the (also-deleted) faststart processor. | None. |
| ~~No HDR tone-mapping.~~ **WITHDRAWN — HDR IS supported.** See new §11. | n/a | User benchmark: "can't be worse than Signal." Signal handles HDR; so do we. Implementation crucially does NOT require restoring the GL pipeline — Signal's strategy uses decoder-side hardware tone-mapping via `KEY_COLOR_TRANSFER_REQUEST` (API 31+) and accepts degraded passthrough on older APIs. No fragment-shader work needed. | None — see §11 for the strategy. |
| **No `LimitedSizeOutputStream` cap.** Skip Signal's "abort if encoder produces > N bytes" guard. | One inner class, ~30 LOC. | We don't take an `upperSizeLimit` parameter. If we ever want a cap, the bitrate envelope + duration gives us a soft estimate. | None given current callers. |
| **No `OutputStream` write target.** Only file paths. | `setOutput(OutputStream)` overload + `StreamOutput` adapter. | `MediaMuxer` doesn't accept an `OutputStream` — Signal's adapter is a Frankenstein of a custom muxer that knows how to write to one. Our scope cut #1 eliminates the need. Callers that want to encrypt during write can encrypt the file post-transcode (one more pass; acceptable). | None given current callers. |
| ~~**No GL pixel pipeline.** Use source rotation metadata, not pixel rotation.~~ **AMENDED 2026-05-17 — minimal GL surface bridge restored.** | n/a — restored | Original cut was made on the basis "we're not flipping / cropping / recoloring; no other reason for a GL pipeline." This missed that **HDR tone-mapping via `KEY_COLOR_TRANSFER_REQUEST` requires Surface-output decoder**, which the ByteBuffer pipeline didn't provide. To match Signal's HDR capability we restored a *minimal* GL bridge: `InputSurface` (EGL14 window-surface wrapper for encoder input), `OutputSurface` (SurfaceTexture wrapper for decoder output), `TextureRender` (pass-through GLES 2.0 fragment shader). Pixel rotation is still NOT done in GL — rotation continues to ride on `MediaMuxer.setOrientationHint()` per the original §10 rationale (ExoPlayer / AVPlayer / VLC-J / `HTMLVideoElement` all honour `tkhd`). YuvCopier deleted in trade — GL_LINEAR bilinear sampling strictly improves on the previous nearest-neighbour scale. Net +430 LOC. | None for current scope. |
| **No async `MediaCodec.Callback`.** Sync pump only (section 5). | `HandlerThread` orchestration, `Channel` plumbing per codec. | Section 5 analysis. | None initially; can revisit. |
| **No multi-audio-track preservation.** Single audio track in, single audio track out. | Extra extractor track selection + muxer setup + per-track pump state. | The send pipeline currently doesn't support multi-track audio (descriptor only carries one); the chat playback also doesn't surface track selection. Source files with multi-track audio collapse to first track. | None given current scope. |

---

---

## 11. HDR support

User benchmark: match Signal. Signal's strategy is decoder-side
hardware tone-mapping on API 31+ and degraded passthrough on older
APIs — neither requires the GL pipeline, so the §10 GL scope-cut
holds.

### Detection

`internal/PreflightProbe.kt` answers "is this HDR?" via the same
logic as Signal's `MediaCodecCompat.isHdrVideo`. Inspect the input
`MediaFormat`:

1. `KEY_COLOR_TRANSFER` == `COLOR_TRANSFER_ST2084` (PQ / HDR10) or
   `COLOR_TRANSFER_HLG`.
2. `KEY_HDR_STATIC_INFO` present (HDR10 mastering display).
3. (API 29+) `KEY_HDR10_PLUS_INFO` present.
4. Fallback: HEVC `Main10HDR10` / `Main10HDR10Plus` profile via
   `KEY_PROFILE`.

Some devices report non-standard `KEY_COLOR_TRANSFER` values (e.g.
`65791`) — anything outside the known SDR set treated as HDR. Copy
the canonical check from `../transcoder/videoconverter/utils/MediaCodecCompat.kt:249-285`.

### Transcode path

**API 31+ (Android 12+):** for each candidate decoder, build a
`MediaFormat` copy with `KEY_COLOR_TRANSFER_REQUEST` set to
`COLOR_TRANSFER_SDR_VIDEO`. Configure + start (with decoder output going
to the `OutputSurface` SurfaceTexture per the §10 amendment). The decoder
performs hardware tone-mapping while writing to the texture; the GL
pass-through shader paints those SDR pixels into the encoder's input
surface. Then call `isToneMapEffective()`:

- If the chosen codec is a software codec (per `MediaCodecInfo.isSoftwareOnly()`
  on API 29+, or name-prefix heuristics earlier), tone-mapping does
  NOT apply — software codecs don't honor the request. Reject; try
  next codec.
- Read the decoder's actual output `MediaFormat`. If its
  `KEY_COLOR_TRANSFER` is still `COLOR_TRANSFER_ST2084` or
  `COLOR_TRANSFER_HLG`, the codec accepted the request without
  honoring it. Reject; try next codec.

If the codec rejects the `KEY_COLOR_TRANSFER_REQUEST` key itself
(`IllegalArgumentException` / `IllegalStateException` from
`configure()`), retry the SAME codec without the key — produces
degraded output, matches the pre-API-31 path below.

If all candidates fail, throw `TranscodeException` subclass
`HdrDecoderUnavailableException` (added to §8 — see below).

**API <31:** configure decoder normally (no tone-map request).
Decoded frames are 10-bit HDR in the BT.2020/PQ or HLG color space;
the SDR-only H.264 encoder receives them and produces an 8-bit
BT.709 file. Result is playable but colors are visibly off — gamma
crushed, highlights blown, color primaries wrong. **This matches
Signal's behavior on the same APIs.** A future v2 could add a GL
fragment-shader tone-map path for pre-API-31 devices; explicitly
out of scope for v1.

### Surface to `TranscodeException`

Restore `HdrDecoderUnavailableException : TranscodeException` (was
removed in §8's collapse). Updated §8 hierarchy is now three
classes:

```kotlin
open class TranscodeException(...)
class CodecUnavailableException(...) : TranscodeException(...)
class UnsupportedSourceException(...) : TranscodeException(...)
class HdrDecoderUnavailableException(...) : TranscodeException(...)  // ← new
```

`isHdrInput` stays on the base `TranscodeException` (any failure can
note HDR-ness for telemetry, not just HDR-specific failures).

### Verify-tone-map helper

Port `isToneMapEffective()` nearly verbatim from
`../transcoder/videoconverter/VideoTrackConverter.java:670-700`.
This is the kind of "we learned this the hard way" device-quirk
logic where reinventing risks missing a vendor codec quirk.

---

## Appendix A — Phase-3 implementation outline (preview)

This is for orientation only; Phase-2 review may revise.

```
homebase-api/src/androidMain/kotlin/id/homebase/api/video/transcoder_v2/
  SPEC.md                          (this file)
  signal-reference-notes.md        (targeted refs)
  HomebaseVideoTranscoder.kt       (the public object + Result/TrimRange)
  TranscodeException.kt            (exception hierarchy — 4 classes)
  internal/
    QualityProfile.kt              (enum → bitrate/dimension mapping)
    PreflightProbe.kt              (already-optimal check + HDR detection)
    CodecSelection.kt              (REGULAR → ALL with dedupe + exclusion set)
    DecoderConfig.kt               (HDR tone-map request + verify dance)
    VideoPair.kt                   (decoder + encoder for the video track, plus step())
    AudioPair.kt                   (decoder + encoder for the audio track, plus step())
    TranscodePump.kt               (main while-loop: interleaved step + lazy muxer start)
    OutputDimensions.kt            (short-edge scaling + multiple-of-16 rounding)
```

Estimated ~1,300-1,600 LOC, down from Signal's ~5,500. Scope cuts
that account for the saving (versus restored items): no custom MP4
muxer, no faststart, no GL pipeline; HDR handled via
decoder-side `KEY_COLOR_TRANSFER_REQUEST` only — no shader work.

## Appendix B — Decisions log

All OPEN items resolved before Phase 2 (user review session). Listed
here for the design-review record.

- **§5 Threading:** sync pump on `Dispatchers.IO`. Decided per
  user review.
- **§6 H.265 for HIGH preset:** no for v1. Stay on H.264 for
  receiver-compatibility; revisit when we have receiver-side
  telemetry. Decided per user review.
- **§6 Bitrate vs CRF:** bitrate (VBR). Decided per user review.
- **§10 Faststart:** no faststart pass. Resolved by verifying
  `MediaMuxer`'s default + that neither downstream path needs
  moov-front (small-MP4 = full local download, large = HLS .ts via
  ffmpeg).
- **§10 Rotation hint:** preserve via `MediaMuxer.setOrientationHint()`,
  no GL pipeline. Resolved by verifying ExoPlayer / AVPlayer / VLC
  all honour `tkhd`.
- **§11 HDR:** support it (matches Signal). Use decoder-side
  `KEY_COLOR_TRANSFER_REQUEST` on API 31+ with post-configure verify;
  pre-API-31 passes through with degraded colors. No GL pipeline
  needed.
- **§10 GL pipeline (amendment, 2026-05-17):** restored minimal GL
  surface bridge. Original Phase-2 decision missed that
  `KEY_COLOR_TRANSFER_REQUEST` only works when the decoder writes to a
  Surface — without GL, HDR transcodes silently degraded or required
  fail-fast. Restoring three small classes (`InputSurface`,
  `OutputSurface`, `TextureRender`, ~450 LOC) provides the Surface
  bridge HDR needs AND obsoletes our nearest-neighbour software YUV
  scaler (`YuvCopier`, ~75 LOC, deleted) in favour of GL_LINEAR
  bilinear sampling. The bridge is pixel-rotation-free; rotation
  metadata still rides on `MediaMuxer.setOrientationHint()` per the
  original §10. The bridge also enables Phase-2 quality improvements
  (e.g. Signal's box-filter shader for aggressive downscales) via the
  `TextureRender.changeFragmentShader` hook. Net +430 LOC.
- **AAC pass-through + trim PTS normalization (2026-05-17):** ported
  Signal's `formatCanSkipTranscode` fast path (`AudioTrackConverter.java:494-505`):
  AAC input at acceptable bitrate now skips the decoder/encoder round
  trip and mux-copies samples directly via the new `AudioRemuxer`
  alongside `AudioPair` (shared `AudioTrack` interface). Sidesteps the
  documented HE-AAC re-encode bug. Also normalised PTS at encoder-queue
  time (subtract `trimStartUs`) so trimmed output starts at t=0 in
  container time — fixes leading silent gap in some players.

---

## Appendix C — Remaining gaps vs Signal (post-2026-05-17)

After surface bridge + AAC pass-through + PTS normalization, the v2
transcoder is at functional parity with Signal for the user-visible
"can Signal transcode this video that we can't?" question. What
follows is the honest list of finer-grained gaps that remain. None
are blocking; this is a "future work" register.

### Quality gaps

- **No box-filter shader for aggressive downscales.** Signal generates
  a custom GLES fragment shader with manual box-filter sampling for
  large downscale ratios (`VideoTrackConverter.java:443-489`). We use
  `GL_LINEAR` (bilinear) on every output dimension. For modest
  downscales (≤2×, e.g. 1080p→720p) bilinear is indistinguishable;
  for >2× downscales (4K→480p, ~5×) bilinear shows mild aliasing on
  high-frequency content. **Mitigation hook already in place:**
  `TextureRender.changeFragmentShader(String)` — a future contributor
  can wire Signal's `createFragmentShader` in ~50 LOC.

### Coverage / robustness gaps

- **Spatial-video / vendor-codec quirk retry path is untested.** We
  plumb the "frame counts should match" error-string detection and
  the `android.media.MediaCodec`-stack-frame predicate (SPEC §9.2)
  but no test fixture exercises it. Signal has tens of millions of
  installs validating the path against real iPhone-spatial-video and
  vendor codec regressions; ours hasn't been stress-tested. **First
  user report from a device that hits this is the canary.**
- **Vendor codec quirk catalogue.** Signal accumulated multi-year
  empirical fixes for specific OEM codecs (e.g. Samsung Exynos
  pre-roll bugs, MediaTek HEVC stride misreports). We inherit the
  *architecture* (REGULAR→ALL fallback, mid-stream exclusion, stuck-
  frame watchdog) but not the *specific* device knowledge. New
  device reports will surface these.

### Less-common feature gaps

- **Anamorphic source aspect ratio.** We probe display dimensions via
  `KEY_DISPLAY_WIDTH`/`KEY_DISPLAY_HEIGHT` (SPEC §9.5) for the
  already-optimal short-edge check, but the encoder is configured at
  the *coded* dimensions from short-edge math. For sources where
  coded ≠ display (e.g. 720×480 NTSC stretched to 854×480), the
  output's aspect ratio gets squashed. Rare in mobile-captured
  content; common in shared/ripped media. Fix would be ~10 LOC to
  use display dimensions in `OutputDimensions.computeOutputDimensions`.
- **Multi-audio-track preservation.** SPEC §10 explicit cut — input
  with multiple audio tracks (e.g. director's commentary; multi-
  language Blu-ray rip) collapses to first track only. Signal does
  the same. Not a regression vs Signal; just noting it's still cut.
- **Faststart MOOV-before-MDAT.** Both downstream paths (HLS .ts
  segments + sub-5 MB MP4 that's fully downloaded before playback)
  make this irrelevant; documented in SPEC §10. Would become a gap
  only if we ever serve MP4s via HTTP progressive download.
- **CRF / constant-quality encoding.** SPEC §6 frozen decision is
  bitrate (VBR). MediaCodec encoders accept `KEY_BITRATE_MODE = CQ`
  on recent devices for more consistent visual quality at variable
  file size — would be a worthwhile experiment once we have
  receiver-side quality telemetry.
- **HEVC for HIGH preset.** SPEC §6 frozen decision is H.264 across
  all presets. HEVC at the same bitrate gives meaningfully better
  quality; revisit when receiver compatibility telemetry justifies
  it. Signal has an experimental `LEVEL_3_H265` preset; we don't.

### Test coverage gaps

- **HDR happy path untested on emulator.** The Android emulator
  exposes only software HEVC decoders (`c2.android.hevc.decoder`,
  `c2.goldfish.hevc.decoder`, `OMX.google.hevc.decoder`), all of
  which fail `isToneMapEffective`. Our HDR instrumented test
  (`compressVideo_hdr10_720p_producesTonemappedSdrOrControlledFailure`)
  branches on `hasHardwareHevcDecoder()` and verifies the *failure*
  path on emulator. Verifying the *success* path requires running
  the same test on a physical device with hardware HEVC decoding —
  any modern Android phone, but not the CI environment.
- **No real HE-AAC fixture.** The AAC pass-through path is tested
  with regular AAC-LC (sample.mp4). The path's main motivation —
  Signal's HE-AAC re-encode bug — would benefit from a fixture
  with HE-AAC audio specifically, to assert no audio distortion
  through the pipeline. Currently we rely on the "no codec
  construction" assertion as a proxy.

### Where we're now better than Signal

- **Suspend coroutine API** vs callback `CancellationSignal`. Native
  to our codebase.
- **No third-party deps.** Signal pulls in isobmff-like parsing
  libraries for their custom MP4 muxer and faststart processor; we
  trust `MediaMuxer` and don't faststart.
- **~1,800 LOC vs Signal's ~5,500.** ~33% the size, with HDR,
  pass-through, surface bridge, all the §9 device-quirk workarounds.
- **Explicit `Result.AlreadyOptimal`** vs Signal's "return 0 to mean
  skipped" — caller can't get confused about null/0 semantics.
- **GL pipeline is minimal** — pass-through shader only (~450 LOC
  across three files). Signal's includes box-filter shader
  generation, flipX vertex variants, rotation matrices — useful but
  not what we need.

### Recommendation

None of the above are blocking; all are quality-of-life or edge-
case improvements. The order I'd tackle them if/when needed:

1. Box-filter shader (only when telemetry shows users transcoding
   4K → 480p often enough to notice the aliasing).
2. Anamorphic dimensions (when first user report).
3. HE-AAC fixture for regression coverage (any time we touch
   `AudioRemuxer` again).
4. Spatial-video stress test (when first device report).
5. HEVC HIGH preset / CRF mode (experiments once receiver
   telemetry exists).
