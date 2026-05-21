# ffmpeg-kit n8.1.1 — iOS validation handoff

This document is for the macOS engineer validating PR
[#558 (upgrade/ffmpeg-kit-n8.1.1)](https://github.com/homebase-id/chat-kmp/pull/558)
on the iOS side. The Android side is fully validated (8/8 instrumented
tests pass on the API 36 emulator). We have **zero iOS-side
instrumented tests** for the video pipeline — manual validation is
required before this PR can land.

This is a binary upgrade of the bundled FFmpeg from **n7.1.3 → n8.1.1
LTS**. The iOS deliverable is the regenerated xcframework at
`homebase-api/libs/ffmpegkit-bundled.xcframework/`. It was built by
the same homebase-id/ffmpeg-kit `upgrade/ffmpeg-8.x` workflow that
produced the Android AAR — same source, same C-items.

## TL;DR — three things to verify

1. **Build the iOS app from this branch and it doesn't crash** on app
   launch or first video upload. The xcframework swap touched 727 files
   but the API surface is unchanged.
2. **Video compression progress ring goes 0% → 100%** during an upload
   (not stuck at 0%). This is the C5 wiring fix — see "Progress UI" below.
3. **Settings → Help shows `ffmpeg` line with a non-empty version
   number**, ideally something like `n8.1.1`. This is the
   `FFmpegKitConfig.getFFmpegVersion()` rewire — see "Version line" below.

If all three are green, comment "iOS LGTM" on the PR. If any fails,
follow the troubleshooting sections below and capture the relevant
logs in the PR.

## What changed for iOS in this PR

### `homebase-api/libs/ffmpegkit-bundled.xcframework/`

Wholesale replacement with the n8.1.1 build:
- 8 sub-xcframeworks (ffmpegkit, libavcodec, libavdevice, libavfilter,
  libavformat, libavutil, libswresample, libswscale). `libpostproc`
  is gone — FFmpeg 8 removed it from mainline.
- Three slices per sub-xcframework: `ios-arm64` (thin arm64 device,
  no `_arm64e` — kernel pointer-auth slice isn't needed for user-space
  apps), `ios-arm64_x86_64-maccatalyst`, `ios-arm64_x86_64-simulator`.
- **Unsigned.** The bundled framework has no `_CodeSignature/`
  directories. Xcode's "Sign on Copy" at the Embed Frameworks phase
  re-signs with the app's identity. This is by design — see
  `homebase-id/ffmpeg-kit` CUSTOMIZATION.md B1.
- ~10 MB smaller than n7 (85 MB vs 95 MB) thanks to libpostproc
  removal and the thin device slice.

### `iosApp/iosApp/FFmpegKitBridgeImpl.swift`

`getFfmpegVersionBanner()` rewritten to use `FFmpegKitConfig.getFFmpegVersion()`
directly (synthesizes a one-line banner so the existing Kotlin parser
still works). The previous implementation called
`FFmpegKit.execute("-version")` which doesn't work on n8 (more on this
under "Version line").

No other Swift / iOS Kotlin files changed.

## What did NOT change for iOS

- No Kotlin actuals were edited beyond what was already in
  `chat-kmp@7dd31298` (the progress-wiring commit).
- No Pod / SPM / Xcode project changes.
- No iOS-specific build script changes.

The C-items (C1, C2, C4, C5 v3, C6, C10, C11) and U-items (U20–U25)
that fix n8 issues all live in shared `fftools_*.c` files and the
ffmpeg-kit wrapper layer — they ride into the iOS xcframework without
any iOS-side code change.

## Step 0 — Confirm you have the right xcframework

```bash
cd chat-kmp
git fetch origin upgrade/ffmpeg-kit-n8.1.1
git checkout upgrade/ffmpeg-kit-n8.1.1

# Sanity-check the ffmpegkit binary is the n8 build (no signatures):
find homebase-api/libs/ffmpegkit-bundled.xcframework/ffmpegkit.xcframework \
  -name '_CodeSignature' -type d
# ↑ Should print nothing. If it prints anything, you've got the wrong build.

# Sanity-check the device slice is thin arm64:
file homebase-api/libs/ffmpegkit-bundled.xcframework/ffmpegkit.xcframework/ios-arm64/ffmpegkit.framework/ffmpegkit
# ↑ Should report: "Mach-O 64-bit dynamically linked shared library arm64"
#                  (the "universal binary with 1 architecture" wrapper is fine)
```

