# transcoder_v2 — Dormant

**Status: dormant since 2026-05-18.** Production `FFmpegUtils.compressVideo`
on Android now calls ffmpeg-kit directly (see
`homebase-api/src/androidMain/kotlin/id/homebase/api/video/FFmpegUtils.android.kt`).
This tree preserves the v2 MediaCodec transcoder + tests + spec for possible
future revival.

Nothing in this directory is part of any Gradle source set — the entire
`archive/` tree is invisible to the build. `git grep` and IDE search still
find it; nothing imports from it; nothing compiles it.

---

## Why dormant

After ~3k LOC of work (GL surface bridge, AAC pass-through, HDR support,
trim PTS normalisation), real-device benchmarks against the prior
ffmpeg-kit `StreamingTranscoder` path showed v2 was **not the dramatic
win we expected**. Summary:

| Workload | v2 | ffmpeg-kit (StreamingTranscoder, deleted in `cf52bc18`) | Signal |
|---|---|---|---|
| 10s landscape 1080p → STANDARD | 8s / 3.1 MB | 9s / 3.5 MB | — |
| 40s portrait 1080p → STANDARD | 30s + 9.5s segment = 39.5s / 18.1 MB | 30s total / 19.6 MB | 30s / 7 MB (downscaled to 480p) |

v2 is faster + smaller on short content but slower on long portrait content
(the CBR rate-control tax — Signal accepted the same tradeoff in their
v7.11.2). The marginal wins (HDR support, predictable file sizes, no
third-party dep dream) don't justify ongoing maintenance of ~3k LOC.

Decision: ship the simpler ffmpeg-kit path. Preserve v2 in case priorities
shift later (HDR telemetry, battery-cost data on hardware H.264 encoders,
HEVC migration, etc).

---

## What's in here

```
archive/transcoder_v2/
├── README.md                              (this file)
├── SPEC.md                                (frozen spec + GL amendment + Appendix C gaps)
├── signal-reference-notes.md              (design rationale cross-refs, Signal-Java citations)
├── src/
│   ├── androidMain/kotlin/id/homebase/api/video/transcoder_v2/
│   │   ├── HomebaseVideoTranscoder.kt     (public API + Result + TrimRange)
│   │   ├── TranscodeException.kt          (4-class hierarchy)
│   │   └── internal/
│   │       ├── AudioPair.kt               (audio decode→encode + AAC pass-through factory)
│   │       ├── AudioRemuxer.kt            (pass-through AudioTrack impl)
│   │       ├── AudioTrack.kt              (sealed interface)
│   │       ├── CodecSelection.kt          (REGULAR→ALL dedupe + exclusion)
│   │       ├── DecoderConfig.kt           (HDR tone-map dance + verify)
│   │       ├── OutputDimensions.kt        (short-edge scale + mult-of-16 rounding)
│   │       ├── PreflightProbe.kt          (already-optimal check + HDR detection)
│   │       ├── QualityProfile.kt          (VideoQuality → bitrate/dim)
│   │       ├── TranscodePump.kt           (sync pump + lazy muxer + watchdog)
│   │       ├── VideoPair.kt               (video decode→GL→encode + factory)
│   │       └── gl/
│   │           ├── InputSurface.kt        (EGL14 window-surface wrapper for encoder)
│   │           ├── OutputSurface.kt       (SurfaceTexture wrapper for decoder)
│   │           └── TextureRender.kt       (pass-through GLES 2.0 shader)
│   └── androidDeviceTest/kotlin/id/homebase/api/video/
│       ├── CompressVideoV2InstrumentedTest.kt  (6 v2-specific tests + helpers)
│       └── CompressVideoBenchmarkTest.kt       (ffmpeg-kit vs v2 benchmark harness, @Ignored)
└── test-fixtures/
    ├── bbb_1080p_2mb.mp4                   (1080p 10s, video-only, MP4 recompress fixture)
    ├── bbb_1080p_10mb.mp4                  (1080p 10s high-bitrate, HLS routing fixture)
    └── hdr10_720p.mp4                      (HEVC HDR10 / PQ 720p, from androidx/media Apache-2.0)
```

