# Media benchmarks

How fast we resize images, compress and segment video, and hash, per device, so a regression or an improvement shows. THUMBNAILS.md has the rules; this file has the numbers and the decisions they led to.

**How to read the tables.** Every table holds only numbers measured with the code that ships: one row per device and input, where a range like `13.9–15.6 s` is the spread across runs. When the code changes what a row measures, measure again and replace the row; until then, leave it out rather than keep a stale number. Where we began is in the [History](#history) appendix.

## How to measure

- **Device tests** are in `homebase-api`. Install them with `./gradlew :homebase-api:installAndroidDeviceTest`, then run one with `adb shell am instrument -w -e class 'id.homebase.api.video.Media3VideoCompressorTest#<test>' id.homebase.api.test/androidx.test.runner.AndroidJUnitRunner`. Results are logged under the `Media3Benchmark` tag (`adb logcat -s Media3Benchmark`).
  - `benchmarkEndToEnd`: a whole send, as `VideoPayloadProcessor` runs it.
  - `RemuxHlsToMp4DeviceTest#benchmark` with `-e remuxBench true`: saving a received video.
  - The comparisons against ffmpeg on Android (`benchmarkAgainstFfmpeg`, `benchmarkSegmenting`, `benchmarkSegmentingAsIs`, the ffmpeg legs of `benchmarkEndToEnd`, `GifShrinkBenchmark` and the remux) were removed with ffmpeg-kit; their numbers below stand, and the tests are in git history before ffmpeg left Android.
  - BLAKE3 speed: `id.homebase.api.crypto.Blake3NativeDeviceTest#throughput` with `-e hashBench true`.
- **Inputs:** the tests read every `.mp4`/`.mov` in the test app's `files/bench/` folder. On Android 14 and later, only the app itself can see that folder, so copy a file in with `adb push <file> /data/local/tmp/`, then `adb shell run-as id.homebase.api.test sh -c 'mkdir -p files/bench && cp /data/local/tmp/<file> files/bench/'`. Delete it afterwards: `adb shell run-as id.homebase.api.test rm -rf files/bench`. Uninstalling the test package also removes it.
- **Fair runs:**
  - The phone must be cool: throttling moved one compress from 20 s to 45 s. The tests pause between runs, alternate which backend goes first, and log `thermal=` (0 means none).
  - Plug in and keep the screen on.
  - With several phones attached, set `ANDROID_SERIAL`.

## Inputs

| Name | What | Make it with |
|---|---|---|
| **HDR phone video** | 226 MB, 83 s, 1080p HEVC HLG (10-bit HDR) from a Pixel 8's camera. Someone's own video, never checked in | — |
| **HDR phone video, compressed** | The above after Media3 at Standard: 27 MB, 720p H.264 | `benchmarkSegmenting` makes it |
| **Stand-in** | 27 MB, 83 s, 720×1280 H.264 at 2.5 Mbps with AAC; shaped like the above, so no one's video is needed | `ffmpeg -f lavfi -i testsrc2=size=720x1280:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 83 -c:v libx264 -preset veryfast -g 30 -b:v 2500k -maxrate 2500k -bufsize 5M -c:a aac -b:a 192k -movflags +faststart stand_in_720p.mp4` |
| **Synthetic 1080p, 83 s** | 210 MB, 1080×1920 H.264 at 20 Mbps with AAC | as the stand-in, with `size=1080x1920`, `-b:v 20M -maxrate 20M -bufsize 40M` |
| **Synthetic 1080p, 10 s** | 25 MB, the same settings for 10 s; compresses to under 5 MB, so it's sent as an MP4, not HLS | as above, with `-t 10` |

## Devices

| Device | Android | Chip | Video codecs |
|---|---|---|---|
| Pixel 8 | 17 | Tensor G3 | `c2.exynos.*` hardware; AAC `c2.android.*` software |
| Pixel 5 | 14 | Snapdragon 765G | Qualcomm hardware; AAC `c2.android.*` software. Also runs 32-bit apps |
| Galaxy S23+ | 17 | Snapdragon 8 Gen 2 | |

## How many times a file is read

Full reads of the file per operation, with the code that ships. Header reads (duration, probe, finding the `moov`) and the one frame a poster decodes aren't counted. Keep this table current whenever a change adds or removes a pass.

| Operation | Android | iOS | Desktop | Web |
|---|---|---|---|---|
| Before compressing a pick | 0 from the media library (read in place); **1** for any other picked or shared file (copied to the cache) | **1** from the Photos library (copied out first); 0 for a file | 0 | 0 |
| Compress | **1** of the source (Media3) | **1** (ffmpeg) | **1** (ffmpeg) | **1** (ffmpeg.wasm) |
| HLS, compressed 5 MB or more | **1** of the compressed file: cut, encrypt and both payload hashes in one pass | **1** (ffmpeg cuts and encrypts; no payload hash) | **1** (as iOS) | **1** (as iOS) |
| Plain MP4, under 5 MB | **1** of the compressed file: encrypt and hash in one pass | **1** | **1** | **1** |
| **A whole send** | **2** (3 for a non-media pick) | **2** (3 from the Photos library) | **2** | **2** |
| Photos backup at Original | **1** (read once at hand-off) | | | |
| Saving a received video to the gallery | **1** of the decrypted `.ts`: the samples are sized as the download writes it (`TsSizing`), then copied behind the index | **1** | **1** | **1** |
| An HDR photo at HD or Original | the photo is decoded as before; its gain map, the file's tail (a few percent of it), is read **1** more time on its own | same | same | same |
| Shrinking a GIF sticker | the file is read **1** time into memory, then decoded **2** times per ladder step (palette, then encode), at most 3 steps | same | same | same |

## Sending a video, end to end

`benchmarkEndToEnd`: compress at Standard and at HD (Original video is HD's transcode outside Photos), then HLS (segment, encrypt, hash) at 5 MB or more, else encrypt the MP4 in one pass. "Ours" is Media3, our segmenter and the payload hash. "ffmpeg" is ffmpeg for both steps without a hash, as Android was before.

| Device | Input | Setting | Sent as | Ours | ffmpeg | Measured |
|---|---|---|---|---|---|---|
| Pixel 5 | Synthetic 1080p, 10 s | Standard | MP4, 3.7 MB (ffmpeg 3.3 MB) | **2.5–3.4 s**: compress 2.3–3.1 s, encrypt and hash 0.14–0.40 s | 6.6–6.8 s: compress 6.4–6.6 s, encrypt 0.12–0.17 s | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 10 s | HD | HLS, 6.8 MB (ffmpeg 6.5 MB) | **4.0 s**: compress 3.5–3.6 s, HLS 0.37–0.53 s | 9.0–9.1 s: compress 8.6–8.7 s, HLS 0.34–0.38 s | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 83 s | Standard | HLS, 27.9 MB (ffmpeg 27.4 MB) | **16.3–17.0 s**: compress 15.6–16.0 s, HLS 0.62–0.93 s | 50.5–51.2 s: compress 49.6–50.2 s, HLS 0.97–1.03 s | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 83 s | HD | HLS, 53.8 MB (ffmpeg 53.5 MB) | **26.8–27.3 s**: compress 25.8–26.2 s, HLS 0.96–1.06 s | 70.9–71.0 s: compress 69.3 s, HLS 1.6–1.7 s | 2026-11-16 |
| Pixel 8 | Synthetic 1080p, 83 s | Standard | HLS, 27.9 MB (ffmpeg 27.4 MB) | **15.8–17.4 s**: compress 14.9–16.4 s, HLS 0.92–0.96 s | 41.5–41.9 s: compress 40.3 s, HLS 1.1–1.6 s | 2026-11-16 |
| Pixel 8 | Synthetic 1080p, 83 s | HD | HLS, 53.8 MB (ffmpeg 53.5 MB) | **16.8–17.5 s**: compress 15.2–15.8 s, HLS 1.6–1.7 s | 65.6–68.2 s: compress 63.4–65.9 s, HLS 2.2–2.3 s | 2026-11-16 |

Compressing is almost all of a send. Our MP4 encrypt also takes both payload hashes: 0.14–0.27 s against ffmpeg's plain 0.12–0.17 s, and 0.40 s on a process's first send, which also loads the native BLAKE3 and checks it.

## Compression: Media3 against ffmpeg

The compress step alone. On the HDR phone video, Media3 tone-maps to SDR; ffmpeg only relabels the colours.

| Device | Input | Standard: Media3 / ffmpeg | HD: Media3 / ffmpeg | Measured |
|---|---|---|---|---|
| Pixel 8 | HDR phone video | **13.9–15.6 s** / 63.3–64.4 s | **11.7–14.0 s** / 156–163 s | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 83 s | **15.6–16.0 s** / 49.6–50.2 s | **25.8–26.2 s** / 69.3 s | 2026-11-16 |
| Pixel 8 | Synthetic 1080p, 83 s | **14.9–16.4 s** / 40.3 s | **15.2–15.8 s** / 63.4–65.9 s | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 10 s | **2.3–3.1 s** / 6.4–6.6 s | **3.5–3.6 s** / 8.6–8.7 s | 2026-11-16 |

**Output size per setting** (`eachQualitySettingGivesItsSizeAndAboutFfmpegsBytes`, a short synthetic clip; Original video is HD's transcode):

| Device | Setting | Size | Media3 | ffmpeg | Measured |
|---|---|---|---|---|---|
| Pixel 5 | Standard | 1280×720 | 1384 kB | 791 kB | 2026-11-16 |
| Pixel 5 | HD and Original | 1920×1080 | 2277 kB | 1581 kB | 2026-11-16 |

Both land on the setting's size. On synthetic test patterns the hardware encoder runs over its bitrate and libx264 under it, so Media3 comes out 1.4–1.7× larger there; on the longer synthetic inputs above, and on real camera footage, the two are within a few percent. On hard synthetic content (a 1440p60 animation), the hardware encoder runs 20–40% over its bitrate and libx264 about 16% under. See THUMBNAILS.md, "Android: Media3".

## Segmenting: ours against ffmpeg

Segment and encrypt, with ffmpeg's `-hls_flags single_file` as the reference. Ours also takes both BLAKE3 payload hashes; ffmpeg takes none.

| Device | Input | Ours | ffmpeg | Measured |
|---|---|---|---|---|
| Pixel 5 | Stand-in | **0.77–0.90 s** | 0.87–1.03 s | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 83 s, compressed | **0.62–0.93 s** | 0.99–1.03 s | 2026-11-16, in `benchmarkEndToEnd` |
| Pixel 8 | Stand-in | **0.65–0.99 s** | 1.68–1.81 s | 2026-11-16 |
| Pixel 8 | Synthetic 1080p, 83 s, compressed | **0.92–1.68 s** (Standard 28 MB to HD 54 MB) | 1.1–2.3 s | 2026-11-16, in `benchmarkEndToEnd` |
| Pixel 8 | HDR phone video, compressed | to measure with native BLAKE3 (0.78–1.18 s / 1.50–2.08 s before it) | | |

On the Pixel 5, what's left is TS writing (about 0.3 s) and parsing the moov (0.1–0.2 s).

## Reading a picked file

`id.homebase.api.file.ContentReadBenchmark` with `-e readBench true`: a video from the test app's `files/bench/` is put in the media library, then copied out through its `content://` URI, as a picked or shared file is. Three rounds, alternating which buffer goes first. The file was just written, so it's read from the page cache: this measures the round trips to the media process, not the flash.

| Device | Input | 8 KB (Java's default) | 1 MB | Measured |
|---|---|---|---|---|
| Pixel 5 | Synthetic 1080p, 83 s (210 MB) | 563–1151 ms (182–373 MB/s) | **159–188 ms** (1117–1320 MB/s) | 2026-11-16 |
| Pixel 8 | Synthetic 1080p, 83 s (210 MB) | 626–769 ms (273–335 MB/s) | **295–318 ms** (660–711 MB/s) | 2026-11-16 |

## Audio

Why AAC is copied (see the decisions below). 83 s of 48 kHz stereo AAC, Pixel 8, 2026-11-16:

| What | Time |
|---|---|
| Android's codecs, decode and re-encode (`c2.android.aac.*`) | 15–27 s |
| ffmpeg's AAC encoder | 3.3–4.5 s |
| Media3 export of the video with no audio track | 8–13 s |

## App size without ffmpeg on Android

Release APKs built locally the same way (`./gradlew :androidApp:assembleRelease`, all four ABIs in one APK). Native libraries are stored uncompressed, so they count in full on the phone.

| Build | Whole APK | arm64-v8a libs | armeabi-v7a libs | x86_64 libs | Measured |
|---|---|---|---|---|---|
| With ffmpeg-kit (main a5396a08b) | 182.5 MB | 37.9 MB (ffmpeg 17.3) | 29.2 MB (ffmpeg 16.4) | 43.8 MB (ffmpeg 21.1) | 2026-11-16 |
| **Without** | **108.1 MB** | **20.7 MB** | **12.8 MB** | **22.6 MB** | 2026-11-16 |

A Play Store install downloads one ABI: the CI bundle before this was about 53 MB for arm64, ffmpeg 8.6 MB of it compressed.

## Saving a received video: TS back to MP4

Saving an HLS video to the gallery decrypts its `.ts` and copies it into an MP4. `RemuxHlsToMp4DeviceTest` (`-e remuxBench true` for videos in the test app's `files/bench/`) times it on the phone, and `TsToMp4Test` checks every sample against the source on the desktop. The video is an 83 s, 27.7 MB 720×1280 H.264 + AAC stand-in.

| Where | Remux | Time | Output | Frames | Measured |
|---|---|---|---|---|---|
| Desktop JVM | **ours** (`TsToMp4`, shared Kotlin) | **90 ms** | 27.5 MB | all | 2026-11-16 |
| Desktop | ffmpeg `-c copy -movflags +faststart` | 90 ms | 27.6 MB | all | 2026-11-16 |
| Pixel 5 (debuggable test app) | **ours** | 558–982 ms (first run cold) | 27.5 MB | **2490 / 2490** | 2026-11-16 |
| Pixel 5 | ffmpeg-kit | 235–349 ms | 27.6 MB | 2490 / 2490 | 2026-11-16 |
| Pixel 5 | Media3 Transformer, transmux | 5.4 s | 27.6 MB | 2490 / 2490 | 2026-11-16 |
| Pixel 5 | MediaExtractor + MediaMuxer | 3.4 s | 27.6 MB | 2489 / 2490 | 2026-11-16 |
| Pixel 5 (debuggable test app) | **ours, sized during the download** (one read of the `.ts`) | **436–659 ms** after the download, against 535–944 ms for two reads in the same runs; the sizing itself, 53–279 ms (53–84 warm), runs while the download writes | 27.3 MB | **2490 / 2490** | 2026-11-17 |

The 2026-11-17 row is a regenerated synthetic stand-in of the same shape (83 s, 720×1280, H.264 + AAC, 27.4 MB), two rounds of two runs each; the debuggable app's JIT makes single runs noisy, so read the ranges. Both paths write the same MP4, byte for byte.

Ours is a little smaller because it keeps parameter sets in the index rather than repeating them in-band. ffmpeg decodes ours to the same frames as the source, with the same audio packets and A/V offset (`TsToMp4Test`). On the phone, the test app is debuggable, so ART won't compile it ahead of time; the release app runs it compiled, as the desktop does.

## GIF stickers: our codec against ffmpeg

`id.homebase.api.image.gif.GifShrinkBenchmark` with `-e gifBench true` re-encodes every GIF in the test app's `files/bench/` at each of `GifShrinker`'s three steps, with our codec (`Gif.kt`) and, while Android still had it, with ffmpeg given the filters `GifShrinker` used to use. Two rounds, alternating order. The device test app is debuggable, so Android runs our Kotlin less optimised there than in the app; ffmpeg is native either way.

| Device | GIF | Step | Ours | ffmpeg | Output: ours / ffmpeg | Measured |
|---|---|---|---|---|---|---|
| Pixel 5 | 800×450, 300 frames, 7 MB | 512 px, 128 colours | **8.5–8.9 s** | 10.7–11.2 s | **2.79** / 3.32 MB | 2026-11-16 |
| Pixel 5 | the same | 384 px, 128 colours | **5.9–6.2 s** | 6.2–7.5 s | **1.75** / 2.14 MB | 2026-11-16 |
| Pixel 5 | the same | 384 px, 64 colours, 12 fps | 3.5–5.9 s | 3.1–3.9 s | **0.71** / 0.86 MB | 2026-11-16 |
| Pixel 5 | video, 360×640, 120 frames, 2.8 MB | 512 px, 128 colours | **2.7–2.9 s** | 4.8–8.7 s | **1.27** / 1.55 MB | 2026-11-16 |
| Pixel 5 | sticker, 480×480, 120 frames, 1.7 MB | 384 px, 128 colours | **2.7–3.3 s** | 3.7–3.8 s | **1.12** / 1.33 MB | 2026-11-16 |
| Pixel 5 | the same | 480 px (no scaling), 128 colours | 2.4–2.6 s | **1.1–1.2 s** | 1.47 / 1.48 MB | 2026-11-16 |
| Desktop JVM | 800×450, 300 frames | 512 px, 128 colours | **1.40–1.51 s** | 2.05 s (285 MB peak) | **2.79** / 3.32 MB | 2026-11-16 |
| Desktop JVM | sticker 480×480 | 480 px (no scaling) | 0.51–0.56 s | **0.36–0.41 s** | 1.47 / 1.48 MB | 2026-11-16 |

Where the time goes without scaling, desktop: decoding 85 ms (done twice: palette, then encode), mapping colours 60–90 ms on one core, changed rectangles and LZW 317 ms.

## Hashing: BLAKE3

| Where | Ships | Speed | Kotlin port on the same | Measured |
|---|---|---|---|---|
| Pixel 5, 64-bit app | native C, NEON | **453–933 MB/s** (which core the scheduler picks moves it) | 44 MB/s | 2026-11-16 |
| Pixel 5, 32-bit app | native C, NEON | **603 MB/s** | 36 MB/s | 2026-11-16 |
| Pixel 8 | native C, NEON | **924 MB/s** | 52 MB/s | 2026-11-16 |
| Desktop JVM | Kotlin port | 141 MB/s | — | 2026-11-16 |

For scale: AES-CBC runs at 681 MB/s on the Pixel 5. A 32-bit app is installed with `adb install --abi armeabi-v7a`.

## HDR photos: Ultra HDR JPEG against today's WebP

`HdrDisplayDeviceTest#benchmarkHdrFinalImages` with `-e hdrBench true`: each photo in the test app's `files/bench/` re-encoded as the final image at Standard and HD, as SDR WebP (what sends today) and as an Ultra HDR JPEG with the gain map (what an HDR photo sends now on Android 14+), twice each in alternating order. Two real Pixel 8 camera photos (Ultra HDR, 2268×4032, 1.25 and 2.04 MB), measured on the Pixel 8.

| Photo | Setting | Size | SDR WebP | Ultra HDR JPEG | Encode: WebP / JPEG | Measured |
|---|---|---|---|---|---|---|
| 1 (1.25 MB) | Standard | 1152×2048 | 72 kB | 135 kB (+88%) | 432–599 / 204–257 ms | 2026-11-16 |
| 1 | HD | 2268×4032 | 279 kB | 618 kB (+122%) | 1010–1012 / 139–150 ms | 2026-11-16 |
| 2 (2.04 MB) | Standard | 1152×2048 | 141 kB | 215 kB (+52%) | 434–453 / 167 ms | 2026-11-16 |
| 2 | HD | 2268×4032 | 614 kB | 1073 kB (+75%) | 1185–1199 / 169–171 ms | 2026-11-16 |

Same quality number for both (60 at Standard, 80 at HD); a JPEG at the same number is not the same picture quality as WebP, which this doesn't measure. For scale: Signal's Ultra HDR copy of a Pixel 8 photo in #2390 was 243 kB at 900×1600. The JPEG encodes 2–7× faster than WebP here.

### The shared writer against Android's encoder

`benchmarkHdrFinalImages` (`-e hdrBench true`, plus `-e hdrSynthetic true` for a generated 4032×3024 Ultra HDR photo with fine noise and a smooth gain map): the HD final image four ways, three rounds in rotating order. The pipeline rows are the whole render (decode, scale, encode); Android's rows are only `compress` of the same scaled pixels, so compare each pair's difference, not the rows across.

| Device | Input | Pipeline SDR WebP | Pipeline SDR JPEG | Shared Ultra HDR | Android: SDR / Ultra HDR `compress` | Measured |
|---|---|---|---|---|---|---|
| Pixel 5 | Synthetic 12 MP | 2198 kB, 2.65–2.72 s | 1552 kB, 280 ms | 1568 kB (+16 kB), 294–304 ms (+14–24 ms) | 1566 / 1566 kB, 155 / 153 ms (+2 ms scaling the gain map) | 2026-11-16 |

The shared writer costs about 15–25 ms for decoding, scaling and encoding the gain map and joining; Android's native encoder hides almost all of that. A smooth synthetic gain map compresses to nearly nothing, so this says nothing about size on real photos yet: to measure on real Pixel 8 photos (the shared writer writes the gain map as a 3-channel JPEG).

**Decided 2026-11-16: no HDR at Standard.** Standard is for small and fast; HD and Original keep HDR. Reopen if a quality-matched comparison (JPEG quality chosen to look like today's WebP) shows the gap is much smaller.

## Images

`ImagePipelinePerfBench`: `createMediaImages` on generated 12 MP (4000×3000) and 48 MP (8000×6000) photos, and the bare Lanczos step. Median of 3, with peak native memory. OPTIMIZE.md, "Image pipeline on an Android phone", says how to run it; with `run-as`, the results are in the test app's `cache/image-bench.txt`.

| Case | Pixel 5 (2026-11-16) | Pixel 8 (2026-11-12) | S23+ (2026-11-11) | Peak native, Pixel 5 |
|---|---|---|---|---|
| 12 MP JPEG, Standard | 866 ms | 771 ms | 592 ms | +58 MB |
| 12 MP JPEG, HD | 2935 ms | 2437 ms | 1965 ms | +85 MB |
| 12 MP HEIC, Standard | 1691 ms | 1813 ms | 975 ms | +58 MB |
| 12 MP HEIC, HD | 3718 ms | 3306 ms | 2388 ms | +87 MB |
| 48 MP JPEG, Standard | 1080 ms | 1001 ms | 719 ms | +58 MB |
| 48 MP JPEG, HD | 3386 ms | 2852 ms | 2397 ms | +231 MB |
| 48 MP HEIC, Standard | 3279 ms | 4562 ms | 1695 ms | +241 MB |
| 48 MP HEIC, HD | 5690 ms | 6353 ms | 3180 ms | +231 MB |
| Lanczos 4000×3000 → 2048×1536 | 596 ms | 203 ms | 99 ms | +12 MB |
| Lanczos 2048×1536 → 1080×810 | 31 ms | 101 ms | 37 ms | +3 MB |

The Pixel 8 and S23+ columns are from #2364 and OPTIMIZE.md. The 48 MP HEIC fixture is about 3–4× a real camera HEIC, which inflates its decode. #2364 also has real Pixel photos: about 1.1 s at Standard, 2.8 s at HD and 1.1 s at Original for 9 MP.

## Decisions, and when to reopen them

- **AAC audio is copied, not re-encoded, on Android** (2026-11-16).
  - **Why:** Android has no hardware AAC encoder, and its software codecs cross into a separate media process for every 1024-sample frame. Re-encoding 83 s of audio took 15–27 s, longer than the whole hardware video path, so it set the pace of every export.
  - **Kept:** plain AAC-LC with up to two channels, the audio every phone records, is copied. Anything else is re-encoded.
  - **Cost:** a copied track keeps its bitrate. That's 192 kbps from a Pixel against Standard's 128 kbps, about 2% of the file. Speed matters more.
  - **Reopen when** Android's AAC re-encode of a long video runs well under its video path, at about 20× real time, or when a hardware AAC encoder appears.
- **BLAKE3's rounds loop over local variables** (2026-11-16).
  - **Not fully unrolled:** an unrolled `compress` is one huge method, and Android 14 left it interpreted, about 20× slower. Keep the method small.
  - **Speed:** about 45 MB/s per core on a Pixel 5 and 55 MB/s on a Pixel 8. This is what the JVM and web run.
- **Android hashes BLAKE3 natively** (2026-11-16).
  - **How:** `Blake3Hasher` is still the one class every platform and caller uses. On Android it runs the official C implementation (`homebase-blake3`: BLAKE3 1.8.7, NEON on 64-bit and 32-bit ARM, `-O3`) through JNI. The library adds about 27 KB on arm64 and 19 KB on 32-bit ARM. A library that doesn't load is logged and the Kotlin port takes over.
  - **Speed:** 453–933 MB/s against 44 MB/s for the Kotlin port on a Pixel 5, 10–21×.
  - **Segmenting:** at this speed, separate hasher threads no longer paid for themselves. The plaintext is hashed inline, and the ciphertext on the writing thread.
  - **Who gains:** segmenting, Photos' hash stage (a 226 MB video in about 0.25 s instead of 5 s) and Filesync.
  - **Proof:** the official vectors (`IncrementalHasherTest`, shared into the device tests) run on a phone, as a 64-bit and as a 32-bit app. `Blake3NativeDeviceTest` also checks it against the Kotlin port across block, chunk and tree boundaries.
- **Copy picked and shared files with a 1 MB buffer** (2026-11-16). A `content://` stream is served by another process, one round trip per read, so Java's 8 KB default copied 3–6× slower on a Pixel 5. `copyInLargeChunks` is used wherever Android copies a stream to a file: the picked file's cache copy, shared files, files opened with the app, and saves to the gallery.
- **Our own TS → MP4 remux instead of ffmpeg on Android** (2026-11-16). Saving a received HLS video was ffmpeg's last job on Android. Media3 Transformer copies correctly but takes 5.4 s for 83 s of video; the platform's MediaExtractor + MediaMuxer take 3.4 s and drop the last frame. Both pay per sample through the framework. `TsToMp4` (shared Kotlin, like the segmenter) reads the TS twice: once to size the samples and build the index, then once to write the file with the index up front. It matches ffmpeg on the desktop and keeps every frame.
- **Media3's MP4s lost 400 kB of padding** (2026-11-16). Media3's muxer reserves about 400 kB at the front for a streamable index and leaves it as a `free` box, so a 6 s clip compressed to 1221 kB instead of 822 kB. Nothing here streams an MP4 progressively (payloads are decrypted whole, and the segmenter finds the index anywhere), so the index now goes at the end.
- **Our own GIF codec instead of ffmpeg** (2026-11-16). Android has no GIF encoder, and shrinking oversized GIF stickers was ffmpeg's last job there that nothing else could do. `Gif.kt` (shared Kotlin, every platform) decodes, scales by area averaging, builds one palette from the pixels that change, and writes only each frame's changed rectangle, as the ffmpeg filters did. Scaling and colour mapping run on four frames at once; decoding and writing stay in order.
  - **Smaller output:** 15–30% below ffmpeg's at every step, so a sticker more often fits at the first, best-looking step.
  - **Memory:** a few frames, against ffmpeg's 285 MB for a 300-frame GIF.
  - **Speed:** faster than ffmpeg when scaling, which is most steps; about 1.4× slower when no scaling is needed, in the LZW loop (Kotlin against C).
  - **Considered:** gif.kt (com.shakster:gifkt, MIT, Kotlin Multiplatform) encodes with a palette per frame and doesn't scale or lower the frame rate, so the shrinker would still need its own code for both, plus about 4,500 lines to maintain.
  - **Reopen** if the no-scaling step shows up as slow in practice: LZW is then the place to look.
- **Read files in full chunks** (2026-11-16). Okio's `read` returns at most one 8 KB segment, so `readFileAsFlow` asked for 256 KB and emitted 8 KB, copied and handed across threads each time. Filling each chunk made reading 27 MB 10× faster on a Pixel 5, for every upload and hash that reads a file.
- **Our own segmenter rather than ffmpeg's** (2026-11-16). It's faster with the hashes included, it reads the file once, and it never re-encodes portrait video. It must keep the same cuts and decoded frames as ffmpeg's; `HlsSegmenterTest.theSegmentsAndDecodedFramesMatchFfmpegsOwnHls` checks that.

## History

Where each measurement began, against now, and the changes that made the difference. The reasons behind them are in the decisions above.

**Segmenting** (ours / ffmpeg)

| Device | Input | First version | Now |
|---|---|---|---|
| Pixel 8 | HDR phone video, compressed | 9.6 s / 1.9 s | to measure (0.78–1.18 s / 1.50–2.08 s before native BLAKE3; the stand-in is 0.65–0.99 s / 1.68–1.81 s with it) |
| Pixel 5 | Stand-in | 23.8 s / 1.0 s | 0.77–0.90 s / 0.87–1.03 s |

- **TS packets written straight to the output:** the first version allocated a 64 KB buffer for every 188-byte packet.
- **Kotlin BLAKE3, rounds looped over local variables:** fully unrolled, it was too big for Android 14 to compile and ran interpreted, which made the Pixel 5's first version 23.8 s.
- **Full read chunks:** reading 27 MB went from 0.64 s to 0.06 s on the Pixel 5.
- **A primitive decode-order sort, and reading, muxing and writing overlapped.**
- **Native BLAKE3:** hashing stopped being the limit, so it runs inline instead of on separate threads.

**Compression** (Pixel 8, HDR phone video, Standard, Media3): about 38–45 s at first, 13.9–15.6 s now, from copying AAC instead of re-encoding it with Android's codecs (see Audio).

**BLAKE3 in Kotlin** (the port desktop, iOS and web run; Android runs native now)

| Where | First version | Now |
|---|---|---|
| Desktop JVM | 97 MB/s | 141 MB/s |
| Pixel 8 | about 34 MB/s | not measured (about 55 MB/s fully unrolled) |
| Pixel 5 | not measured | 44 MB/s |
