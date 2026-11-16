# Video benchmarks

How fast we compress and segment video, per device, so a regression or an improvement shows. THUMBNAILS.md, "Video", has the rules; this file has the numbers and the decisions they led to. Add a row whenever you measure.

## How to measure

- **Device tests:** `homebase-api` `Media3VideoCompressorTest`. Install them with `./gradlew :homebase-api:installAndroidDeviceTest`, then run one with `adb shell am instrument -w -e class 'id.homebase.api.video.Media3VideoCompressorTest#<test>' id.homebase.api.test/androidx.test.runner.AndroidJUnitRunner`. Results are logged under the `Media3Benchmark` tag (`adb logcat -s Media3Benchmark`).
  - `benchmarkAgainstFfmpeg`: Media3 against ffmpeg compression at Standard and HD, then segmenting and the AAC timings.
  - `benchmarkSegmenting`: compresses once with Media3, then times our segmenter against ffmpeg's.
  - `benchmarkSegmentingAsIs`: segments the file as it is.
- **Inputs:** the tests read every `.mp4`/`.mov` in the test app's own folder, `/sdcard/Android/data/id.homebase.api.test/files/bench/`. Copy a phone video there with `adb shell cp`, or push one, and delete it afterwards. Uninstalling the test package also removes it.
- **The stand-in:** a 27 MB file shaped like a compressed phone video, so no one's own video is needed. Make it with `ffmpeg -f lavfi -i testsrc2=size=720x1280:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 83 -c:v libx264 -preset veryfast -g 30 -b:v 2500k -maxrate 2500k -bufsize 5M -c:a aac -b:a 192k -movflags +faststart stand_in_720p.mp4`.
- **Fair runs:**
  - The phone must be cool: throttling moved one compress from 20 s to 45 s. The tests pause between runs, alternate which backend goes first, and log `thermal=` (0 means none).
  - Plug in and keep the screen on.
  - With several phones attached, set `ANDROID_SERIAL`.

## Devices

| Device | Android | Chip | Video codecs |
|---|---|---|---|
| Pixel 8 | 17 | Tensor G3 | `c2.exynos.*` hardware; AAC `c2.android.*` software |
| Pixel 5 | 14 | Snapdragon 765G | Qualcomm hardware; AAC `c2.android.*` software |

## Compression: Media3 against ffmpeg

The source is a 226 MB, 83 s, 1080p HEVC HLG (10-bit HDR) video from a Pixel 8. Media3 tone-maps it to SDR; ffmpeg only relabels the colours.

| Date | Device | Standard: Media3 / ffmpeg | HD: Media3 / ffmpeg | Note |
|---|---|---|---|---|
| 2026-11-16 | Pixel 8 | 13.9 s / 63.3 s | 11.7 s / 156.1 s | AAC copied |
| 2026-11-16 | Pixel 8 | 15.6 s / 64.4 s | 14.0 s / 163.2 s | AAC copied |
| 2026-11-16 | Pixel 8 | about 38–45 s / – | – | AAC re-encoded by Android's codecs, phone warm |

Output sizes match ffmpeg's within a few percent on camera footage. On hard synthetic content (a 1440p60 animation), the hardware encoder runs 20–40% over its bitrate and libx264 about 16% under. See THUMBNAILS.md, "Android: Media3".

## Segmenting: ours against ffmpeg

This is segment and encrypt, with ffmpeg's `-hls_flags single_file` as the reference. Ours also takes both BLAKE3 payload hashes; ffmpeg takes none.

| Date | Device | Input | Ours | ffmpeg | Version |
|---|---|---|---|---|---|
| 2026-11-16 | Pixel 8 | the 226 MB video compressed (27 MB) | 9.6 s | 1.9 s | first version, 64 KB buffer per TS packet |
| 2026-11-16 | Pixel 8 | the same | 2.2–2.8 s | 1.7–2.4 s | packets written straight to the output |
| 2026-11-16 | Pixel 8 | stand-in (27 MB) | 1.6–2.1 s | 1.8 s | BLAKE3 unrolled |
| 2026-11-16 | Pixel 8 | stand-in (27 MB) | 0.92–0.96 s | 1.87–2.03 s | both hashes on their own cores |
| 2026-11-16 | Pixel 8 | the 226 MB video compressed (27 MB) | 0.78–1.18 s | 1.50–2.08 s | both hashes on their own cores |

| 2026-11-16 | Pixel 5 | stand-in (27 MB) | 23.8 s | 1.0 s | fully unrolled BLAKE3: Android 14 left the huge method interpreted |
| 2026-11-16 | Pixel 5 | stand-in (27 MB) | 1.9–2.5 s | 0.9–1.1 s | BLAKE3 rounds looped over locals |
| 2026-11-16 | Pixel 5 | stand-in (27 MB) | 1.4–1.8 s | 1.0–1.1 s | primitive decode-order sort, pipelined reading and writing, full read chunks |
| 2026-11-16 | Pixel 5 | stand-in (27 MB) | 0.89–0.91 s | 0.89–1.12 s | native BLAKE3 |