The `transcoder/` tree that was vendored from Signal-Android (StreamingTranscoder
+ Signal's MediaConverter + GL bridge + faststart post-processor) was deleted
in commit `cf52bc18` BEFORE this archive existed — it lives in git history if
ever needed, not here.

---

## How to revive

Five mechanical steps:

1. **Move source back**:
   ```
   git mv archive/transcoder_v2/src/androidMain/kotlin/id/homebase/api/video/transcoder_v2 \
          homebase-api/src/androidMain/kotlin/id/homebase/api/video/
   ```

2. **Move tests back**:
   ```
   git mv archive/transcoder_v2/src/androidDeviceTest/kotlin/id/homebase/api/video/CompressVideoV2InstrumentedTest.kt \
          homebase-api/src/androidDeviceTest/kotlin/id/homebase/api/video/
   git mv archive/transcoder_v2/src/androidDeviceTest/kotlin/id/homebase/api/video/CompressVideoBenchmarkTest.kt \
          homebase-api/src/androidDeviceTest/kotlin/id/homebase/api/video/
   ```

3. **Move fixtures back**:
   ```
   git mv archive/transcoder_v2/test-fixtures/bbb_1080p_2mb.mp4 \
          homebase-api/src/androidDeviceTest/assets/test_videos/
   git mv archive/transcoder_v2/test-fixtures/bbb_1080p_10mb.mp4 \
          homebase-api/src/androidDeviceTest/assets/test_videos/
   git mv archive/transcoder_v2/test-fixtures/hdr10_720p.mp4 \
          homebase-api/src/androidDeviceTest/assets/test_videos/
   ```

4. **Re-wire `FFmpegUtils.compressVideo`** in
   `homebase-api/src/androidMain/kotlin/id/homebase/api/video/FFmpegUtils.android.kt`
   to delegate to `HomebaseVideoTranscoder.transcode(...)`. Look at the git
   commit that archived v2 — the prior version of that function shows the
   exact delegation pattern (try/catch on `TranscodeException`, `Result`
   sealed-type handling, EXIF strip on the `AlreadyOptimal` branch).

5. **Verify**:
   ```
   ./gradlew :homebase-api:compileAndroidMain
   ./gradlew :homebase-api:connectedAndroidDeviceTest \
     -Pandroid.testInstrumentationRunnerArguments.class=id.homebase.api.video.CompressVideoV2InstrumentedTest
   ```

If Android API levels have shifted significantly since dormancy
(2026-05-18), expect some friction around:
- `MediaCodecInfo.isSoftwareOnly()` / `isHardwareAccelerated()` API changes
- New HDR profiles or color-transfer enum values
- GL EGL14 deprecations
- `MediaCodec.BufferInfo.set(...)` signature drift

---

## What was working at time of dormancy

- All 10 instrumented tests passed on `Medium_Phone_API_36.0` emulator
  (6 v2-specific + 4 production-style that have since been split between
  this archive's `CompressVideoV2InstrumentedTest` and the main
  `CompressVideoAndroidInstrumentedTest`).
- **Real-device CBR correctness verified**: 3.1 MB output on a real phone
  for a 10s 1080p clip at STANDARD (target 2.5 Mbps × 10s ≈ 3 MB); the
  emulator's `c2.goldfish.avc.encoder` ignores CBR and produces ~6.3 MB
  for the same workload, so the emulator can't validate this directly —
  hardware encoders honour the flag.
- **HDR controlled-failure path verified** on emulator: software-only HEVC
  decoder → `isToneMapEffective` rejects → `HdrDecoderUnavailableException`
  → FFmpegUtils returns null. Defensive (better than producing colour-broken
  output).

The HDR happy path (hardware tone-mapping → clean SDR output) was never
verified on a real device because we lacked an HDR-capable test phone at
the time. A future reviver should run `CompressVideoV2InstrumentedTest`
on a modern phone with hardware HEVC + add a visual check.

---

## Known gaps at dormancy

Cribbed from `SPEC.md` Appendix C:

- **No box-filter shader** for aggressive downscales (4K → 480p).
  `TextureRender.changeFragmentShader(String)` hook is in place; a future
  contributor can wire Signal's `createFragmentShader` (see
  `signal-reference-notes.md` for the line citation in the deleted
  vendored tree) in ~50 LOC.
- **Spatial-video / vendor-codec retry path** plumbed (the "frame counts
  should match" string + `android.media.MediaCodec` stack-frame detector
  in `HomebaseVideoTranscoder.isRetryableMidStreamFailure`) but never
  exercised by a fixture. First user report from a device that hits this
  is the canary.
- **Anamorphic source aspect ratio**: we probe display dimensions but
  encode at coded dimensions. Rare in mobile-captured content; common in
  ripped media.
- **Multi-audio-track preservation**: collapses to first track. Same as
  Signal; not a regression.
- **Faststart MOOV-before-MDAT**: not needed for current downstream paths
  (HLS .ts + full-download MP4). Would matter if we ever serve MP4s via
  HTTP progressive download.
- **HEVC for HIGH preset**: deliberately H.264-only. Revisit when
  receiver-side telemetry justifies HEVC compatibility risk.

---

## Future re-evaluation triggers

Re-open this archive if any of the following becomes true:

1. **HDR uploads start mattering** — telemetry shows >X% of users sending
   HDR videos and our ffmpeg path produces visibly-wrong colours that
   prompt support tickets.
2. **Battery cost becomes a concern** — telemetry shows large videos
   draining the user's battery during upload; hardware H.264 encoders
   use orders of magnitude less power than libx264.
3. **HEVC migration** — when receiver-side compatibility is broad enough
   to justify HEVC output, MediaCodec's hardware HEVC encoder is the only
   sensible path (libx265 in ffmpeg-kit is slow + power-hungry on phones).
4. **Predictable file sizes become critical** — if storage/bandwidth caps
   start mattering more than encode speed, CBR (which the ffmpeg path
   approximates but doesn't enforce strictly) becomes a meaningful win.
5. **GL bridge for other features** — if we ever need real-time video
   effects (filters, overlays, custom rotations beyond `tkhd`), the GL
   surface bridge is the foundation.

Until then, ffmpeg-kit's libx264 is good enough and one less moving part
to maintain.

---

## Notes on the archive

- Repo size: this tree adds ~20 MB of binary test fixtures
  (`test-fixtures/*.mp4`). Source files are tiny. If repo size ever
  becomes a concern, candidate to migrate to Git LFS — but Signal-Android
  ships their similar fixtures inline, so it's not crazy as-is.
- The `transcoder/` tree (deleted Signal-vendored ~5,500 LOC) is NOT
  here. It's available in git history at `cf52bc18^` if a future
  re-evaluation needs the original Signal reference material.
- This README and `SPEC.md` together are the load-bearing docs;
  `signal-reference-notes.md` is the supporting design-rationale
  document.
