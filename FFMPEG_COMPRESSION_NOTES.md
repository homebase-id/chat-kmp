# FFmpeg compressVideo — notes & baseline benchmarks

Reference notes for `FFmpegUtils.compressVideo` across the live platforms
(Android, iOS, Desktop JVM). Pairs with the
`FfmpegCompressPlanner` in
`homebase-api/src/commonMain/kotlin/id/homebase/api/video/FfmpegCompressPlanner.kt`
and the manual benchmark test at
`homebase-chat/src/jvmTest/kotlin/id/homebase/api/video/FFmpegCompressBaselineJvmTest.kt`.

## Architecture (post-planner refactor)

```
                FfmpegCompressPlanner (commonMain)
                ─────────────────────────────────
                  - QualityTargets per VideoQuality
                  - isAlreadyOptimal predicate
                  - computeOutputDims (down-scale)
                  - buildFfmpegArgs
                  → FfmpegCompressPlan(args, skip)
                              │
                              │ pure function — no I/O, no platform deps
                              │
        ┌─────────────────────┼──────────────────────┐
        │                     │                      │
   Android actual         iOS actual            JVM actual
   MediaExtractor          Swift bridge          ffprobe
   probe → FFmpegKit       probe → FFmpegKit     probe → ProcessBuilder
   `executeWithArgumentsAsync`  HW(h264_videotoolbox)→SW(libx264) fallback
```

Each platform actual is a thin probe-and-invoke wrapper. All
quality-mapping, already-optimal predicate, output-dim math, and arg-list
assembly lives in commonMain — one source of truth.

## VideoQuality → target mapping

| Quality | Short-edge | Video bitrate | Audio bitrate |
|---------|-----------|---------------|---------------|
| LOW      | 480 px  | 1.25 Mbps | 128 kbps |
| STANDARD | 720 px  | 2.5 Mbps  | 128 kbps |
| HIGH     | 1080 px | 5.0 Mbps  | 192 kbps |

All platforms use `libx264 -preset veryfast` (iOS additionally tries
`h264_videotoolbox` first; that encoder ignores `-preset`).

## Baseline benchmarks — Desktop JVM, Windows

Captured **2026-05-18** on Windows laptop, single-run, against
`archive/transcoder_v2/test-fixtures/bbb_1080p_2mb.mp4` (1920×1080
H.264, ~2 MB) with `trim=(0, 5000 ms)` to force a real re-encode.

| Branch | Quality | Output | Wall time |
|--------|---------|--------|-----------|
| **main** (pre-planner) | hardcoded 1280px / 3000k / preset fast | **1772 KB** | **1658 ms** |
| `archive-android-video-v2` | LOW (480p / 1250k / veryfast) | 752 KB | 887 ms |
| `archive-android-video-v2` | **STANDARD** (720p / 2500k / veryfast) | **1505 KB** | **1147 ms** |
| `archive-android-video-v2` | HIGH (1080p / 5000k / veryfast) | 2931 KB | 1540 ms |

### Apples-to-apples (main vs PR STANDARD)

Both produce 1280×720 output (a 1920×1080 input gets downscaled to the
same dims under main's `MAX_WIDTH=1280` rule and under STANDARD's 720p
short-edge rule). Only differences: bitrate target and preset.

|                | Output  | Wall time | Args |
|----------------|---------|-----------|------|
| main           | 1772 KB | 1658 ms | `-b:v 3000k -preset fast` |
| PR STANDARD    | 1505 KB | 1147 ms | `-b:v 2500k -preset veryfast` |
| Δ              | −15%    | −31%    |  |

PR is **15% smaller and 31% faster** on the equivalent path. Both gains
are deliberate spec-frozen choices:

- `veryfast` vs `fast` trades ~0.3 dB PSNR for materially faster
  encoding (visually indistinguishable on phone-recorded content) and
  unifies with the rest of the codebase, which already uses `veryfast`
  everywhere.
