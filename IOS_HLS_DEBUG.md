# iOS HLS Black-Screen Debugging Reference

This document captures everything needed to triage iOS HLS video playback failures
("video sits on a black screen, never plays") from a captured `homebase.log` file.

When the user brings in a log and asks "why didn't this video play?", read this
doc first, then walk the log against the **Triage Order** at the bottom.

---

## 1. The pipeline

### Upload (any platform that produces HLS)

`homebase-api/.../video/VideoPayloadProcessor.kt`:
- Compresses the video. If `compressedSize >= 5 MB`, segments+encrypts it as HLS;
  otherwise uploads as a single encrypted MP4.
- HLS uses FFmpeg with `-hls_time 6 -hls_list_size 0 -hls_flags single_file
  -hls_key_info_file …` — i.e. **all segments concatenated into one `index.ts`**,
  with the `.m3u8` referencing them via `#EXT-X-BYTERANGE`.
- Each segment is independently AES-CBC encrypted with `keyHeader.aesKey` and
  `keyHeader.iv` (same IV for every segment), with PKCS7 padding to round up to
  the next 16-byte boundary.
- `metadata.fileSize` is `getFileSize(finalVideoPath)` — the size of the **encrypted**
  `index.ts` on disk, including all per-segment PKCS7 pad blocks.
- `metadata.hlsPlaylist` is the FFmpeg-emitted `.m3u8` text. It is embedded in the
  payload descriptor when small enough; otherwise stored as a separate payload
  (`isDescriptorContentComplete=false`).

### Playback (iOS)

`homebase-chat/src/nativeMain/.../video/VideoPlayerSurface.native.kt`:
- `resolveVideoContent(...)` decides HLS vs MP4. Branches:
  - HLS path: serves a stripped playlist (with `#EXT-X-KEY` lines removed) +
    handles `.ts` byterange requests via an `AVAssetResourceLoader` delegate.
  - MP4 path: writes decrypted bytes to a temp file and points `AVPlayer` at it.
- HLS resource loader (`HomebaseResourceLoaderDelegate`):
  - For `.m3u8`: returns the stripped playlist bytes.
  - For `.ts`: reads `[chunkStart .. chunkStart+length)` of encrypted payload from
    `getPayloadBytesEncryptedChunk`, AES-CBC-decrypts (PKCS7 strips 1–16 bytes),
    then **zero-pads back up to `length`** so the Range response size matches
    AVPlayer's request. The trailing zeros sit past the last valid 188-byte TS
    packet.

### The math you need to keep straight

- TS packet size: **188 bytes**.
- AES block: **16 bytes**.
- 188 mod 16 = 12. So `N×188 mod 16` cycles through `{12, 8, 4, 0}` as N grows.
- Plaintext segment = `N × 188`. Ciphertext = plaintext + (1..16) PKCS7 pad bytes.
  Specifically: 16 bytes when N mod 4 == 0, else (16 - (12N mod 16)).
- The original code comment claims "ciphertext = plaintext + 16". **This is only
  true when N mod 4 == 0** — for any other segment length the pad is 1, 4, 8, or
  12 bytes. Both cases are valid PKCS7; the decrypt path handles both.
- After decrypt + zero-pad, AVPlayer receives `length` bytes total: `N × 188`
  bytes of valid TS followed by `length - N×188` trailing zeros (1 to 16 bytes).
  Its TS demuxer is supposed to ignore the partial tail and resync on the next
  `0x47` sync byte — but on the last segment there is no next sync, so the tail
  is genuinely junk.

---

## 2. Reference capture: `signal-2026-04-29-104155.plain`

The original failing log. Memorize the fingerprint of "broken" vs "working":

### Failing video (`479edd19-5083-5900-bcf4-ac88ede6e44e`)

- 7,666,672 bytes total. Inbound from another device (push notification, no
  upload trace on this iOS client).
- Two playback attempts (`15:38:56` and `15:41:27`). Each one shows:
  1. preload completes (cache hit on second try)
  2. `(VideoHLS) resourceLoader request: url=homebase://video/index.m3u8`
  3. `Serving playlist (262 chars)` ← **suspiciously short**
  4. `resourceLoader request: url=homebase://video/index.ts`
  5. `avplayer chunk request: chunkStart=0 chunkLength=2789360 totalFileSize=7666672`
     ← chunk is 36% of the file
  6. `Segment decrypt: 2789360 cipher → 2789356 plain → 2789360 zero-padded at 0`
     ← only **4** PKCS7 pad bytes (not 16)
