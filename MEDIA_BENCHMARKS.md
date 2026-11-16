# Media benchmarks

How fast we resize images, compress and segment video, and hash, per device, so a regression or an improvement shows. THUMBNAILS.md has the rules; this file has the numbers and the decisions they led to.

**How to read the tables.** Every table holds only numbers measured with the code that ships: one row per device and input, where a range like `13.9–15.6 s` is the spread across runs. When the code changes what a row measures, measure again and replace the row; until then, leave it out rather than keep a stale number. Where we began is in the [History](#history) appendix.

## How to measure

- **Device tests** are in `homebase-api`. Install them with `./gradlew :homebase-api:installAndroidDeviceTest`, then run one with `adb shell am instrument -w -e class 'id.homebase.api.video.Media3VideoCompressorTest#<test>' id.homebase.api.test/androidx.test.runner.AndroidJUnitRunner`. Results are logged under the `Media3Benchmark` tag (`adb logcat -s Media3Benchmark`).
  - `benchmarkAgainstFfmpeg`: Media3 against ffmpeg compression at Standard and HD, then segmenting and the AAC timings.
  - `benchmarkSegmenting`: compresses once with Media3, then times our segmenter against ffmpeg's.
  - `benchmarkSegmentingAsIs`: segments the file as it is.
  - `benchmarkEndToEnd`: a whole send, as `VideoPayloadProcessor` runs it.
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

## Sending a video, end to end

`benchmarkEndToEnd`: compress at Standard, then HLS (segment, encrypt, hash) at 5 MB or more, else encrypt the MP4 in one pass. "Ours" is Media3, our segmenter and the payload hash. "ffmpeg" is ffmpeg for both steps without a hash, as Android was before.

| Device | Input | Sent as | Ours | ffmpeg | Measured |
|---|---|---|---|---|---|
| Pixel 5 | Synthetic 1080p, 10 s → 3.7 MB (ffmpeg 3.3 MB) | MP4 | **2.5–3.5 s**: compress 2.3–3.1 s, encrypt and hash 0.19–0.40 s | 6.6–6.8 s: compress 6.4–6.6 s, encrypt 0.12 s | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 83 s → 27.9 MB (ffmpeg 27.4 MB) | HLS | **16.5–17.0 s**: compress 15.8–16.0 s, HLS 0.62–0.93 s | 51.0–51.2 s: compress 50.0–50.2 s, HLS 0.99–1.03 s | 2026-11-16 |

Compressing is almost all of a send. **Open question:** the small MP4's encrypt-and-hash step (0.19–0.40 s) is slower than ffmpeg's plain encrypt (0.12 s), though two native hashes of 3.7 MB cost about 15 ms. The 0.40 s run also loaded the library. To profile with the read-buffer fixes.

## Compression: Media3 against ffmpeg

The compress step alone. On the HDR phone video, Media3 tone-maps to SDR; ffmpeg only relabels the colours.

| Device | Input | Standard: Media3 / ffmpeg | HD: Media3 / ffmpeg | Measured |
|---|---|---|---|---|
| Pixel 8 | HDR phone video | **13.9–15.6 s** / 63.3–64.4 s | **11.7–14.0 s** / 156–163 s | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 83 s | **15.8–16.0 s** / 50.0–50.2 s | not measured | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 10 s | **2.3–3.1 s** / 6.4–6.6 s | not measured | 2026-11-16 |

Output sizes match ffmpeg's within a few percent on camera footage. On hard synthetic content (a 1440p60 animation), the hardware encoder runs 20–40% over its bitrate and libx264 about 16% under. See THUMBNAILS.md, "Android: Media3".

## Segmenting: ours against ffmpeg

Segment and encrypt, with ffmpeg's `-hls_flags single_file` as the reference. Ours also takes both BLAKE3 payload hashes; ffmpeg takes none.

| Device | Input | Ours | ffmpeg | Measured |
|---|---|---|---|---|
| Pixel 5 | Stand-in | **0.77–0.90 s** | 0.87–1.03 s | 2026-11-16 |
| Pixel 5 | Synthetic 1080p, 83 s, compressed | **0.62–0.93 s** | 0.99–1.03 s | 2026-11-16, in `benchmarkEndToEnd` |
| Pixel 8 | | to measure | | |

On the Pixel 5, what's left is TS writing (about 0.3 s) and parsing the moov (0.1–0.2 s).

## Audio

Why AAC is copied (see the decisions below). 83 s of 48 kHz stereo AAC, Pixel 8, 2026-11-16:

| What | Time |
|---|---|
| Android's codecs, decode and re-encode (`c2.android.aac.*`) | 15–27 s |
| ffmpeg's AAC encoder | 3.3–4.5 s |
| Media3 export of the video with no audio track | 8–13 s |

## Hashing: BLAKE3

| Where | Ships | Speed | Kotlin port on the same | Measured |
|---|---|---|---|---|
| Pixel 5, 64-bit app | native C, NEON | **453–933 MB/s** (which core the scheduler picks moves it) | 44 MB/s | 2026-11-16 |
| Pixel 5, 32-bit app | native C, NEON | **603 MB/s** | 36 MB/s | 2026-11-16 |
| Pixel 8 | native C, NEON | to measure | | |
| Desktop JVM | Kotlin port | 141 MB/s | — | 2026-11-16 |

For scale: AES-CBC runs at 681 MB/s on the Pixel 5. A 32-bit app is installed with `adb install --abi armeabi-v7a`.

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
- **Read files in full chunks** (2026-11-16). Okio's `read` returns at most one 8 KB segment, so `readFileAsFlow` asked for 256 KB and emitted 8 KB, copied and handed across threads each time. Filling each chunk made reading 27 MB 10× faster on a Pixel 5, for every upload and hash that reads a file.
- **Our own segmenter rather than ffmpeg's** (2026-11-16). It's faster with the hashes included, it reads the file once, and it never re-encodes portrait video. It must keep the same cuts and decoded frames as ffmpeg's; `HlsSegmenterTest.theSegmentsAndDecodedFramesMatchFfmpegsOwnHls` checks that.

## History

Where each measurement began, against now, and the changes that made the difference. The reasons behind them are in the decisions above.

**Segmenting** (ours / ffmpeg)

| Device | Input | First version | Now |
|---|---|---|---|
| Pixel 8 | HDR phone video, compressed | 9.6 s / 1.9 s | to measure (0.78–1.18 s / 1.50–2.08 s before native BLAKE3) |
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