Where the time went before the hashes ran in parallel (stand-in, Pixel 8): BLAKE3 1.6–1.9 s, TS writing 0.1–0.25 s, AES 0.03–0.07 s.

On the Pixel 5 at the last row: reading 0.06 s (0.64 s before the read-chunk fix), TS writing 0.3–0.44 s, AES 0.07–0.1 s, parsing the moov 0.1–0.2 s, and waiting on hashing 0.4–0.7 s. Hashing was the limit there; with native BLAKE3 it no longer is. What's left on the Pixel 5 is TS writing (about 0.3 s) and parsing the moov (0.1–0.2 s).

## Audio

| Date | Device | 83 s of 48 kHz stereo AAC | Time |
|---|---|---|---|
| 2026-11-16 | Pixel 8 | Android's codecs, decode and re-encode (`c2.android.aac.*`) | 15–27 s |
| 2026-11-16 | Pixel 8 | ffmpeg's AAC encoder | 3.3–4.5 s |
| 2026-11-16 | Pixel 8 | Media3 export with no audio track | 8–13 s |

## Hashing

| Date | Where | BLAKE3 | Note |
|---|---|---|---|
| 2026-11-16 | desktop JVM | 97 → 120 MB/s | `compress` unrolled over local variables |
| 2026-11-16 | Pixel 8 | about 34 → 55 MB/s | the same change; measured inside the segmenter |
| 2026-11-16 | desktop JVM | 141 MB/s | rounds looped over locals instead of unrolled |
| 2026-11-16 | Pixel 5 | 45 MB/s | rounds looped over locals; AES-CBC on the same phone runs at 681 MB/s |
| 2026-11-16 | Pixel 5 | **933 MB/s** (Kotlin 44 MB/s) | native: the official C BLAKE3 1.8.7 with NEON, `-O3`; `Blake3NativeDeviceTest.throughput` |

## Decisions, and when to reopen them

- **AAC audio is copied, not re-encoded, on Android** (2026-11-16).
  - **Why:** Android has no hardware AAC encoder, and its software codecs cross into a separate media process for every 1024-sample frame. Re-encoding 83 s of audio took 15–27 s, longer than the whole hardware video path, so it set the pace of every export.
  - **Kept:** plain AAC-LC with up to two channels, the audio every phone records, is copied. Anything else is re-encoded.
  - **Cost:** a copied track keeps its bitrate. That's 192 kbps from a Pixel against Standard's 128 kbps, about 2% of the file. Speed matters more.
  - **Reopen when** Android's AAC re-encode of a long video runs well under its video path, at about 20× real time, or when a hardware AAC encoder appears.
- **BLAKE3's rounds loop over local variables, and the two payload hashes run in parallel** (2026-11-16).
  - **Not fully unrolled:** an unrolled `compress` is one huge method, and Android 14 left it interpreted, about 20× slower. Keep the method small.
  - **Speed:** about 45 MB/s per core on a Pixel 5 and 55 MB/s on a Pixel 8. This is what the JVM and web run.
- **Android hashes BLAKE3 natively** (2026-11-16).
  - **How:** `Blake3Hasher` is still the one class every platform and caller uses. On Android it runs the official C implementation (`homebase-blake3`: BLAKE3 1.8.7, NEON on 64-bit ARM, `-O3`) through JNI. The library adds 27 KB on arm64 and 19 KB on 32-bit ARM.
  - **Speed:** 933 MB/s against 44 MB/s for the Kotlin port on a Pixel 5, 21×.
  - **Who gains:** segmenting, Photos' hash stage (a 226 MB video in about 0.25 s instead of 5 s) and Filesync.
  - **Proof:** `Blake3NativeDeviceTest` checks it hashes exactly as the Kotlin port across block, chunk and tree boundaries.
- **Read files in full chunks** (2026-11-16). Okio's `read` returns at most one 8 KB segment, so `readFileAsFlow` asked for 256 KB and emitted 8 KB, copied and handed across threads each time. Filling each chunk made reading 27 MB 10× faster on a Pixel 5, for every upload and hash that reads a file.
- **Our own segmenter rather than ffmpeg's** (2026-11-16). It's faster with the hashes included, it reads the file once, and it never re-encodes portrait video. It must keep the same cuts and decoded frames as ffmpeg's; `HlsSegmenterTest.theSegmentsAndDecodedFramesMatchFfmpegsOwnHls` checks that.