- **Then silence.** No further `.ts` request. No `AVPlayerItem` notification of
  any kind. No errors. Black screen.

### Working baselines (same log)

- `b8d4d919...` (13.9 MB, plays fine) → playlist 383 chars.
- `9c81d919...` (18.7 MB, plays fine) → playlist 504 chars.
- Both follow the same flow but their playlists have ~3–10 segment blocks.

### What the contrast suggests

A 262-char stripped playlist on a 7.6 MB video is enough for ~4–5 EXTINF blocks,
but probably only **1**. That hypothesis would explain:
- AVPlayer makes one `.ts` byterange request and stops (one segment listed = one
  request).
- 4.9 MB of the file is unreferenced.
- Black screen with no error: the demuxer parses the one segment, but it's the
  *whole* video stuck in a single mis-described EXTINF block, so the asset has
  no proper track structure.

But: that capture was a **stripped/old build**. The diagnostic info that would
confirm the playlist contents was being truncated by the iOS file sink. That's
why the diagnostics below were added.

---

## 3. Diagnostic logging that ships with the current source

All entries are tagged `(VideoHLS)` or `(VideoIO)`. Every line is short, single-line
(no embedded `\n`), and intended to survive Kermit's `RollingFileLogWriter` sink.

### `VideoContentResolver.kt`

Logged at the start of every playback attempt:

```
metadata: fileId=<uuid> key=<key> mimeType=<...> isSegmented=<bool> fileSize=<N> duration=<ms> codec=<...> hlsPlaylistChars=<N>
metadata: hls path chosen — original=<N> chars stripped=<N> chars
```

Plus a **smoking-gun warning** when the metadata path will fail silently:

```
metadata: isSegmented=true but hlsPlaylist=null — falling through to MP4 branch will fail silently. fileId=<…>
```

### `VideoPlayerSurface.native.kt` — resource loader requests

Every incoming `resourceLoader(... shouldWaitForLoadingOfRequestedResource: ...)`:

```
rl req: path=<...> file=<uuid> key=<...>
rl req: cInfo=<bool> dReq=<bool> toEnd=<bool>
rl req: reqOffset=<N> reqLength=<N> currentOffset=<N>
rl req: UNEXPECTED path=<...> — falling through to .ts branch (likely bug)   ← if path isn't .m3u8 or .ts
```

Cancellations from AVPlayer (`didCancelLoadingRequest` delegate):

```
rl CANCELLED: path=<...> file=<uuid> reqOffset=<N> reqLength=<N>
```

### Playlist serving

```
Serving playlist (<N> chars) for fileId=<...>
playlist[0]: #EXTM3U
playlist[1]: #EXT-X-VERSION:7
playlist[N]: ...                                           ← every line dumped individually
playlist dumped to /var/.../hbvid_playlist_<fileId>.m3u8   ← sidecar copy
playlist still contains a key directive after stripping    ← if EXT-X-KEY/METHOD=AES survived
playlist respond: bytes=<N> dataRequest=<bool>
playlist finishLoading() called
```

### TS chunk serving

```
avplayer chunk request: fileId=<...> key=<...> chunkStart=<N> chunkLength=<N> totalFileSize=<N>
ts request: already cancelled before fetch — bailing out          ← isCancelled check #1
ts fetch SHORT-READ: got <N>, expected <N> at offset=<N> ...      ← if encrypted.size != length
ts request: cancelled after fetch — bailing out before decrypt    ← isCancelled check #2
decrypt sizes: cipher=<N> plain=<N> padded=<N> at=<N>
decrypt align: startMod16=<N> lenMod16=<N> plainMod188=<N> tsPackets=<N> firstByte=0x47?=<bool>
decrypt cipher[0..16]=<hex>
decrypt plain[0..16]=<hex>
decrypt plain[tail-32..]=<hex>
decrypt padded[tail-32..]=<hex>
ts request: cancelled after decrypt — skipping respondWithData/finishLoading  ← isCancelled #3
ts respond: handing <N> bytes to AVPlayer at offset=<N>
ts request had no dataRequest — nothing to respond with           ← edge case
ts request: cancelled before finishLoading — skipping             ← isCancelled #4
ts finishLoading() called for fileId=<...>
finishLoadingWithError() called                                   ← exception path
```

Catch block now uses `Logger.e(throwable = e, ...)`, so stack traces appear.