- 2500k vs 3000k at 720p is comfortably above the visual threshold;
  matches Signal's bitrate budget for the same quality tier.

### Per-quality dynamic range (planner-only)

| Quality  | Output  | vs main  |
|----------|---------|----------|
| LOW      |  752 KB | −58%     |
| STANDARD | 1505 KB | −15%     |
| HIGH     | 2931 KB | +65%     |

Output size and wall time both scale monotonically with quality — the
planner correctly honors the enum on JVM. main couldn't express any of
these choices (no `quality` parameter; hardcoded constants).

## Re-running the benchmark

The test is `@Ignore`'d so it doesn't run on every JVM test pass.

1. Open `FFmpegCompressBaselineJvmTest.kt` and comment out the `@Ignore`
   annotation (or invoke the methods directly from the IDE — IDEA's
   "run individual test" bypasses `@Ignore` on demand).
2. Run:

   ```bash
   ./gradlew :homebase-chat:jvmTest --tests "*FFmpegCompressBaselineJvm*" --info \
     2>&1 | grep "BASELINE " > /tmp/baseline-<label>.txt
   ```

3. Compare across branches / ffmpeg versions:

   ```bash
   git stash; git checkout <other-branch>
   ./gradlew :homebase-chat:jvmTest --tests "*FFmpegCompressBaselineJvm*" --info \
     2>&1 | grep "BASELINE " > /tmp/baseline-<other>.txt
   diff /tmp/baseline-<label>.txt /tmp/baseline-<other>.txt
   ```

Three test methods (one per VideoQuality) keep each ffmpeg invocation in
its own subprocess, which matches production usage (one
`compressVideo` call per video send).

### Tips when comparing ffmpeg upgrades (e.g. 6 → 8)

- Run multiple times and take medians — single-run wall time has
  ±10-20% noise on consumer hardware due to subprocess startup, OS
  scheduling, and thermal throttling on sustained loops.
- The output bytes are deterministic (libx264 is reproducible at the
  same preset+bitrate for the same input) — any byte-count delta
  between ffmpeg versions IS a real change, not noise.
- If a new ffmpeg version produces materially larger output at the same
  preset/bitrate, suspect a default change in encoder tuning
  (`-tune`, `-x264-params`) and re-pin those explicitly if needed.

## Android instrumented baselines

The Android side has parallel `compressVideo_baselineLow / Standard / High`
methods in
`homebase-api/src/androidDeviceTest/kotlin/id/homebase/api/video/CompressVideoAndroidInstrumentedTest.kt`.
They're affected by the same FFmpegKit `libx264` same-process re-entry
limitation — back-to-back baseline runs in a single test process may
crash. Run one quality at a time with `--tests "*baselineStandard*"` if
needed.

## Known constraints

- **FFmpegKit `libx264` same-process re-entry**: invoking `libx264`
  multiple times in the same JVM process (Android instrumented or JVM
  tests) can crash on the second invocation. `executeWithArgumentsAsync`
  helps but doesn't fully resolve it. JVM Desktop uses subprocess
  isolation (each call spawns a fresh ffmpeg binary), so it doesn't hit
  this on the production path.
- **iOS `h264_videotoolbox` preset**: the hardware encoder ignores
  `-preset`. The planner emits `-preset` only for `libx264`.
- **iOS `executeFFmpeg(String)` quoting**: takes a single string and
  pseudo-shell-quotes. The planner emits no special characters in any
  arg today (paths are cacheDir-controlled, numbers, simple flags).
  Naive `if (' ' in it) "\"$it\""` handles spaces. Anything with quotes
  or `$` would break — same risk as before this refactor.
- **No HEVC encoder by default**: planner targets `libx264` everywhere
  for cross-platform decode compatibility. Adding HEVC means writing a
  separate planner path (HEVC bitrate budget is ~30% lower for the same
  visual quality) — not in scope today.
