# Signal reference notes

Targeted reading log accumulated while drafting `SPEC.md`. NOT a full
annotation pass — entries appear only when reading a Signal file
surfaces a decision worth preserving (a device-quirk workaround, a
non-obvious EOS edge, a magic constant whose history matters).

Citations use the path relative to this file, so:
`../transcoder/videoconverter/MediaConverter.java:142-211` resolves to
the Signal-vendored file.

Most entries cross-reference `SPEC.md` section 9 (Device-quirk
allowlist) or section 10 (Scope cuts).

---

## ../transcoder/videoconverter/MediaConverter.java:409-442
**Codec selection: REGULAR → ALL with dedupe** _(SPEC §9.1)_

```java
static List<MediaCodecInfo> selectCodecs(final String mimeType) { ... }
```

Walks `MediaCodecList(REGULAR_CODECS).getCodecInfos()` first, then
`MediaCodecList(ALL_CODECS).getCodecInfos()` — adding only codec names
not already seen. REGULAR_CODECS is the curated set the manufacturer
promises will work; ALL_CODECS adds software fallbacks and corner-case
hardware codecs.

**Phase-3 implementation notes:**
- Port nearly verbatim into `internal/CodecSelection.kt`.
- Accept an `excludedNames: Set<String>` parameter so the mid-stream
  retry path (next entry) can skip a known-bad codec.
- Return `List<MediaCodecInfo>` (caller iterates trying each); don't
  collapse to one — the retry mechanism wants the rest of the list.

---

## ../transcoder/videoconverter/MediaConverter.java:152-199
**Mid-stream codec-failure retry with exclusion** _(SPEC §9.2)_

```java
public long convert() throws ... {
    final Set<String> excludedDecoders = new HashSet<>();
    while (true) {
        mCancelled = false;
        try { return doConvert(excludedDecoders); }
        catch (EncodingException e) {
            if (e.decoderName != null
                    && isRetryableMidStreamFailure(e)
                    && excludedDecoders.add(e.decoderName)) {
                Log.w(TAG, "Mid-stream codec failure with decoder " + e.decoderName + ", retrying ...");
                continue;
            }
            throw e;
        }
    }
}

private static boolean isRetryableMidStreamFailure(EncodingException e) {
    // Walks cause chain; returns true iff some cause is an IllegalStateException
    // whose stack contains a frame in `android.media.MediaCodec`, OR whose
    // message contains "frame counts should match" (spatial video on some decoders).
}
```

The retry catches the class of bug where a hardware codec accepts the
stream initially but throws `IllegalStateException` partway through.
Without this, the entire transcode fails on a per-device codec
regression we'd never reproduce in dev.

**Phase-3 implementation notes:**
- Wrap our `TranscodePump.run()` in this same retry-with-exclusion
  pattern.
- Stack-frame inspection is brittle but the alternatives (catching
  every `IllegalStateException` broadly) are worse. Keep the
  `android.media.MediaCodec` frame check.
- Also propagate the "frame counts should match" string check —
  spatial-video iPhone content reportedly trips this.

---

## ../transcoder/videoconverter/MediaConverter.java:341-348
**Stuck-frame watchdog** _(SPEC §9.3)_

```java
private static final int STUCK_FRAME_THRESHOLD = 100;
// ...
final MediaConverterState currentState = new MediaConverterState(
    videoTrackConverter != null ? videoTrackConverter.dumpState() : null,
    audioTrackConverter != null ? audioTrackConverter.dumpState() : null,
    muxing);
if (muxing && currentState.equals(oldState)) {
    if (++stuckFrames >= STUCK_FRAME_THRESHOLD) {
        mCancelled = true;
    }
} else {
    oldState = currentState;
    stuckFrames = 0;
}
```

`dumpState()` returns a value record of (extractor PTS, decoder PTS,
encoder PTS, frame counts). If 100 consecutive iterations show NO
forward progress on either track, the loop bails. Catches the case
where a hardware decoder enters a broken state with no error: it
returns no output buffer and no error indefinitely.

**Phase-3 implementation notes:**
- Port into `TranscodePump`. Hold a `data class PumpState(...)` and
  compare with `equals()`.
- 100 iterations × 10 ms timeout = ~1 second of no progress before
  giving up. Reasonable for "stuck", agressive enough to not pause
  the user noticeably.