### `attachHlsDiagnostics` — `AVPlayerItem` notifications

Confirmation it registered:

```
diagnostics attached: <N> notification observers
```

Notification handlers (fire only when AVPlayer posts):

```
AVPlayerItem FailedToPlayToEndTime: status=<...> itemError=<...> userInfoError=<...>
AVPlayerItem PlaybackStalled: status=<...> itemError=<...>
AVPlayerItem NewErrorLogEntry: status=<...> eventCount=<N>
errorLog[0]: <event>           ← up to last 10 events dumped per notification
errorLog[1]: <event>
...
AVPlayerItem NewAccessLogEntry: eventCount=<N>
accessLog[0]: <event>          ← up to last 3
```

### `attachPlaybackTicker` — periodic state probe (every 0.5 s, on main queue)

Confirmation it registered:

```
playback ticker attached for fileId=<...>
```

Then on every status/rate/error/buffer-flag/timeControl/reason/track-count change,
or every ~2 s otherwise:

```
tick#<N> fileId=<...> status=<Unknown|ReadyToPlay|Failed> rate=<f> t=<sec>s dur=<sec>s tracks=<N>
tick#<N> timeControl=<Paused|Waiting|Playing> reason=<AVPlayerWaitingTo...|null>
tick#<N> loaded=<none|N ranges> bufEmpty=<bool> bufLikely=<bool> bufFull=<bool>
tick#<N> presentationSize=<W>x<H>
tick#<N> errors: item=<...> | player=<...>     ← only when non-null
tick#<N> status TRANSITION <prev> → <new>      ← on flip
tick#<N> track[0]: <NSObject description>      ← per track on status flip
```

### `kickAssetMetadataLoad` — async asset metadata

Async load of `playable`/`tracks`/`duration`/`hasProtectedContent`:

```
asset.loadValuesAsynchronouslyForKeys kicked for fileId=<...> at <ts>ms
asset metadata callback fired after <ms>ms fileId=<...>     ← if it never fires, that itself is the answer
asset.playable load status=<N> fileId=<...>                  ← 1=Loading, 2=Loaded, 3=Failed, 4=Cancelled
asset.tracks load status=<N> fileId=<...>
asset.duration load status=<N> fileId=<...>
asset.hasProtectedContent load status=<N> fileId=<...>
asset playable=<bool> hasProtectedContent=<bool> fileId=<...>
asset tracks=<N> fileId=<...>
asset track[0]: <NSObject description>          ← mediaType/enabled embedded
asset duration=<sec>s fileId=<...>
```

---

## 4. Log signal → failure mode