## Step 1 — Open in Xcode and build

```bash
open iosApp/iosApp.xcworkspace        # or .xcodeproj, whichever your setup uses
```

In Xcode:
1. Select an iPhone Simulator target (any arm64 / x86_64 simulator is fine).
2. Product → Build. Should succeed in 2–4 minutes (clean build will
   re-process the 177 MB xcframework — slower than usual).

**If build fails with `codesign` errors on `ffmpegkit.framework` or any
`libav*.framework`:** the unsigned xcframework requires that
"Sign on Copy" be enabled at the Embed Frameworks phase. Verify
`homebase-api`'s xcconfig or build settings haven't been changed.
The previous n7 setup worked this way, so this shouldn't have
regressed — but if it has, restore the embed-and-sign setting.

**If build fails with `Undefined symbols`** (especially anything
referencing `libpostproc`): something didn't get the memo about
postproc being removed in n8. Grep the iOS project files for
`libpostproc`, `-framework libpostproc`, or `pp_filter` and remove
those references. Should not be necessary — chat-kmp has no
references to libpostproc in its iOS sources.

## Step 2 — Launch the app and run the manual flow

On the simulator (or device, your call):

1. Sign in / open any chat thread.
2. Attach a short video clip (≤30 s ideally). The video icon in the
   attachment picker.