- The cancellation here surfaces as `TranscodeException("Conversion
  cancelled before muxing started")` — for our rewrite, surface a
  more specific message ("Codec stalled — no frames after 1s of
  pumping") for telemetry.

---

## ../transcoder/videoconverter/MediaConverter.java:350-356
**Time-interleaved decoder feeding** _(SPEC §9.7)_

```java
if (videoTrackConverter != null && (audioTrackConverter == null ||
    audioTrackConverter.mAudioExtractorDone ||
    videoTrackConverter.mMuxingVideoPresentationTime <= audioTrackConverter.mMuxingAudioPresentationTime)) {
    videoTrackConverter.step();
}

if (audioTrackConverter != null && (videoTrackConverter == null ||
    videoTrackConverter.mVideoExtractorDone ||
    videoTrackConverter.mMuxingVideoPresentationTime >= audioTrackConverter.mMuxingAudioPresentationTime)) {
    audioTrackConverter.step();
}
```

Whichever side has the LOWER muxing PTS gets stepped this iteration
(or both, if PTS are equal — the predicates are intentionally
non-exclusive). Keeps the muxer's per-track buffer queues roughly
balanced; without this, one side can race ahead and the muxer's
internal buffer pressure causes the other side to stall.

**Phase-3 implementation notes:**
- Port the interleaving logic verbatim. Easy to omit by accident.
- The "if extractor done, force the other side to keep stepping"
  guard is what drains the trailing buffers when one track ends
  earlier than the other (typical: trim cuts audio short).

---

## ../transcoder/videoconverter/MediaConverter.java:373-385
**Lazy muxer start** _(SPEC §9.8)_

```java
if (!muxing
        && (videoTrackConverter == null || videoTrackConverter.mEncoderOutputVideoFormat != null)
        && (audioTrackConverter == null || audioTrackConverter.mEncoderOutputAudioFormat != null)) {
    if (videoTrackConverter != null) videoTrackConverter.setMuxer(muxer);
    if (audioTrackConverter != null) audioTrackConverter.setMuxer(muxer);
    muxer.start();
    muxing = true;
}
```

`MediaMuxer.start()` requires `addTrack(format)` to have been called
with the encoder's REAL output format — which is only available after
the encoder emits its first `INFO_OUTPUT_FORMAT_CHANGED`. Until BOTH
encoders have emitted that, we can't start the muxer. The pump
accumulates frames in the encoder's output buffers in the meantime
(the encoder buffers them internally).

**Phase-3 implementation notes:**
- This is non-obvious and easy to get wrong. The natural shape is
  "create muxer at the start, write samples as they come" — that's
  WRONG because `addTrack` needs the format-with-CSD that only
  appears after the first encoder output.
- `mEncoderOutputVideoFormat` is captured by `VideoTrackConverter`
  when it sees `INFO_OUTPUT_FORMAT_CHANGED` from the encoder. Same
  pattern for audio.
- The `setMuxer(muxer)` calls give each track converter the muxer +
  the track index they should write to.

---

## ../transcoder/videoconverter/VideoTrackConverter.java:144-146
**Output dimension alignment to multiple of 16** _(SPEC §9.4)_

```java
outputHeight = (outputHeight + 7) & ~0xF;
outputWidth  = (outputWidth  + 7) & ~0xF;
```

Bit-twiddle: round up to next multiple of 16. The `+ 7` is a slight
bias (rounds halfway-cases up rather than down).

Many encoders require width/height to be multiples of 16 (macroblock
alignment for H.264). iPhone playback also reportedly chokes on
non-multiple-of-16 dimensions for some sources.

**Phase-3 implementation notes:**
- Port into `internal/OutputDimensions.kt` as a tiny helper.
- Make sure this happens AFTER short-edge scaling, not before
  (otherwise the aspect ratio gets distorted by a few percent).

---

## ../transcoder/videoconverter/VideoTrackConverter.java:129-134
**Display-dimension preference over coded dimension** _(SPEC §9.5)_

```java
final int width = inputVideoFormat.containsKey(MEDIA_FORMAT_KEY_DISPLAY_WIDTH)
                  ? inputVideoFormat.getInteger(MEDIA_FORMAT_KEY_DISPLAY_WIDTH)
                  : inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH);
final int height = inputVideoFormat.containsKey(MEDIA_FORMAT_KEY_DISPLAY_HEIGHT)
                   ? inputVideoFormat.getInteger(MEDIA_FORMAT_KEY_DISPLAY_HEIGHT)
                   : inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT);
```

Note: `MEDIA_FORMAT_KEY_DISPLAY_WIDTH` is a string literal
`"display-width"` — there is no public `MediaFormat.KEY_DISPLAY_WIDTH`
constant. (Defined by Signal as their own string constant.)

For anamorphic content (where coded width ≠ display width — e.g.
720x480 NTSC stretched to 853x480 on display), `KEY_DISPLAY_WIDTH` is
what the user actually sees. Encoding against the coded dimension
gives the wrong aspect ratio.

Rare in mobile-captured content but real for shared media.

**Phase-3 implementation notes:**
- Port the same fallback chain. Use the string literal directly;
  there's no public constant.

---

## ../transcoder/videoconverter/VideoTrackConverter.java:228-244
**Trim EOS at extractor side** _(SPEC §9.10)_

```java
mVideoExtractorDone = size < 0 || (mTimeTo > 0 && presentationTime > mTimeTo * 1000);

if (mVideoExtractorDone) {
    mVideoDecoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0,
        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
} else {
    mVideoDecoder.queueInputBuffer(decoderInputBufferIndex, 0, size,
        presentationTime, mVideoExtractor.getSampleFlags());
}
mVideoExtractor.advance();
```

When the next extractor sample's PTS exceeds `mTimeTo * 1000` (μs),
don't feed it — instead queue a zero-length EOS buffer to the decoder.
The decoder drains its remaining frames; encoder finishes its work;
muxer writes the EOS marker.

`mTimeTo` here is in milliseconds; sample PTS is in microseconds —
hence the `* 1000`. (Signal's `setTimeRange` takes milliseconds for
historical reasons.)

**Phase-3 implementation notes:**
- We take μs in our `TrimRange.endMs * 1000`; just compare directly.
- Audio side does the same dance in `AudioTrackConverter`.

---

## ../transcoder/videoconverter/utils/MediaCodecCompat.kt:249-285
**HDR detection on input format** _(SPEC §9.6, §11)_

```kotlin
fun isHdrVideo(format: MediaFormat): Boolean {
    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
        val colorTransfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        if (colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            colorTransfer == MediaFormat.COLOR_TRANSFER_HLG) return true
        // Anything outside the known SDR set: treat as HDR.
        // Devices report non-standard values like 65791 for HDR.
    }
    if (format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) return true
    if (Build.VERSION.SDK_INT >= 29 && format.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)) return true
    // HEVC profile fallback
    if (format.containsKey(MediaFormat.KEY_PROFILE)) {
        val profile = format.getInteger(MediaFormat.KEY_PROFILE)
        if (profile == CodecProfileLevel.HEVCProfileMain10HDR10 ||
            profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus) return true
    }
    return false
}
```

Four detection signals: explicit color transfer (PQ/HLG), HDR10
static metadata presence, HDR10+ dynamic metadata presence (API 29+),
HEVC profile fallback (for older extractors that don't populate the
color/metadata keys).

**Phase-3 implementation notes:**
- Port verbatim into `internal/PreflightProbe.kt`.
- The non-standard `KEY_COLOR_TRANSFER` heuristic (treat anything
  not-known-SDR as HDR) is empirical from Signal — keep it; the
  alternative is silently mis-transcoding HDR as SDR.
- Used by both the preflight short-circuit (we don't pass HDR
  through unchanged; always re-encode for SDR receivers) and by
  the per-attempt decoder config in `DecoderConfig.kt`.

---

## ../transcoder/videoconverter/VideoTrackConverter.java:490-564
**HDR tone-mapping request + per-codec fallback** _(SPEC §11)_

Per-decoder-candidate loop. For HDR + API 31+:

```java
final boolean requestToneMapping = Build.VERSION.SDK_INT >= 31 && isHdr;
for (Pair<String, MediaFormat> candidate : candidates) {
    decoder = MediaCodec.createByCodecName(codecName);
    if (requestToneMapping) {
        try {
            final MediaFormat toneMapFormat = new MediaFormat(baseFormat);
            toneMapFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                                     MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            decoder.configure(toneMapFormat, surface, null, 0);
            decoder.start();
            mToneMapApplied = isToneMapEffective(decoder, codecName);  // verify!
            mDecoderName = codecName;
            return decoder;
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Codec doesn't accept the key — release + recreate, then fall through to plain configure
            decoder.release();
            decoder = MediaCodec.createByCodecName(codecName);
        }
    }
    decoder.configure(baseFormat, surface, null, 0);  // plain — degraded HDR passthrough
    decoder.start();
    return decoder;
}

if (mIsHdrInput) throw new HdrDecoderUnavailableException(...);
throw new CodecUnavailableException(...);
```

Critical order: the tone-map attempt is tried PER CODEC. If codec X
rejects the request, fall through to plain configure on codec X
(produces degraded output but at least works). Only after all
candidates fail entirely do we throw.

**Phase-3 implementation notes:**
- Port this loop into `internal/DecoderConfig.kt`.
- Pre-API-31 path: just configure normally; let HDR pixels flow
  through to SDR encoder. Result: wrong colors, playable. Matches
  Signal. v2 could add a GL fragment-shader tone-map for pre-API-31
  — out of scope for v1.
- Note Signal's per-codec `release()` + `createByCodecName()` after
  rejection — needed because `decoder.configure()` failing leaves
  the decoder in a partially-initialized state where retrying
  config doesn't work cleanly.

---

## ../transcoder/videoconverter/VideoTrackConverter.java:670-700
**isToneMapEffective — verify the codec actually tone-maps** _(SPEC §11)_

After `decoder.configure(toneMapFormat, ...)` + `decoder.start()`,
check that the request actually took effect:

```kotlin
private fun isToneMapEffective(decoder: MediaCodec, codecName: String): Boolean {
    // 1. Software codecs never tone-map. Check via MediaCodecInfo.
    val info = decoder.codecInfo
    if (info.isSoftwareOnly /* API 29+ */) {
        Log.w(TAG, "Video decoder: software codec $codecName cannot tone-map")
        return false
    }
    // 2. Read the decoder's actual output format. If transfer is still ST2084/HLG,
    //    the codec accepted the request without honoring it.
    val outputFormat = decoder.outputFormat
    if (outputFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
        val transfer = outputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) {
            Log.w(TAG, "Video decoder: $codecName accepted tone-map request but output is still HDR ($transfer)")
            return false
        }
    }
    return true
}
```

This is real device-quirk learning — some vendor codecs accept
`KEY_COLOR_TRANSFER_REQUEST` without complaint but produce HDR
output anyway. Without this verify, the encoder would receive HDR
pixels (silently degraded output) on those devices.

**Phase-3 implementation notes:**
- Port nearly verbatim.
- When the verify fails, the candidate codec is excluded for THIS
  transcode attempt — try the next codec. (Signal does this by
  returning the decoder anyway with `mToneMapApplied = false`; we
  should be stricter and reject — try next candidate.)
- `MediaCodecInfo.isSoftwareOnly()` is API 29+. For 27-28, name
  prefix heuristic (`"OMX.google."`, `"c2.android."`) — see Signal's
  fallback in MediaCodecCompat.

---

## ../transcoder/StreamingTranscoder.java:79
**The "already optimal" condition Signal uses** _(SPEC §7)_

```java
this.transcodeRequired =
        inputBitRate >= targetQuality.getTargetTotalBitRate() * 1.2
     || inSize > upperSizeLimit
     || containsLocation(mediaMetadataRetriever)  // ← we already dropped this
     || options != null
     || !isH264(dataSource);
```

We dropped the `containsLocation` clause earlier (`Mp4Sanitizer` was a
no-op stub, so the re-encode it forced was pure waste).

**Phase-3 implementation notes:**
- Our equivalent is in `internal/PreflightProbe.kt`. The 1.2× margin
  is empirical — gives ~20% slack so we don't re-encode files that
  are "close enough" to target.
- Drop `containsLocation`. EXIF location is handled separately by
  `Mp4LocationStripper` outside the transcoder.
- Drop `upperSizeLimit` — not in our API.

---

## ../transcoder/videoconverter/MediaConverter.java:358-371
**Progress calculation** _(SPEC §2 onProgress contract)_

```java
final int curPercentProcessed = (int) (100 *
    (Math.max(videoTrackConverter == null ? 0 : videoTrackConverter.mMuxingVideoPresentationTime,
              audioTrackConverter == null ? 0 : audioTrackConverter.mMuxingAudioPresentationTime)
     - timeFromUs) / (timeToUs - timeFromUs));

if (curPercentProcessed != percentProcessed) {
    percentProcessed = curPercentProcessed;
    mCancelled = mCancelled || mListener.onProgress(percentProcessed);
}
```

Progress = `(latestMuxedPts - trimStartUs) / (trimEndUs - trimStartUs)`,
in percent. Only emits when the integer percent value changes — so
fires ~100 times over a full transcode (less for short clips).

**Phase-3 implementation notes:**
- Port the integer-percent debouncing. Our public API exposes
  `Float`, so divide by 100f at the boundary.
- The "use max of video/audio" is intentional: muxer write order is
  per-track and they don't advance lock-step. Max is the better
  visual approximation of "how done are we".
- Cancellation: Signal piggybacks on the return value of `onProgress`
  (callback returns true to cancel). We use coroutine cancellation
  instead, so our `onProgress` is just `(Float) -> Unit`.

---

## ../transcoder/videoconverter/MediaConverter.java:240-279
**Resource teardown order in `finally`** _(SPEC §5 lifecycle)_

The `finally` block releases video converter, audio converter, then
muxer — even if any of them throws during release. The first exception
encountered is captured to re-throw; later exceptions are logged but
swallowed. The muxer is stopped (if it was started and not already
stopped) before release.

**Phase-3 implementation notes:**
- Single `try/finally` in `transcode()`. Use `runCatching` per resource
  to mimic the "swallow secondary exceptions" pattern, then re-throw
  the first.
- Order matters: encoders must be stopped before the muxer's `stop()`
  (which finalizes the MP4 trailer). Decoders can be released after
  encoders.
- `mMuxer.stop()` actually writes the moov atom — calling it on a
  muxer that never had any samples written throws. Track
  `muxerStarted` like Signal does.