| Symptom in log | Diagnosis |
|---|---|
| `metadata: isSegmented=true but hlsPlaylist=null …` | Upload-side metadata is broken (descriptor truncated, descriptor-content payload lost). Falls through to MP4 branch with TS bytes → silent black screen. |
| Sidecar playlist + `playlist[N]:` dump shows fewer EXTINF blocks than expected (e.g., 1 segment for an 8-MB file) | Upload side wrote a malformed/truncated playlist. Bug is on the uploader, not playback. |
| `playlist still contains a key directive after stripping` | The `lines().filter { !it.startsWith("#EXT-X-KEY") }` filter missed an indented or differently-prefixed key line. AVPlayer thinks data is encrypted, demuxer rejects it. |
| `rl req: UNEXPECTED path=enc.key` (or any non-`.ts`/`.m3u8`) | Same root cause as above: AVPlayer is asking for a key file because the playlist still references one. |
| `decrypt align: ... firstByte=0x47?=false` | After decrypt, the first byte isn't a TS sync byte. Either the encrypted bytes are misaligned, the wrong IV is being used, or the chunk offset is wrong. |
| `ts fetch SHORT-READ: got N, expected M` | Server/cache returned fewer bytes than asked for. Decrypt will produce garbage or throw on bad padding. |
| `rl CANCELLED:` between our chunk response and silence | AVPlayer abandoned the request. Common when the asset failed to validate; subsequent silence is *expected*, not the bug — look earlier in the log for what made AVPlayer give up. |
| Asset metadata callback never fires | Asset was destroyed before parse completed. Usually means the player was disposed mid-load (look for screen navigation in the log). |
| `asset.playable load status=3` (Failed) or `asset playable=false` | AVPlayer parsed the playlist and decided it can't play. Combine with `errorLog[N]:` events for the reason. |
| `asset tracks=0` | Playlist parsed but no tracks discovered. Almost always a malformed playlist or completely-wrong segment data. |
| `hasProtectedContent=true` | AVPlayer thinks the asset is FairPlay-protected (probably because EXT-X-KEY survived stripping). Same root cause as the residue warning. |
| `tick#N status=ReadyToPlay rate=0.0 timeControl=Waiting reason=AVPlayerWaitingToMinimizeStallsReason` | Buffer never filled enough. AVPlayer received our bytes but isn't satisfied with the rate. Check `bufEmpty=true` indefinitely → AVPlayer is rejecting every segment. |
| `tick#N status=ReadyToPlay tracks=N>0 presentationSize=0.0x0.0` | **Black-screen smoking gun.** Asset parsed, tracks found, but no video frames decoded. Codec mismatch (e.g., HEVC in TS that AVPlayer's TS demuxer doesn't support) or corrupt video stream. Note: `VideoPayloadProcessor.kt` hardcodes `codec="h264"` — if the source is actually HEVC, this is the bug. |
| `tick#N tracks=N` but only `mediaType=soun` entries | Audio-only output, no video track. Black screen with audio playback. Upload-side issue. |
| `tick#N errors: item=AVFoundationErrorDomain:-11829 ...` (or similar) | AVPlayer surfaced an error post-hoc. Look up the OSStatus code. Common ones: `-11828` (cannot decode), `-11829` (cannot open), `-11800` (unknown), `-12889` (HTTP/parsing). |
| Many `errorLog[i]:` events on a single `NewErrorLogEntry` | The first event usually has the real cause. Later events are cascade failures. |
| `loaded=none bufEmpty=true` indefinitely | AVPlayer accepted zero of our returned bytes. Either the response was too short, the data didn't validate as TS, or `finishLoading()` was never reached (look for the `ts finishLoading() called` line). |
| `playback ticker attached` line never appears | The `attachPlaybackTicker` call was never reached, or the player was created on a non-main thread. Check just before for the `LaunchedEffect` / `Dispatchers.Main` setup. |

---

## 5. Triage order when reviewing a new log

Walk top-down for one playback attempt. Use `grep -E "(VideoHLS|VideoIO)"` to
reduce noise.

1. **Find the playback attempt boundary.** Look for a `metadata:` line for the
   target `fileId`. Note: `mimeType`, `isSegmented`, `fileSize`, `hlsPlaylistChars`.
2. **Routing check.** Was the HLS branch taken? Look for `metadata: hls path chosen`
   immediately after, vs an absence (which means MP4 branch was taken).
3. **Playlist sanity.** Find `Serving playlist (N chars)`, then read the
   `playlist[0]…playlist[K]:` lines. Count EXTINF blocks. Compare segment-count
   vs expected (`fileSize / typical_segment_size_bytes`). Verify no `EXT-X-KEY`
   lines appear; verify URIs all reference `index.ts`.
4. **EXT-X-KEY residue warning.** Search for `playlist still contains a key
   directive` — if present, that's the bug.
5. **TS chunk request flow.** For the FIRST chunk request, verify in order:
   - `avplayer chunk request: chunkStart=0 chunkLength=<N> totalFileSize=<N>`
   - no `SHORT-READ` warning
   - `decrypt align: startMod16=0 lenMod16=0 plainMod188=0 firstByte=0x47?=true`
   - `ts respond: handing N bytes`
   - `ts finishLoading() called`
   - **NOT** `rl CANCELLED` between any of these
6. **Asset parse outcome.** Find `asset metadata callback fired` and the
   subsequent `asset playable=` and `asset tracks=N` lines. If callback never
   fired, the asset was torn down before parse — look earlier in the log for
   why (navigation, dispose, error).
7. **Player progression.** Find `playback ticker attached`, then walk the
   `tick#N …` series. The expected healthy sequence is:
   ```
   tick#1 status=Unknown rate=0.0 timeControl=Paused
   tick#2 status=ReadyToPlay rate=1.0 timeControl=Playing presentationSize=1280.0x720.0
   tick#3 status=ReadyToPlay rate=1.0 t=0.5s tracks=2 loaded=1 ranges
   ...
   ```
   Any deviation (status stuck Unknown, rate stays 0, presentationSize=0x0,
   tracks=0, indefinite Waiting) maps to the table in Section 4.
8. **Errors anywhere.** Search for `errorLog[`, `tick#N errors:`,
   `FailedToPlayToEndTime`, `PlaybackStalled`. Don't trust the *last* event —
   the first error in a cascade usually has the real cause.
