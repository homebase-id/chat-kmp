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