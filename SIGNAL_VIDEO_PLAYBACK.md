# How Signal Android Plays Video Smoothly

A distilled writeup of how `signal-android` achieves smooth inline video playback in
its conversation/Giphy MP4 feed and media gallery. Captured by reading the actual
source at `/Users/todd/src/odin/signal-android/` so we can borrow the right pieces
for our Moments inline-video tiles.

Headline: the smoothness is not one trick. It is **(a) a small pool of reused
ExoPlayers**, **(b) a visibility scheduler that only plays the videos closest to
the viewport center**, **(c) TextureView surfaces that can be re-projected without
rebinding**, **(d) decrypt-on-read streaming so plaintext never hits disk**, and
**(e) sender-side transcoding to H.264 + faststart so the receiver's decoder has
an easy job**.

---

## 1. Player library

- `androidx.media3` **1.9.1** (modern ExoPlayer).
- `androidx.media3.ui.PlayerView` with `app:surface_type="texture_view"` and
  `app:use_controller="false"`.
- They do **not** use one player per tile and they do **not** use a single global
  player. They use a **pool** (next section).

## 2. Player pool & lifecycle — `SimpleExoPlayerPool`

`app/src/main/java/org/thoughtcrime/securesms/video/exo/SimpleExoPlayerPool.kt`

- Max concurrent players is derived from the device, not hard-coded:
  `MediaCodecUtil.getDecoderInfo(MimeTypes.VIDEO_H264)` → cap at **6** on modern
  devices, **3** on low-memory devices.
- 1 "reserved/primary" player is kept warm; the rest are leased on demand via
  `get(tag)` / `require(tag)` and returned via `pool(exoPlayer)`.
- Implements `AppForegroundObserver.Listener`: on background it stops playing
  players and evicts the available ones, so the codec pool never lingers off
  screen.
- Players are configured for video playback (`configureForVideoPlayback()`) the
  moment they're handed out — no per-call setup cost on the UI thread.

The conversation list takes this further with a **center-of-viewport
scheduler** in `GiphyMp4PlaybackController.java`:

- Computes the vertical center of the visible viewport.
- Sorts visible video tiles by distance from that center.
- Plays only the top-N where N = `maxSimultaneousPlaybackInConversation()` =
  `maxUnreservedPlayers / 3`. So even when the device *can* decode 6 streams it
  intentionally plays ~2 in chat to save CPU/battery.
- Tiles further from center are paused, not destroyed — the pool reassigns
  their player to whatever scrolled into the hot zone.

This is the single most important idea to copy: **scrolling does not bind/unbind
players, it just shifts which tiles the existing players point at.**

## 3. Surface handling — TextureView + projection, not rebind

`gif_player.xml`:

```xml
<androidx.media3.ui.PlayerView
    android:id="@+id/video_view"
    app:surface_type="texture_view"
    app:keep_content_on_player_reset="false"
    app:use_controller="false" />
```

- **TextureView** (not SurfaceView) because TextureView can be moved, animated
  and transformed by the view system — required for the scroll-projection
  pattern below. SurfaceView lives in its own window and would flash.
- `keep_content_on_player_reset="false"` clears the last frame on player reset
  so a freshly-bound player doesn't briefly show the previous tile's content.
- `GiphyMp4ProjectionRecycler.java` updates the *position and size* of the
  PlayerView overlay as the user scrolls — the player and surface stay put,
  only the projection moves. No `setVideoSurface` swap, no first-frame flash.

## 4. Buffering, caching, network

Signal does **not** tune `LoadControl` or call `setPreloadConfiguration`. They
trust media3's defaults for short clips. What they do customize:

- `ChunkedDataSource.java` performs **range-chunked** HTTP fetches through their
  content proxy. When many tiles all become visible at once it adds a **random
  0–750ms stagger** to avoid a thundering-herd of simultaneous opens.
- `GiphyMp4Cache.kt` is an **LRU disk cache scoped to the app session** — it is
  cleared on app start so it never bloats. Repeated views of the same GIF in a
  session don't re-fetch.
- No N-ahead prefetch. The combination of small cap + center-of-viewport
  scheduling + chunked range fetch is enough.