9. **The `description` of tracks** — both `asset track[0]:` and `tick#N
   track[0]:` — embeds `mediaType` (`vide`, `soun`, `clcp`, `meta`). If only
   `soun` is present, you have audio-only.

---

## 6. Capturing logs from the device

**Log file location** (debug build, package = `id.homebase.feed.dev` on Android,
display name `HomebaseChatDev` on iOS/Desktop):

| Platform | Path |
|---|---|
| iOS Simulator | `~/Library/Developer/CoreSimulator/Devices/<UDID>/data/Containers/Data/Application/<APP-UUID>/Documents/logs/homebase.log` (locate via `xcrun simctl get_app_container <device> id.homebase.feed.dev data`) |
| iOS device (debug) | Share via the Files app, or pull through Xcode > Devices > Container > Download |
| Android | `adb shell run-as id.homebase.feed.dev cat files/logs/homebase.log` |
| macOS | `~/Library/Application Support/HomebaseChatDev/logs/homebase.log` |

**Sidecar playlist** (only on iOS, only when HLS path was hit):
- Written to `NSTemporaryDirectory()/hbvid_playlist_<fileId>.m3u8`
- Pull from same iOS container path under `tmp/` instead of `Documents/logs/`.

**Rotation:** `RollingFileLogWriter` is configured for **10 MB × 5 files** in
`homebase-common/.../LoggerConfig.kt`. Heavy app sessions can scroll the
relevant entries out. Pull the log *immediately* after reproducing.

**Quick filter for a triage session:**
```bash
grep -E "\((VideoHLS|VideoIO)\)" homebase.log
```

---

## 7. Known unknowns / things this logging does NOT cover

Keep these in mind if the log still doesn't pin the cause:

1. **`AVAudioSession` misconfiguration.** Could prevent video playback in some
   background-audio scenarios. App-wide state, not logged here.
2. **Codec actually being HEVC.** `VideoPayloadProcessor.kt:detectVideoCodec`
   hardcodes `"h264"`. If the upload was HEVC-in-TS, AVPlayer's TS demuxer may
   reject it, producing the exact silent-black-screen mode. The asset-track
   `description` log will show `mediaType=vide` either way; only `ffprobe` on
   the sidecar `index.ts` (or the on-disk encrypted file post-decrypt) confirms
   codec.
3. **iOS version-specific HLS-byterange parser quirks.** iOS 17 vs 18 have
   different strictness. Compare logs across devices.
4. **Server-side file vs metadata mismatch.** `metadata.fileSize` is set at
   upload time. If the actual file on the server was truncated post-upload, no
   client-side log can detect that without a HEAD request.
5. **The decrypted TS bytes themselves.** We log 16-byte hex heads/tails but
   not the full payload. If you reach the point of needing offline `ffprobe`
   inspection of the decrypted stream, the next addition is dumping the
   plaintext to `NSTemporaryDirectory()/hbvid_segment_<fileId>_<offset>.ts` —
   it's a 5-line change.

---

## 8. Files I edited (current source as of this doc)

- `homebase-chat/src/nativeMain/kotlin/id/homebase/chat/widget/video/VideoPlayerSurface.native.kt`
  — all of section 3's logging plus `attachPlaybackTicker`, `kickAssetMetadataLoad`,
  `didCancelLoadingRequest`, `isCancelled` checks, ticker token retention.
- `homebase-api/src/commonMain/kotlin/id/homebase/api/video/VideoContentResolver.kt`
  — `metadata:` and `hls path chosen` logs plus the `isSegmented but hlsPlaylist=null`
  warning.

Both files compile clean against `iosArm64` (Kotlin 2.3.10 Native, Compose
Multiplatform 1.10.2). JVM tests (`homebase-api:jvmTest`) pass.

---

## 9. When the user comes back with a new log

1. Read this document first.
2. `grep -E "\((VideoHLS|VideoIO)\)"` the new log to isolate playback events.
3. Walk the **Triage order** in Section 5 for the failing `fileId`.
4. Cross-reference against Section 4 (signal → failure mode).
5. If still ambiguous, the next step is logging the decrypted plaintext to the
   sidecar (Section 7 item 5).

The single most diagnostic line for any new capture is the first
`tick#N timeControl=… reason=…` line after status reaches `ReadyToPlay`. If
`rate=0` and `reason` is non-null at that point, that one line is the answer.