3. **Watch the progress ring in the message bubble.** It should:
   - Start at 0%.
   - Update visibly during compression (you should see it tick up at
     ~1–2 Hz, matching ffmpeg's internal progress cadence).
   - Reach ~100% before the upload phase begins.
   - For HLS uploads, then start again at 0% during segmentation,
     also ticking up to ~100%.

   **If the ring stays at 0% throughout compression:** the C5 wiring
   regressed. Run the troubleshooting steps in "Progress UI debugging"
   below. This is the single highest-value check in this PR.

4. **After the upload succeeds, navigate to Settings → Help.**
   Look for the line that displays the bundled FFmpeg version. It
   should show something like `ffmpeg n8.1.1` or `FFmpeg version: n8.1.1`
   (the exact UI string depends on your Settings → Help screen — find
   the existing label and verify the version field is non-empty).

   **If it shows `null`, blank, or the old version (`n7.1.3`):** the
   version rewire didn't land. Verify
   `iosApp/iosApp/FFmpegKitBridgeImpl.swift` on the branch you're
   building. Search for `FFmpegKitConfig.getFFmpegVersion()` — that
   call must be there.

5. (Optional but recommended.) Try a longer video (1+ min) to flush
   out any re-entry issues. Then attach a SECOND video without
   restarting the app. C11 (var_cleanup) is supposed to handle
   back-to-back encodes; verify both succeed without crash.

## Step 3 — Optional: add an iOS XCTest mirror

Android has 8 instrumented tests in
`homebase-api/src/androidDeviceTest/.../CompressVideoAndroidInstrumentedTest.kt`
that exercise:
- 5 quality presets (low / standard / high / standardOptimal / withTrim)
- 1 `h264_mediacodec` smoke test (HW path — Android-specific)
- 1 progress-stats regression test (catches future C5 regressions)
- 1 version-string regression test (catches future version-API regressions)

iOS has no equivalent. Consider adding:
- `compressVideoIosTest.swift` with at least the equivalents of
  `compressVideo_baselineStandard` (simple libx264 transcode),
  `compressVideo_emitsProgressStatistics` (verify the StatisticsCallback
  fires on iOS — the bug we just fixed could regress here), and
  `ffmpegVersion_isReportedFromBundledBuild` (`FFmpegKitConfig.getFFmpegVersion()`
  returns non-null and matches a release tag pattern).

This isn't gating for this PR — it's a follow-up. The Android tests
cover the same shared `fftools_ffmpeg.c` code path, so a regression
there would be caught.

## Progress UI debugging

If the progress ring stays at 0% during compression:

1. Open Console.app, filter by your app's process name, attach the
   simulator/device.
2. Trigger the video upload.
3. Look for these signals:

   **Good signal — progress is firing:**
   ```
   ffmpeg-kit: ffmpeg version n8.1.1
   ...
   ffmpeg-kit: frame=  120 fps= 38.4 q=22.0 size=     128kB time=00:00:04.00 ...
   ```
   The `frame=...time=...` lines mean `print_report()` is running. If
   you see those but the UI stays at 0%, the issue is on the Swift
   side — `executeFFmpegAsync` may not be wiring its
   `statisticsCallback` correctly.

   **Bad signal — process is dead:**
   ```
   SIGSEGV / SIGABRT in libffmpegkit
   ```
   Capture the crash. The most likely culprit is something n8-related
   in the FFmpeg library set; report on the PR with the tombstone.

4. Spot check the C5 wiring on the Swift side:
   ```swift
   // iosApp/iosApp/FFmpegKitBridgeImpl.swift
   FFmpegKit.executeAsync(
       command,
       executeCallback: { ... },
       logCallback:      nil,
       statisticsCallback: { stats in ... }   // ← this MUST be passed
   )
   ```
   If `statisticsCallback` is nil or missing, that's the regression.

5. If logging shows the `print_report` lines but the
   `statisticsCallback` lambda is never invoked, that's the case where
   the iOS xcframework you're using does NOT contain the C5 v3 wiring.
   Re-confirm Step 0 — you might have an older cached xcframework
   somewhere in Xcode's DerivedData. `rm -rf ~/Library/Developer/Xcode/DerivedData/<your project>*`
   and rebuild.

## Version line debugging

If Settings → Help shows the version line as blank/null:

1. Open Console.app, filter by your app.
2. Restart the app, navigate to Settings → Help.
3. The actual JNI call happens via the bridge — you should see no
   crash but also no specific log line just from the version probe.
   The way to verify it works is by the UI string.

4. Manual smoke test from a Swift REPL or LLDB:
   ```swift
   (lldb) po FFmpegKitConfig.getFFmpegVersion()
   ```
   Should print `"n8.1.1"` (or similar non-empty string). If it
   returns `nil` or `""`, the xcframework is missing the n8 build.

5. Verify the bridge wiring in
   `iosApp/iosApp/FFmpegKitBridgeImpl.swift::getFfmpegVersionBanner()`:
   ```swift
   guard let v = FFmpegKitConfig.getFFmpegVersion(), !v.isEmpty else {
       return nil
   }
   return "ffmpeg version \(v)\n"
   ```

   The previous (broken) implementation called
   `FFmpegKit.execute("-version")` + `session.getAllLogsAsString()` —
   that does NOT work on n8 because `show_version` in fftools writes
   to stdout via printf and swaps the log callback away from
   arthenica's capture hook. If the broken implementation is still
   there, the PR didn't merge cleanly.

## Background reading (optional)

The upgrade work is documented at the binary source repository:
- Branch: [`homebase-id/ffmpeg-kit @ upgrade/ffmpeg-8.x`](https://github.com/homebase-id/ffmpeg-kit/tree/upgrade/ffmpeg-8.x)
- Customization framework: `CUSTOMIZATION.md` in that repo. Of
  particular interest for iOS:
  - **B1** — strip unsigned xcframeworks (no `_CodeSignature/`).
  - **B2** — thin arm64 device slice (drop `_arm64e`).
  - **C5** — `forward_report()` / `report_callback` wiring for
    progress events.
  - **U20** — libpostproc removal.
  - **U25** — `-lz` link for iOS resman.

## Sign-off

If everything in Step 1 + Step 2 passes:
- Comment "iOS LGTM, build + manual upload + version line green" on
  PR #558.
- Tag whoever's merging the PR.

If anything fails:
- Capture the failure mode in a screenshot or log paste.
- Comment on PR #558 with: which step, what you observed, simulator
  vs device model + iOS version.
- We can iterate from there.