## 5. Sender-side transcoding — `StreamingTranscoder` (the real secret)

`StreamingTranscoder.java` and `TranscodingQuality.kt`. Before a video is sent,
Signal decides whether to transcode using roughly:

```
transcode if  inputBitrate >= targetQuality * 1.2
           or fileSize > limit
           or has location metadata
           or user requested a trim
           or codec != H.264
```

Target profile:

- **H.264** (the receiver explicitly verifies `MimeTypes.VIDEO_H264`).
- Tiered bitrates: 1.25 Mbps @ 480p, 1.25 Mbps @ 720p, 2.5 Mbps @ 720p, plus an
  experimental H.265 level.
- Audio constant: **128 kbps AAC**.
- `Mp4FaststartPostProcessor.kt` rewrites the file with `moov` at the front —
  this is what lets ExoPlayer start playback after a tiny prefix download
  instead of waiting for the whole file.

The receiver almost never gets an exotic codec, a 10-Mbps stream, or a back-loaded
`moov`. The decode side is easy because the encode side did the work.

## 6. Encrypted streaming — never spill plaintext

`EncryptedMediaDataSource.java` has two backends:

- `ClassicEncryptedMediaDataSource` (legacy).
- `ModernEncryptedMediaDataSource` — per-file random IV, decrypts via
  `ModernDecryptingPartInputStream.createFor()` as ExoPlayer issues reads.

`SignalDataSource.java` routes URIs:

- `BlobProvider://`         → `BlobDataSource`
- `PartAuthority://` (local encrypted attachments) → `PartDataSource`
  (wraps `EncryptedMediaDataSource`)
- `http(s)://`              → `ChunkedDataSource` (proxy + chunked range)
- otherwise                  → `DefaultDataSource`

Plaintext frames exist only inside the decoder pipeline — never on disk.

## 7. Inline autoplay UX

`GiphyMp4PlaybackPolicy.java`:

- `autoplay()` returns `!DeviceProperties.isLowMemoryDevice(...)` — on low-RAM
  devices autoplay is off entirely.
- `maxDurationOfSinglePlayback()` = **8000 ms**.
- `maxRepeatsOfSinglePlayback()` = **4 loops**.
- `GiphyMp4PlaybackPolicyEnforcer.java` actually stops the player when one of
  the caps is hit, so a loud GIF can't burn the battery forever.
- Audio: muted by default — `volume = 0f` and audio renderer disabled via
  `trackSelectionParameters` so it isn't even decoded.
- Visual: `GiphyMp4ProjectionPlayerHolder.onPlaybackReady()` fires once the
  first frame is decoded and **only then** hides the still-image placeholder —
  no black frame between thumbnail and video.

## 8. Other small things worth stealing

- **Lifecycle-aware return:** `GiphyMp4ProjectionPlayerHolder` is a
  `DefaultLifecycleObserver`; `onPause`/`onStop` return the player to the pool
  automatically. No manual `release()` calls in tile code.
- **Audio renderer off** at track-selection time, not just `setVolume(0)` — saves
  one decoder per tile.
- **No `snapshotFlow { ... }.distinctUntilChanged()` equivalent** on the hot
  path; the scheduler is invalidated by scroll callbacks, not by polling.
- **Repeat:** `REPEAT_MODE_ALL` for GIFs, capped by the policy enforcer.

---

## What this means for our Moments inline-video tile

Concrete actions, in priority order:

1. **Build a small ExoPlayer pool** (cap from `MediaCodecUtil`, ~3 on low-mem,
   ~6 otherwise). One player per visible tile is the bug; one player per
   *concurrent playback slot* is the fix.
2. **Center-of-viewport scheduler.** On scroll, sort visible tiles by distance
   from the LazyColumn's visible center, take the top-N, assign players to
   those. Pause everything else. Do *not* destroy players on scroll.
3. **TextureView surface** with `keep_content_on_player_reset="false"` (or the
   Compose equivalent — we already have a `VideoPlayerSurface.android.kt` —
   make sure it's TextureView-backed, not SurfaceView, for inline tiles).
4. **Faststart + H.264 on upload.** Whatever we ship to the drive must have
   `moov` at the front and be a sane bitrate. This is upstream of playback
   smoothness — if we skip it, no amount of player tuning will save us.
5. **Decrypt-on-read DataSource.** We already stream encrypted attachments —
   make sure the video path uses a Ktor/`DataSource` adapter that decrypts on
   the fly and supports HTTP range, so ExoPlayer can seek without buffering the
   whole file.
6. **Stagger network opens** by 0–750 ms when many tiles autostart at once.
7. **Mute + disable audio renderer** by default for inline tiles. Tap-to-expand
   re-enables audio.
8. **Cap concurrent playback to ~2** in a long feed even if the device can do
   more — UX trumps the decoder budget.
9. **Hide the still-image poster on the first decoded frame**, not on the
   `Player.STATE_READY` transition — `onRenderedFirstFrame` is what removes the
   black flash.

The pieces we *don't* need to copy: aggressive `LoadControl` tuning, manual
prefetching, custom `HandlerThread` for ExoPlayer. Signal proves you can leave
those at framework defaults if the pool, the scheduler, and the upload pipeline
are right.

---

# How Signal iOS Plays Video Smoothly

A distilled writeup of how `signal-ios` (at `/Users/todd/src/odin/signal-ios/`)
achieves smooth video playback in inline cells (gallery, conversation feed) and
the full-screen media gallery. Captured by reading the actual source so we can
borrow the right pieces for our KMP iOS target.

Headline: **iOS doesn't replicate Android's player pool or center-of-viewport
scheduler.** Instead, the architecture leans on three things — (a) one
`AVPlayer` per visible cell with cells managing their own lifetime via
UICollectionView recycling, (b) a UIView subclass whose `layerClass` is
`AVPlayerLayer` (no `AVPlayerViewController` in tile contexts), and (c) an
`AVAssetResourceLoaderDelegate` that decrypts encrypted attachments on the fly
without ever decrypting the whole file to disk. The sender side does
faststart-and-moov-to-front via `AVAssetExportSession.shouldOptimizeForNetworkUse`.

## 1. Player class

- **`AVPlayer`** is the only player class, wrapped by two thin Swift types:
  - `SignalUI/AV/VideoPlayer.swift:13` — `VideoPlayer` owns one `AVPlayer` for
    full-screen / interactive playback (controls, scrubbing).
  - `SignalUI/Views/LoopingVideoView.swift:35` — `LoopingVideoPlayer`
    *subclasses* `AVPlayer` and auto-rewinds on completion. Used for inline
    GIF-as-MP4 cells.
- No `AVQueuePlayer`, no `AVPlayerLooper`. Looping is implemented manually by
  observing playback completion and seeking back to zero.
- No `AVPlayerViewController` anywhere in the inline-cell paths. It's only
  reached when the user enters the full-screen scrubbable player, and even
  there Signal prefers their own custom controls layered over a bare
  `AVPlayer`.

## 2. Pool / lifecycle — there isn't one

- **No pool.** Every cell that needs to play video builds a fresh
  `LoopingVideoView` (and the `LoopingVideoPlayer` inside it). The cost is
  swallowed because:
  - `UICollectionView` cell recycling is the de facto scheduler — offscreen
    cells get reused and their videos are torn down via `unloadMedia()`
    (`Signal/ConversationView/CellViews/CVMediaView.swift:59`).
  - `MediaPageViewController` (full-screen gallery) explicitly stops the
    previous page on swipe (`stopVideoIfPlaying()` at line 295 — only the
    centered page is allowed to be playing).
- The one reuse pattern that *does* show up: `replaceCurrentItem(with:)`
  (`LoopingVideoView.swift:68-88`). When the same `LoopingVideoView` cell is
  rebound to a different attachment (typical when scrolling reuses cells), the
  player is kept and just retargeted — `AVPlayer` allocation is avoided.

This is the part most worth internalizing: **on iOS the cell is the pool of
one**, and the platform's view recycling handles concurrency for free. We
don't need to invent a Signal-Android-style scheduler if we use Compose's
disposal correctly.

## 3. Surface / view hierarchy

The trick that gives Signal iOS its smooth cell-level playback is a
**plain `UIView` subclass that returns `AVPlayerLayer` from its `layerClass`**:

`SignalUI/Views/LoopingVideoView.swift:157`:

```swift
override class var layerClass: AVPlayerLayer.Type {
    return AVPlayerLayer.self
}
```

Why not `AVPlayerViewController`:
- `AVPlayerViewController` brings the iOS system transport controls and a
  gesture recognizer that intercepts taps/pans — fatal in any tile context
  where the parent owns gestures (a pager, a collection view, a tap-to-open
  detector).
- A `UIView` whose root layer is `AVPlayerLayer` gives you raw rendering and
  zero gesture conflicts. Tap/long-press recognizers on the parent fire
  cleanly.

First-frame flash mitigation:
- Inline thumbnails are loaded first; the video view mounts only when
  playback is asked for (`MediaItemViewController.swift:36, 114-157`).
- Looping cells wait on KVO of `AVPlayerItem.status` and only call `play()`
  once it transitions to `.readyToPlay` (`LoopingVideoView.swift:108-114`).
- There's no explicit `isReadyForDisplay` observation — they trust KVO of
  status as the "we're about to paint a real frame" signal.

## 4. Buffering / preload — defaults all the way

- No `preferredForwardBufferDuration`, no `preferredPeakBitRate`, no
  `automaticallyWaitsToMinimizeStalling` tuning anywhere.
- No prefetch of the next gallery item. They trust AVPlayer to start fetching
  on `replaceCurrentItem(with:)`.
- Same philosophy as the Android side: get the upload pipeline and the
  decrypt path right, and the framework defaults are fine for short clips.

## 5. Encrypted attachment streaming — `AVAssetResourceLoaderDelegate`

This is the most replicable trick for us. `AVAsset+Attachment.swift:22-196`:

- `AttachmentStream.decryptedAVAsset()` returns an `AVURLAsset` whose URL uses
  a custom scheme (`signal://`) — AVFoundation does not know how to fetch
  that, so it falls back to the asset's `resourceLoader.delegate`.
- The delegate is `EncryptedFileResourceLoader` (lines 119-196). For every
  `dataRequest` (byte range) AVPlayer issues, it:
  1. Translates the plaintext offset → encrypted offset via
     `EncryptedFileHandle`,
  2. Decrypts in ~4 KB chunks on a dedicated `videoDecryptionQueue`
     (line 50, 155),
  3. Feeds each chunk back via `dataRequest.respond(with:)`.
- `contentInformationRequest` answers plaintext length + MIME type once
  (lines 142-152) and sets `byteRangeAccessSupported = true` so AVPlayer is
  free to seek inside the file.
- The delegate is retained out-of-band via
  `ObjectRetainer.retainObject()` (line 109) because
  `AVAssetResourceLoader.delegate` is held *weakly* — drop the reference and
  AVFoundation silently stops calling you back.

Plaintext frames live only in memory and only for as long as AVPlayer needs
them. The encrypted file on disk is the only persistent representation.

## 6. Encoding / format — `AVAssetExportSession`

`PreviewableAttachment.swift:132-207`:

```swift
let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset640x480)
exporter.outputFileType = .mp4
exporter.shouldOptimizeForNetworkUse = true
exporter.metadataItemFilter = .forSharing()
```

- `AVAssetExportPreset640x480` — a known, well-supported preset. iOS doesn't
  expose granular bitrate/codec control through the public API, so the preset
  is the lever.
- `shouldOptimizeForNetworkUse = true` is the **moov-to-front** flag — the
  exact iOS analog of Android's `Mp4FaststartPostProcessor`. Without this,
  receivers can't start playback before the whole file lands.
- `.metadataItemFilter = .forSharing()` strips EXIF/geolocation. Worth
  mirroring on our upload pipeline for the same privacy reason.

## 7. Inline autoplay / GIF-as-MP4

- GIFs are not animated GIFs at runtime — they're converted to MP4 on send
  and replayed via `LoopingVideoView`. iOS has no `Fresco` analog because it
  doesn't need one; `AVPlayer` plus a `UIView`+`AVPlayerLayer` is the
  cheapest possible looper.
- `isMuted = true` is set on the `LoopingVideoPlayer` at init
  (`LoopingVideoView.swift:63`). There is **no AVFoundation equivalent of
  Android's "disable the audio renderer entirely"** — once the audio track is
  in the asset, AVPlayer will decode it. The only knobs are `isMuted` (mixer
  level) and `volume = 0` (output level). For our purposes muting is fine.
- Autoplay decision is implicit: the `LoopingVideoView` starts playing the
  moment its `video` property is assigned, regardless of viewport position.
  The "only the centered cell plays" property comes from the cell lifecycle,
  not a scheduler.

## 8. Small cleverness

- `CVMediaCache` (`Signal/ConversationView/CellViews/ReusableMediaView.swift`)
  is an in-memory cache keyed by attachment ID that holds the decrypted
  `LoopingVideo` assets so reusing a cell for the same attachment doesn't
  re-decrypt.
- Layer scaling: `magnificationFilter = .trilinear` /
  `minificationFilter = .trilinear` on the player view's layer — keeps the
  thumbnail-to-video crossover from showing aliasing artifacts during scaling
  animations.
- KVO observers on `status`, `timeControlStatus`, `rate`, and a periodic time
  observer at 10 ms intervals (`VideoPlayerView.swift:118-140`) drive UI
  state. Equivalent of media3's `Player.Listener` callbacks.

---

## What we'd need to change in our KMP iOS path

For context: our current iOS implementation is in
`homebase-chat/src/nativeMain/kotlin/id/homebase/chat/widget/video/VideoPlayerSurface.native.kt`.
Compared with Signal iOS, two things stand out:

1. **We use `AVPlayerViewController`.** Fine for the full-screen player, wrong
   for inline tiles — its built-in controls and tap gesture will fight the
   moments carousel pager the same way `PlayerView`'s controller fights it on
   Android. The carousel-friendly path on iOS should be a `UIKitView` whose
   underlying `UIView`'s `layerClass` is `AVPlayerLayer`. Signal's
   `LoopingVideoView.swift:157` is the template.

2. **We stream encrypted bytes through `LocalVideoServer`** (a loopback HTTP
   server registered on a localhost port). That works, but it has more moving
   parts than necessary: a TCP listener, a port-binding retry loop, a session
   table, and a network round-trip for every chunk AVPlayer asks for. Signal's
   `EncryptedFileResourceLoader` (`AVAsset+Attachment.swift:119-196`)
   accomplishes the same job inside AVFoundation's own
   `AVAssetResourceLoaderDelegate` — no socket, no server, no port. Worth a
   future migration when we have appetite for it; the perf win is small but
   the surface-area reduction is large.

Concrete actions, in priority order:

1. **Add an inline-tile iOS surface backed by `AVPlayerLayer`**, separate from
   the existing `AVPlayerViewController`-based full-screen path. Use it from
   the moments carousel. Mirror the Compose API of
   `VideoPlayerSurface.android.kt` (TextureView + transparent shutter) so the
   thumbnail behind shows through until the first frame paints.

2. **Observe `AVPlayerItem.status` KVO** and only fade out the spinner / start
   playback once it transitions to `.readyToPlay`. This is the iOS analog of
   `onRenderedFirstFrame`. We can't see frame rendering directly on iOS, but
   `readyToPlay` is the closest signal and is what Signal uses.

3. **Set `shouldOptimizeForNetworkUse = true`** in our `AVAssetExportSession`
   transcode path (the iOS upload pipeline). This is upstream of playback —
   the receiver of a non-faststart MP4 can't begin playback until the whole
   file lands, no matter how good the player code is.

4. **Apply `.metadataItemFilter = .forSharing()`** on the same export path.
   One line, strips location metadata for free.

5. **Later (optional):** swap `LocalVideoServer` for an
   `AVAssetResourceLoaderDelegate`. The performance delta is modest, but it
   eliminates the loopback socket and its port-binding fallback. Not urgent.

Things we **don't** need to copy from Signal iOS:
- A player pool (their architecture is one-per-cell and so is ours).
- An active-playback coordinator (Compose disposal does it).
- `preferredForwardBufferDuration` tuning (defaults are fine).
- A custom `AVPlayer` subclass for looping (we can call `seek(.zero); play()`
  on end-of-play notification, same as them).